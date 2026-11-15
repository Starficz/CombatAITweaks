package org.starficz.combataitweaks.sim

/**
 * The values tuning works on (plan milestone 6), each overridable at launch with `-Dcat.tune.<name>=<value>` (the
 * `tunebattle` group's CAT_TUNE_SET). Without the property: the value CAT ships with. Read once, at class load.
 */
object Tuning {
    private fun float(name: String, default: Float): Float =
        System.getProperty("cat.tune.$name")?.toFloatOrNull() ?: default

    /** Prediction horizon H, seconds (plan §7; also the commit window, §5.1). */
    val horizon = float("horizon", 10f)
    /** τ: how long an option is kept up before the ship can turn around (plan §8.2, `then escape`). */
    val reactionTime = float("reactionTime", 0.7f)
    /** Cost of hull against armor in an option's cost (hull x this + armor). */
    val hullWeight = float("hullWeight", 3f)
    /** A system or defensive variant must cost at most this share of the plain option, and save [systemMinSaving]. */
    val systemGain = float("systemGain", 0.8f)
    val systemMinSaving = float("systemMinSaving", 50f)
    /** Margin for mispredicted hit times when the defensive system is used as late as possible. */
    val defenseBuffer = float("defenseBuffer", 0.35f)

    // the per-ship config's defaults (CombatAIConfig; a ship's own saved config wins)
    val minVentLevel = float("minVentLevel", 0.2f)
    val maxArmorDamageLevelToVent = float("maxArmorDamageLevelToVent", 0.1f)
    val maxHullDamageLevelToVent = float("maxHullDamageLevelToVent", 0.05f)
    val stopVentingBelowHullLevel = float("stopVentingBelowHullLevel", 0.5f)
    val maxFluxLevelRaiseToHarass = float("maxFluxLevelRaiseToHarass", 0.3f)
    val reengageIfFluxDropsBelowLevel = float("reengageIfFluxDropsBelowLevel", 0.4f)
    val forceAdvanceIfFluxBelowLevel = float("forceAdvanceIfFluxBelowLevel", 0.7f)
    val backoffIfFluxAboveLevel = float("backoffIfFluxAboveLevel", 0.85f)
    val backoffIfHardfluxAboveLevel = float("backoffIfHardfluxAboveLevel", 0.75f)

    /** Every setting with its value, for the run's log. */
    fun describe(): String = listOf(
        "horizon" to horizon, "reactionTime" to reactionTime, "hullWeight" to hullWeight, "systemGain" to systemGain,
        "systemMinSaving" to systemMinSaving, "defenseBuffer" to defenseBuffer, "minVentLevel" to minVentLevel,
        "maxArmorDamageLevelToVent" to maxArmorDamageLevelToVent, "maxHullDamageLevelToVent" to maxHullDamageLevelToVent,
        "stopVentingBelowHullLevel" to stopVentingBelowHullLevel, "maxFluxLevelRaiseToHarass" to maxFluxLevelRaiseToHarass,
        "reengageIfFluxDropsBelowLevel" to reengageIfFluxDropsBelowLevel,
        "forceAdvanceIfFluxBelowLevel" to forceAdvanceIfFluxBelowLevel, "backoffIfFluxAboveLevel" to backoffIfFluxAboveLevel,
        "backoffIfHardfluxAboveLevel" to backoffIfHardfluxAboveLevel,
    ).joinToString(" ") { (k, v) -> "$k=$v" }
}
