package org.starficz.combataitweaks.capture

import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.WeaponAPI
import org.starficz.combataitweaks.getOnTimeRemaining
import org.starficz.combataitweaks.sim.DefenseSystem
import org.starficz.combataitweaks.sim.ShooterSystem
import org.starficz.combataitweaks.sim.WeaponBoost
import org.starficz.combataitweaks.sim.WeaponClass
import kotlin.math.max

/*
 * A ship's system for the damage sim (plan §11b): its profile's effect on each weapon, and its timing.
 */

/** Factors on one weapon stat: the system's part of the live value ([now]), and its profile's at full effect ([boost]). */
internal class StatFactor(val now: Float, val boost: Float) {
    companion object { val NONE = StatFactor(1f, 1f) }
}

/**
 * What a ship's system does to one weapon: per stat, the factor its effect has on the live value now and the factor
 * its profile has at full effect, both against the value with the system off. Divide (fire rate, flux, range) or
 * multiply (damage) the live value by [StatFactor.now] for the system-off value.
 */
internal class WeaponSystemFactors(
    val weaponClass: WeaponClass,
    val rof: StatFactor = StatFactor.NONE,
    val damage: StatFactor = StatFactor.NONE,
    val flux: StatFactor = StatFactor.NONE,
    val range: StatFactor = StatFactor.NONE,
    /** Whether the profile changes this weapon at all. */
    val boosted: Boolean = false,
) {
    fun boost(): WeaponBoost? = if (!boosted) null else WeaponBoost(rof.boost, damage.boost, flux.boost, range.boost)
}

internal fun WeaponAPI.weaponClass(): WeaponClass = when (type) {
    WeaponAPI.WeaponType.BALLISTIC -> WeaponClass.BALLISTIC
    WeaponAPI.WeaponType.ENERGY -> WeaponClass.ENERGY
    WeaponAPI.WeaponType.MISSILE -> WeaponClass.MISSILE
    else -> WeaponClass.OTHER
}

/** Whether the system's effect is on now (charging up, active or charging down). */
internal fun ShipSystemAPI.effectOn(): Boolean =
    state == ShipSystemAPI.SystemState.IN || state == ShipSystemAPI.SystemState.ACTIVE ||
        state == ShipSystemAPI.SystemState.OUT

/**
 * [w]'s system factors on this ship. The weapon stats of its class (plus the beam stats for beams) are combined as
 * vanilla combines a stat's modifiers. Only a system that is on counts as part of the live value: one that is off may
 * still leave modifiers under its id (Lidar Array's passive range bonus), which stay.
 */
internal fun ShipAPI.weaponFactors(w: WeaponAPI, profile: SystemProfile?): WeaponSystemFactors {
    val cls = w.weaponClass()
    val sys = system ?: return WeaponSystemFactors(cls)
    val sysId = sys.specAPI?.id ?: return WeaponSystemFactors(cls)
    val id = SystemProfiles.effectId(sysId)
    val on = sys.effectOn()
    val p = profile?.takeIf { it.use == SystemUse.OFFENSIVE }
    if (!on && p == null) return WeaponSystemFactors(cls)
    val beam = w.isBeam
    val stats = mutableStats
    val (rof, damage, flux, range) = when (cls) {
        WeaponClass.BALLISTIC -> listOf(ProfileStat.BALLISTIC_ROF, ProfileStat.BALLISTIC_DAMAGE, ProfileStat.BALLISTIC_FLUX, ProfileStat.BALLISTIC_RANGE)
        WeaponClass.ENERGY -> listOf(ProfileStat.ENERGY_ROF, ProfileStat.ENERGY_DAMAGE, ProfileStat.ENERGY_FLUX, ProfileStat.ENERGY_RANGE)
        WeaponClass.MISSILE -> listOf(ProfileStat.MISSILE_ROF, ProfileStat.MISSILE_DAMAGE, ProfileStat.MISSILE_FLUX, ProfileStat.MISSILE_RANGE)
        WeaponClass.OTHER -> return WeaponSystemFactors(cls)
    }
    var boosted = false
    fun factor(base: Float, vararg channel: ProfileStat?): StatFactor {
        val views = channel.filterNotNull().map { it to it.statOf(stats, base) }
        fun value(pick: (ProfileStat, StatView) -> StatModifier?): Float {
            var flat = 0f; var percent = 0f; var mult = 1f
            for ((stat, view) in views) {
                val (f, pc, m) = view.sumsWithout(id, pick(stat, view))
                flat += f; percent += pc; mult *= m
            }
            return (base + base * percent / 100f + flat) * mult
        }
        val now = value { _, v -> v.modifierOf(id) }
        val off = if (on) value { _, _ -> null } else now
        if (off <= 0f) return StatFactor.NONE
        val withProfile = if (p == null) off else value { s, v -> p[s] ?: if (on) null else v.modifierOf(id) }
        if (p != null && channel.any { it != null && p[it] != null }) boosted = true
        return StatFactor(now / off, withProfile / off)
    }
    val baseRange = w.spec?.maxRange?.takeIf { it > 0f } ?: max(1f, w.range)
    return WeaponSystemFactors(
        weaponClass = cls,
        rof = factor(1f, rof),
        damage = factor(1f, damage, if (beam) ProfileStat.BEAM_DAMAGE else null),
        flux = factor(1f, flux, if (beam) ProfileStat.BEAM_FLUX else null),
        range = factor(baseRange, range, if (beam) ProfileStat.BEAM_RANGE else null),
        boosted = boosted,
    )
}

