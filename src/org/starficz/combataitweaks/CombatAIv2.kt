package org.starficz.combataitweaks

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import org.json.JSONObject
import org.lazywizard.lazylib.MathUtils
import org.lazywizard.lazylib.ext.getDirectionalVector
import org.lwjgl.util.vector.Vector2f
import org.starficz.combataitweaks.evaluators.*
import org.starficz.combataitweaks.predictor.Constants.PREDICTION_DURATION
import org.starficz.combataitweaks.predictor.DamageTimeline
import org.starficz.combataitweaks.predictor.PredictorManager

data class CombatAIConfig(
    val bufferTime: Float = 0.1f,
    val minVentLevel: Float = 0.2f,
    val safeShieldArc: Float = 60f,
    val maxArmorDamageLevelToVent: Float = 0.1f,
    val maxHullDamageLevelToVent: Float = 0.05f,
    val stopVentingBelowHullLevel: Float = 0.5f,
    val maxFluxLevelRaiseToHarass: Float = 0.3f,
    val reengageIfFluxDropsBelowLevel: Float = 0.4f,
    val forceAdvanceIfFluxBelowLevel: Float = 0.7f,
    val disengageIfFluxAboveLevel: Float = 1f,
    val backoffIfFluxAboveLevel: Float = 0.85f,
    val backoffIfHardfluxAboveLevel: Float = 0.75f
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("bufferTime", bufferTime.toDouble())
        json.put("minVentLevel", minVentLevel.toDouble())
        json.put("safeShieldArc", safeShieldArc.toDouble())
        json.put("maxArmorDamageLevelToVent", maxArmorDamageLevelToVent.toDouble())
        json.put("maxHullDamageLevelToVent", maxHullDamageLevelToVent.toDouble())
        json.put("stopVentingBelowHullLevel", stopVentingBelowHullLevel.toDouble())
        json.put("maxFluxLevelRaiseToHarass", maxFluxLevelRaiseToHarass.toDouble())
        json.put("reengageIfFluxDropsBelowLevel", reengageIfFluxDropsBelowLevel.toDouble())
        json.put("forceAdvanceIfFluxBelowLevel", forceAdvanceIfFluxBelowLevel.toDouble())
        json.put("disengageIfFluxAboveLevel", disengageIfFluxAboveLevel.toDouble())
        json.put("backoffIfFluxAboveLevel", backoffIfFluxAboveLevel.toDouble())
        json.put("backoffIfHardfluxAboveLevel", backoffIfHardfluxAboveLevel.toDouble())
        return json.toString()
    }

    companion object {
        const val TAG_PREFIX = "cat_config:"

        fun fromJsonString(jsonStr: String): CombatAIConfig {
            return try {
                val json = JSONObject(jsonStr)
                CombatAIConfig(
                    bufferTime = json.optDouble("bufferTime", 0.1).toFloat(),
                    minVentLevel = json.optDouble("minVentLevel", 0.2).toFloat(),
                    safeShieldArc = json.optDouble("safeShieldArc", 60.0).toFloat(),
                    maxArmorDamageLevelToVent = json.optDouble("maxArmorDamageLevelToVent", 0.1).toFloat(),
                    maxHullDamageLevelToVent = json.optDouble("maxHullDamageLevelToVent", 0.05).toFloat(),
                    stopVentingBelowHullLevel = json.optDouble("stopVentingBelowHullLevel", 0.5).toFloat(),
                    maxFluxLevelRaiseToHarass = json.optDouble("maxFluxLevelRaiseToHarass", 0.3).toFloat(),
                    reengageIfFluxDropsBelowLevel = json.optDouble("reengageIfFluxDropsBelowLevel", 0.4).toFloat(),
                    forceAdvanceIfFluxBelowLevel = json.optDouble("forceAdvanceIfFluxBelowLevel", 0.7).toFloat(),
                    disengageIfFluxAboveLevel = json.optDouble("disengageIfFluxAboveLevel", 1.0).toFloat(),
                    backoffIfFluxAboveLevel = json.optDouble("backoffIfFluxAboveLevel", 0.85).toFloat(),
                    backoffIfHardfluxAboveLevel = json.optDouble("backoffIfHardfluxAboveLevel", 0.75).toFloat()
                )
            } catch (e: Exception) {
                CombatAIConfig() // Fallback to default if parsing fails
            }
        }
    }
}

