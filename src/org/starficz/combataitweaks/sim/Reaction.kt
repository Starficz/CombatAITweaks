package org.starficz.combataitweaks.sim

import kotlin.math.max
import kotlin.math.min

/**
 * When a ship's AI backs off under flux. Translation of obfuscated code: vanilla's StrafeTargetManeuverV2 backs off
 * - while its hard flux level is over a threshold, or its flux level over 0.9 (+ a fifth of the CR offset), unless
 *   its target is overloaded for 3 s more or venting for 4 s more;
 * - and always while it is overloaded or venting, unless DO_NOT_BACK_OFF.
 *
 * The hard flux threshold depends on the personality (reckless and aggressive 0.8, steady 0.5, cautious and timid
 * 0.3), drops per hull size the target is larger, + 0.1 for phase ships, + the CR offset.
 */
class BackOffTraits(
    /** Hard flux level it backs off at against a target its own size. */
    val base: Float,
    /** Lowered this much per hull size the target is larger, ... */
    val sizeStep: Float,
    /** ... but not below this. */
    val floor: Float,
    val phase: Boolean,
    /** Vanilla raises both thresholds as peak CR runs out (0 while it lasts), a quarter of it for phase ships. */
    val crOffset: Float,
    /** False for AIs that only back off while overloaded or venting (automated ships). */
    val onFlux: Boolean,
    /** False with DO_NOT_BACK_OFF: it never backs off. */
    val atAll: Boolean,
) {
    fun hardFluxThreshold(own: HullClass, target: HullClass): Float {
        var t = base
        val diff = sizeIndex(target, own) - sizeIndex(own, own)
        if (diff > 0) t = max(floor, base - diff * sizeStep)
        if (phase) t = min(t + 0.1f, 0.8f)
        return t + crOffset
    }

    val fluxThreshold get() = 0.9f + crOffset * 0.2f

    companion object {
        /** Personality values (base, step, floor); unknown personalities get the aggressive ones (latest back-off). */
        fun forPersonality(id: String?): Triple<Float, Float, Float> = when (id) {
            "reckless" -> Triple(0.8f, 0.05f, 0.5f)
            "steady" -> Triple(0.5f, 0.15f, 0.2f)
            "cautious", "timid" -> Triple(0.3f, 0.15f, 0.1f)
            else -> Triple(0.8f, 0.15f, 0.3f)
        }

        private fun sizeIndex(c: HullClass, fallback: HullClass) =
            if (c == HullClass.OTHER) fallback.ordinal else c.ordinal
    }
}

/**
 * What one enemy does in reaction to the protected ship's fire in a branch (plan §13.8).
 *
 * Backing off, a ship holds its engagement range + radii without pressing in (vanilla backs up only when closer
 * than that - 100). Overloaded or venting for over 2 s more ("in danger"), frigates and destroyers retreat instead:
 * thrust straight away facing the ship (vanilla accelerates backwards), at any range.
 */
class Reaction(
    val cause: Cause,
    /** Backs off from this time (shared clock) ... */
    val from: Float,
    /** ... until this time at the latest (Float.MAX_VALUE: the rest of the window). */
    val until: Float,
    /** False: it doesn't move differently (DO_NOT_BACK_OFF, or not a pursuer), only its weapons go quiet. */
    val moves: Boolean,
    val retreat: Boolean,
    /** A retreat ends once the enemy is this far from the protected ship (centre to centre). */
    val clearAt: Float,
    /** Its weapons are silent over [quietFrom, quietUntil) (overloaded); empty when quietUntil <= quietFrom. */
    val quietFrom: Float,
    val quietUntil: Float,
    /** The hard flux level it backs off at against the protected ship (for traces; NaN when not on flux). */
    val threshold: Float = Float.NaN,
) {
    enum class Cause { OVERLOAD, FLUX }

    fun backsOffAt(t: Float) = moves && t >= from && t < until
    fun quietAt(t: Float) = t >= quietFrom && t < quietUntil
}

/** What the second run of a branch applies (plan §13.8). */
class Coupling(
    /** How committed enemies move and when they go quiet, by enemy id. */
    val reactions: Map<String, Reaction>,
    /** Hard flux the protected ship's side put on each enemy's shield by each step, by enemy id: its fire
     * discipline sees it. */
    val pressure: Map<String, FloatArray>,
) {
    fun isEmpty() = reactions.isEmpty() && pressure.isEmpty()

    companion object {
        val NONE = Coupling(emptyMap(), emptyMap())
    }
}