/** The stat's value with every modifier under [id] left out. */
internal fun MutableStat.valueWithout(id: String): Float {
    val view = StatView.of(this)
    val (flat, percent, mult) = view.sumsWithout(id, null)
    return (view.base + view.base * percent / 100f + flat) * mult
}

/**
 * The system on now whose effect and restrictions the defence capture handles: the right-click system first, then the
 * ship system (a damper can sit in either: `DamperFieldStats.getDamper`). Not a phase cloak: phasing is its own case.
 */
internal fun ShipAPI.activeDefenseSystem(): ShipSystemAPI? =
    listOfNotNull(phaseCloak, system).firstOrNull { it.effectOn() && it.specAPI?.isPhaseCloak != true }

/**
 * [sys] (on now, from [activeDefenseSystem]) as the defence sim sees it: the ship's defensive values with it on, its
 * restrictions and its flux, for the rest of its on-time. The capture keeps the values with it off
 * ([valueWithout] its effect id); no profile is needed, since it reads what the system does now.
 */
internal fun ShipAPI.captureDefenseSystem(sys: ShipSystemAPI): DefenseSystem {
    val tm = mutableStats.timeMult.modifiedValue.coerceAtLeast(0.01f)
    val spec = sys.specAPI
    val stats = mutableStats
    val onLeft = sys.getOnTimeRemaining()
    return DefenseSystem(
        // a toggle on (not turning off) reports a huge on-time: it stays on (plan §11b, known optimism)
        onLeft = if (onLeft >= 100000f) Float.MAX_VALUE else onLeft / tm,
        chargeDown = sys.chargeDownDur / tm,
        shieldDamageTakenMult = stats.shieldDamageTakenMult.modifiedValue,
        armorDamageTakenMult = stats.armorDamageTakenMult.modifiedValue,
        hullDamageTakenMult = stats.hullDamageTakenMult.modifiedValue,
        upkeep = shield?.upkeep ?: 0f,
        noShield = spec?.isShieldAllowed == false,
        noVent = spec?.isVentingAllowed == false,
        noFiring = spec?.isFiringAllowed == false,
        fluxPerSecond = sys.fluxPerSecond.coerceAtLeast(0f),
        hardFlux = spec?.generatesHardFlux() == true,
        effectFrom = if (sys.state == ShipSystemAPI.SystemState.IN) sys.chargeUpDur * (1f - sys.effectLevel) / tm else 0f,
    )
}

/**
 * [sys] used now (plan §11b, CAT's `+DEFENSE` options): its effect from [profile] on the ship's values with the system
 * off, from the end of its charge-up to the start of its charge-down (a toggle stays on), its restrictions and flux
 * over the whole use. The ship's own values come from the live stats with the system's modifiers left out, so the
 * profile adds to whatever else the ship has.
 */
