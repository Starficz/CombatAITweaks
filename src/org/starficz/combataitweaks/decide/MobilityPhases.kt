package org.starficz.combataitweaks.decide

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import org.lazywizard.lazylib.combat.AIUtils
import org.starficz.combataitweaks.capture.ProfileStat
import org.starficz.combataitweaks.capture.SystemProfile
import org.starficz.combataitweaks.capture.SystemProfiles
import org.starficz.combataitweaks.capture.SystemUse
import org.starficz.combataitweaks.getOnTimeRemaining
import org.starficz.combataitweaks.sim.MotionPhase

/**
 * The protected ship's own mobility system (plan §12) as movement phases, from its profile's max speed, acceleration
 * and deceleration modifiers (capture/SystemProfiles: `use` mobility). Turn rate changes are left out; the movement
 * options don't depend on them much.
 */
object MobilityPhases {

    /** The ship's mobility profile, or null when its system isn't one. */
    fun of(ship: ShipAPI): SystemProfile? = SystemProfiles.of(ship)?.takeIf { it.use == SystemUse.MOBILITY }

    /** Max speed, accel and decel with the system's own modifiers replaced by [profile]'s (none: system off). */
    private fun limits(ship: ShipAPI, profile: SystemProfile?): Triple<Float, Float, Float> {
        val stats = ship.mutableStats
        val id = SystemProfiles.effectId(ship.system.specAPI.id)
        // engine-level effects (the zero-flux boost) are in the ship's effective values but not in the stats
        val speedOffset = ship.maxSpeed - stats.maxSpeed.modifiedValue
        val accelOffset = ship.acceleration - stats.acceleration.modifiedValue
        val decelOffset = ship.deceleration - stats.deceleration.modifiedValue
        fun value(stat: ProfileStat): Float = stat.statOf(stats).valueWith(id, profile?.get(stat))
        return Triple(
            (value(ProfileStat.MAX_SPEED) + speedOffset).coerceAtLeast(0f),
            (value(ProfileStat.ACCELERATION) + accelOffset).coerceAtLeast(0f),
            (value(ProfileStat.DECELERATION) + decelOffset).coerceAtLeast(0f),
        )
    }

    /** Movement phases for options without the system: an active system runs out first, then it's off. */
    fun plainPhases(ship: ShipAPI): List<MotionPhase> {
        val sys = ship.system ?: return emptyList()
        val (speed, accel, decel) = limits(ship, null)
        val off = MotionPhase(Float.MAX_VALUE, speed, accel, decel)
        return if (sys.isActive && !sys.specAPI.isToggle) {
            listOf(MotionPhase(sys.getOnTimeRemaining(), ship.maxSpeed, ship.acceleration, ship.deceleration), off)
        } else listOf(off)
    }

    /** Movement phases using the system now (a toggle stays on), or null when it can't be used now. */
    fun systemPhases(ship: ShipAPI, profile: SystemProfile): List<MotionPhase>? {
        val sys = ship.system ?: return null
        if (sys.specAPI.isToggle) {
            if (!sys.isOn && (sys.isCoolingDown || !AIUtils.canUseSystemThisFrame(ship))) return null
        } else if (sys.state != ShipSystemAPI.SystemState.IDLE || !AIUtils.canUseSystemThisFrame(ship)) return null
        val (speed, accel, decel) = limits(ship, profile)
        if (sys.specAPI.isToggle) return listOf(MotionPhase(Float.MAX_VALUE, speed, accel, decel))
        val (offSpeed, offAccel, offDecel) = limits(ship, null)
        return listOf(
            MotionPhase(sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur, speed, accel, decel),
            MotionPhase(Float.MAX_VALUE, offSpeed, offAccel, offDecel),
        )
    }
}