class CombatAIv2(val ship: ShipAPI, val config: CombatAIConfig = CombatAIConfig()) : AdvanceableListener {
    companion object {
        const val BASE_DAMAGE = "BASE_DAMAGE"
        const val BACKOFF_DAMAGE = "BACKOFF_DAMAGE"
        const val BEHAVIOR_STATE = "BEHAVIOR_STATE"
        const val USE_MOVEMENT_SYSTEM_TO_BACKOFF = "USE_MOVEMENT_SYSTEM_BACKOFF"
        const val USE_MOVEMENT_SYSTEM_TO_ADVANCE = "USE_MOVEMENT_SYSTEM_ADVANCE"

        private val EVALUATORS: List<SystemEvaluator> = listOf(
            ScyThrustersEvaluator(),
            ScyArmorSwitchEvaluator(),
            ManeuveringJetsEvaluator(),
            PlasmaJetsEvaluator()
        )
    }

    enum class BehaviorState {
        ADVANCE,
        STANDOFF,
        BACKOFF,
        DISENGAGE,
        VENTING,
    }

    private val interval = IntervalUtil(0.05f, 0.1f)
    private var currentSituation: TacticalVariables? = null

    private val ventLogic = VentLogic(config, Global.getSettings().getShipSystemSpec(ship.hullSpec.shipSystemId))
    private val behaviorLogic = BehaviorLogic(config)
    private val mobilitySystemLogic = MobilitySystemLogic()

    init { Global.getCombatEngine().customData[PredictorManager.FLAG_KEY] = true }

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (engine.isUIAutopilotOn && engine.playerShip == ship) return
        interval.advance(amount)

        if (interval.intervalElapsed()) {
            currentSituation = TacticalVariables(ship, engine, EVALUATORS, config)

            ship.setCustomData(BASE_DAMAGE, currentSituation?.baseDamage)
            ship.setCustomData(BACKOFF_DAMAGE, currentSituation?.backoffDamage)
        }

        val snap = currentSituation ?: return

        if (snap.isVanillaFallback) {
            ship.setCustomData(BEHAVIOR_STATE, "VANILLA_FALLBACK")
            if (snap.enforceOverride) mobilitySystemLogic.shouldUseSystem(ship, true)
            return
        }

