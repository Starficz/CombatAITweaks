package org.starficz.combataitweaks.runtime

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BeamAPI
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEntityAPI
import com.fs.starfarer.api.combat.DamageAPI
import com.fs.starfarer.api.combat.DamagingProjectileAPI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI
import com.fs.starfarer.api.combat.listeners.DamageListener
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier
import org.lwjgl.util.vector.Vector2f
import org.starficz.combataitweaks.CombatAIv2
import org.starficz.combataitweaks.decide.VentController
import org.starficz.combataitweaks.predictor.PredictorManager
import org.starficz.combataitweaks.predictor.RequestResult
import org.starficz.combataitweaks.sim.DT
import org.starficz.combataitweaks.sim.DamageInputs
import org.starficz.combataitweaks.sim.DefenseOutcome
import org.starficz.combataitweaks.sim.Timeline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap

/**
 * Records a battle for offline analysis: what really happened (ships, hits, missiles) next to what CAT predicted.
 * JSON lines, one record per line, written to `saves/common/cat_trace/<battle time>/part_NNN.data` (the game allows
 * text writes there only, 1 MB per write). Analyse with `tools/trace_report.py`.
 *
 * On in dev mode, or with the JVM option -Dcat.trace=true.
 *
 * Records (field `k`):
 * - `meta`: once.
 * - `ship`: every ship and fighter, every [SHIP_EVERY] s: position, velocity, facing, flux, hull, shield, system,
 *   vent/overload; for CAT ships also the behaviour state and the vent decision's reason line.
 * - `hit`: every hit on a CAT ship before defences (raw damage, shooter, delivery, shield hit); `dmg`: what it did.
 * - `msl`: every missile within [MISSILE_RANGE] of a CAT ship, every [SHIP_EVERY] s.
 * - `in`: the missiles and shots the predictor was given for a batch (what the capture saw).
 * - `pred`: a CAT ship's newest hold-branch prediction, at most every [PRED_EVERY] s: its path, the committed
 *   enemies and their paths, the predicted hits binned by shooter, and the defence outcome with and without venting.
 * - `predo`: the same for the batch's other vent option (`BACK_OFF`, field `opt`), when it was predicted.
 */
class TraceRecorder private constructor() {

    private val tag = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    private val buffer = StringBuilder(CHUNK + 4096)
    private var part = 0
    private var lastFlush = 0f
    private var lastShips = -1f
    private var failed = false
    private val listened = HashSet<String>()
    private val lastPred = HashMap<String, Float>()
    private val lastPredStart = HashMap<String, Float>()
    private var lastInputs: DamageInputs? = null
    private var lastInputsTime = -1f
    private val missileIds = IdentityHashMap<Any, Int>()
    private val line = JsonLine()

    fun advance(engine: CombatEngineAPI) {
        val now = engine.getTotalElapsedTime(false)
        if (buffer.isEmpty() && part == 0) {
            emit(line.begin("meta", now).num("dt", DT, 2).str("tag", tag).end())
        }
        val catShips = engine.ships.filter { it.hasListenerOfClass(CombatAIv2::class.java) && it.isAlive && !it.isHulk }
        for (s in catShips) if (listened.add(s.id)) attach(engine, s)

        if (now - lastShips >= SHIP_EVERY) {
            lastShips = now
            for (s in engine.ships) if (!s.isHulk && s.isAlive && !s.isShuttlePod) recordShip(now, s)
            recordMissiles(engine, now, catShips)
        }
        val predictor = PredictorManager.getInstance(engine)
        recordInputs(now, predictor.latestDamageInputs)
        for (s in catShips) {
            if (now - (lastPred[s.id] ?: -99f) < PRED_EVERY) continue
            val hold = predictor.latestHoldResult(s.id) ?: continue
            if (hold.outlook.startTime == lastPredStart[s.id]) continue
            lastPred[s.id] = now
            lastPredStart[s.id] = hold.outlook.startTime
            recordPrediction(now, s, hold)
            predictor.latestResult(s.id, PredictorManager.BACK_OFF)
                ?.takeIf { it.outlook.startTime == hold.outlook.startTime }
                ?.let { recordPrediction(now, s, it, "predo") }
        }
        if (now - lastFlush >= FLUSH_EVERY || engine.isCombatOver) {
            lastFlush = now
            flush()
        }
    }

    // ---------------------------------------------------------------------------------------------------- actual

