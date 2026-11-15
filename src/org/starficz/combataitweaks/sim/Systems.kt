package org.starficz.combataitweaks.sim

import kotlin.math.max
import kotlin.math.min

/*
 * Ship systems on the shared clock (plan §11b). A system's profile (capture/SystemProfiles) says what it does to its
 * ship's stats; the capture turns that into factors on each weapon ([WeaponBoost]) and the system's timing
 * ([ShooterSystem]). The sim only switches the factors on and off.
 */

/** Weapon types as the ship's stats split them. */
enum class WeaponClass { BALLISTIC, ENERGY, MISSILE, OTHER }

/** Vanilla's setting `minRefireDelay`: a fire rate above 1 never makes a weapon delay shorter. */
const val MIN_REFIRE_DELAY = 0.05f

/**
 * A weapon delay [raw] at fire rate [rof] (translation of obfuscated code: the weapon trackers run the weapon's clock
 * at the fire rate; above 1, a delay that would end up under [MIN_REFIRE_DELAY] is only sped up to it, and one already
 * under it not at all).
 */
fun refireDelay(raw: Float, rof: Float): Float =
    if (rof <= 1f) raw / rof.coerceAtLeast(0.01f) else max(raw / rof, min(raw, MIN_REFIRE_DELAY))

/**
 * What the shooter's system does to one weapon while the system is on: factors on the weapon's values with the
 * system off. Fire rate shortens the cooldown, and for projectile weapons the charge-up and the delay between burst
 * shots too ([refireDelay]).
 */
class WeaponBoost(
    val rof: Float = 1f,
    val damage: Float = 1f,
    val flux: Float = 1f,
    val range: Float = 1f,
) {
    /** Whether the system makes this weapon any stronger (only then does it trigger a worst-case use). */
    val strengthens: Boolean get() = rof > 1.001f || damage > 1.001f || flux < 0.999f || range > 1.001f
}

/**
 * When a shooter's ship system can be on. Times are engine seconds after the snapshot.
 * The effect counts at full strength over the whole on-time, charge-up and charge-down included: the worst case for
 * the ship it is used against.
 */
class ShooterSystem(
    /** Seconds of the effect still to come at the snapshot (charge-up, active and charge-down left); 0 = off. */
    val onLeft: Float,
    /** The charge-down part of [onLeft] and [onTime]: left out when the ship's own fire counts it (see [SystemClock]). */
    val chargeDown: Float,
    /** On-time of a new use: charge-up + active + charge-down; Float.MAX_VALUE for toggles. */
    val onTime: Float,
    /** Seconds from the end of the effect until it can be used again. */
    val cooldown: Float,
    /** Seconds until it can be activated: cooldown, the current use or a reload; Float.MAX_VALUE = never. */
    val readyIn: Float,
    /** Uses left now; Int.MAX_VALUE for systems without charges. */
    val charges: Int = Int.MAX_VALUE,
    val maxCharges: Int = Int.MAX_VALUE,
    /** Charges regained per second, and progress (0..1) toward the next one. */
    val regen: Float = 0f,
    val regenProgress: Float = 0f,
)

/**
 * One branch's run of a [ShooterSystem].
 * @param autoUse used again whenever it is ready and one of the weapons it strengthens could fire (the worst case,
 * for enemies). Without it only a use already on at the snapshot counts, without its charge-down (the protected ship's
 * and its side's own fire: vanilla decides those, so the sim doesn't count on them).
 */
class SystemClock(private val sys: ShooterSystem, private val autoUse: Boolean) {
    private var onUntil = if (autoUse) sys.onLeft else max(0f, sys.onLeft - sys.chargeDown)
    private var readyAt = sys.readyIn
    private var charges = if (sys.charges == Int.MAX_VALUE) Float.MAX_VALUE else sys.charges + sys.regenProgress
    private var lastTime = 0f

    fun isOn(time: Float): Boolean = time < onUntil

    /** Uses it at [time] if it can be used then; true when it is on from [time]. */
    fun tryUse(time: Float): Boolean {
        if (isOn(time)) return true
        if (!autoUse || time < readyAt) return false
        regenTo(time)
        if (charges < 1f) return false
        if (charges != Float.MAX_VALUE) charges -= 1f
        onUntil = if (sys.onTime == Float.MAX_VALUE) Float.MAX_VALUE else time + sys.onTime
        readyAt = if (sys.onTime == Float.MAX_VALUE) Float.MAX_VALUE else onUntil + sys.cooldown
        return true
    }

    private fun regenTo(time: Float) {
        if (charges != Float.MAX_VALUE && sys.regen > 0f) {
            charges = min(sys.maxCharges.toFloat(), charges + sys.regen * (time - lastTime))
        }
        lastTime = time
    }
}
