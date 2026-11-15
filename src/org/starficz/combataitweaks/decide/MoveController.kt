package org.starficz.combataitweaks.decide

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import org.lazywizard.lazylib.MathUtils
import org.starficz.combataitweaks.CombatAIConfig
import org.starficz.combataitweaks.CombatAIv2
import org.starficz.combataitweaks.predictor.OptionRequest
import org.starficz.combataitweaks.predictor.PredictorManager.Companion.ADVANCE
import org.starficz.combataitweaks.predictor.PredictorManager.Companion.BACK_OFF
import org.starficz.combataitweaks.predictor.PredictorManager.Companion.DEFENSE_SUFFIX
import org.starficz.combataitweaks.predictor.PredictorManager.Companion.HOLD
import org.starficz.combataitweaks.predictor.PredictorManager.Companion.STANDOFF
import org.starficz.combataitweaks.predictor.PredictorManager.Companion.SYSTEM_SUFFIX
import org.starficz.combataitweaks.predictor.RequestResult
import org.starficz.combataitweaks.runtime.CombatScan
import org.starficz.combataitweaks.runtime.VanillaManeuver
import org.starficz.combataitweaks.runtime.VanillaTarget
import org.starficz.combataitweaks.sim.OwnPlan
import org.starficz.combataitweaks.sim.Pursuit
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.WeaponAPI
import org.starficz.combataitweaks.predictor.Constants
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns the ship's movement (plan §8.2 to §8.4). Main thread.
 * - [options] lists the branches to predict: `HOLD`, `BACK_OFF` and `ADVANCE`, and system variants of the last two
 *   when the ship has a known mobility system ready (milestone 4a: `BACK_OFF` is vanilla's direction, straight
 *   away from the target).
 * - [decide] runs [MoveDecider] on the fresh results every decision tick.
 * - [apply] sets the state's flags every frame (each for 0.1 s), thrusts directly where vanilla won't back off
 *   (automated ships), and uses or blocks the mobility system.
 */