    private fun recordShip(now: Float, s: ShipAPI) {
        val l = line.begin("ship", now).str("id", s.id).str("hull", s.hullSpec.hullId).int("own", s.owner)
            .num("x", s.location.x).num("y", s.location.y).num("vx", s.velocity.x).num("vy", s.velocity.y)
            .num("f", s.facing)
        if (s.isFighter) { emit(l.bool("ftr", true).end()); return }
        val ft = s.fluxTracker
        l.num("flux", ft.currFlux, 0).num("hard", ft.hardFlux, 0).num("maxFlux", ft.maxFlux, 0)
            .num("hp", s.hitpoints, 0).bool("vent", ft.isVenting).bool("ovl", ft.isOverloaded)
            .bool("sh", s.shield?.isOn == true).num("shf", s.shield?.facing ?: Float.NaN, 0)
            .bool("sys", s.system?.isActive == true)
        // the system as the motion capture reads it: state, charges (max > 1 only), cooldown left
        s.system?.let { sys ->
            l.str("sst", sys.state.name)
            if (sys.maxAmmo in 2 until Int.MAX_VALUE) l.int("sam", sys.ammo)
            if (sys.cooldownRemaining > 0f) l.num("scd", sys.cooldownRemaining, 2)
        }
        s.shipAI?.aiFlags?.let { flags ->
            VanillaTarget.of(s)?.let { l.str("mt", it.id) }
            // the target of the maneuver vanilla's sequence switched to in the last second, if any
            (flags.getCustom(AIFlags.MANEUVER_TARGET) as? ShipAPI)?.let { l.str("mft", it.id) }
            val override = runCatching { s.shipAI?.config?.personalityOverride }.getOrNull()
            l.str("pers", override ?: s.captain?.personalityAPI?.id)
            (flags.getCustom(AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float)?.let { l.num("mr", it, 0) }
        }
        if (s.hasListenerOfClass(CombatAIv2::class.java)) {
            l.str("st", s.customData[CombatAIv2.BEHAVIOR_STATE]?.toString())
            l.str("vt", s.customData[VentController.TRACE]?.toString())
            l.str("mv", s.customData[org.starficz.combataitweaks.decide.MoveController.TRACE]?.toString())
            (s.customData[org.starficz.combataitweaks.decide.MoveController.TARGET] as? ShipAPI)?.let { l.str("ct", it.id) }
            if (s.customData[org.starficz.combataitweaks.decide.ShieldController.DROPPING] == true) l.bool("shd", true)
            // why the shield stays up while there is hard flux to drain (plan §13.9)
            if (s.shield?.isOn == true && ft.hardFlux > 0.1f * ft.maxFlux) {
                l.str("shr", s.customData[org.starficz.combataitweaks.decide.ShieldController.TRACE]?.toString())
            }
        }
        emit(l.end())
    }

    private fun missileId(m: Any): Int = missileIds.getOrPut(m) { missileIds.size + 1 }

    private fun recordMissiles(engine: CombatEngineAPI, now: Float, catShips: List<ShipAPI>) {
        if (catShips.isEmpty()) return
        for (m in engine.missiles) {
            if (m.isFading || m.isExpired) continue
            val near = catShips.any { Vector2f.sub(it.location, m.location, null).lengthSquared() < MISSILE_RANGE * MISSILE_RANGE }
            if (!near) continue
            val target = ((m.unwrappedMissileAI ?: m.missileAI) as? com.fs.starfarer.api.combat.GuidedMissileAI)?.target as? ShipAPI
            emit(line.begin("msl", now).int("id", missileId(m)).str("spec", m.projectileSpecId).int("own", m.owner)
                .num("x", m.location.x).num("y", m.location.y).num("vx", m.velocity.x).num("vy", m.velocity.y)
                .num("f", m.facing).str("tgt", target?.id).num("ft", m.flightTime, 2).num("mft", m.maxFlightTime, 2)
                .num("turn", m.maxTurnRate, 0).str("src", m.source?.id).num("hp", m.hitpoints, 0).end())
        }
    }

    private fun attach(engine: CombatEngineAPI, ship: ShipAPI) {
        ship.addListener(object : DamageTakenModifier {
            override fun modifyDamageTaken(param: Any?, target: CombatEntityAPI?, damage: DamageAPI, point: Vector2f?, shieldHit: Boolean): String? {
                if (target !== ship) return null
                val dpsTime = if (damage.dpsDuration > 0f) damage.dpsDuration else engine.elapsedInLastFrame
                val raw = if (damage.isDps) damage.damage * dpsTime else damage.damage
                if (raw <= 0f) return null
                val (src, del) = when (param) {
                    is MissileAPI -> param.source?.id to "M"
                    is DamagingProjectileAPI -> param.source?.id to "P"
                    is BeamAPI -> param.source?.id to "B"
                    // EMP arcs (systems like the EMP emitter): the API doesn't expose their source ship
                    is com.fs.starfarer.api.combat.EmpArcEntityAPI -> null to "E"
                    else -> null to "O"
                }
                emit(line.begin("hit", engine.getTotalElapsedTime(false)).str("tgt", ship.id).str("src", src).str("del", del)
                    .num("raw", raw).str("type", damage.type.name).bool("shield", shieldHit)
                    .num("x", point?.x ?: Float.NaN).num("y", point?.y ?: Float.NaN)
                    .int("mid", if (param is MissileAPI) missileId(param) else 0).end())
                return null
            }
        })
        ship.addListener(DamageListener { _, target, r: ApplyDamageResultAPI ->
            if (target !== ship) return@DamageListener
            // the game reports many empty applications; only the ones that did something are worth a line
            if (r.damageToShields <= 0f && r.totalDamageToArmor <= 0f && r.damageToHull <= 0f && r.empDamage <= 0f) {
                return@DamageListener
            }
            emit(line.begin("dmg", engine.getTotalElapsedTime(false)).str("tgt", ship.id).num("sh", r.damageToShields)
                .num("ar", r.totalDamageToArmor).num("hl", r.damageToHull).num("emp", r.empDamage).end())
        })
    }

    // ----------------------------------------------------------------------------------------------- predicted

    private fun recordInputs(now: Float, inputs: DamageInputs?) {
        if (inputs == null || inputs === lastInputs || now - lastInputsTime < PRED_EVERY) return
        lastInputs = inputs
        lastInputsTime = now
        val l = line.begin("in", now).int("shooters", inputs.shooters.size).int("shots", inputs.shots.size)
        l.raw("missiles") { sb ->
            sb.append('[')
            for ((k, m) in inputs.missiles.withIndex()) {
                if (k > 0) sb.append(',')
                sb.append('[').num(m.x).append(',').num(m.y).append(',').num(m.vx).append(',').num(m.vy).append(',')
                    .num(m.facing).append(',').append(m.owner).append(',')
                if (m.targetId == null) sb.append("null") else sb.append('"').append(m.targetId).append('"')
                sb.append(',').num(m.info.maxTurnRate).append(',').num(m.flightTime).append(',')
                    .num(m.info.maxFlightTime).append(',').num(m.damage).append(']')
            }
            sb.append(']')
        }
        emit(l.end())
    }

    private fun recordPrediction(now: Float, ship: ShipAPI, hold: RequestResult, kind: String = "pred") {
        val result = hold.outlook.result
        val l = line.begin(kind, now).str("id", ship.id).num("start", hold.outlook.startTime, 2)
        if (kind != "pred") l.str("opt", hold.name)
        l.path("path", hold.shipTimeline)
        l.raw("committed") { sb ->
            sb.append('{')
            var first = true
            for ((id, range) in hold.committed) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(id).append("\":{\"hold\":").num(range).append(",\"path\":")
                hold.pursuitTimelines[id]?.let { pathInto(sb, it) } ?: sb.append("null")
                hold.reactions[id]?.let { r ->
                    sb.append(",\"react\":{\"cause\":\"").append(r.cause.name).append("\",\"from\":").num(r.from)
                        .append(",\"t\":").num(if (r.threshold.isNaN()) -1f else r.threshold, 2)
                        .append('}')
                }
                sb.append('}')
            }
            sb.append('}')
        }
        if (result != null) {
            // point defense (plan §14): missiles at the ship shot down, and missiles that hit
            l.int("pdk", result.intercepted).int("pdn", result.arrived)
            // predicted hits, binned per 0.5 s per shooter and delivery
            val bins = HashMap<String, FloatArray>()
            val nBins = (result.normal.steps * DT / BIN).toInt() + 1
            for (h in result.hits) {
                val b = (h.time / BIN).toInt()
                if (b < 0 || b >= nBins) continue
                bins.getOrPut(h.sourceId + "|" + h.delivery.name.first()) { FloatArray(nBins) }[b] += h.damage
            }
            l.raw("hits") { sb ->
                sb.append('{')
                var first = true
                for ((k, v) in bins) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(k).append("\":")
                    floats(sb, v)
                }
                sb.append('}')
            }
            l.outcome("normal", result.normal)
            result.ventNow?.let { l.outcome("vent", it) }
        }
        emit(l.end())
    }

    private fun JsonLine.path(key: String, t: Timeline): JsonLine = raw(key) { sb -> pathInto(sb, t) }

    private fun pathInto(sb: StringBuilder, t: Timeline) {
        sb.append('[')
        var first = true
        var i = SAMPLE - 1
        while (i < t.steps) {
            if (!first) sb.append(',')
            first = false
            sb.append('[').num(t.x[i]).append(',').num(t.y[i]).append(',').num(t.facing[i]).append(']')
            i += SAMPLE
        }
        sb.append(']')
    }

    private fun JsonLine.outcome(key: String, o: DefenseOutcome): JsonLine = raw(key) { sb ->
        sb.append("{\"flux\":"); sampled(sb, o.flux, o.steps)
        sb.append(",\"hard\":"); sampled(sb, o.hardFlux, o.steps)
        sb.append(",\"hull\":"); sampled(sb, o.hullLost, o.steps)
        sb.append(",\"armor\":"); sampled(sb, o.armorLost, o.steps)
        // a lean outcome (the trace was switched on mid-batch) has no detail arrays
        if (o.moduleHullLost.isNotEmpty()) {
            sb.append(",\"modules\":"); sampled(sb, o.moduleHullLost, o.steps)
            sb.append(",\"shieldFlux\":"); sampled(sb, o.shieldFlux, o.steps)
        }
        sb.append(",\"overload\":").num(o.overloadStart).append(",\"overloadDur\":").num(o.overloadDuration)
        sb.append('}')
    }

    private fun sampled(sb: StringBuilder, v: FloatArray, steps: Int) {
        sb.append('[')
        var i = SAMPLE - 1
        var first = true
        while (i < steps) {
            if (!first) sb.append(',')
            first = false
            sb.num(v[i])
            i += SAMPLE
        }
        sb.append(']')
    }

    private fun floats(sb: StringBuilder, v: FloatArray) {
        sb.append('[')
        for (i in v.indices) { if (i > 0) sb.append(','); sb.num(v[i]) }
        sb.append(']')
    }

    // ------------------------------------------------------------------------------------------------- writing

    private fun emit(record: String) {
        if (failed) return
        buffer.append(record).append('\n')
        if (buffer.length >= CHUNK) {
            flush()
            buffer.setLength(0)
            part++
        }
    }

    private fun flush() {
        if (failed || buffer.isEmpty()) return
        try {
            Global.getSettings().writeTextFileToCommon("cat_trace/$tag/part_%03d".format(part), buffer.toString())
        } catch (e: Throwable) {
            failed = true
            Global.getLogger(TraceRecorder::class.java).error("CAT trace: write failed, recording stopped", e)
        }
    }

    companion object {
        private const val KEY = "CAT_TRACE_RECORDER"
        const val SHIP_EVERY = 0.25f
        const val PRED_EVERY = 0.5f
        const val FLUSH_EVERY = 5f
        const val MISSILE_RANGE = 3000f
        /** Prediction samples every SAMPLE steps (0.5 s); hit bins are BIN seconds wide. */
        const val SAMPLE = 10
        const val BIN = 0.5f
        /** Characters per part file; the game refuses writes over 1 MiB. */
        private const val CHUNK = 900_000

        fun enabled(): Boolean = System.getProperty("cat.trace") == "true" || Global.getSettings().isDevMode

        /** Writes what's buffered now (for scripts that end the game without the next periodic flush). */
        fun flushNow(engine: CombatEngineAPI) {
            (engine.customData[KEY] as? TraceRecorder)?.flush()
        }

        /** The recorder for this battle, created on first use when tracing is on; null when off. */
        fun get(engine: CombatEngineAPI): TraceRecorder? {
            (engine.customData[KEY] as? TraceRecorder)?.let { return it }
            if (!enabled()) return null
            return TraceRecorder().also { engine.customData[KEY] = it }
        }
    }
}

