package org.starficz.combataitweaks.predictor


import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ViewportAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import kotlinx.coroutines.*
import org.lwjgl.opengl.GL11
import org.starficz.combataitweaks.CombatAIConfig
import org.starficz.combataitweaks.CombatAIv2
import org.starficz.combataitweaks.capture.captureDamage
import org.starficz.combataitweaks.decide.MoveController
import org.starficz.combataitweaks.decide.MoveState
import org.starficz.combataitweaks.capture.captureMotion
import org.starficz.combataitweaks.sim.DamageInputs
import org.starficz.combataitweaks.sim.DamageSim
import org.starficz.combataitweaks.sim.OwnFire
import org.starficz.combataitweaks.sim.Coupling
import org.starficz.combataitweaks.sim.Reactions
import org.starficz.combataitweaks.sim.SideFire
import org.starficz.combataitweaks.sim.SimParams
import org.starficz.combataitweaks.sim.Timeline
import org.starficz.combataitweaks.sim.World
import org.starficz.combataitweaks.ui.ConfigDialog.Companion.ENABLE_CAT_AI_TAG
import java.awt.Color
import java.util.*
import java.util.concurrent.Executors
import org.starficz.combataitweaks.sim.Scratch
import org.starficz.combataitweaks.sim.PredictedBranch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin
import kotlin.text.removePrefix

