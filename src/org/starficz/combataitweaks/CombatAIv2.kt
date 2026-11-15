package org.starficz.combataitweaks

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.*
import com.fs.starfarer.api.combat.listeners.AdvanceableListener
import com.fs.starfarer.api.util.IntervalUtil
import org.json.JSONObject
import org.lazywizard.lazylib.MathUtils
import org.starficz.combataitweaks.decide.DefenseController
import org.starficz.combataitweaks.decide.MoveController
import org.starficz.combataitweaks.decide.MobilityPhases
import org.starficz.combataitweaks.decide.ShieldController
import org.starficz.combataitweaks.decide.VentController
import org.starficz.combataitweaks.predictor.PredictorManager
import org.starficz.combataitweaks.runtime.CombatScan
import org.starficz.combataitweaks.runtime.VanillaTarget
import org.starficz.combataitweaks.sim.Tuning

// defaults from Tuning (-Dcat.tune.<name> at launch overrides them, for the tuning battles)
data class CombatAIConfig(
    val bufferTime: Float = 0.1f,
    val minVentLevel: Float = Tuning.minVentLevel,
    val safeShieldArc: Float = 60f,
    val maxArmorDamageLevelToVent: Float = Tuning.maxArmorDamageLevelToVent,
    val maxHullDamageLevelToVent: Float = Tuning.maxHullDamageLevelToVent,
    val stopVentingBelowHullLevel: Float = Tuning.stopVentingBelowHullLevel,
    val maxFluxLevelRaiseToHarass: Float = Tuning.maxFluxLevelRaiseToHarass,
    val reengageIfFluxDropsBelowLevel: Float = Tuning.reengageIfFluxDropsBelowLevel,
    val forceAdvanceIfFluxBelowLevel: Float = Tuning.forceAdvanceIfFluxBelowLevel,
    val disengageIfFluxAboveLevel: Float = 1f,
    val backoffIfFluxAboveLevel: Float = Tuning.backoffIfFluxAboveLevel,
    val backoffIfHardfluxAboveLevel: Float = Tuning.backoffIfHardfluxAboveLevel
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

/**
 * The CAT AI on one ship: every decision tick it asks the predictor for its movement options, then lets the vent
 * decision ([VentController], plan §8.1) and the movement decision ([MoveController], §8.2 to §8.4) act on the
 * newest results. Vanilla's AI keeps flying the ship; CAT steers it through AI flags (and direct engine commands
 * where vanilla won't back off).
 */
class CombatAIv2(val ship: ShipAPI, val config: CombatAIConfig = CombatAIConfig()) : AdvanceableListener {
    companion object {
        /** Custom-data keys read by SCY's system AIs (plan §8.4, SCY contract): keep the names and values. */
        const val BEHAVIOR_STATE = "BEHAVIOR_STATE"
        const val USE_MOVEMENT_SYSTEM_TO_BACKOFF = "USE_MOVEMENT_SYSTEM_BACKOFF"
        const val USE_MOVEMENT_SYSTEM_TO_ADVANCE = "USE_MOVEMENT_SYSTEM_ADVANCE"
        /** BEHAVIOR_STATE with no enemy ship near (SCY reads this one), or with no fresh prediction yet. */
        const val VANILLA_FALLBACK = "VANILLA_FALLBACK"
        const val NO_PREDICTION = "NO_PREDICTION"
        /** Enemy ships within this range keep CAT in charge. */
        private const val NEARBY = 3000f
    }

    private val interval = IntervalUtil(0.05f, 0.1f)
    private val ventController = VentController(ship, config)
    private val defenseController = DefenseController(ship)
    private val moveController = MoveController(ship, config, defenseController)
    private val shieldController = ShieldController(ship)
    private var latestResults: Map<String, org.starficz.combataitweaks.predictor.RequestResult> = emptyMap()
    private var fallback = true
    private var travel = false
    private var defendStandingDown = false

    init { Global.getCombatEngine().customData[PredictorManager.FLAG_KEY] = true }

    override fun advance(amount: Float) {
        val engine = Global.getCombatEngine() ?: return
        if (!ship.isAlive || ship.isHulk) return
        // Despite the name, isUIAutopilotOn() is true while the PLAYER is flying (vanilla returns !autopilot).
        // So this skips the player's ship only while it is under manual control.
        if (engine.isUIAutopilotOn && engine.playerShip == ship) return
        // plan §8.5: a retreating ship is vanilla's
        if (ship.isRetreating) {
            if (!fallback) moveController.standDown()
            fallback = true
            ship.setCustomData(BEHAVIOR_STATE, VANILLA_FALLBACK)
            return
        }
        interval.advance(amount)
        if (interval.intervalElapsed()) tick(engine)
        ventController.holdOff()
        // plan §13.9: the branch the ship is flying, else holding
        val flying = (ship.customData[MoveController.OPTION] as? String)?.let { latestResults[it] }
            ?: latestResults[PredictorManager.HOLD]
        val now = engine.getTotalElapsedTime(false)
        shieldController.apply(now, flying?.outlook, holdUp = defenseController.holdsShield())
        // plan §11b: CAT decides the defensive system, used only while a +DEFENSE option is driven (or, standing down,
        // while HOLD+DEFENSE clearly beats HOLD)
        defenseController.apply(if (fallback) defendStandingDown else moveController.choice?.option?.defense == true, now)

        if (fallback) {
            // nothing to fight: burn the mobility system to travel toward a far target, else leave it to vanilla
            if (MobilityPhases.of(ship)?.enforceOverride == true) MoveController.useSystem(ship, travel)
            return
        }
        moveController.apply(ventController.holdWhileVenting)
    }

    private fun tick(engine: CombatEngineAPI) {
        val now = engine.getTotalElapsedTime(false)
        val predictor = PredictorManager.getInstance(engine)
        val target = pickTarget()
        val results = predictor.results(ship.id)
        latestResults = results
        predictor.request(ship, moveController.options(target, results))

        // the vent decision runs with or without enemies nearby: nothing threatening is the best time to vent
        ventController.execute(now, results[PredictorManager.HOLD]?.outlook, results[PredictorManager.BACK_OFF]?.outlook)

        val enemiesNear = CombatScan.of(engine).anyEnemyNear(ship.owner, ship.location.x, ship.location.y, NEARBY)
        if (target == null || !enemiesNear) {
            if (!fallback) moveController.standDown()
            fallback = true
            defendStandingDown = defenseController.owned && moveController.standingDownDefense(now, results)
            travel = ship.shipTarget?.let { t ->
                val range = (ship.aiFlags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_RANGE_FROM_TARGET) as? Float ?: 0f) + 50f
                MathUtils.getDistance(t.location, ship.location) > range
            } ?: true
            ship.setCustomData(BEHAVIOR_STATE, VANILLA_FALLBACK)
            return
        }
        fallback = false
        val choice = moveController.decide(now, target, results, ventController)
        ship.setCustomData(BEHAVIOR_STATE, choice?.state?.name ?: NO_PREDICTION)
    }

    /**
     * The ship CAT advances on and backs off from: vanilla's attack target, else the ship target, else the nearest,
     * the first two only within [NEARBY]. Vanilla's first picker only looks within 2200 and its fallbacks go to the
     * nearest ship of a size class anywhere on the map, so its target can be a ship far away while another is close.
     */
    private fun pickTarget(): ShipAPI? {
        fun ShipAPI.usable() = isAlive && !isHulk && !isFighter && owner != ship.owner && owner != 100 &&
            MathUtils.isWithinRange(this, ship, NEARBY)
        val vanilla = VanillaTarget.of(ship)
        if (vanilla != null && vanilla.usable()) return vanilla
        ship.shipTarget?.takeIf { it.usable() }?.let { return it }
        val engine = Global.getCombatEngine() ?: return null
        return CombatScan.of(engine).nearestEnemy(ship.owner, ship.location.x, ship.location.y, NEARBY)
    }
}
