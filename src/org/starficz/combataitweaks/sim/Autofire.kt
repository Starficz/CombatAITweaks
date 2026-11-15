package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One shooter's weapons on autofire over several targets (plan §13.1). Translation of obfuscated code: vanilla's
 * autofire AI (`com.fs.starfarer.combat.ai.P`, an AutofireAIPlugin) re-chooses each weapon's target every 0.25–0.5 s:
 * 1. the ship's own target, whenever it is in range and arc;
 * 2. else the current target while it stays in range and arc;
 * 3. else the enemy in range and arc that needs the least turn from the weapon's current angle.
 *
 * `STRIKE` weapons skip frigates unless they are also `USE_VS_FRIGATES`. Left out: PD against missiles, the friendly
 * line-of-fire check, and fighters as targets (only ships with captured defences are [targets]).
 *
 * @param targets one [FireControl] per enemy ship, collecting what this shooter's weapons do to it.
 * @param shipTarget index in [targets] of the ship's own target, or -1.
 */
class Volley(
    private val shooter: SimShooter,
    private val timeline: Timeline,
    private val targets: List<FireControl>,
    private val shipTarget: Int,
    /** Vanilla's fire discipline for a ship its own AI fires (null: everything fires until the flux bar is full). */
    private val discipline: FireDiscipline? = null,
) {
    private val gunnery = Gunnery(shooter, timeline)
    private val weapons = shooter.weapons
    private val current = IntArray(weapons.size) { -1 }
    private val sinkOf: (Int) -> FireControl? = { k -> current[k].let { if (it < 0) null else targets[it] } }
    private val enabled = if (discipline != null) BooleanArray(weapons.size) { true } else null

    /** Weapon [k]'s target index in [targets], or -1. */
    fun targetOf(k: Int): Int = current[k]

    fun step(i: Int, gate: FluxGate) {
        if (i % RETARGET_STEPS == 0) retarget(i)
        if (discipline != null && i % FireDiscipline.EVERY_STEPS == 0) {
            val t = if (shipTarget >= 0) targets[shipTarget].target.hullClass else null
            discipline.toggle(weapons, gate, t, enabled!!)
        }
        gunnery.step(i, gate, sinkOf, enabled)
    }

    private fun retarget(i: Int) {
        val sx = timeline.x[i]; val sy = timeline.y[i]; val sf = timeline.facing[i]
        for (k in weapons.indices) {
            if (shipTarget >= 0 && fits(k, targets[shipTarget], i, sx, sy, sf) >= 0f) { current[k] = shipTarget; continue }
            val cur = current[k]
            if (cur >= 0 && fits(k, targets[cur], i, sx, sy, sf) >= 0f) continue
            var best = -1
            var bestTurn = Float.MAX_VALUE
            for (t in targets.indices) {
                val bearing = fits(k, targets[t], i, sx, sy, sf)
                if (bearing < 0f) continue
                val turn = abs(shortestRotation(gunnery.angle(k), bearing - 360f))
                if (turn < bestTurn) { bestTurn = turn; best = t }
            }
            current[k] = best
        }
    }

    /**
     * Whether weapon [k] can engage [t] at step [i]: in range (its range now, + the target's radius) and arc. Returns the
     * ship-local bearing to it + 360 (so always >= 0), or -1 when it can't.
     */
    private fun fits(k: Int, t: FireControl, i: Int, sx: Float, sy: Float, sf: Float): Float {
        val w = weapons[k]
        if (w.strike && !w.vsFrigates && t.target.hullClass == HullClass.FRIGATE) return -1f
        val dx = t.targetTimeline.x[i] - sx; val dy = t.targetTimeline.y[i] - sy
        val dist = sqrt(dx * dx + dy * dy)
        val r = t.target.hitRadius
        if (dist > gunnery.range(k) + r) return -1f
        val local = shortestRotation(sf, angleOf(dx, dy))
        if (!w.noAim) {
            val angularRadius = if (dist > r) asin(r / dist) * RAD_TO_DEG else 90f
            if (abs(shortestRotation(w.slotAngle, local)) > w.arc / 2f + angularRadius) return -1f
        }
        return local + 360f
    }

    companion object {
        /** Vanilla re-chooses every 0.25–0.5 s; the sim every 0.4 s. */
        const val RETARGET_STEPS = 8
    }
}

