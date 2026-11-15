package org.starficz.combataitweaks.capture

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.DamageType
import com.fs.starfarer.api.combat.GuidedMissileAI
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.MutableStat
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.StatBonus
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.campaign.ids.Stats
import com.fs.starfarer.api.impl.combat.dem.DEMScript
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI
import com.fs.starfarer.api.loading.MissileSpecAPI
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI
import org.json.JSONObject
import org.lwjgl.util.vector.Vector2f
import org.starficz.combataitweaks.ReflectionUtils.get
import org.starficz.combataitweaks.sim.ArmorInfo
import org.starficz.combataitweaks.sim.Bonus
import org.starficz.combataitweaks.sim.DamageInputs
import org.starficz.combataitweaks.sim.DamageKind
import org.starficz.combataitweaks.sim.DamageRules
import org.starficz.combataitweaks.sim.DefenseResolver
import org.starficz.combataitweaks.sim.Delivery
import org.starficz.combataitweaks.sim.DemInfo
import org.starficz.combataitweaks.sim.DemState
import org.starficz.combataitweaks.sim.FireMode
import org.starficz.combataitweaks.sim.HullClass
import org.starficz.combataitweaks.sim.InFlightMissile
import org.starficz.combataitweaks.sim.InFlightShot
import org.starficz.combataitweaks.sim.MirvInfo
import org.starficz.combataitweaks.sim.MissileInfo
import org.starficz.combataitweaks.sim.PdWeapon
import org.starficz.combataitweaks.sim.PointDefense
import org.starficz.combataitweaks.sim.ShieldKind
import org.starficz.combataitweaks.sim.SimDefense
import org.starficz.combataitweaks.sim.SimShooter
import org.starficz.combataitweaks.sim.SimWeapon
import org.starficz.combataitweaks.sim.SystemThreat
import org.starficz.combataitweaks.getOnTimeRemaining
import org.starficz.combataitweaks.sim.SubmunitionInfo
import org.starficz.combataitweaks.sim.WeaponClass
import org.starficz.combataitweaks.sim.WeaponPhase
import org.starficz.combataitweaks.sim.refireDelay
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The damage half of a batch snapshot (plan §3): shooters, what's in flight, and the protected ships' defences.
 * Main thread only. Values are live: rate-of-fire, range and flux-cost stats are in the weapon getters; weapon damage
 * bonuses are added here the way fired shots get them (vanilla's computeExtraDamageComponents), because the
 * weapon's own damage object is the spec's.
 */

private val missileCache = HashMap<String, MissileInfo?>()
private val geometryCache = WeakHashMap<ShipAPI, Geometry>()

/** Per-ship armor-grid geometry and the cell hit from each direction; fixed for a ship's life. */
private class Geometry(
    val originX: Float, val originY: Float,
    val axisIX: Float, val axisIY: Float, val axisJX: Float, val axisJY: Float,
    var rayCells: IntArray? = null,
)

/** Enemy ships this close to a protected ship get their defences captured: its fire is resolved on them (§13.2). */
private const val TARGET_CAPTURE_RANGE = 4000f

fun CombatEngineAPI.captureDamage(protectedIds: Set<String>): DamageInputs {
    val rules = captureRules()
    val shooters = ArrayList<SimShooter>()
    val shots = ArrayList<InFlightShot>()
    val defenses = HashMap<String, SimDefense>()
    val protectedShips = ArrayList<ShipAPI>()

    for (ship in ships) {
        if (ship.hullSpec.hullId == "dem_drone") { captureDemDroneBeam(ship, shots); continue }
        if (!ship.isCapturedForPrediction()) continue
        ship.toShooter()?.let { shooters += it }
        if (ship.id in protectedIds && !ship.isStationModule) {
            defenses[ship.id] = ship.toDefense(withModules = true)
            protectedShips += ship
        }
    }
    // the enemies near protected ships, as targets of their fire
    for (ship in ships) {
        if (ship.id in defenses || ship.isFighter || ship.isStationModule || !ship.isCapturedForPrediction()) continue
        val near = protectedShips.any { p ->
            p.owner != ship.owner && org.lazywizard.lazylib.MathUtils.isWithinRange(p, ship, TARGET_CAPTURE_RANGE)
        }
        if (near) defenses[ship.id] = ship.toDefense(withModules = true)
    }

    // point defense of every ship with captured defences: it shoots the missiles fired at it (plan §14)
    val shipById = ships.associateBy { it.id }
    val pointDefense = HashMap<String, PointDefense>()
    // -Dcat.pd=false leaves it out (A/B runs in the checks)
    if (System.getProperty("cat.pd") != "false") {
        for (id in defenses.keys) shipById[id]?.capturePointDefense()?.let { pointDefense[id] = it }
    }

    val missiles = ArrayList<InFlightMissile>()
    for (proj in projectiles) {
        if (proj.isFading || proj.isExpired) continue
        if (proj is MissileAPI) {
            if (!proj.isFlare) proj.toInFlight()?.let { missiles += it }
            continue
        }
        val speed = proj.velocity.length()
        if (speed < 1f) continue
        val range = proj.weapon?.range ?: proj.projectileSpec?.maxRange ?: 1000f
        val fade = proj.weapon?.projectileFadeRange ?: 200f
        val timeLeft = (range + fade) / speed - proj.elapsed
        if (timeLeft <= 0f) continue
        shots += InFlightShot(
            x = proj.location.x, y = proj.location.y, vx = proj.velocity.x, vy = proj.velocity.y, owner = proj.owner,
            damage = proj.damageAmount, hitStrength = proj.damageAmount, emp = proj.empAmount,
            kind = proj.damageType.toKind(), delivery = Delivery.PROJECTILE, softFlux = proj.damage?.isSoftFlux == true,
            timeLeft = timeLeft,
        )
    }
    return DamageInputs(shooters, shots, missiles, defenses, rules, pointDefense)
}

private fun captureRules(): DamageRules {
    val types = DamageKind.entries.map { it.toType() }
    return DamageRules(
        shieldMult = FloatArray(types.size) { types[it].shieldMult },
        armorMult = FloatArray(types.size) { types[it].armorMult },
        hullMult = FloatArray(types.size) { types[it].hullMult },
        dpsToHitStrengthMult = runCatching { Global.getSettings().getFloat("dpsToHitStrengthMult") }.getOrDefault(0.5f),
    )
}

fun DamageType.toKind(): DamageKind = when (this) {
    DamageType.KINETIC -> DamageKind.KINETIC
    DamageType.HIGH_EXPLOSIVE -> DamageKind.HIGH_EXPLOSIVE
    DamageType.FRAGMENTATION -> DamageKind.FRAGMENTATION
    DamageType.ENERGY -> DamageKind.ENERGY
    else -> DamageKind.OTHER
}

private fun DamageKind.toType(): DamageType = when (this) {
    DamageKind.KINETIC -> DamageType.KINETIC
    DamageKind.HIGH_EXPLOSIVE -> DamageType.HIGH_EXPLOSIVE
    DamageKind.FRAGMENTATION -> DamageType.FRAGMENTATION
    DamageKind.ENERGY -> DamageType.ENERGY
    DamageKind.OTHER -> DamageType.OTHER
}

private fun MutableStat.value() = modifiedValue
private fun StatBonus.toBonus() = Bonus(percentMod, flatBonus, mult)

/** Converts a world point to this ship's local frame (facing +x). */
private fun ShipAPI.toLocal(p: Vector2f): Pair<Float, Float> {
    val dx = p.x - location.x; val dy = p.y - location.y
    val a = Math.toRadians(-facing.toDouble())
    val c = cos(a).toFloat(); val s = sin(a).toFloat()
    return (dx * c - dy * s) to (dx * s + dy * c)
}

// --------------------------------------------------------------------------------------------------------- shooters

private fun ShipAPI.toShooter(): SimShooter? {
    if (isHoldFire) return null
    // the system's effect on the weapons (plan §11b): weapons are captured with it off, plus its boost
    SystemProfiles.learn(this)
    val profile = SystemProfiles.of(this)
    val weapons = allWeapons.mapNotNull { captureWeapon(it, weaponFactors(it, profile)) }
    val system = if (weapons.any { it.boost != null }) captureShooterSystem() else null
    val systemThreat = captureSystemThreat()
    if (weapons.isEmpty() && systemThreat == null) return null
    val tm = mutableStats.timeMult.modifiedValue.coerceAtLeast(0.01f)
    val silence = when {
        fluxTracker.isOverloaded -> fluxTracker.overloadTimeRemaining / tm
        fluxTracker.isVenting -> fluxTracker.timeToVent / tm
        else -> 0f
    }
    val stats = mutableStats
    // order of HullClass: FIGHTER, FRIGATE, DESTROYER, CRUISER, CAPITAL, OTHER
    val vsSize = floatArrayOf(
        stats.damageToFighters.value(), stats.damageToFrigates.value(), stats.damageToDestroyers.value(),
        stats.damageToCruisers.value(), stats.damageToCapital.value(), 1f,
    )
    return SimShooter(
        shipId = id, owner = owner, flux = fluxTracker.currFlux, hardFlux = fluxTracker.hardFlux,
        maxFlux = fluxTracker.maxFlux, dissipation = stats.fluxDissipation.modifiedValue, timeMult = tm,
        silenceTime = silence, damageVsSize = vsSize, weapons = weapons, systemThreat = systemThreat, system = system,
    )
}

/** The ship system as a damage threat, when its AI hints give it threat damage (see [SystemThreat]). */
private fun ShipAPI.captureSystemThreat(): SystemThreat? {
    val sys = system ?: return null
    val spec = sys.specAPI
    val hints = spec.aiHints2 ?: return null
    if (hints.threatDamage <= 0f) return null
    // systems without ammo report a huge max ammo; treat anything plausible as a real ammo count
    val hasAmmo = sys.maxAmmo in 1..1000
    val reload = if (hasAmmo && sys.ammo <= 0) {
        if (sys.ammoPerSecond <= 0f) return null
        (1f - sys.ammoReloadProgress) / sys.ammoPerSecond
    } else 0f
    val readyIn = when (sys.state) {
        ShipSystemAPI.SystemState.IDLE -> 0f
        ShipSystemAPI.SystemState.COOLDOWN -> sys.cooldownRemaining
        else -> sys.getOnTimeRemaining() + sys.cooldown
    }
    val tm = mutableStats.timeMult.modifiedValue.coerceAtLeast(0.01f)
    return SystemThreat(
        readyIn = max(readyIn, reload) / tm,
        cycle = ((sys.chargeUpDur + sys.chargeActiveDur + sys.chargeDownDur + sys.cooldown) / tm).coerceAtLeast(1f),
        uses = if (hasAmmo) max(sys.ammo, 1) else Int.MAX_VALUE,
        damage = hints.threatDamage,
        emp = spec.empDamage.coerceAtLeast(0f),
        kind = (spec.damageType ?: DamageType.ENERGY).toKind(),
        range = spec.getThreatRange(mutableStats),
        arc = spec.threatArc.takeIf { it > 0f } ?: 360f,
        angle = spec.threatAngle,
        speed = hints.threatSpeed,
    )
}

/**
 * Multiplier vanilla adds to every shot this weapon fires: the ship's weapon-type damage stat, plus the beam stat for
 * beams, combined as one modifier (percent and flat summed, mults multiplied) on a base of 1.
 */
private fun ShipAPI.damageFactor(w: WeaponAPI): Float {
    var percent = 0f; var flat = 0f; var mult = 1f
    fun add(stat: MutableStat) { percent += stat.percentMod; flat += stat.flatMod; mult *= stat.mult }
    if (w.isBeam) add(mutableStats.beamWeaponDamageMult)
    when (w.type) {
        WeaponAPI.WeaponType.ENERGY -> add(mutableStats.energyWeaponDamageMult)
        WeaponAPI.WeaponType.BALLISTIC -> add(mutableStats.ballisticWeaponDamageMult)
        WeaponAPI.WeaponType.MISSILE -> add(mutableStats.missileWeaponDamageMult)
        else -> {}
    }
    return ((1f + percent / 100f + flat) * mult).coerceAtLeast(0f)
}

/**
 * One weapon, with its ship's system off: [sys] takes the system's part out of the live values and gives its boost.
 */
private fun ShipAPI.captureWeapon(w: WeaponAPI, sys: WeaponSystemFactors): SimWeapon? {
    if (w.isDecorative || w.slot.isDecorative || w.slot.isHidden || w.isPermanentlyDisabled) return null
    if (w.type == WeaponAPI.WeaponType.LAUNCH_BAY) return null
    if (w.hasAIHint(WeaponAPI.AIHints.PD_ONLY)) return null
    if (w.usesAmmo() && w.ammo <= 0 && w.ammoPerSecond <= 0f) return null
    val spec = w.spec
    val dmg = w.damage ?: return null
    val tm = mutableStats.timeMult.modifiedValue.coerceAtLeast(0.01f)
    val factor = damageFactor(w) / sys.damage.now
    val baseDamage = dmg.baseDamage
    if (baseDamage <= 0f && dmg.fluxComponent <= 0f) return null

    val missileSpec = spec.projectileSpec as? MissileSpecAPI
    val missile = missileSpec?.let { missileInfo(spec.weaponId, it) }
    val guided = missileSpec != null && w.hasAIHint(WeaponAPI.AIHints.DO_NOT_AIM)
    val mode = when {
        w.isBurstBeam -> FireMode.BURST_BEAM
        w.isBeam -> FireMode.BEAM
        guided -> FireMode.GUIDED_MISSILE
        else -> FireMode.PROJECTILE
    }
    val projSpec = spec as? ProjectileWeaponSpecAPI
    val beamSpec = spec as? BeamWeaponSpecAPI
    val specRefire = projSpec?.refireDelay ?: 0f
    val refireRatio = if (specRefire > 0f && w.refireDelay > 0f) w.refireDelay / specRefire else 1f
    // fire rate (translation of obfuscated code: the projectile and burst beam weapon trackers, combat.entities.ship.A.J
    // and A.G): the ship's fire-rate stat for the weapon's type isn't in getRefireDelay(); it runs the weapon's clock
    // faster (projectile weapons: charge, burst delay and cooldown; burst beams: the cooldown), never below
    // minRefireDelay ([refireDelay]). Without the system's part.
    val liveRof = when (sys.weaponClass) {
        WeaponClass.BALLISTIC -> mutableStats.ballisticRoFMult.modifiedValue
        WeaponClass.ENERGY -> mutableStats.energyRoFMult.modifiedValue
        WeaponClass.MISSILE -> mutableStats.missileRoFMult.modifiedValue
        WeaponClass.OTHER -> 1f
    }.coerceAtLeast(0.01f)
    val rof = liveRof / sys.rof.now
    val burst = if (w.isBeam) 1 else spec.burstSize.coerceAtLeast(1)
    val burstDelay = refireDelay((projSpec?.burstDelay ?: 0f) * refireRatio, rof)
    // Burst beams ramp (translation of obfuscated code: the beam weapon tracker and BeamWeaponRay): brightness goes
    // 0 -> 1 linearly over the charge-up, holds for the burst, then 1 -> 0 over the charge-down, and damage scales
    // with brightness squared, so each ramp is worth a third of its length at full power. The sim fires at full power
    // only, so a burst gets that third of both ramps as full-power time, taken out of the charge-up wait and the
    // cooldown (vanilla's getCooldown() is charge-down + burst cooldown): the same cycle, the same sustained DPS.
    val rampUp = if (w.isBurstBeam) beamSpec?.chargeupTime ?: 0f else 0f
    val rampDown = if (w.isBurstBeam) beamSpec?.chargedownTime ?: 0f else 0f
    val chargeUp = when {
        w.isBurstBeam -> rampUp * 2f / 3f
        w.isBeam -> beamSpec?.chargeupTime ?: 0f
        else -> refireDelay(spec.chargeTime, rof)
    }
    val cooldown = when (mode) {
        FireMode.BEAM -> 0f
        FireMode.BURST_BEAM -> refireDelay((w.cooldown - rampDown / 3f).coerceAtLeast(0f), rof)
        else -> refireDelay(w.refireDelay, rof)
    }
    val burstDuration = if (w.isBurstBeam) (beamSpec?.burstDuration ?: 1f) + (rampUp + rampDown) / 3f else 0f

    // state now, in this ship's time
    var phase = WeaponPhase.READY
    var timeLeft = 0f
    var shotsLeft = 0
    when {
        w.isBeam && !w.isBurstBeam -> if (w.isFiring) phase = WeaponPhase.FIRING
        w.isBurstBeam -> {
            // a beam fading out: what is left of its ramp, brightness b falling to 0 over b x charge-down at
            // brightness squared, is b^3 x charge-down / 3 at full power (getChargeLevel() isn't the brightness
            // there; the live beam's is)
            val fading = w.beams?.maxOfOrNull { it.brightness } ?: 0f
            when {
                w.isFiring && w.burstFireTimeRemaining > 0f -> {
                    phase = WeaponPhase.FIRING; timeLeft = w.burstFireTimeRemaining + rampDown / 3f
                }
                fading > 0.01f && rampDown > 0f -> {
                    phase = WeaponPhase.FIRING; timeLeft = fading * fading * fading * rampDown / 3f
                }
                w.cooldownRemaining > 0f -> { phase = WeaponPhase.COOLDOWN; timeLeft = w.cooldownRemaining / liveRof }
            }
        }
        w.isInBurst -> {
            phase = WeaponPhase.FIRING
            shotsLeft = if (burstDelay > 0f) (w.burstFireTimeRemaining / burstDelay).toInt() + 1 else 1
            shotsLeft = shotsLeft.coerceIn(1, burst)
        }
        w.cooldownRemaining > 0f -> { phase = WeaponPhase.COOLDOWN; timeLeft = w.cooldownRemaining / liveRof }
        w.isFiring && w.chargeLevel > 0f && chargeUp > 0f -> {
            phase = WeaponPhase.CHARGING; timeLeft = chargeUp * (1f - w.chargeLevel)
        }
    }

    val damage = baseDamage * factor
    val speed = when {
        beamSpec != null -> beamSpec.beamSpeed
        missileSpec != null && !guided -> missileSpec.hullSpec?.engineSpec?.maxSpeed ?: w.projectileSpeed
        else -> w.projectileSpeed
    }.coerceAtLeast(100f)
    var spread = spec.maxSpread * mutableStats.maxRecoilMult.modifiedValue
    if (w.slot.isHardpoint) spread /= 2f
    val cycle = chargeUp + burstDelay * (burst - 1) + cooldown
    val sustained = when {
        mode == FireMode.BEAM -> damage
        mode == FireMode.BURST_BEAM -> damage * burstDuration / max(0.1f, burstDuration + chargeUp + cooldown)
        else -> damage * burst / max(0.1f, cycle)
    }
    val (mx, my) = toLocal(w.location)

    return SimWeapon(
        mountX = mx, mountY = my,
        slotAngle = w.arcFacing, arc = w.arc, turnRate = w.turnRate,
        angle = org.starficz.combataitweaks.sim.shortestRotation(facing, w.currAngle),
        range = w.range / sys.range.now, mode = mode, damage = damage, emp = dmg.fluxComponent,
        kind = w.damageType.toKind(),
        softFlux = if (w.isBeam) !dmg.isForceHardFlux else dmg.isSoftFlux,
        chargeUp = chargeUp, burstSize = burst, burstDelay = burstDelay, burstDuration = burstDuration,
        cooldown = cooldown, fluxCost = w.fluxCostToFire / sys.flux.now,
        phase = phase, phaseTimeLeft = timeLeft, shotsLeftInBurst = shotsLeft,
        disabledFor = if (w.isDisabled) w.disabledDuration / tm else 0f,
        ammo = if (w.usesAmmo()) w.ammo else -1, maxAmmo = w.maxAmmo, ammoPerSecond = w.ammoPerSecond,
        reloadSize = w.ammoTracker?.reloadSize ?: 0f,
        speed = speed, spread = spread,
        conservative = w.usesAmmo() && w.ammoPerSecond < 0.2f && !w.hasAIHint(WeaponAPI.AIHints.DO_NOT_CONSERVE),
        noAim = guided, missile = missile, sustainedDps = sustained,
        strike = w.hasAIHint(WeaponAPI.AIHints.STRIKE), vsFrigates = w.hasAIHint(WeaponAPI.AIHints.USE_VS_FRIGATES),
        weaponClass = sys.weaponClass, boost = sys.boost(),
    )
}

// --------------------------------------------------------------------------------------------------------- missiles

/** A missile spec's flight model, MIRV and DEM data; cached per [key] (specs don't change during a game). */
private fun missileInfo(key: String, spec: MissileSpecAPI): MissileInfo? = missileCache.getOrPut(key) {
    runCatching { buildMissileInfo(spec, null) }.getOrNull()
}

private fun buildMissileInfo(spec: MissileSpecAPI, live: MissileAPI?): MissileInfo {
    val engine = spec.hullSpec?.engineSpec
    val behavior = spec.behaviorJSON
    val mine = live?.isMine ?: (spec.typeString in MINE_TYPES)
    return MissileInfo(
        maxSpeed = live?.maxSpeed ?: engine?.maxSpeed ?: 500f,
        acceleration = live?.acceleration ?: engine?.acceleration ?: 1000f,
        maxTurnRate = live?.maxTurnRate ?: engine?.maxTurnRate ?: 0f,
        turnAcceleration = live?.turnAcceleration ?: engine?.turnAcceleration ?: 0f,
        launchSpeed = spec.launchSpeed,
        maxFlightTime = live?.maxFlightTime ?: spec.maxFlightTime,
        flameoutTime = spec.flameoutTime,
        fadeTime = spec.fadeTime,
        armingTime = spec.armingTime,
        noCollisionWhileFading = spec.isNoCollisionWhileFading,
        reduceDamageWhileFading = spec.isReduceDamageWhileFading,
        isMine = mine,
        mineRange = if (live != null && live.isMine) live.mineExplosionRange else 0f,
        mirv = behavior?.let { parseMirv(it) },
        dem = behavior?.let { parseDem(it) },
        hitpoints = live?.maxHitpoints ?: spec.hullSpec?.hitpoints ?: 0f,
        radius = live?.collisionRadius ?: spec.hullSpec?.collisionRadius?.takeIf { it > 0f } ?: 15f,
    )
}

private val MINE_TYPES = setOf("BOMB", "BOMB_WITH_SLOW", "PHASE_CHARGE", "PHASE_MINE", "NO_AI")

/** DEMScript.getValue: a [min, max] array is rolled per missile; launchers get the midpoint. */
private fun JSONObject.rolled(key: String, default: Float): Float {
    val arr = optJSONArray(key)
    if (arr != null && arr.length() >= 2) return ((arr.optDouble(0) + arr.optDouble(1)) / 2.0).toFloat()
    return optDouble(key, default.toDouble()).toFloat()
}

private fun parseMirv(b: JSONObject): MirvInfo? {
    if (b.optString("behavior") != "MIRV") return null
    val projId = b.optString("projectileSpec", null) ?: return null
    val settings = Global.getSettings()
    val subMissile = runCatching { settings.getMissileSpec(projId) }.getOrNull()
    val subProj = if (subMissile == null) runCatching { settings.getProjectileSpec(projId) }.getOrNull() else null
    val kind = b.optString("damageType", null)?.let { runCatching { DamageType.valueOf(it) }.getOrNull() }
        ?: subMissile?.damage?.type ?: subProj?.damage?.type ?: DamageType.HIGH_EXPLOSIVE
    val baseDamage = subMissile?.damage?.baseDamage ?: subProj?.damage?.baseDamage ?: 0f
    val spreadSpeed = (b.optDouble("spreadSpeed", 0.0) + b.optDouble("spreadSpeedRange", 0.0) / 2.0).toFloat()
    val sub = SubmunitionInfo(
        damage = if (b.has("damage")) b.optDouble("damage").toFloat() else baseDamage,
        emp = if (b.has("emp")) b.optDouble("emp").toFloat() else 0f,
        kind = kind.toKind(),
        missile = subMissile?.let { missileInfo("sub:$projId", it) },
        speed = spreadSpeed,
    )
    return MirvInfo(
        splitRange = (b.optDouble("splitRange", 500.0) + b.optDouble("splitRangeRange", 0.0) / 2.0).toFloat(),
        minTimeToSplit = b.optDouble("minTimeToSplit", 0.0).toFloat(),
        numShots = b.optInt("numShots", 1),
        arc = b.optDouble("arc", 0.0).toFloat(),
        arcOffset = b.optDouble("arcOffset", 0.0).toFloat(),
        spreadSpeed = spreadSpeed,
        evenSpread = b.optBoolean("evenSpread", false),
        sub = sub,
    )
}

private fun parseDem(b: JSONObject, script: DEMScript? = null): DemInfo? {
    if (b.optString("behavior") != "CUSTOM" || !b.has("triggerDistance")) return null
    // the payload as the DEM will fire it; spec values (the live weapon only exists once the drone fires)
    val payloadId = b.optString("payloadWeaponId", null)
    val payload = payloadId?.let { runCatching { Global.getSettings().getWeaponSpec(it) }.getOrNull() }
    val firingTime = b.optDouble("firingTime", 1.25).toFloat()
    val beam = payload as? BeamWeaponSpecAPI
    val trigger = b.rolled("triggerDistance", 500f)
    fun f(name: String, fallback: Float) = (script?.get(name = name) as? Float) ?: fallback
    // the payload beam's full-power time (the burst beam ramps, as captureWeapon's): vanilla's drone fires it once,
    // ramping up, holding for the burst, ramping down; continuous ones for the firing time after the charge-up
    val up = beam?.chargeupTime ?: 0f
    val down = beam?.chargedownTime ?: 0f
    val burst = beam?.burstDuration ?: 0f
    val start = up * 2f / 3f
    val duration = when {
        beam == null -> firingTime
        burst > 0f -> min(burst + (up + down) / 3f, firingTime + down / 3f)
        else -> max(0f, firingTime - start)
    }
    return DemInfo(
        minDelay = f("minDelayBeforeTriggering", b.rolled("minDelayBeforeTriggering", 1f)),
        triggerDistance = f("triggerDistance", trigger),
        preferredMin = f("preferredMinFireDistance", b.rolled("preferredMinFireDistance", 0f)),
        preferredMax = f("preferredMaxFireDistance", b.rolled("preferredMaxFireDistance", trigger)),
        turnRateBoost = b.optDouble("turnRateBoost", 100.0).toFloat(),
        targetingArc = b.optDouble("targetingLaserArc", 10.0).toFloat(),
        targetingTime = f("targetingTime", b.rolled("targetingTime", 1f)),
        firingTime = firingTime,
        allowedDriftFraction = b.optDouble("allowedDriftFraction", 0.33).toFloat(),
        payloadIsBeam = beam != null,
        payloadDamage = beam?.damagePerSecond ?: payload?.derivedStats?.burstDamage ?: 0f,
        payloadEmp = beam?.empPerSecond ?: 0f,
        payloadKind = (payload?.damageType ?: DamageType.ENERGY).toKind(),
        payloadSoftFlux = beam != null,
        payloadStart = if (beam != null) start else 0f,
        payloadDuration = duration,
        payloadRange = payload?.maxRange?.takeIf { it > 0f } ?: Float.MAX_VALUE,
        payloadCutWithMissile = !b.optBoolean("bombPumped", false),
    )
}

private fun MissileAPI.toInFlight(): InFlightMissile? {
    val spec = spec ?: return null
    val ai = unwrappedMissileAI ?: missileAI
    var targetId = ((ai as? GuidedMissileAI)?.target as? ShipAPI)?.id
    var info = runCatching { buildMissileInfo(spec, this) }.getOrNull() ?: return null
    var demState = DemState.WAIT
    var demElapsed = 0f

    // a DEM that triggered runs DEMScript as its AI: read the values it rolled and where it is in its sequence
    if (ai is DEMScript && info.dem != null) {
        runCatching {
            (ai.get(name = "fireTarget") as? ShipAPI)?.let { targetId = it.id }
            (ai.get(name = "state") as? Enum<*>)?.let { demState = DemState.valueOf(it.name) }
            demElapsed = when (demState) {
                DemState.SIGNAL -> ai.get(name = "elapsedTargeting") as? Float ?: 0f
                DemState.FIRE -> ai.get(name = "elapsedFiring") as? Float ?: 0f
                else -> 0f
            }
            val rolled = spec.behaviorJSON?.let { parseDem(it, ai) }
            if (rolled != null) info = MissileInfo(
                info.maxSpeed, info.acceleration, info.maxTurnRate, info.turnAcceleration, info.launchSpeed,
                info.maxFlightTime, info.flameoutTime, info.fadeTime, info.armingTime, info.noCollisionWhileFading,
                info.reduceDamageWhileFading, info.isMine, info.mineRange, info.mirv, rolled, info.hitpoints, info.radius,
            )
        }
    }
    // a DEM firing (or done) is already captured through its drone's payload beam (captureDemDroneBeam): vanilla's
    // DEMScript spawns the drone at the trigger and keeps the missile alive while the drone fires, so counting the
    // missile's payload too counted every firing DEM twice
    if (demState == DemState.FIRE || demState == DemState.DONE) return null
    // a missile that can't turn (rockets, torpedoes) goes where it points, whatever its AI calls its target
    if (info.maxTurnRate <= 0f && info.dem == null) targetId = null
    return InFlightMissile(
        x = location.x, y = location.y, vx = velocity.x, vy = velocity.y, facing = facing,
        angularVelocity = angularVelocity, owner = owner, damage = damageAmount, emp = empAmount,
        kind = damageType.toKind(), softFlux = false, info = info, flightTime = flightTime, targetId = targetId,
        demState = demState, demElapsed = demElapsed, hitpoints = hitpoints, radius = collisionRadius,
    )
}

/**
 * This ship's point defense (plan §14): its PD and PD_ONLY weapons (not STRIKE, not disabled, with ammo), aimed as
 * vanilla's autofire aims them (see [PdFire]). PD_ALSO weapons shoot ships first and are left out.
 */
private fun ShipAPI.capturePointDefense(): PointDefense? {
    val stats = mutableStats
    val bestLeading = stats.dynamic.getValue(Stats.PD_BEST_TARGET_LEADING, 0f) >= 1f
    val baseK = 2f - stats.autofireAimAccuracy.modifiedValue.coerceIn(0f, 1f)
    val vsMissiles = stats.damageToMissiles.modifiedValue
    val list = ArrayList<PdWeapon>()
    for (w in allWeapons) {
        if (w.isDecorative || w.slot.isDecorative || w.slot.isHidden || w.isPermanentlyDisabled || w.isDisabled) continue
        if (!(w.hasAIHint(WeaponAPI.AIHints.PD) || w.hasAIHint(WeaponAPI.AIHints.PD_ONLY))) continue
        if (w.hasAIHint(WeaponAPI.AIHints.STRIKE)) continue
        if (w.usesAmmo() && w.ammo <= 0) continue
        val derived = w.derivedStats ?: continue
        val dps = derived.sustainedDps.takeIf { it > 0f } ?: derived.dps
        val dpsVsMissiles = dps * damageFactor(w) * vsMissiles
        if (dpsVsMissiles <= 0f) continue
        val (mx, my) = toLocal(w.location)
        var spread = w.spec.maxSpread * stats.maxRecoilMult.modifiedValue
        if (w.slot.isHardpoint) spread /= 2f
        list += PdWeapon(
            mountX = mx, mountY = my, slotAngle = w.arcFacing, arc = w.arc, turnRate = w.turnRate, range = w.range,
            speed = if (w.isBeam) PdWeapon.BEAM_SPEED else w.projectileSpeed.coerceAtLeast(100f), spread = spread,
            dps = dpsVsMissiles,
            inaccuracy = if (bestLeading) 1f else (baseK - w.spec.autofireAccBonus).coerceAtLeast(1f),
        )
    }
    return if (list.isEmpty()) null else PointDefense(list, collisionRadius)
}

/** A DEM drone firing its payload beam: the remaining ticks, along its facing. */
private fun captureDemDroneBeam(drone: ShipAPI, out: MutableList<InFlightShot>) {
    val payload = drone.allWeapons.find { it.slot.id == "WS 001" } ?: drone.allWeapons.firstOrNull() ?: return
    if (!payload.isBeam || !(payload.isFiring || payload.chargeLevel > 0f)) return
    // full-power time left: the rest of the burst and a third of the charge-down, or what the fade still holds
    // (b^3 x charge-down / 3, as captureWeapon's burst beams)
    val down = (payload.spec as? BeamWeaponSpecAPI)?.chargedownTime ?: 0f
    val fading = payload.beams?.maxOfOrNull { it.brightness } ?: 0f
    val remaining = when {
        !payload.isBurstBeam -> 0.5f
        payload.isFiring && payload.burstFireTimeRemaining > 0f -> payload.burstFireTimeRemaining + down / 3f
        else -> fading * fading * fading * down / 3f
    }
    if (remaining < 0.02f) return
    val dps = (payload.damage?.baseDamage ?: 0f) * drone.damageFactor(payload)
    val emp = payload.damage?.fluxComponent ?: 0f
    val a = Math.toRadians(drone.facing.toDouble())
    val speed = 10000f
    var t = 0f
    while (t < remaining) {
        val tick = minOf(0.1f, remaining - t)
        out += InFlightShot(
            x = payload.location.x, y = payload.location.y,
            vx = (cos(a) * speed).toFloat(), vy = (sin(a) * speed).toFloat(), owner = drone.owner,
            damage = dps * tick, hitStrength = dps, emp = emp * tick, kind = payload.damageType.toKind(),
            delivery = Delivery.BEAM, softFlux = payload.damage?.isForceHardFlux != true,
            timeLeft = payload.range / speed, delay = t,
        )
        t += tick
    }
}

// --------------------------------------------------------------------------------------------------------- defences

private fun ShipAPI.toDefense(withModules: Boolean): SimDefense {
    val stats = mutableStats
    val tracker = fluxTracker
    val s: ShieldAPI? = shield
    val shieldKind = when (s?.type) {
        ShieldAPI.ShieldType.FRONT -> ShieldKind.FRONT
        ShieldAPI.ShieldType.OMNI -> ShieldKind.OMNI
        else -> ShieldKind.NONE
    }
    // a system on now: the values below are with it off, its effect and restrictions go in [SimDefense.system]
    val activeSystem = activeDefenseSystem()
    val effectId = activeSystem?.specAPI?.id?.let { SystemProfiles.effectId(it) }
    fun MutableStat.off() = if (effectId == null) modifiedValue else valueWithout(effectId)
    val armor = captureArmor(effectId)
    val modules = if (withModules) childModulesCopy.filter { it.isAlive && !it.isHulk }.map { it.toDefense(false) }
                  else emptyList()
    val dissipation = stats.fluxDissipation.modifiedValue
    return SimDefense(
        shipId = id,
        hullClass = when (hullSize) {
            ShipAPI.HullSize.FIGHTER -> HullClass.FIGHTER
            ShipAPI.HullSize.FRIGATE -> HullClass.FRIGATE
            ShipAPI.HullSize.DESTROYER -> HullClass.DESTROYER
            ShipAPI.HullSize.CRUISER -> HullClass.CRUISER
            ShipAPI.HullSize.CAPITAL_SHIP -> HullClass.CAPITAL
            else -> HullClass.OTHER
        },
        isDrone = isDrone,
        collisionRadius = collisionRadius,
        hitRadius = shieldRadiusEvenIfNoShield,
        timeMult = stats.timeMult.modifiedValue.coerceAtLeast(0.01f),
        shield = shieldKind,
        shieldArc = s?.arc ?: 0f,
        shieldOffset = if (s != null) org.starficz.combataitweaks.sim.shortestRotation(facing, s.facing) else 0f,
        shieldOn = s?.isOn == true,
        shieldActiveArc = s?.activeArc ?: 0f,
        unfoldTime = s?.unfoldTime ?: 0f,
        upkeep = if (effectId == null) s?.upkeep ?: 0f else upkeepWithout(effectId),
        fluxPerDamage = s?.fluxPerPointOfDamage ?: 1f,
        shieldDamageTakenMult = stats.shieldDamageTakenMult.off(),
        shieldKindMult = floatArrayOf(
            stats.kineticShieldDamageTakenMult.value(), stats.highExplosiveShieldDamageTakenMult.value(),
            stats.fragmentationShieldDamageTakenMult.value(), stats.energyShieldDamageTakenMult.value(), 1f,
        ),
        shieldDeliveryMult = floatArrayOf(
            stats.projectileShieldDamageTakenMult.value(), stats.beamShieldDamageTakenMult.value(),
            stats.missileShieldDamageTakenMult.value(),
        ),
        flux = tracker.currFlux,
        hardFlux = tracker.hardFlux,
        maxFlux = tracker.maxFlux,
        dissipation = dissipation,
        hardFluxDissipationFraction = stats.hardFluxDissipationFraction.value(),
        ventRate = dissipation * 2f * stats.ventRateMult.value(),
        isVenting = tracker.isVenting,
        overloadLeft = if (tracker.isOverloaded) tracker.overloadTimeRemaining else 0f,
        overloadTimeMod = stats.overloadTimeMod.toBonus(),
        kindMult = floatArrayOf(
            stats.kineticDamageTakenMult.value(), stats.highExplosiveDamageTakenMult.value(),
            stats.fragmentationDamageTakenMult.value(), stats.energyDamageTakenMult.value(), 1f,
        ),
        deliveryMult = floatArrayOf(
            stats.projectileDamageTakenMult.value(), stats.beamDamageTakenMult.value(),
            stats.missileDamageTakenMult.value(),
        ),
        armor = armor,
        rayCells = rayCellsFor(armor),
        hull = hitpoints,
        maxHull = maxHitpoints,
        modules = modules,
        owner = owner,
        system = activeSystem?.let { captureDefenseSystem(it) },
    )
}

private fun ShipAPI.geometry(): Geometry? {
    val grid = armorGrid ?: return null
    return geometryCache.getOrPut(this) {
        val (ox, oy) = toLocal(grid.getLocation(0, 0))
        val (ix, iy) = toLocal(grid.getLocation(1, 0))
        val (jx, jy) = toLocal(grid.getLocation(0, 1))
        Geometry(ox, oy, ix - ox, iy - oy, jx - ox, jy - oy)
    }
}

/** [effectId]: a system on now, whose modifiers are left out of the damage-taken mults. */
private fun ShipAPI.captureArmor(effectId: String? = null): ArmorInfo? {
    val grid = armorGrid ?: return null
    val cells = grid.grid ?: return null
    val w = cells.size
    if (w < 2) return null
    val h = cells[0].size
    if (h < 2) return null
    val flat = FloatArray(w * h)
    for (i in 0 until w) System.arraycopy(cells[i], 0, flat, i * h, h)
    val g = geometry() ?: return null
    val stats = mutableStats
    return ArmorInfo(
        width = w, height = h, cells = flat, rating = grid.armorRating,
        originX = g.originX, originY = g.originY, axisIX = g.axisIX, axisIY = g.axisIY, axisJX = g.axisJX, axisJY = g.axisJY,
        armorDamageTakenMult = effectId?.let { stats.armorDamageTakenMult.valueWithout(it) }
            ?: stats.armorDamageTakenMult.value(),
        kineticArmorDamageTakenMult = stats.kineticArmorDamageTakenMult.value(),
        effectiveArmorBonus = stats.effectiveArmorBonus.toBonus(),
        minArmorFraction = stats.minArmorFraction.value(),
        maxArmorDamageReduction = stats.maxArmorDamageReduction.value(),
        hullDamageTakenMult = effectId?.let { stats.hullDamageTakenMult.valueWithout(it) }
            ?: stats.hullDamageTakenMult.value(),
    )
}

private fun ShipAPI.rayCellsFor(armor: ArmorInfo?): IntArray {
    val g = geometry() ?: return DefenseResolver.rayCells(null, FloatArray(0), collisionRadius)
    g.rayCells?.let { return it }
    val segments = exactBounds?.origSegments ?: emptyList()
    val quads = FloatArray(segments.size * 4)
    for ((k, seg) in segments.withIndex()) {
        quads[k * 4] = seg.p1.x; quads[k * 4 + 1] = seg.p1.y; quads[k * 4 + 2] = seg.p2.x; quads[k * 4 + 3] = seg.p2.y
    }
    val cells = DefenseResolver.rayCells(armor, quads, collisionRadius * 0.7f)
    g.rayCells = cells
    return cells
}