        ventLogic.execute(ship, snap)
        behaviorLogic.execute(ship, snap, ventLogic.safeToVent)
        mobilitySystemLogic.execute(ship, snap, behaviorLogic.currentState)
    }

    class TacticalVariables(
        val ship: ShipAPI,
        engine: CombatEngineAPI,
        evaluators: List<SystemEvaluator>,
        config: CombatAIConfig
    ) {
        val currentTime = engine.getTotalElapsedTime(false)
        private val predictor = PredictorManager.getInstance(engine)

        // 1. Spatial Awareness
        val safePoint: Vector2f? = getBackingOffStrafePoint(ship)
        val isVanillaFallback = safePoint == null
        val backoffVector: Vector2f = safePoint?.let { ship.location.getDirectionalVector(it) } ?: Misc.ZERO

        val getCloser = run {
            val targetRange = ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float
            val range = ((targetRange ?: 0f) + 50f).coerceAtLeast(0f)
            ship.shipTarget?.let { MathUtils.getDistanceSquared(it.location, ship.location) > range*range } ?: true
        }

        // 2. Base Predictions
        val baseDamage: DamageTimeline? = run {
            predictor.queueRequest(ship, Misc.ZERO)
            predictor.getResult(ship, Misc.ZERO)
        }

        val baseRatio: Float = run {
            val flux = baseDamage?.fluxToShield(currentTime, PREDICTION_DURATION, ship) ?: 0f
            (flux + ship.currFlux) / ship.maxFlux
        }

        val wantToPoke = baseRatio < config.maxFluxLevelRaiseToHarass

        // 3. System & Backoff Predictions
        val systemEvaluation: EvaluatorResult? = run {
            val eval = evaluators.find { it.canHandle(ship) }
            eval?.evaluate(ship, predictor, backoffVector, currentTime, config.backoffIfFluxAboveLevel)
        }
        val enforceOverride = evaluators.find { it.canHandle(ship) }?.enforceOverride ?: false

        val backoffDamage: DamageTimeline? = run {
            if (systemEvaluation != null) {
                systemEvaluation.backoffDamage
            } else {
                predictor.queueRequest(ship, backoffVector)
                predictor.getResult(ship, backoffVector)
            }
        }

        private val backoffFluxCalc = run {
            val flux = backoffDamage?.fluxToShield(currentTime, PREDICTION_DURATION, ship) ?: 0f
            ((flux + ship.currFlux) / ship.maxFlux) to (ship.hardFluxLevel + flux / ship.maxFlux)
        }

        val backoffRatio = backoffFluxCalc.first
        val backoffHardfluxRatio = backoffFluxCalc.second
    }

    class VentLogic(val config: CombatAIConfig, val shipSystemSpec: ShipSystemSpecAPI) {
        var safeToVent = false

        fun execute(ship: ShipAPI, snap: TacticalVariables) {
            // calc danger time if going to vent
            val timeToRaiseShields = ship.shield?.let {
                it.unfoldTime * (config.safeShieldArc / it.arc).coerceAtMost(1f)
            } ?: 0f
            val dangerTime = ship.fluxTracker.timeToVent + timeToRaiseShields + config.bufferTime

            val hasModularArmor = ship.childModulesCopy.count() == ship.hullSpec.allWeaponSlotsCopy.count { it.isStationModule }

            // find actual armor and calc if venting now is safer than trying to tank
            val armor = if (hasModularArmor) ship.armorGrid.armorRating * 1.5f
                        else ship.armorGrid.armorAtCell(ship.armorGrid.weakestArmorRegion()!!) ?: 0f
            val aggressiveVentResult = snap.backoffDamage?.compareVentingVsNotVenting(
                snap.currentTime, dangerTime, (1 - ship.hardFluxLevel) * ship.maxFlux, ship, startingArmor = armor
            )

            // calc "safe" vent limits and if we are below those
            val armorLimit = run {
                val armorRating = (ship.armorGrid.armorRating * if (hasModularArmor) 1.5f else 1f)
                armorRating * config.maxArmorDamageLevelToVent
            }

            val hullLimit = run {
                val hullLevelLimit = if (ship.hullLevel < config.stopVentingBelowHullLevel) 0.001f
                else config.maxHullDamageLevelToVent

                ship.maxHitpoints * hullLevelLimit
            }

            val (ventArmor, ventHull) = snap.backoffDamage?.damageToArmorAndHull(snap.currentTime, dangerTime, ship)
                ?: Pair(Float.MAX_VALUE, Float.MAX_VALUE)

            // vent if safe, or safer then not venting
            safeToVent = ventArmor < armorLimit && ventHull < hullLimit
            val defensiveVent = aggressiveVentResult?.isVentingSafer == true && ship.hullLevel > config.stopVentingBelowHullLevel


            val shouldNotVent = ship.usableWeapons.any { it.isInBurst } ||
                    ((ship.system.isOn || ship.system.isChargeup) && !shipSystemSpec.isToggle)
            val shouldVent = (safeToVent || defensiveVent) && !shouldNotVent


            if (shouldVent && ship.fluxLevel > config.minVentLevel && !ship.fluxTracker.isVenting) {
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
            }
        }
    }

    class MobilitySystemLogic {
        fun execute(ship: ShipAPI, snap: TacticalVariables, currentState: BehaviorState) {
            val eval = snap.systemEvaluation ?: return

            val isBackingOff = currentState == BehaviorState.BACKOFF || currentState == BehaviorState.DISENGAGE
            val isAdvancing = currentState == BehaviorState.STANDOFF || currentState == BehaviorState.ADVANCE

            val useSystemToBackoff = isBackingOff && eval.useSystemToBackoff
            val useSystemToAdvance = isAdvancing && snap.getCloser && (ship.system.specAPI.isToggle || snap.wantToPoke)

            ship.setCustomData(USE_MOVEMENT_SYSTEM_TO_BACKOFF, useSystemToBackoff)
            ship.setCustomData(USE_MOVEMENT_SYSTEM_TO_ADVANCE, useSystemToAdvance)


            if (!snap.enforceOverride) return
            shouldUseSystem(ship, useSystemToBackoff || useSystemToAdvance)
        }

        fun shouldUseSystem(ship: ShipAPI, shouldUse: Boolean = false){
            val sys = ship.system ?: return

            if (sys.specAPI.isToggle){
                if (!ship.system.isCoolingDown && (shouldUse xor ship.system.isOn))
                    ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
            } else {
                if (shouldUse && sys.state == ShipSystemAPI.SystemState.IDLE) {
                    ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0)
                } else if (!shouldUse) {
                    ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM)
                }
            }
        }
    }

    class BehaviorLogic(val config: CombatAIConfig) {
        var currentState = BehaviorState.STANDOFF

        fun execute(ship: ShipAPI, snap: TacticalVariables, safeToVent: Boolean) {
            currentState = when {
                ship.fluxTracker.isVenting -> BehaviorState.VENTING
                ship.fluxLevel > config.reengageIfFluxDropsBelowLevel && currentState == BehaviorState.DISENGAGE -> BehaviorState.DISENGAGE
                snap.backoffRatio > config.disengageIfFluxAboveLevel -> BehaviorState.DISENGAGE
                snap.backoffRatio > config.backoffIfFluxAboveLevel -> BehaviorState.BACKOFF
                snap.backoffHardfluxRatio > config.backoffIfHardfluxAboveLevel -> BehaviorState.BACKOFF
                snap.baseRatio < config.forceAdvanceIfFluxBelowLevel -> BehaviorState.ADVANCE
                else -> BehaviorState.STANDOFF
            }

            ship.setCustomData(BEHAVIOR_STATE, currentState)
            applyFlags(ship, snap.getCloser, safeToVent)
        }

        private fun applyFlags(ship: ShipAPI, getCloser: Boolean, safeToVent: Boolean) {
            val flagDuration = 0.1f

            ship.aiFlags.apply {
                when (currentState) {
                    BehaviorState.ADVANCE -> {
                        setFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN_COOLDOWN)
                        unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                        (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                            if (it >= 2400f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                        }
                    }
                    BehaviorState.STANDOFF -> {
                        if (getCloser) setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                        (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                            if (it >= 2400f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                        }
                    }
                    BehaviorState.BACKOFF -> {
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                    }
                    BehaviorState.DISENGAGE -> {
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, flagDuration, 2500f)
                        setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                        setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                        unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                    }
                    BehaviorState.VENTING -> {
                        if (!safeToVent) {
                            setFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE, flagDuration, 2500f)
                            setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, flagDuration)
                            setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, flagDuration)
                            unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF)
                            unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING)
                        } else {
                            if (getCloser) {
                                setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF, flagDuration)
                                setFlag(ShipwideAIFlags.AIFlags.DO_NOT_BACK_OFF_EVEN_WHILE_VENTING, flagDuration)
                            }
                            unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF)
                            unsetFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)
                            (getCustom(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE) as? Float)?.let {
                                if (it >= 2400f) unsetFlag(ShipwideAIFlags.AIFlags.BACK_OFF_MIN_RANGE)
                            }
                        }
                    }
                }
            }
        }
    }
}

