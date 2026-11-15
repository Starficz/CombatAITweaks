package org.starficz.combataitweaks.decide

// Pure: no game API, compiled into the unit tests with `sim` (test/run-unit-tests.ps1).

/** One movement option's predicted outcome over the vent window, venting now and not venting. */
class VentOption(
    val name: String,
    val ventArmor: Float,
    val ventHull: Float,
    val stayArmor: Float,
    val stayHull: Float,
    /** Seconds until staying overloads the ship, or -1 when it doesn't within the horizon. */
    val stayOverloadsIn: Float = -1f,
) {
    val ventCost get() = VentRule.cost(ventArmor, ventHull)
    val stayCost get() = VentRule.cost(stayArmor, stayHull)
}

class VentInputs(
    val fluxLevel: Float,
    val hullLevel: Float,
    val armorRating: Float,
    val maxHull: Float,
    /** Why venting isn't possible right now (weapon mid-burst, system active, ...), or null when it is. */
    val blockedBy: String?,
    /** Seconds the vent leaves the ship exposed: time to vent + shield raise time + buffer (plan §8.1). */
    val window: Float,
    val minVentLevel: Float,
    val maxArmorDamageLevelToVent: Float,
    val maxHullDamageLevelToVent: Float,
    val stopVentingBelowHullLevel: Float,
    val options: List<VentOption>,
)

class VentChoice(
    val vent: Boolean,
    /** The vent is within the safe limits (the ship may hold position while venting). */
    val safe: Boolean,
    /** The movement option to keep while venting. */
    val option: VentOption?,
    /** One line with the numbers behind the choice, for the decision trace (plan §8.6). */
    val reason: String,
    /** The vent window and the safe limits the choice was checked against (for the vent audit). */
    val window: Float = 0f,
    val armorLimit: Float = 0f,
    val hullLimit: Float = 0f,
)

/**
 * The vent decision (plan §8.1). Vent when the best way to vent is either
 * - **safe**: armor lost in the window within [SAFE_MARGIN] x maxArmorDamageLevelToVent x armor rating and hull lost
 *   within [SAFE_MARGIN] x maxHullDamageLevelToVent x hitpoints (no hull loss at all below stopVentingBelowHullLevel),
 *   or
 * - **better than staying**: it loses at most [BETTER_FACTOR] x what the best option without venting loses in the
 *   same window, and hull is above stopVentingBelowHullLevel.
 */
object VentRule {
    const val BETTER_FACTOR = 0.8f
    /**
     * A safe vent must predict at most this share of the limits: a vent can't be undone, and beams (semi-continuous,
     * modelled as 0.1 s ticks) are the least precise part of the prediction. Added after two aibattle vents at 28/30
     * and 0/30 armor lost 61 and 199 (plan §14, beam ramps). The audit still judges against the full limits.
     */
    const val SAFE_MARGIN = 0.8f
    /** Cost weights (plan §8.2): hull counts triple. */
    val HULL_WEIGHT = org.starficz.combataitweaks.sim.Tuning.hullWeight
    /** Hull loss treated as none: a stray fragment shouldn't forbid a vent. */
    const val HULL_EPSILON = 1f

    fun cost(armor: Float, hull: Float) = hull * HULL_WEIGHT + armor

    fun decide(i: VentInputs): VentChoice {
        if (i.blockedBy != null) return VentChoice(false, false, null, "no vent: ${i.blockedBy}")
        if (i.fluxLevel <= i.minVentLevel) {
            return VentChoice(false, false, null, if (!Explain.enabled) "no vent: low flux"
                else "no vent: flux %.2f <= %.2f".format(i.fluxLevel, i.minVentLevel))
        }
        if (i.options.isEmpty()) return VentChoice(false, false, null, "no vent: no prediction")

        val bestVent = i.options.minBy { it.ventCost }
        val bestStay = i.options.minBy { it.stayCost }
        val armorLimit = i.armorRating * i.maxArmorDamageLevelToVent
        val lowHull = i.hullLevel < i.stopVentingBelowHullLevel
        val hullLimit = if (lowHull) HULL_EPSILON else i.maxHull * i.maxHullDamageLevelToVent

        val numbers = if (!Explain.enabled) "-" else "W %.1fs, vent %s armor %.0f/%.0f hull %.0f/%.0f, stay %s cost %.0f%s".format(
            i.window, bestVent.name, bestVent.ventArmor, armorLimit, bestVent.ventHull, hullLimit,
            bestStay.name, bestStay.stayCost,
            if (bestStay.stayOverloadsIn >= 0f) ", stay overloads in %.1fs".format(bestStay.stayOverloadsIn) else "")

        // safe: the safe way to vent that loses least
        val safeOptions = i.options.filter { it.ventArmor <= armorLimit * SAFE_MARGIN && it.ventHull <= hullLimit * SAFE_MARGIN }
        if (safeOptions.isNotEmpty()) {
            val pick = safeOptions.minBy { it.ventCost }
            return VentChoice(true, true, pick, "VENT safe ($numbers)", i.window, armorLimit, hullLimit)
        }
        if (!lowHull && bestVent.ventCost <= BETTER_FACTOR * bestStay.stayCost) {
            return VentChoice(true, false, bestVent, "VENT better than staying ($numbers)", i.window, armorLimit, hullLimit)
        }
        return VentChoice(false, false, null, "no vent: unsafe ($numbers)", i.window, armorLimit, hullLimit)
    }
}