class MoveController(
    private val ship: ShipAPI, private val config: CombatAIConfig, private val defense: DefenseController,
) {

    private val decider = MoveDecider()
    private val profile = MobilityPhases.of(ship)

    /** The latest decision; null while there is no fresh prediction (vanilla moves on its own). */
    var choice: MoveChoice? = null
        private set
    private var driven: RequestResult? = null
    private var target: ShipAPI? = null
    private var holdRange = -1f
    private var lastHoldOk = true
    private var lastRepick = -99f
    private var lastVanillaBackOffFree = false
    private var holdTarget: ShipAPI? = null
    /** The last strafe maneuver CAT handed vanilla: when, on whom, at what surface range. */
    private var issuedAt = -99f
    private var issuedTarget: ShipAPI? = null
    private var issuedRange = 0f
    private var issuedManeuver: Any? = null

    /**
     * The options to predict next. [latest] is the ship's newest results: their committed enemies set the escape
     * direction and the 4b back-off directions. The 4b directions are only asked for while backing off is in play
     * (the ship is backing off, or holding wasn't ok last time), to keep the branch count down.
     * - `HOLD`: keep still (the vent's hold option); `STANDOFF`: the `HOLD` state, just outside every enemy's gun
     *   reach (plan §13.4, its own safety and flux).
     * - `ADVANCE`: close to range and stay (its flux limit); `ADVANCE~`: do it for [REACTION_TIME], then escape (its
     *   safety).
     * - `+DEFENSE` (plan §11b): `HOLD`, `STANDOFF` and `BACK_OFF` with the defensive system used now, when it can be;
     *   every other option keeps it off.
     */
    fun options(target: ShipAPI?, latest: Map<String, RequestResult>): List<OptionRequest> {
        val plain = if (profile != null) MobilityPhases.plainPhases(ship) else emptyList()
        val sys = profile?.let { MobilityPhases.systemPhases(ship, it) }
        val off = defense.plainPlan()
        val on = defense.defensePlan()
        val list = ArrayList<OptionRequest>(14)
        list += OptionRequest(HOLD, OwnPlan.Hold(plain), withVent = true)
        if (on != null) list += OptionRequest(HOLD + DEFENSE_SUFFIX, OwnPlan.Hold(plain), withVent = false, on)
        if (target != null) {
            val committed = latest[HOLD]?.committed?.keys ?: emptySet()
            val centre = threatCentre(committed)
            // the escape: straight away from the threat (or from the target when nothing commits yet)
            val escape = centre?.let { (cx, cy) -> OwnPlan.Thrust(ship.location.x - cx, ship.location.y - cy, plain) }
                ?: OwnPlan.BackAway(target.id, plain)
            fun thenEscape(first: OwnPlan) = OwnPlan.Then(first, REACTION_TIME, escape)
            val state = choice?.state
            // branches the decision can't pick are skipped (lossless): no ADVANCE while flux keeps it disengaged,
            // and no 4b directions while vanilla's own back-off is ok and free (ties go to it)
            val advanceMatters = !(state == MoveState.DISENGAGE && ship.fluxLevel > config.reengageIfFluxDropsBelowLevel)
            val backingOffMatters = (state == MoveState.BACK_OFF || state == MoveState.DISENGAGE || !lastHoldOk) &&
                !lastVanillaBackOffFree
            list += OptionRequest(STANDOFF, OwnPlan.Standoff(target.id, plain), withVent = false)
            list += OptionRequest(BACK_OFF, OwnPlan.BackAway(target.id, plain), withVent = true)
            if (on != null) {
                list += OptionRequest(STANDOFF + DEFENSE_SUFFIX, OwnPlan.Standoff(target.id, plain), withVent = false, on)
                list += OptionRequest(BACK_OFF + DEFENSE_SUFFIX, OwnPlan.BackAway(target.id, plain), withVent = false, on)
            }
            if (advanceMatters) {
                list += OptionRequest(ADVANCE + ESCAPE, thenEscape(OwnPlan.Approach(target.id, plain)), withVent = false)
                // keeping it up (close to range and stay): the flux it builds, for the advance flux limit
                list += OptionRequest(ADVANCE, OwnPlan.Approach(target.id, plain), withVent = false)
            }
            if (sys != null) {
                list += OptionRequest(BACK_OFF + SYSTEM_SUFFIX, OwnPlan.BackAway(target.id, sys), withVent = false)
                if (advanceMatters) {
                    list += OptionRequest(ADVANCE + ESCAPE + SYSTEM_SUFFIX,
                        OwnPlan.Then(OwnPlan.Approach(target.id, sys), REACTION_TIME, escape), withVent = false)
                }
            }
            // toward the friendly ships whenever backing off is in play: it wins ties, so it isn't covered by the
            // "vanilla's back-off is free" shortcut
            if (state == MoveState.BACK_OFF || state == MoveState.DISENGAGE || !lastHoldOk) {
                allyDirection()?.let { (dx, dy) ->
                    list += OptionRequest(MoveRule.ALLY_BACK_OFF, OwnPlan.Thrust(dx, dy, plain), withVent = false)
                }
            }
            if (backingOffMatters && centre != null) {
                for ((name, dir) in backOffDirections(centre)) {
                    list += OptionRequest(name, OwnPlan.Thrust(dir.first, dir.second, plain), withVent = false)
                }
            }
        }
        // every option without the suffix keeps the defensive system off
        if (off == null) return list
        return list.map { if (it.system != null) it else OptionRequest(it.name, it.plan, it.withVent, off) }
    }

    /**
     * Toward the DPS-weighted centre of the friendly ships within [ALLY_RANGE] (the help that can bail this ship out),
     * or null when there are none, they are already within [ALLY_CLOSE], or the way leads off the map.
     */
    private fun allyDirection(): Pair<Float, Float>? {
        val engine = Global.getCombatEngine() ?: return null
        val scan = CombatScan.of(engine)
        var cx = 0f; var cy = 0f; var total = 0f
        for (k in scan.ships.indices) {
            val ally = scan.ships[k]
            if (ally === ship || ally.owner != ship.owner) continue
            if (ally.isRetreating || ally.isStationModule) continue
            if (MathUtils.getDistanceSquared(ally.location, ship.location) > ALLY_RANGE * ALLY_RANGE) continue
            val dps = scan.dps(ally)
            if (dps <= 0f) continue
            cx += ally.location.x * dps; cy += ally.location.y * dps; total += dps
        }
        if (total <= 0f) return null
        val dx = cx / total - ship.location.x
        val dy = cy / total - ship.location.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < ALLY_CLOSE) return null
        val reach = ship.maxSpeed * Constants.PREDICTION_DURATION
        val endX = ship.location.x + dx / len * reach; val endY = ship.location.y + dy / len * reach
        if (abs(endX) > engine.mapWidth / 2f - EDGE_MARGIN || abs(endY) > engine.mapHeight / 2f - EDGE_MARGIN) return null
        return (dx / len) to (dy / len)
    }

    /** The DPS-weighted centre of the committed enemies (non-fighter ships), or null when none is around. */
    private fun threatCentre(committed: Set<String>): Pair<Float, Float>? {
        if (committed.isEmpty()) return null
        val engine = Global.getCombatEngine() ?: return null
        val scan = CombatScan.of(engine)
        var cx = 0f; var cy = 0f; var total = 0f
        for (k in scan.ships.indices) {
            val enemy = scan.ships[k]
            if (enemy.id !in committed) continue
            val dps = scan.dps(enemy).coerceAtLeast(1f)
            cx += enemy.location.x * dps; cy += enemy.location.y * dps; total += dps
        }
        return if (total > 0f) (cx / total) to (cy / total) else null
    }

    /**
     * Plan §7 back-off directions (milestone 4b): away from the threat [centre], and +-35, +-70 degrees from it.
     * Directions that could reach within [EDGE_MARGIN] of the map edge within the horizon at full speed are dropped.
     */
    private fun backOffDirections(centre: Pair<Float, Float>): List<Pair<String, Pair<Float, Float>>> {
        val engine = Global.getCombatEngine() ?: return emptyList()
        val away = Math.toDegrees(atan2((ship.location.y - centre.second).toDouble(),
            (ship.location.x - centre.first).toDouble())).toFloat()
        val reach = ship.maxSpeed * Constants.PREDICTION_DURATION
        val halfW = engine.mapWidth / 2f - EDGE_MARGIN
        val halfH = engine.mapHeight / 2f - EDGE_MARGIN
        return DIRECTIONS.mapNotNull { offset ->
            val rad = Math.toRadians((away + offset).toDouble())
            val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
            val endX = ship.location.x + dx * reach; val endY = ship.location.y + dy * reach
            if (abs(endX) > halfW || abs(endY) > halfH) null
            else (BACK_OFF + "@" + (if (offset > 0f) "+" else "") + offset.toInt()) to (dx to dy)
        }
    }

    /** One decision tick. Returns null (and drops the decision) without a fresh `HOLD` prediction. */
    fun decide(now: Float, target: ShipAPI, results: Map<String, RequestResult>, vent: VentController): MoveChoice? {
        val fresh = results.values.filter { now - it.outlook.startTime <= VentController.MAX_AGE }
        val options = fresh.mapNotNull { r ->
            val kind = when {
                r.name.startsWith(ADVANCE) -> MoveKind.ADVANCE
                r.name.startsWith(BACK_OFF) -> MoveKind.BACK_OFF
                else -> MoveKind.HOLD
            }
            r.outlook.moveOption(r.name, kind, r.name.endsWith(SYSTEM_SUFFIX), now, ship.maxFlux, r.name.contains(ESCAPE),
                standoff = r.name.startsWith(STANDOFF), defense = r.name.endsWith(DEFENSE_SUFFIX))
        }
        val hullLimit = hullLimit()
        lastHoldOk = options.firstOrNull { it.name == STANDOFF }?.let { MoveRule.ok(it, hullLimit) } ?: true
        lastVanillaBackOffFree = options.firstOrNull { it.name == BACK_OFF }?.let { MoveRule.ok(it, hullLimit) && it.cost <= 0f } ?: false
        if (options.none { it.kind == MoveKind.HOLD }) {
            standDown()
            return null
        }
        val advance = fresh.firstOrNull { it.name == ADVANCE + ESCAPE }
        val gap = advance?.let { MathUtils.getDistance(ship.location, target.location) - it.approachRange } ?: 0f
        val c = decider.decide(now, inputs(options, vent.activeVent?.option?.name, hullLimit, gap.coerceAtLeast(0f)))
        choice = c
        driven = c.option?.let { o -> fresh.firstOrNull { it.name == o.name } } ?: fresh.firstOrNull { it.name == HOLD }
        this.target = target
        ship.setCustomData(TARGET, target)
        ship.setCustomData(TRACE, c.reason)
        ship.setCustomData(OPTION, c.option?.name)
        return c
    }

    /** Hull lost over the horizon above this makes an option not ok (the vent's limit). */
    private fun hullLimit() = if (ship.hullLevel < config.stopVentingBelowHullLevel) VentRule.HULL_EPSILON
        else ship.maxHitpoints * config.maxHullDamageLevelToVent

    private fun inputs(options: List<MoveOption>, ventOption: String?, hullLimit: Float, gap: Float) = MoveInputs(
        options = options,
        venting = ship.fluxTracker.isVenting,
        ventOption = ventOption,
        fluxLevel = ship.fluxLevel,
        hullLimit = hullLimit,
        previous = decider.state,
        advanceGap = gap,
        forceAdvanceIfFluxBelowLevel = config.forceAdvanceIfFluxBelowLevel,
        backoffIfFluxAboveLevel = config.backoffIfFluxAboveLevel,
        backoffIfHardfluxAboveLevel = config.backoffIfHardfluxAboveLevel,
        reengageIfFluxDropsBelowLevel = config.reengageIfFluxDropsBelowLevel,
        maxFluxLevelRaiseToHarass = config.maxFluxLevelRaiseToHarass,
        defenseLead = defense.lead(),
    )

    /**
     * With CAT standing down (no enemy ship near, plan §8.5) the defensive system is still CAT's: used when keeping on
     * as vanilla does is worse than with it (torpedoes with no ship near). `HOLD` stands in for vanilla's movement.
     */
    fun standingDownDefense(now: Float, results: Map<String, RequestResult>): Boolean {
        fun option(name: String, defense: Boolean) = results[name]?.takeIf { now - it.outlook.startTime <= VentController.MAX_AGE }
            ?.outlook?.moveOption(name, MoveKind.HOLD, false, now, ship.maxFlux, defense = defense)
        val hold = option(HOLD, false) ?: return false
        val withSystem = option(HOLD + DEFENSE_SUFFIX, true) ?: return false
        if (ship.fluxTracker.isVenting) return false
        return MoveRule.withDefense(inputs(listOf(hold, withSystem), null, hullLimit(), 0f), hold) === withSystem
    }

    /** Gives the ship back to vanilla (no enemies, no prediction, standing down). */
    fun standDown() {
        choice = null
        driven = null
        decider.reset()
        holdRange = -1f
        ship.setCustomData(TRACE, null)
        ship.setCustomData(OPTION, null)
        ship.setCustomData(CombatAIv2.USE_MOVEMENT_SYSTEM_TO_BACKOFF, false)
        ship.setCustomData(CombatAIv2.USE_MOVEMENT_SYSTEM_TO_ADVANCE, false)
    }

    /** Every frame: the flags of the current state (plan §8.4), direct thrust, the mobility system. */
    fun apply(holdWhileVenting: Boolean) {
        val c = choice ?: return
        val target = target?.takeIf { it.isAlive && !it.isHulk }
        // make vanilla maneuver on the ship CAT predicted against, so the range flags apply to it. Vanilla takes
        // the override when it next picks a maneuver and then clears it, so it is set every frame (as vanilla's
        // own DarkenedGazeSystemAI does)
        if (c.state != MoveState.HOLD) holdRange = -1f
        val standoffRange = driven?.takeIf { it.name.startsWith(STANDOFF) }?.standoffRange?.takeIf { it > 0f }
        if (target != null && c.state == MoveState.HOLD) {
            if (standoffRange != null) {
                // vanilla's strafe backs up only once it is 100 inside its range: start that at the standoff
                holdRange = standoffRange + STANDOFF_BAND
                holdTarget = target
            } else if (holdRange < 0f || holdTarget !== target) {
                // no standoff (no prediction for it): the range it had when it started holding
                holdRange = MathUtils.getDistance(ship.location, target.location)
                holdTarget = target
            }
        }
        if (target != null) {
            val ai = ship.shipAI
            ai?.setTargetOverride(target)
            val now = Global.getCombatEngine()?.getTotalElapsedTime(false) ?: 0f
            // ADVANCE and HOLD: hand vanilla the strafe maneuver itself (the override doesn't count while an order
            // brings its own maneuver, e.g. escorting the flagship), re-issued before it runs out
            val centre = when (c.state) {
                MoveState.ADVANCE -> driven?.approachRange?.takeIf { it > 0f }
                MoveState.HOLD -> holdRange.takeIf { it > 0f }
                else -> null
            }
            val issued = centre != null && issueStrafe(now, target, centre - ship.collisionRadius - target.collisionRadius)
            // otherwise: a maneuver vanilla picked earlier runs for 2-5 s; when it is on another ship, have it
            // re-pick now (at most once a second, so vanilla's own maneuvers still get to run)
            if (!issued && ai != null && VanillaTarget.of(ship) !== target && now - lastRepick >= REPICK_INTERVAL) {
                ai.cancelCurrentManeuver()
                lastRepick = now
            }
        } else { issuedTarget = null; issuedManeuver = null }
        val flags = ship.aiFlags
        when (c.state) {
            MoveState.ADVANCE -> {
                flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_DURATION)
                flags.setFlag(AIFlags.HARASS_MOVE_IN, FLAG_DURATION)
                flags.unsetFlag(AIFlags.HARASS_MOVE_IN_COOLDOWN)
                flags.unsetFlag(AIFlags.BACK_OFF)
                flags.unsetFlag(AIFlags.DO_NOT_PURSUE)
                flags.unsetFlag(AIFlags.BACK_OFF_MIN_RANGE)
                // the lever that actually brings it closer: cap the range vanilla holds at its engagement range
                val range = driven?.approachRange ?: 0f
                if (range > 0f) flags.setFlag(AIFlags.BACK_OFF_MAX_RANGE, FLAG_DURATION, range + Pursuit.PRESS_IN)
            }
            MoveState.HOLD -> {
                // the standoff backs up as enemies close: vanilla's strafe does that only without DO_NOT_BACK_OFF
                // (it also backs up with worse flux than its target, which only adds distance). Keeping still
                // without a standoff stops it backing off at all
                if (standoffRange != null) flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF)
                else flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_DURATION)
                flags.unsetFlag(AIFlags.BACK_OFF)
                flags.unsetFlag(AIFlags.DO_NOT_PURSUE)
                // the standoff's range (or the one it had when it started holding): no closer (vanilla would press
                // in), no further
                if (target != null) {
                    flags.setFlag(AIFlags.BACK_OFF_MIN_RANGE, FLAG_DURATION, holdRange)
                    flags.setFlag(AIFlags.BACK_OFF_MAX_RANGE, FLAG_DURATION, holdRange)
                }
            }
            MoveState.BACK_OFF, MoveState.DISENGAGE -> {
                flags.setFlag(AIFlags.BACK_OFF, FLAG_DURATION)
                flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_DURATION)
                flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF)
                flags.unsetFlag(AIFlags.BACK_OFF_MAX_RANGE)
                if (c.state == MoveState.DISENGAGE) {
                    val clear = driven?.clearRange?.takeIf { it > 0f } ?: DEFAULT_CLEAR_RANGE
                    flags.setFlag(AIFlags.BACK_OFF_MIN_RANGE, FLAG_DURATION, clear.coerceIn(MIN_CLEAR_RANGE, MAX_CLEAR_RANGE))
                } else flags.unsetFlag(AIFlags.BACK_OFF_MIN_RANGE)
                // a 4b direction is flown directly; vanilla's own direction too for automated ships, which ignore
                // BACK_OFF unless venting (plan §8.4)
                val plan = driven?.plan
                if (plan is OwnPlan.Thrust) thrust(plan.dirX, plan.dirY)
                else if (target != null && !vanillaBacksOff()) {
                    thrust(ship.location.x - target.location.x, ship.location.y - target.location.y)
                }
            }
            MoveState.VENTING -> {
                // plan §8.4: a safe vent CAT chose to do in place holds position; any other vent backs off
                if (holdWhileVenting) {
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_DURATION)
                    flags.setFlag(AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, FLAG_DURATION)
                    flags.unsetFlag(AIFlags.BACK_OFF)
                    flags.unsetFlag(AIFlags.DO_NOT_PURSUE)
                    flags.unsetFlag(AIFlags.BACK_OFF_MIN_RANGE)
                    flags.unsetFlag(AIFlags.BACK_OFF_MAX_RANGE)
                } else {
                    flags.setFlag(AIFlags.BACK_OFF_MIN_RANGE, FLAG_DURATION, DEFAULT_CLEAR_RANGE)
                    flags.setFlag(AIFlags.BACK_OFF, FLAG_DURATION)
                    flags.setFlag(AIFlags.DO_NOT_PURSUE, FLAG_DURATION)
                    flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF)
                    flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING)
                    flags.unsetFlag(AIFlags.BACK_OFF_MAX_RANGE)
                }
            }
        }
        applySystem(c)
    }

    /**
     * The strafe maneuver on [target] at [surface] range (plan §8.4): handed over again as soon as vanilla runs
     * something else (it ran out, or vanilla picked another), or the target or range changed. When the current
     * maneuver can't be read, on a timer instead. False when vanilla's AI can't take one.
     */
    private fun issueStrafe(now: Float, target: ShipAPI, surface: Float): Boolean {
        val last = issuedManeuver
        if (last != null && issuedTarget === target && abs(surface - issuedRange) <= RANGE_TOLERANCE) {
            val current = VanillaManeuver.isCurrent(ship, last)
            if (current == true || (current == null && now - issuedAt < REISSUE_INTERVAL)) return true
        }
        val maneuver = VanillaManeuver.strafe(ship, target, surface, MANEUVER_SECONDS) ?: return false
        issuedManeuver = maneuver; issuedAt = now; issuedTarget = target; issuedRange = surface
        return true
    }

    /** Vanilla backs off on the BACK_OFF flag unless its AI config forbids it outside a vent (automated ships). */
    private fun vanillaBacksOff(): Boolean = ship.shipAI?.config?.backingOffWhileNotVentingAllowed != false

    /**
     * Thrusts toward world direction (dx, dy) with the engine commands, the move the back-off branches predict.
     * Only the movement commands are touched (vanilla keeps turning, shielding and firing). Blocks remove every
     * command of a type, ours included, so a type we give is never blocked.
     */
    private fun thrust(dx: Float, dy: Float) {
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        val rad = Math.toRadians(ship.facing.toDouble())
        val fx = cos(rad).toFloat(); val fy = sin(rad).toFloat()
        val forward = (dx * fx + dy * fy) / len
        val left = (-dx * fy + dy * fx) / len   // left of facing is facing + 90
        when {
            forward > AXIS_DEADZONE -> { ship.giveCommand(ShipCommand.ACCELERATE, null, 0); ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS) }
            forward < -AXIS_DEADZONE -> { ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0); ship.blockCommandForOneFrame(ShipCommand.ACCELERATE) }
            else -> { ship.blockCommandForOneFrame(ShipCommand.ACCELERATE); ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS) }
        }
        when {
            left > AXIS_DEADZONE -> { ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0); ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT) }
            left < -AXIS_DEADZONE -> { ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0); ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT) }
            else -> { ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT); ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT) }
        }
        ship.blockCommandForOneFrame(ShipCommand.DECELERATE)
    }

    /**
     * Plan §8.3: use the system when the driven option is a system variant (a toggle stays on while it is);
     * otherwise block vanilla's use for systems v1 overrode. Publishes SCY's two keys either way.
     */
    private fun applySystem(c: MoveChoice) {
        val useSystem = c.option?.system == true
        val backingOff = c.state == MoveState.BACK_OFF || c.state == MoveState.DISENGAGE
        ship.setCustomData(CombatAIv2.USE_MOVEMENT_SYSTEM_TO_BACKOFF, useSystem && backingOff)
        ship.setCustomData(CombatAIv2.USE_MOVEMENT_SYSTEM_TO_ADVANCE, useSystem && c.state == MoveState.ADVANCE)
        if (profile?.enforceOverride == true) useSystem(ship, useSystem)
    }

    companion object {
        /** Custom-data keys: the latest decision's reason line, and the option it drives. */
        const val TRACE = "CAT_MOVE_TRACE"
        const val OPTION = "CAT_MOVE_OPTION"
        /** The ship CAT advances on and backs off from (for the trace). */
        const val TARGET = "CAT_MOVE_TARGET"
        private const val FLAG_DURATION = 0.1f
        /** Disengage range when nothing commits (v1's value), and the clamp on the computed one. */
        private const val DEFAULT_CLEAR_RANGE = 2500f
        private const val MIN_CLEAR_RANGE = 1000f
        private const val MAX_CLEAR_RANGE = 4000f
        private const val AXIS_DEADZONE = 0.3f
        private const val EDGE_MARGIN = 300f
        private val DIRECTIONS = listOf(0f, 35f, -35f, 70f, -70f)
        /** Suffix of the options judged "do it, then escape". */
        const val ESCAPE = "~"
        private const val REPICK_INTERVAL = 1f
        /** A handed-over strafe maneuver lasts [MANEUVER_SECONDS] (vanilla: +-0.5 s); re-issued after this. */
        private const val REISSUE_INTERVAL = 1f
        private const val MANEUVER_SECONDS = 3f
        /**
         * Translation of obfuscated code (StrafeTargetManeuverV2): the strafe closes in beyond its range + 20 and
         * backs up only inside its range - 100. The standoff flies its range + this, so it backs up at the standoff
         * and never sits closer than the sim has it.
         */
        private const val STANDOFF_BAND = 100f
        /** A range change bigger than this re-issues the maneuver at once. */
        private const val RANGE_TOLERANCE = 50f
        /** Friendly ships within this range count as help; within [ALLY_CLOSE] the ship is already with them. */
        private const val ALLY_RANGE = 3000f
        private const val ALLY_CLOSE = 300f
        /**
         * How long the ship keeps doing an option before it can turn around: the decision's 0.5 s hysteresis plus
         * about 0.2 s of decision tick and prediction age. The "then escape" options switch to the escape here.
         */
        const val REACTION_TIME = 0.7f

        /** Turns the ship's system on ([use]) or keeps it off: toggles flip to match, others fire or are blocked. */
        fun useSystem(ship: ShipAPI, use: Boolean) {
            val sys = ship.system ?: return
            if (sys.specAPI.isToggle) {
                if (!sys.isCoolingDown && (use xor sys.isOn)) ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
            } else if (use && sys.state == ShipSystemAPI.SystemState.IDLE) {
                ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
            } else if (!use) {
                ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
            }
        }
    }
}