internal fun ShipAPI.plannedDefenseSystem(sys: ShipSystemAPI, profile: SystemProfile): DefenseSystem {
    val tm = mutableStats.timeMult.modifiedValue.coerceAtLeast(0.01f)
    val spec = sys.specAPI
    val stats = mutableStats
    val id = SystemProfiles.effectId(spec.id)
    fun value(stat: ProfileStat) = stat.statOf(stats).valueWith(id, profile[stat])
    val toggle = spec.isToggle
    val upkeep = shield?.let {
        val now = stats.shieldUpkeepMult.modifiedValue
        val base = if (now > 0f) it.upkeep / now else hullSpec?.shieldSpec?.upkeepCost ?: 0f
        max(0f, base * value(ProfileStat.SHIELD_UPKEEP))
    } ?: 0f
    return DefenseSystem(
        onLeft = if (toggle) Float.MAX_VALUE else (sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur) / tm,
        chargeDown = sys.chargeDownDur / tm,
        shieldDamageTakenMult = value(ProfileStat.SHIELD_DAMAGE_TAKEN),
        armorDamageTakenMult = value(ProfileStat.ARMOR_DAMAGE_TAKEN),
        hullDamageTakenMult = value(ProfileStat.HULL_DAMAGE_TAKEN),
        upkeep = upkeep,
        noShield = !spec.isShieldAllowed,
        noVent = !spec.isVentingAllowed,
        noFiring = !spec.isFiringAllowed,
        fluxPerSecond = sys.fluxPerSecond.coerceAtLeast(0f),
        hardFlux = spec.generatesHardFlux(),
        effectFrom = sys.chargeUpDur / tm,
    )
}

/** Whether [sys] can be used now: idle, with a charge, not blocked by overload or venting. */
internal fun ShipAPI.canUseNow(sys: ShipSystemAPI): Boolean =
    sys.state == ShipSystemAPI.SystemState.IDLE && !sys.isOutOfAmmo && !fluxTracker.isOverloadedOrVenting &&
        (sys.fluxPerUse <= 0f || fluxTracker.currFlux + sys.fluxPerUse <= fluxTracker.maxFlux)

/** Shield upkeep with every modifier under [effectId] left out of the upkeep stat. */
internal fun ShipAPI.upkeepWithout(effectId: String): Float {
    val s = shield ?: return 0f
    val stat = mutableStats.shieldUpkeepMult
    val now = stat.modifiedValue
    val off = stat.valueWithout(effectId)
    // vanilla's upkeep is the base upkeep x this stat (floored at 0): with the stat at 0 the base is the spec's
    val base = if (now > 0f) s.upkeep / now else hullSpec?.shieldSpec?.upkeepCost ?: 0f
    return max(0f, base * off)
}

/**
 * The system's timing for the damage sim, in engine seconds; null without a system. Charge-up, active and
 * charge-down all count as on (see [ShooterSystem]).
 */
internal fun ShipAPI.captureShooterSystem(): ShooterSystem? {
    val sys = system ?: return null
    val tm = mutableStats.timeMult.modifiedValue.coerceAtLeast(0.01f)
    val toggle = sys.specAPI?.isToggle == true
    val onLeft = if (sys.effectOn()) sys.getOnTimeRemaining() else 0f
    // systems without ammo report a huge max ammo; anything plausible is a real charge count
    val hasCharges = sys.maxAmmo in 1..1000
    val reload = when {
        !hasCharges || sys.ammo > 0 -> 0f
        sys.ammoPerSecond <= 0f -> Float.MAX_VALUE
        else -> (1f - sys.ammoReloadProgress) / sys.ammoPerSecond
    }
    val wait = when (sys.state) {
        ShipSystemAPI.SystemState.IDLE -> 0f
        ShipSystemAPI.SystemState.COOLDOWN -> sys.cooldownRemaining
        else -> if (toggle) Float.MAX_VALUE else onLeft + sys.cooldown
    }
    val readyIn = max(wait, reload)
    fun engine(t: Float) = if (t >= Float.MAX_VALUE / 2f || t >= 100000f) Float.MAX_VALUE else t / tm
    return ShooterSystem(
        onLeft = engine(onLeft),
        chargeDown = sys.chargeDownDur / tm,
        onTime = if (toggle) Float.MAX_VALUE else (sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur) / tm,
        cooldown = sys.cooldown / tm,
        readyIn = engine(readyIn),
        charges = if (hasCharges) sys.ammo else Int.MAX_VALUE,
        maxCharges = if (hasCharges) sys.maxAmmo else Int.MAX_VALUE,
        regen = if (hasCharges) sys.ammoPerSecond * tm else 0f,
        regenProgress = if (hasCharges) sys.ammoReloadProgress else 0f,
    )
}
