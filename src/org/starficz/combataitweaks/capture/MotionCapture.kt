package org.starficz.combataitweaks.capture

import kotlin.math.max
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.CombatAssignmentType
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.util.Misc
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.getAngle
import org.lazywizard.lazylib.ext.minus
import org.lazywizard.lazylib.ext.rotate
import org.starficz.combataitweaks.getOnTimeRemaining
import org.starficz.combataitweaks.runtime.VanillaTarget
import org.starficz.combataitweaks.sim.BackOffTraits
import org.starficz.combataitweaks.sim.Engagement
import org.starficz.combataitweaks.sim.HullClass
import org.starficz.combataitweaks.sim.SimShip
import org.starficz.combataitweaks.sim.SystemInfo
import org.starficz.combataitweaks.sim.WeaponInfo
import org.starficz.combataitweaks.sim.WeaponKind
import org.starficz.combataitweaks.sim.WeaponSize

/** The movement half of a batch snapshot: every ship as the `sim` package sees it. */
class MotionCapture(
    val ships: List<SimShip>,
    /** Vanilla's targeting radius from each ship to each protected ship: (protected id, other id) -> radius. */
    val targetingRadius: Map<String, Map<String, Float>>,
    /** The other way round, for each protected ship's targets only: (protected id, target id) -> target's radius. */
    val ownTargetRadius: Map<String, Map<String, Float>> = emptyMap(),
)

/** Toggle systems (burn drive toggle and the like) have no fixed active time; assume they stay on this long. */
private const val TOGGLE_ACTIVE_TIME = 10f

/** Same ship filter as the damage half of the capture, so both halves describe the same ships. */
fun ShipAPI.isCapturedForPrediction(): Boolean {
    if (hullSpec.hullId == "dem_drone") return false
    if (isShuttlePod || isHulk || !isAlive || isPiece) return false
    if (isFighter && (isLanding || isFinishedLanding || wing?.isReturning(this) == true)) return false
    return true
}

/**
 * Captures every ship for the movement model. Main thread only.
 * @param protectedIds ships predictions are requested for; targeting radii are measured to these.
 */
fun CombatEngineAPI.captureMotion(protectedIds: Set<String>): MotionCapture {
    val captured = ships.filter { it.isCapturedForPrediction() }
    val simShips = captured.map { it.toSimShip() }

    val settings = Global.getSettings()
    val radii = HashMap<String, Map<String, Float>>()
    val ownRadii = HashMap<String, Map<String, Float>>()
    for (p in captured) {
        if (p.id !in protectedIds) continue
        radii[p.id] = captured.filter { it !== p }.associate { it.id to settings.getTargetingRadius(it.location, p, false) }
        // the ships CAT may advance on: vanilla's maneuver target and the ship target
        val maneuverTarget = VanillaTarget.of(p)
        ownRadii[p.id] = listOfNotNull(maneuverTarget, p.shipTarget).filter { it !== p }
            .associate { it.id to settings.getTargetingRadius(p.location, it, false) }
    }
    return MotionCapture(simShips, radii, ownRadii)
}

