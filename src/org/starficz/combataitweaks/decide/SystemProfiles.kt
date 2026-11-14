package org.starficz.combataitweaks.decide

import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import org.lazywizard.lazylib.combat.AIUtils
import org.starficz.combataitweaks.getOnTimeRemaining
import org.starficz.combataitweaks.sim.MotionPhase

/**
 * The protected ship's own mobility systems as explicit profiles (plan §12), from each system's stats script:
 * - `maneuveringjets` (data/shipsystems/scripts/ManeuveringJetsStats): max speed +50, accel and decel +200%;
 * - `plasmajets` (PlasmaJetsStats): +125, +375%;
 * - `SCY_secondaryThrusters` (SCY_secondaryThrustersStats): +100, +200%;
 * - `SCY_armorSwitch` (ArmorSwitchStats, a toggle): +40, +150%.
 * Turn rate changes are left out; the movement options don't depend on them much.
 * Systems run their stats scripts with the stat id "<system id> effect" (decompiled ship system class).
 */
object SystemProfiles {

    class Profile(
        val speedFlat: Float,
        val accelPercent: Float,
        /** Block vanilla's own use of the system whenever CAT isn't using it (v1's rule, plan §8.3). */
        val enforceOverride: Boolean,
    )

    private val profiles = mapOf(
        "maneuveringjets" to Profile(50f, 200f, enforceOverride = true),
        "plasmajets" to Profile(125f, 375f, enforceOverride = true),
        "SCY_secondaryThrusters" to Profile(100f, 200f, enforceOverride = true),
        "SCY_armorSwitch" to Profile(40f, 150f, enforceOverride = false),
    )

    fun of(ship: ShipAPI): Profile? = ship.system?.specAPI?.id?.let { profiles[it] }

    private fun effectId(ship: ShipAPI) = ship.system.specAPI.id + " effect"

    /** Max speed, accel and decel with the system's own modifiers replaced by (flat, percent). */
    private fun limits(ship: ShipAPI, flat: Float, percent: Float): Triple<Float, Float, Float> {
        val stats = ship.mutableStats
        val id = effectId(ship)
        // engine-level effects (the zero-flux boost) are in the ship's effective values but not in the stats
        val speedOffset = ship.maxSpeed - stats.maxSpeed.modifiedValue
        val accelOffset = ship.acceleration - stats.acceleration.modifiedValue
        val decelOffset = ship.deceleration - stats.deceleration.modifiedValue
        return Triple(
            (stats.maxSpeed.valueWith(id, flat, 0f) + speedOffset).coerceAtLeast(0f),
            (stats.acceleration.valueWith(id, 0f, percent) + accelOffset).coerceAtLeast(0f),
            (stats.deceleration.valueWith(id, 0f, percent) + decelOffset).coerceAtLeast(0f),
        )
    }

    /** Movement phases for options without the system: an active system runs out first, then it's off. */
    fun plainPhases(ship: ShipAPI, profile: Profile): List<MotionPhase> {
        val sys = ship.system ?: return emptyList()
        val (speed, accel, decel) = limits(ship, 0f, 0f)
        val off = MotionPhase(Float.MAX_VALUE, speed, accel, decel)
        return if (sys.isActive && !sys.specAPI.isToggle) {
            listOf(MotionPhase(sys.getOnTimeRemaining(), ship.maxSpeed, ship.acceleration, ship.deceleration), off)
        } else listOf(off)
    }

    /** Movement phases using the system now (a toggle stays on), or null when it can't be used now. */
    fun systemPhases(ship: ShipAPI, profile: Profile): List<MotionPhase>? {
        val sys = ship.system ?: return null
        if (sys.specAPI.isToggle) {
            if (!sys.isOn && (sys.isCoolingDown || !AIUtils.canUseSystemThisFrame(ship))) return null
        } else if (sys.state != ShipSystemAPI.SystemState.IDLE || !AIUtils.canUseSystemThisFrame(ship)) return null
        val (speed, accel, decel) = limits(ship, profile.speedFlat, profile.accelPercent)
        if (sys.specAPI.isToggle) return listOf(MotionPhase(Float.MAX_VALUE, speed, accel, decel))
        val (offSpeed, offAccel, offDecel) = limits(ship, 0f, 0f)
        return listOf(
            MotionPhase(sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur, speed, accel, decel),
            MotionPhase(Float.MAX_VALUE, offSpeed, offAccel, offDecel),
        )
    }

    /** The value [MutableStat] would have with [sourceId]'s modifiers replaced by [flat] and [percent]. */
    private fun MutableStat.valueWith(sourceId: String, flat: Float, percent: Float): Float {
        var flatMod = flat
        var percentMod = percent
        var mult = 1f
        for ((id, mod) in flatMods) if (id != sourceId) flatMod += mod.value
        for ((id, mod) in percentMods) if (id != sourceId) percentMod += mod.value
        for ((id, mod) in multMods) if (id != sourceId) mult *= mod.value
        // MutableStat.recompute(): base + base x percent / 100 + flat, then x mult
        return (baseValue + baseValue * percentMod / 100f + flatMod) * mult
    }
}
