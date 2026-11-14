package org.starficz.combataitweaks.predictor

import org.starficz.combataitweaks.decide.MoveKind
import org.starficz.combataitweaks.decide.MoveOption
import org.starficz.combataitweaks.decide.MoveRule
import org.starficz.combataitweaks.decide.Trade
import org.starficz.combataitweaks.decide.VentOption
import org.starficz.combataitweaks.sim.DamageResult
import org.starficz.combataitweaks.sim.DefenseOutcome

/**
 * One branch's damage prediction as the decisions read it: as a vent option (plan §8.1) and as a movement option
 * (§8.2). Times passed in are absolute engine times; [startTime] is the snapshot the prediction was made from.
 */
class DamageOutlook(val startTime: Float, val result: DamageResult?) {

    private fun cumulative(values: FloatArray, outcome: DefenseOutcome, relative: Float): Float =
        if (relative < 0f) 0f else values[outcome.at(relative)]

    /**
     * This branch as a vent option: armor and hull lost over [window] seconds from now if the ship vents now, and if
     * it doesn't. Null without a prediction.
     */
    fun ventOption(name: String, currentTime: Float, window: Float): VentOption? {
        val r = result ?: return null
        val vent = r.ventNow ?: return null
        val from = currentTime - startTime
        val to = from + window
        fun lost(values: FloatArray, o: DefenseOutcome) = cumulative(values, o, to) - cumulative(values, o, from)
        // armor lost is a running maximum of regional loss, so its value at the window's end is the worst region
        return VentOption(
            name = name,
            ventArmor = cumulative(vent.armorLost, vent, to),
            ventHull = lost(vent.hullLost, vent),
            stayArmor = cumulative(r.normal.armorLost, r.normal, to),
            stayHull = lost(r.normal.hullLost, r.normal),
            stayOverloadsIn = if (r.normal.overloadStart >= 0f) (r.normal.overloadStart - from).coerceAtLeast(0f) else -1f,
        )
    }

    /**
     * This branch as a movement option (plan §8.2): from now to the end of the horizon, with the shield used as
     * vanilla would. Null without a prediction.
     */
    fun moveOption(
        name: String, kind: MoveKind, system: Boolean, currentTime: Float, maxFlux: Float, escape: Boolean = false,
    ): MoveOption? {
        val o = result?.normal ?: return null
        val from = currentTime - startTime
        val end = o.steps - 1
        val flux = maxFlux.coerceAtLeast(1f)
        return MoveOption(
            name = name,
            kind = kind,
            system = system,
            hullLost = o.hullLost[end] - cumulative(o.hullLost, o, from),
            // a running maximum of regional loss: the worst region over the horizon
            armorLost = o.armorLost[end],
            overloadsIn = if (o.overloadStart >= 0f) (o.overloadStart - from).coerceAtLeast(0f) else -1f,
            endFlux = o.flux[end] / flux,
            endHardFlux = o.hardFlux[end] / flux,
            escape = escape,
            trade = trade(from),
        )
    }

    /**
     * Seconds from [currentTime] until the next hit the shield would block in this branch (plan §13.9);
     * Float.MAX_VALUE when none is predicted, null without a prediction.
     */
    fun nextShieldHit(currentTime: Float): Float? {
        val times = result?.normal?.shieldHitTimes ?: return null
        val from = currentTime - startTime
        for (t in times) if (t >= from) return t - from
        return Float.MAX_VALUE
    }

    /** The branch's trade (plan §13.3) from [from] seconds after the snapshot; null when it wasn't resolved. */
    private fun trade(from: Float): Trade? {
        val r = result ?: return null
        val dealt = r.dealt ?: return null
        var dealtDamage = 0f
        var dealtFlux = 0f
        for (t in dealt) {
            dealtDamage += damageShare(t.defense, t.outcome, from)
            dealtFlux += fluxShare(t.defense, t.outcome, from)
        }
        return Trade(dealtDamage, damageShare(r.defense, r.normal, from), dealtFlux, fluxShare(r.defense, r.normal, from))
    }

    private fun damageShare(def: org.starficz.combataitweaks.sim.SimDefense, o: DefenseOutcome, from: Float): Float {
        val end = o.steps - 1
        val hull = o.hullLost[end] - cumulative(o.hullLost, o, from)
        return MoveRule.cost(o.armorLost[end], hull) / def.worth.coerceAtLeast(1f)
    }

    /** Net flux gained from [from] to the end, over max flux; an overload within the horizon counts as 1. */
    private fun fluxShare(def: org.starficz.combataitweaks.sim.SimDefense, o: DefenseOutcome, from: Float): Float {
        if (o.overloadStart >= 0f && o.overloadStart >= from) return 1f
        val start = if (from < org.starficz.combataitweaks.sim.DT) def.flux else o.flux[o.at(from)]
        return (o.flux[o.steps - 1] - start) / def.maxFlux.coerceAtLeast(1f)
    }
}