// Global Coroutine scope so we don't leak thread pools between combat engagements
object PredictorThreadPool {
    // Leave cores for the game's own threads (main loop, GC, sound): the predictor reruns as soon as it finishes, so
    // it would otherwise keep every core busy for the whole battle.
    private val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1)
    private val threadFactory = ThreadFactory { r ->
        Thread(r).apply {
            name = "Starficz-CAT-Predictor-Worker-${UUID.randomUUID().toString().take(4)}"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    val dispatcher = Executors.newFixedThreadPool(threads, threadFactory).asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
}

class PredictorManager private constructor() {

    companion object {

        private const val DATA_KEY = "CAT_PREDICTOR"
        const val FLAG_KEY = "NEEDS_CAT_PREDICTOR"
        private const val MIN_BATCH_INTERVAL = 0.1f
        /** Option names (plan §7); the vent decision reads HOLD and BACK_OFF. */
        const val HOLD = "HOLD"
        /** The standoff `HOLD` (plan §13.4): what the `HOLD` state is judged by and flies. */
        const val STANDOFF = "STANDOFF"
        const val ADVANCE = "ADVANCE"
        const val BACK_OFF = "BACK_OFF"
        const val SYSTEM_SUFFIX = "+SYSTEM"
        /** The option with the ship's defensive system used now (plan §11b). */
        const val DEFENSE_SUFFIX = org.starficz.combataitweaks.decide.MoveRule.DEFENSE_SUFFIX
        fun getInstance(engine: CombatEngineAPI): PredictorManager {
            if (!engine.customData.containsKey(DATA_KEY)) {
                engine.customData[DATA_KEY] = PredictorManager()
            }
            return engine.customData[DATA_KEY] as PredictorManager
        }
    }

    /** The damage inputs of the newest batch, for the trace recorder. */
    @Volatile var latestDamageInputs: org.starficz.combataitweaks.sim.DamageInputs? = null
        private set
    @Volatile private var isSimulating = false
    /** Batches finished, their worker wall time (ms) and branches, for the battle check's throughput line. */
    var batchesCompleted = 0
        private set
    var batchMillisTotal = 0L
        private set
    var branchesTotal = 0
        private set
    @Volatile private var lastBatchMillis = 0L
    @Volatile private var lastBatchBranches = 0
    private var lastBatchStart = -1f
    private val outputRef = AtomicReference<SimulationOutput?>(null)

    /** Latest options each CAT ship asked for, with when it asked. */
    private val pendingRequests = HashMap<String, Pair<Float, List<OptionRequest>>>()
    private var latestResults: Map<String, Map<String, RequestResult>> = emptyMap()
    private var latestEnemyTimelines: Map<String, Timeline> = emptyMap()

    private val resultTimestamps = HashMap<String, Float>()
    private val timelineTimestamps = HashMap<String, Float>()

    /** Asks for [options] to be predicted for [ship] in the next batch; replaces what it asked for before. */
    fun request(ship: ShipAPI, options: List<OptionRequest>) {
        val engine = Global.getCombatEngine() ?: return
        pendingRequests[ship.id] = engine.getTotalElapsedTime(false) to options
    }

    /** The newest predicted options of [shipId] by name (empty when there are none). */
    fun results(shipId: String): Map<String, RequestResult> = latestResults[shipId] ?: emptyMap()

    fun advance(engine: CombatEngineAPI) {
        val completedOutput = outputRef.getAndSet(null)
        val currentTime = engine.getTotalElapsedTime(false)

        if (completedOutput != null) {
            latestResults = mergeWithRetention(
                oldMap = latestResults,
                newMap = completedOutput.results,
                timestamps = resultTimestamps,
                currentTime = currentTime,
                isOverridden = { oldKey, newBatch -> newBatch.containsKey(oldKey) }
            )

            latestEnemyTimelines = mergeWithRetention(
                oldMap = latestEnemyTimelines,
                newMap = completedOutput.enemyTimelines,
                timestamps = timelineTimestamps,
                currentTime = currentTime,
                isOverridden = { oldKey, newBatch -> newBatch.containsKey(oldKey) }
            )

            batchesCompleted++
            batchMillisTotal += lastBatchMillis
            branchesTotal += lastBatchBranches
            isSimulating = false
        }

        // plan §9: at most 10 batches a second, so faster simulation means spare CPU, not more batches
        if (!isSimulating && pendingRequests.isNotEmpty() && currentTime - lastBatchStart >= MIN_BATCH_INTERVAL) {
            val freshestRequests = pendingRequests.filter { currentTime - it.value.first <= 0.1f }
                .mapValues { it.value.second }

            pendingRequests.clear()

            if (freshestRequests.isNotEmpty()) {
                val aiShipIds = freshestRequests.keys
                val snapshotTime = engine.getTotalElapsedTime(false)
                // the trace reads every hit and the detail arrays; otherwise runs are lean (plan: garbage)
                val lean = !org.starficz.combataitweaks.runtime.TraceRecorder.enabled()
                val motion = engine.captureMotion(aiShipIds)
                val damageInputs = engine.captureDamage(aiShipIds)
                latestDamageInputs = damageInputs

                isSimulating = true
                lastBatchStart = currentTime
                PredictorThreadPool.scope.launch {
                    val started = System.nanoTime()
                    try {
                        // the sim world (base once, one branch per option), then each branch's damage
                        val world = World(motion.ships, SimParams(Constants.TOTAL_FUTURE_STATES), motion.targetingRadius,
                            motion.ownTargetRadius)

                        val wanted = freshestRequests.filterKeys { id ->
                            damageInputs.defenses.containsKey(id) && world.byId.containsKey(id)
                        }
                        // each side's fire on the enemies, on base paths: allies' part of the trade (plan §13.3)
                        val sides = wanted.keys.mapNotNull { world.byId[it]?.owner }.toSet()
                        val sideFire = sides.map { owner ->
                            async { owner to SideFire.compute(owner, world.base, damageInputs) }
                        }.awaitAll().toMap()
                        val jobs = wanted.map { (shipId, options) ->
                            async {
                                shipId to predictOptions(world, shipId, options, damageInputs, snapshotTime,
                                    sideFire[world.byId[shipId]!!.owner] ?: emptyMap(), lean)
                            }
                        }

                        val resultsMap = jobs.awaitAll().toMap()
                        lastBatchMillis = (System.nanoTime() - started) / 1_000_000
                        lastBatchBranches = resultsMap.values.sumOf { it.size }
                        val baseEnemyTimelines = world.base.filterKeys { it !in aiShipIds }

                        outputRef.set(SimulationOutput(baseEnemyTimelines, resultsMap))
                    } catch (e: Throwable) {
                        // Throwable, not Exception: an Error (NoClassDefFoundError, StackOverflowError...) used to
                        // escape here and leave isSimulating stuck at true, silently killing the predictor.
                        Global.getLogger(PredictorManager::class.java).error("CAT predictor simulation failed", e)
                        isSimulating = false
                    }
                }
            }
        }
    }

    /**
     * One CAT ship's options. Movement is simulated for every option; the damage only once when no enemy commits
     * to the ship (plan §9 budget): then nothing re-plans around its path, and every option reads the hold
     * branch's damage.
     *
     * When the ship's fire makes committed enemies back off or overload (plan §13.8), the branch runs a second time
     * with those reactions. The vent-now outcome stays the first run's: the ship doesn't fire while venting.
     */
    private suspend fun predictOptions(
        world: World, shipId: String, options: List<OptionRequest>, inputs: DamageInputs, snapshotTime: Float,
        sideHits: Map<String, List<org.starficz.combataitweaks.sim.Hit>>, lean: Boolean,
    ): Map<String, RequestResult> = coroutineScope {
        // each block below runs start to end on one worker thread (nothing suspends inside), so the thread's pool
        // is safe to reset at its start
        fun pool() = if (lean) Scratch.local().also { it.reset() } else null
        val ship = world.byId[shipId]!!
        val owner = ship.owner
        val own = OwnFire(ship.maneuverTargetId, resolveTargets = true, sideHits = sideHits)
        val branches = options.map { it to world.branch(shipId, it.plan) }
        val quiet = branches.all { it.second.committed.isEmpty() }
        val holdBranch = branches.firstOrNull { it.first.name == HOLD } ?: branches.first()
        val shared = if (quiet) {
            DamageSim.run(shipId, owner, holdBranch.second.timelines, inputs, withVent = branches.any { it.first.withVent },
                own, pool = pool(), ownSystem = holdBranch.first.system)
        } else null
        branches.map { (req, branch) ->
            async {
                // quiet: every option reads the hold branch's damage, except one with another defensive system
                // (torpedoes with no ship committed: the system is what changes the outcome)
                val predicted = when {
                    !quiet -> DamageSim.branch(world, shipId, req.plan, branch, inputs, req.withVent, own,
                        if (lean) Scratch.local() else null, req.system)
                    req.system === holdBranch.first.system -> PredictedBranch(branch, shared, Coupling.NONE)
                    else -> PredictedBranch(branch, DamageSim.run(shipId, owner, branch.timelines, inputs, req.withVent,
                        own, pool = pool(), ownSystem = req.system), Coupling.NONE)
                }
                val final = predicted.branch
                val damage = predicted.damage
                val coupling = predicted.coupling
                RequestResult(
                    name = req.name,
                    plan = req.plan,
                    outlook = DamageOutlook(snapshotTime, damage),
                    shipTimeline = final.timelines[shipId]!!,
                    committed = final.committed,
                    pursuitTimelines = final.committed.keys.associateWith { final.timelines[it]!! },
                    approachRange = final.approachRange,
                    clearRange = final.clearRange,
                    standoffRange = final.standoffRange,
                    reactions = coupling.reactions,
                )
            }
        }.awaitAll().associateBy { it.name }
    }

    /** Newest hold-position result for [shipId], or null. For the overlay, debugging tools and the battle check. */
    fun latestHoldResult(shipId: String): RequestResult? = latestResults[shipId]?.get(HOLD)

    /** Newest result of option [name] for [shipId], or null (the trace). */
    fun latestResult(shipId: String, name: String): RequestResult? = latestResults[shipId]?.get(name)

    /** Base-world path (current plan, then coast) of a non-CAT ship from the latest batch, or null. */
    fun latestBaseTimeline(shipId: String): Timeline? = latestEnemyTimelines[shipId]

    private inline fun <K, V> mergeWithRetention(
        oldMap: Map<K, V>,
        newMap: Map<K, V>,
        timestamps: HashMap<K, Float>,
        currentTime: Float,
        threshold: Float = 0.5f,
        isOverridden: (K, Map<K, V>) -> Boolean
    ): Map<K, V> {
        val merged = mutableMapOf<K, V>()

        for ((key, value) in oldMap) {
            val age = currentTime - (timestamps[key] ?: currentTime)
            if (age > threshold || isOverridden(key, newMap)) {
                timestamps.remove(key)
            } else {
                merged[key] = value
            }
        }

        for ((key, value) in newMap) {
            merged[key] = value
            timestamps[key] = currentTime
        }

        return merged
    }

    fun renderDebug(viewport: ViewportAPI) {
        val engine = Global.getCombatEngine() ?: return
        val shipMap = engine.ships.associateBy { it.id }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

        for ((shipId, timeline) in latestEnemyTimelines) {
            val ship = shipMap[shipId] ?: continue
            if (ship.isStationModule || ship.isFighter || !viewport.isNearViewport(ship.location, 2000f)) continue
            GL11.glColor4f(0.6f, 0.6f, 0.6f, 0.3f)
            drawTimeline(timeline)
        }

        val drawnShips = HashSet<String>()
        for ((shipId, options) in latestResults) {
            val ship = shipMap[shipId] ?: continue
            if (ship.isStationModule || !viewport.isNearViewport(ship.location, 2000f)) continue
            drawnShips.add(shipId)
            val driven = ship.customData[MoveController.OPTION] as? String

            for ((name, result) in options) {
                // heat colour: flux the shield would take over the horizon (hull lost for shieldless ships)
                val normal = result.outlook.result?.normal
                val dangerRatio = when {
                    normal == null -> 0f
                    ship.shield != null && ship.shield.type != ShieldAPI.ShieldType.NONE ->
                        normal.potentialFluxEnd / ship.maxFlux.coerceAtLeast(1f)
                    else -> normal.hullLost[normal.steps - 1] / ship.maxHitpoints.coerceAtLeast(1f)
                }
                val hue = 0.55f * (1f - dangerRatio.coerceIn(0f, 1f))
                val heatmapColor = Color.getHSBColor(hue, 1f, 1f)
                GL11.glColor4f(heatmapColor.red / 255f, heatmapColor.green / 255f, heatmapColor.blue / 255f,
                    if (name == driven) 0.9f else 0.35f)
                drawTimeline(result.shipTimeline, if (name == driven) 3f else 1.5f)

                // the hold-position branch shows the enemy plan: each committed enemy's pursuit path (red) and the
                // ring at the range it closes to (faint red, around the ship's current position)
                if (name != HOLD) continue
                for ((enemyId, path) in result.pursuitTimelines) {
                    val enemy = shipMap[enemyId] ?: continue
                    GL11.glColor4f(1f, 0.15f, 0.1f, if (enemy.isFighter) 0.35f else 0.8f)
                    drawTimeline(path)
                    if (!enemy.isFighter) {
                        GL11.glColor4f(1f, 0.15f, 0.1f, 0.25f)
                        GL11.glLineWidth(1f)
                        drawCircle(ship.location.x, ship.location.y, result.committed[enemyId] ?: continue)
                    }
                }
            }
        }

        GL11.glLineWidth(4f)
        for (shipId in drawnShips) {
            val ship = shipMap[shipId] ?: continue
            when (ship.customData[CombatAIv2.BEHAVIOR_STATE] as? String) {
                MoveState.ADVANCE.name -> GL11.glColor4f(0f, 1f, 0f, 0.8f)
                MoveState.HOLD.name -> GL11.glColor4f(0.5f, 1f, 0f, 0.8f)
                MoveState.BACK_OFF.name -> GL11.glColor4f(1f, 0.5f, 0f, 0.8f)
                MoveState.DISENGAGE.name -> GL11.glColor4f(1f, 0f, 0f, 0.8f)
                MoveState.VENTING.name -> GL11.glColor4f(1f, 1f, 1f, 0.8f)
                else -> continue
            }
            drawCircle(ship.location.x, ship.location.y, ship.collisionRadius * 1.2f)
        }
        GL11.glPopAttrib()
    }

    private fun drawCircle(cx: Float, cy: Float, radius: Float, segments: Int = 64) {
        GL11.glBegin(GL11.GL_LINE_LOOP)
        for (i in 0 until segments) {
            val theta = 2.0 * Math.PI * i / segments
            GL11.glVertex2f(cx + (radius * cos(theta)).toFloat(), cy + (radius * sin(theta)).toFloat())
        }
        GL11.glEnd()
    }

    private fun drawTimeline(timeline: Timeline, width: Float = 2f) {
        GL11.glLineWidth(width)
        GL11.glBegin(GL11.GL_LINE_STRIP)
        for (i in 0 until timeline.steps) {
            GL11.glVertex2f(timeline.x[i], timeline.y[i])
        }
        GL11.glEnd()

        GL11.glBegin(GL11.GL_LINES)
        for (i in 0 until timeline.steps step 20) {
            val facingRad = Math.toRadians(timeline.facing[i].toDouble())
            val p2x = timeline.x[i] + (50f * cos(facingRad)).toFloat()
            val p2y = timeline.y[i] + (50f * sin(facingRad)).toFloat()
            GL11.glVertex2f(timeline.x[i], timeline.y[i])
            GL11.glVertex2f(p2x, p2y)
        }
        GL11.glEnd()
    }
}

class CATCombatPlugin : BaseEveryFrameCombatPlugin() {

    override fun advance(amount: Float, events: List<InputEventAPI>?) {
        val engine = Global.getCombatEngine() ?: return
        // reasons with numbers only when something reads them (the trace)
        org.starficz.combataitweaks.decide.Explain.enabled = org.starficz.combataitweaks.runtime.TraceRecorder.enabled()

        for (ship in engine.ships) {
            if (ship.isFighter || ship.isHulk || !ship.isAlive) continue
            if (ship.hasListenerOfClass(CombatAIv2::class.java)) continue
            if (!wantsCatAI(ship)) continue

            val existingTag = ship.variant.tags.find { it.startsWith(CombatAIConfig.TAG_PREFIX) }
            val currentConfig = if (existingTag != null) {
                CombatAIConfig.fromJsonString(existingTag.removePrefix(CombatAIConfig.TAG_PREFIX))
            } else CombatAIConfig()
            ship.addListener(CombatAIv2(ship, currentConfig))
        }

        if (engine.isPaused) return
        if (engine.customData[PredictorManager.FLAG_KEY] != true) return

        PredictorManager.getInstance(engine).advance(engine)
        // battle trace for offline analysis (dev mode or -Dcat.trace=true)
        org.starficz.combataitweaks.runtime.TraceRecorder.get(engine)?.advance(engine)
    }

    /**
     * Ships enabled in the refit dialog, plus every SCY hull: the config dialog says those must use this AI, but
     * before, only SCY ships the player had opened and saved in the dialog got it (and no NPC ones).
     */
    private fun wantsCatAI(ship: ShipAPI): Boolean {
        if (ship.hullSpec.isPhase) return false
        return ship.variant.hasTag(ENABLE_CAT_AI_TAG) || ship.hullSpec.sourceMod?.id == "SCY"
    }

    // Dev mode only: predicted paths (heat-coloured by incoming flux) and a ring showing each ship's behaviour state.
    override fun renderInWorldCoords(viewport: ViewportAPI) {
        if (!Global.getSettings().isDevMode) return
        val engine = Global.getCombatEngine() ?: return
        if (engine.customData[PredictorManager.FLAG_KEY] != true) return

        PredictorManager.getInstance(engine).renderDebug(viewport)
    }
}