/**
 * Vanilla's autofire flux discipline for ships its AI fires (translation of obfuscated code:
 * `combat.ai.attack.AutofireManagerV2`, every 0.4–0.7 s per weapon group; the sim treats each weapon as a group
 * and re-decides every 0.5 s):
 * - weapons that cost no flux always fire (vanilla: while flux < 0.95);
 * - cheapest first, weapons whose sustained flux fits in half the net dissipation (dissipation - shield upkeep)
 *   always fire;
 * - the rest: off with less than 10 s of firing left in the free flux; else off above a flux level threshold and
 *   back on 0.1 below it. The threshold is 0.2, + 0.07 x (own size - target size + 1) when the target is at least
 *   the ship's size, + 0.25 against a slower target ([bonus]: the caller decides). Left out: - 5 x recent damage.
 */
class FireDiscipline(
    private val ownClass: HullClass,
    private val dissipation: Float,
    private val upkeep: Float,
    /** Added to the threshold: [SLOWER_TARGET] for enemies (conservative: they fire as long as vanilla ever does). */
    private val bonus: Float = 0f,
) {
    // the weapons by sustained flux cost, cheapest first: sorted once per weapon list (it doesn't change)
    private var orderOf: List<SimWeapon>? = null
    private var order = IntArray(0)
    private var costs = FloatArray(0)

    private fun orderFor(weapons: List<SimWeapon>): IntArray {
        if (orderOf === weapons) return order
        val n = weapons.size
        val c = FloatArray(n) { perSecond(weapons[it]) }
        val o = IntArray(n) { it }
        // insertion sort: stable like sortedBy, and a ship has few weapons
        for (a in 1 until n) {
            val k = o[a]
            var b = a - 1
            while (b >= 0 && c[o[b]] > c[k]) { o[b + 1] = o[b]; b-- }
            o[b + 1] = k
        }
        orderOf = weapons; order = o; costs = c
        return o
    }

    fun toggle(weapons: List<SimWeapon>, gate: FluxGate, target: HullClass?, enabled: BooleanArray) {
        val level = gate.level()
        var budget = max(0f, dissipation - upkeep) * NEUTRAL_SHARE
        val order = orderFor(weapons)
        val costs = costs
        var threshold = BASE_THRESHOLD + bonus
        if (target != null && target.ordinal >= ownClass.ordinal) {
            threshold += SIZE_STEP * max(0, ownClass.ordinal - target.ordinal + 1)
        }
        for (k in order) {
            val cost = costs[k]
            if (cost <= 0f) { enabled[k] = level < 0.95f; continue }
            if (cost <= budget) { enabled[k] = true; budget -= cost; continue }
            if (gate.available() / cost < MIN_SECONDS) { enabled[k] = false; continue }
            if (level > threshold) enabled[k] = false
            else if (level < max(0.05f, threshold - 0.1f)) enabled[k] = true
        }
    }

    companion object {
        const val EVERY_STEPS = 10
        const val NEUTRAL_SHARE = 0.5f
        const val MIN_SECONDS = 10f
        const val BASE_THRESHOLD = 0.2f
        const val SIZE_STEP = 0.07f
        const val SLOWER_TARGET = 0.25f

        /** Sustained flux per second of firing (ship time). */
        fun perSecond(w: SimWeapon): Float = when (w.mode) {
            FireMode.BEAM -> w.fluxCost
            FireMode.BURST_BEAM -> w.fluxCost / max(0.1f, w.chargeUp + w.burstDuration + w.cooldown)
            else -> w.fluxCost / max(0.1f, w.chargeUp + w.burstDelay * (w.burstSize - 1) + w.cooldown)
        }
    }
}