/**
 * Finds how enemies react to the protected ship's fire in a branch, from that branch's first damage run (plan §13.8):
 * the enemies' defences took the protected ship's and its side's hits plus their own disciplined firing flux.
 *
 * Only each enemy's first event counts, because the first run had no reactions: what happens to an enemy after it
 * starts backing off would differ. Conservative choices:
 * - a back-off on flux ends only when the protected ship overloads for 3 s more (its hard flux can't drain while its
 *   shield is up), or with the window;
 * - later events (a second back-off, an overload after a back-off on flux) are left out;
 * - only enemies whose live target is the protected ship move: vanilla backs off from its own target, so one fighting
 *   another ship backs away from that one, maybe toward the protected ship;
 * - the pressure on an enemy that backs off drains at its full dissipation from then on.
 */
object Reactions {
    /** Vanilla presses on a target that stays overloaded for this many seconds more. */
    const val PRESS_OVERLOADED = 3f
    /** Pressure below this share of an enemy's flux bar is left out (no second run for it). */
    const val MIN_PRESSURE = 0.02f

    fun find(world: World, protectedId: String, committed: Set<String>, damage: DamageResult): Coupling {
        val p = world.byId[protectedId] ?: return Coupling.NONE
        val pOut = damage.normal
        // the protected ship overloaded with more than 3 s left: enemies press instead of backing off on flux
        val pressFrom = if (pOut.overloadStart >= 0f && pOut.overloadDuration > PRESS_OVERLOADED) pOut.overloadStart
                        else Float.MAX_VALUE
        val pressUntil = if (pressFrom < Float.MAX_VALUE) pOut.overloadStart + pOut.overloadDuration - PRESS_OVERLOADED
                         else Float.MAX_VALUE

        // Not modelled: enemies already overloaded or venting at the snapshot backing off. In aibattle most of them
        // came closer than that predicted (likely vanilla's safe-vent spot); their fire is silent either way.
        val result = HashMap<String, Reaction>()
        for (o in damage.dealt ?: emptyList()) {
            val id = o.defense.shipId
            val e = world.byId[id] ?: continue
            if (e.isFighter || e.isModule) continue
            val traits = e.backOff
            val out = o.outcome
            val maxFlux = o.defense.maxFlux.coerceAtLeast(1f)
            // it backs off from its own target: only one already fighting the protected ship backs off from it
            val moves = id in committed && traits != null && traits.atAll && e.maneuverTargetId == protectedId

            var fluxAt = Float.MAX_VALUE
            if (moves && traits!!.onFlux) {
                val hardT = traits.hardFluxThreshold(e.hullClass, p.hullClass)
                val fluxT = traits.fluxThreshold
                for (i in 0 until out.steps) {
                    val t = (i + 1) * DT
                    if (t >= pressFrom && t < pressUntil) continue
                    if (out.hardFlux[i] / maxFlux > hardT || out.flux[i] / maxFlux > fluxT) { fluxAt = t; break }
                }
            }
            val overloadAt = out.overloadStart
            if (overloadAt >= 0f && overloadAt <= fluxAt) {
                val end = overloadAt + out.overloadDuration
                result[id] = Reaction(Reaction.Cause.OVERLOAD, overloadAt, end, moves, retreats(e, out.overloadDuration),
                    Float.MAX_VALUE,
                    overloadAt, end)
            } else if (fluxAt < Float.MAX_VALUE) {
                val until = if (pressFrom > fluxAt) pressFrom else Float.MAX_VALUE
                result[id] = Reaction(Reaction.Cause.FLUX, fluxAt, until, true, false,
                    p.weaponReach + e.collisionRadius, 0f, 0f, traits!!.hardFluxThreshold(e.hullClass, p.hullClass))
            }
        }

        val pressure = HashMap<String, FloatArray>()
        for (o in damage.dealt ?: emptyList()) {
            val def = o.defense
            val hard = o.outcome.hardFlux
            val r = result[def.shipId]?.takeIf { it.moves }
            val from = r?.let { o.outcome.at(it.from) } ?: Int.MAX_VALUE
            var peak = 0f
            val added = FloatArray(hard.size) { i ->
                var v = max(0f, hard[i] - def.hardFlux)
                if (i > from) v = min(v, max(0f, max(0f, hard[from] - def.hardFlux) - def.dissipation * (i - from) * DT))
                peak = max(peak, v)
                v
            }
            if (peak > MIN_PRESSURE * def.maxFlux) pressure[def.shipId] = added
        }
        return Coupling(result, pressure)
    }

    /** Vanilla's "in danger": overloaded or venting for over this many seconds more. */
    const val DANGER_SILENCE = 2f

    private fun retreats(e: SimShip, silence: Float) =
        silence > DANGER_SILENCE && (e.hullClass == HullClass.FRIGATE || e.hullClass == HullClass.DESTROYER)
}
