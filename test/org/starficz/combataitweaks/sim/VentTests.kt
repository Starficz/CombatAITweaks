package org.starficz.combataitweaks.sim

import org.starficz.combataitweaks.decide.VentInputs
import org.starficz.combataitweaks.decide.VentOption
import org.starficz.combataitweaks.decide.VentRule

// Unit tests for the vent decision rule (plan §8.1).

private fun inputs(
    vararg options: VentOption, flux: Float = 0.8f, hull: Float = 1f, allowed: Boolean = true,
) = VentInputs(
    fluxLevel = flux, hullLevel = hull, armorRating = 1000f, maxHull = 10000f,
    blockedBy = if (allowed) null else "test mid-burst", window = 5f,
    minVentLevel = 0.2f, maxArmorDamageLevelToVent = 0.1f, maxHullDamageLevelToVent = 0.05f,
    stopVentingBelowHullLevel = 0.5f, options = options.toList(),
)

private fun opt(name: String, ventArmor: Float = 0f, ventHull: Float = 0f, stayArmor: Float = 0f, stayHull: Float = 0f) =
    VentOption(name, ventArmor, ventHull, stayArmor, stayHull)

internal fun ventRule() {
    val quiet = opt("HOLD")
    check(!VentRule.decide(inputs(quiet, flux = 0.1f)).vent, "vent: not below minVentLevel")
    check(!VentRule.decide(inputs(quiet, allowed = false)).vent, "vent: not while a burst or system is running")
    check(!VentRule.decide(inputs()).vent, "vent: not without a prediction")

    val calm = VentRule.decide(inputs(quiet, opt("BACK_OFF")))
    check(calm.vent && calm.safe && calm.option?.name == "HOLD", "vent: nothing threatens -> vents in place") { calm.reason }

    // limits: armor 1000 x 0.1 = 100, hull 10000 x 0.05 = 500
    val backOffSafe = VentRule.decide(inputs(opt("HOLD", ventArmor = 300f), opt("BACK_OFF", ventArmor = 60f)))
    check(backOffSafe.vent && backOffSafe.safe && backOffSafe.option?.name == "BACK_OFF",
        "vent: safe only while backing off -> vents backing off") { backOffSafe.reason }

    // the safe margin: 90 of a 100 armor limit is within the limit but not safe (80% of it), 75 is
    val nearLimit = VentRule.decide(inputs(opt("HOLD", ventArmor = 90f)))
    check(!nearLimit.vent, "vent: within the limit but not its 80% margin -> doesn't vent") { nearLimit.reason }
    val inMargin = VentRule.decide(inputs(opt("HOLD", ventArmor = 75f)))
    check(inMargin.vent && inMargin.safe, "vent: within the 80% margin -> safe") { inMargin.reason }

    val torpedo = VentRule.decide(inputs(opt("HOLD", ventArmor = 400f, ventHull = 2000f), opt("BACK_OFF", ventArmor = 300f, ventHull = 1500f)))
    check(!torpedo.vent, "vent: a big hit while venting and none when staying -> doesn't vent") { torpedo.reason }

    // staying overloads into armor and hull: venting (cost 300 + 3 x 200 = 900) beats staying (500 + 3 x 800 = 2900)
    val trapped = inputs(opt("HOLD", ventArmor = 300f, ventHull = 200f, stayArmor = 500f, stayHull = 800f))
    val better = VentRule.decide(trapped)
    check(better.vent && !better.safe, "vent: unsafe but far better than staying -> vents") { better.reason }

    val lowHull = VentRule.decide(inputs(opt("HOLD", ventArmor = 300f, ventHull = 200f, stayArmor = 500f, stayHull = 800f), hull = 0.4f))
    check(!lowHull.vent, "vent: below stopVentingBelowHullLevel only safe vents") { lowHull.reason }

    val graze = VentRule.decide(inputs(opt("HOLD", ventHull = 20f), hull = 0.4f))
    check(!graze.vent, "vent: below stopVentingBelowHullLevel any hull loss is unsafe") { graze.reason }

    val close = VentRule.decide(inputs(opt("HOLD", ventArmor = 300f, stayArmor = 340f)))
    check(!close.vent, "vent: only a little better than staying -> doesn't vent") { close.reason }
}