private fun ShipAPI.toSimShip(): SimShip {
    val weapons = captureWeaponInfo()
    val parent = if (isStationModule) parentStation else null
    val flags = shipAI?.aiFlags
    val maneuverTarget = VanillaTarget.of(this)
    val maneuverRange = flags?.takeIf { it.hasFlag(AIFlags.MANEUVER_RANGE_FROM_TARGET) }
        ?.getCustom(AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float

    val silence = when {
        fluxTracker.isOverloaded -> fluxTracker.overloadTimeRemaining
        fluxTracker.isVenting -> fluxTracker.timeToVent
        else -> 0f
    }
    val cruise = if (isEngineBoostActive) maxSpeed - mutableStats.zeroFluxSpeedBoost.modifiedValue else maxSpeed

    return SimShip(
        id = id,
        owner = owner,
        hullClass = when (hullSize) {
            ShipAPI.HullSize.FIGHTER -> HullClass.FIGHTER
            ShipAPI.HullSize.FRIGATE -> HullClass.FRIGATE
            ShipAPI.HullSize.DESTROYER -> HullClass.DESTROYER
            ShipAPI.HullSize.CRUISER -> HullClass.CRUISER
            ShipAPI.HullSize.CAPITAL_SHIP -> HullClass.CAPITAL
            else -> HullClass.OTHER
        },
        collisionRadius = collisionRadius,
        x = location.x, y = location.y,
        vx = velocity.x, vy = velocity.y,
        facing = facing,
        angularVelocity = angularVelocity,
        maxSpeed = maxSpeed,
        acceleration = acceleration,
        deceleration = deceleration,
        turnAcceleration = turnAcceleration,
        maxTurnRate = maxTurnRate,
        timeMult = mutableStats.timeMult.modifiedValue,
        cruiseSpeed = cruise.coerceAtLeast(0f),
        engineForward = engineController?.isAccelerating == true,
        engineBack = engineController?.isAcceleratingBackwards == true,
        engineLeft = engineController?.isStrafingLeft == true,
        engineRight = engineController?.isStrafingRight == true,
        silenceTime = silence,
        hardFluxLevel = hardFluxLevel,
        isPhaseShip = phaseCloak != null,
        system = system?.toSystemInfo(),
        weaponReach = Engagement.weaponReach(weapons),
        engagementRange = Engagement.baseRange(weapons, collisionRadius),
        bestFacingOffset = Engagement.bestFacingOffset(weapons),
        maneuverTargetId = maneuverTarget?.id,
        maneuverRange = maneuverRange,
        carrierId = wing?.sourceShip?.id,
        wingRange = wing?.range ?: 0f,
        parentId = parent?.id,
        moduleOffsetDist = parent?.let { MathUtils.getDistance(it.location, location) } ?: 0f,
        moduleOffsetAngle = parent?.let { Misc.normalizeAngle(it.location.getAngle(location) - it.facing) } ?: 0f,
        moduleFacingOffset = parent?.let { Misc.normalizeAngle(facing - it.facing) } ?: 0f,
        backOff = backOffTraits(),
        gunReach = Engagement.gunReach(weapons),
    )
}

/**
 * When this ship's AI backs off under flux (plan §13.8); null for fighters, modules and ships without an AI (the
 * player's). Translation of obfuscated code (combat.ai.N and StrafeTargetManeuverV2):
 * - the personality is the AI config's override, else the captain's; full assault, an intercept order or a strike
 *   order (non-carriers) make it reckless;
 * - the CR offset grows as peak CR time runs out: (1 - min(1, peak time left / min(90, no-loss seconds))) x 0.4,
 *   + (0.6 - CR) x 0.4 once it is past peak; halved for cautious and timid, a quarter for phase ships.
 */
private fun ShipAPI.backOffTraits(): BackOffTraits? {
    if (isFighter || isStationModule) return null
    val ai = shipAI ?: return null
    val config = runCatching { ai.config }.getOrNull()
    var personality = config?.personalityOverride ?: captain?.personalityAPI?.id
    val tasks = Global.getCombatEngine()?.getFleetManager(owner)?.getTaskManager(isAlly)
    val order = tasks?.getAssignmentFor(this)?.type
    if (tasks?.isFullAssault == true || order == CombatAssignmentType.INTERCEPT ||
        (order == CombatAssignmentType.STRIKE && variant?.isCarrier != true)) {
        personality = "reckless"
    }
    val cautious = personality == "cautious" || personality == "timid"

    val noLoss = minOf(90f, hullSpec.noCRLossSeconds)
    val peak = mutableStats.peakCRDuration.computeEffective(hullSpec.noCRLossTime)
    val left = maxOf(0f, peak - timeDeployedForCRReduction)
    var cr = if (noLoss > 0f) (1f - minOf(1f, left / noLoss)) * 0.4f else 0f
    if (cr > 0f) cr += maxOf(0f, 0.6f - currentCR) * 0.4f
    if (cautious) cr *= 0.5f
    if (phaseCloak != null) cr *= 0.25f

    val (base, step, floor) = BackOffTraits.forPersonality(personality)
    return BackOffTraits(
        base = base, sizeStep = step, floor = floor,
        phase = phaseCloak != null,
        crOffset = cr,
        onFlux = config?.backingOffWhileNotVentingAllowed != false,
        atAll = ai.aiFlags?.hasFlag(AIFlags.DO_NOT_BACK_OFF) != true,
    )
}

/**
 * Only systems whose AI hints say they move the ship matter to the movement model. Vanilla's hints are averages
 * (burn drive says +100 where its script gives up to +200); they're what vanilla's own threat evaluator uses.
 */
private fun ShipSystemAPI.toSystemInfo(): SystemInfo? {
    val hints = specAPI.getAIHints2() ?: return null
    val burst = hints.burstMovementPotential
    val speed = hints.activeSpeedIncrease
    if (burst <= 0f && speed <= 0f) return null
    // out of charges: usable again when the next one regenerates (as the damage capture's system threats); a burn
    // on its last charge is out of ammo while it runs, and used to read as never usable, the burn itself unseen
    val reload = if (isOutOfAmmo) {
        if (ammoPerSecond <= 0f) Float.MAX_VALUE else (1f - ammoReloadProgress) / ammoPerSecond
    } else 0f
    val on = state == ShipSystemAPI.SystemState.IN || state == ShipSystemAPI.SystemState.ACTIVE
    val readyIn = when (state) {
        ShipSystemAPI.SystemState.IDLE -> 0f
        ShipSystemAPI.SystemState.COOLDOWN -> cooldownRemaining
        else -> getOnTimeRemaining() + cooldown
    }
    val activeTime = (if (specAPI.isToggle) TOGGLE_ACTIVE_TIME else chargeUpDur + chargeActiveDur).coerceAtLeast(0.1f)
    return SystemInfo(
        readyIn = max(readyIn, reload),
        activeTime = activeTime,
        cooldown = chargeDownDur + cooldown,
        burstPotential = burst,
        activeSpeedIncrease = speed,
        // already on at the snapshot: the charge-up and active time still to come (the charge-down doesn't move it)
        activeLeft = if (on) (getOnTimeRemaining() - chargeDownDur).coerceIn(0f, activeTime) else 0f,
    )
}

/** Weapons as the engagement-range and facing rules see them (weapon groups only, as vanilla's scan does). */
private fun ShipAPI.captureWeaponInfo(): List<WeaponInfo> {
    val result = ArrayList<WeaponInfo>()
    for ((groupIndex, group) in weaponGroupsCopy.withIndex()) {
        for (w in group.weaponsCopy) {
            if (w.isDecorative || w.slot.isDecorative) continue
            val kind = when (w.type) {
                WeaponAPI.WeaponType.BALLISTIC -> WeaponKind.BALLISTIC
                WeaponAPI.WeaponType.ENERGY -> WeaponKind.ENERGY
                WeaponAPI.WeaponType.MISSILE -> WeaponKind.MISSILE
                else -> WeaponKind.OTHER
            }
            if (w.type == WeaponAPI.WeaponType.LAUNCH_BAY) continue
            val hasAmmo = !w.usesAmmo() || w.ammo > 0
            val burst = w.spec.burstSize.coerceAtLeast(1)
            result += WeaponInfo(
                group = groupIndex,
                size = when (w.size) {
                    WeaponAPI.WeaponSize.SMALL -> WeaponSize.SMALL
                    WeaponAPI.WeaponSize.LARGE -> WeaponSize.LARGE
                    else -> WeaponSize.MEDIUM
                },
                kind = kind,
                isHardpoint = w.slot.isHardpoint,
                slotAngle = w.slot.angle,
                arc = w.arc,
                range = w.range,
                slotForward = (w.location - location).rotate(-facing).x,
                usable = hasAmmo && !w.hasAIHint(WeaponAPI.AIHints.NO_MANUAL_FIRE),
                lowAmmoMissile = kind == WeaponKind.MISSILE && w.usesAmmo() && w.ammo <= burst * 5 &&
                    !w.hasAIHint(WeaponAPI.AIHints.DO_NOT_CONSERVE),
                pd = w.hasAIHint(WeaponAPI.AIHints.PD),
                pdOnly = w.hasAIHint(WeaponAPI.AIHints.PD_ONLY),
                dps = w.derivedStats.dps,
            )
        }
    }
    return result
}