/** Appends a float rounded to [digits] decimals; NaN and infinities as null. */
private fun StringBuilder.num(v: Float, digits: Int = 1): StringBuilder {
    if (v.isNaN() || v.isInfinite()) return append("null")
    return when (digits) {
        0 -> append(Math.round(v))
        else -> {
            var scale = 1.0
            repeat(digits) { scale *= 10.0 }
            append(Math.round(v * scale) / scale)
        }
    }
}

/** A minimal JSON object writer for one trace line. Not thread-safe: main thread only. */
private class JsonLine {
    private val sb = StringBuilder(512)
    private var first = true

    fun begin(kind: String, t: Float): JsonLine {
        sb.setLength(0)
        sb.append('{')
        first = true
        return str("k", kind).num("t", t, 2)
    }

    private fun key(k: String) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(k).append("\":")
    }

    fun num(k: String, v: Float, digits: Int = 1): JsonLine { key(k); sb.num(v, digits); return this }
    fun int(k: String, v: Int): JsonLine { key(k); sb.append(v); return this }
    fun bool(k: String, v: Boolean): JsonLine { key(k); sb.append(v); return this }

    fun str(k: String, v: String?): JsonLine {
        key(k)
        if (v == null) { sb.append("null"); return this }
        sb.append('"')
        for (c in v) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                else -> if (c < ' ') sb.append(' ') else sb.append(c)
            }
        }
        sb.append('"')
        return this
    }

    fun raw(k: String, write: (StringBuilder) -> Unit): JsonLine { key(k); write(sb); return this }

    fun end(): String { sb.append('}'); return sb.toString() }
}
