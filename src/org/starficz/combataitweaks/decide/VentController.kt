package org.starficz.combataitweaks.decide

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import org.starficz.combataitweaks.CombatAIConfig
import org.starficz.combataitweaks.predictor.DamageOutlook

/**
 * Owns the ship's vent decision (plan §8.1, §8.4). Main thread, every decision tick.
 *
 * With fresh predictions CAT decides: it vents when [VentRule] says so, and otherwise sets DO_NOT_VENT, which
 * switches off vanilla's own vent logic (threat evaluator, retreat and flux-dump maneuvers). Without fresh
 * predictions it sets nothing and vanilla vents as usual.
 */
class VentController(private val ship: ShipAPI, private val config: CombatAIConfig) {

    /** The latest decision (vent or not), for the trace. */
    var latest: VentChoice? = null
        private set

    /** The vent CAT ordered, while it lasts; null for vents vanilla started or when not venting. */
    var activeVent: VentChoice? = null
        private set

    private var ventOrderedAt = -1f

    /** While venting: hold position (safe vent chosen in place) instead of backing off. */
    val holdWhileVenting: Boolean
        get() = activeVent?.let { it.safe && it.option?.name == HOLD } == true

    /**
     * @param hold the hold-position branch; [backOff] the branch moving the way v1 backs off (may be null).
     */
    fun execute(now: Float, hold: DamageOutlook?, backOff: DamageOutlook?) {
        val tracker = ship.fluxTracker
        if (tracker.isVenting) {
            publish()
            return
        }
        if (activeVent != null && now - ventOrderedAt > ORDER_GRACE) activeVent = null

        val window = ventWindow()
        val options = listOfNotNull(
            hold?.takeIf { now - it.startTime <= MAX_AGE }?.ventOption(HOLD, now, window),
            backOff?.takeIf { now - it.startTime <= MAX_AGE }?.ventOption(BACK_OFF, now, window),
        )
        if (options.isEmpty()) {
            // no fresh prediction: vanilla keeps its own vent logic
            latest = null
            publish()
            return
        }

        val choice = VentRule.decide(VentInputs(
            fluxLevel = tracker.fluxLevel,
            hullLevel = ship.hullLevel,
            armorRating = ship.armorGrid.armorRating,
            maxHull = ship.maxHitpoints,
            blockedBy = ventBlocker(),
            window = window,
            minVentLevel = config.minVentLevel,
            maxArmorDamageLevelToVent = config.maxArmorDamageLevelToVent,
            maxHullDamageLevelToVent = config.maxHullDamageLevelToVent,
            stopVentingBelowHullLevel = config.stopVentingBelowHullLevel,
            options = options,
        ))
        latest = choice
        if (choice.vent) {
            ship.giveCommand(ShipCommand.VENT_FLUX, null, 0)
            activeVent = choice
            ventOrderedAt = now
        } else {
            ship.aiFlags.setFlag(AIFlags.DO_NOT_VENT, FLAG_DURATION)
        }
        publish()
    }

    /**
     * Every frame: keeps vanilla's own vent logic off while the latest decision is "no vent". Decisions run every
     * 0.05-0.1 s and the flag lasts 0.1 s, so without this it can lapse for a frame and let vanilla vent.
     */
    fun holdOff() {
        if (latest?.vent == false && !ship.fluxTracker.isVenting) ship.aiFlags.setFlag(AIFlags.DO_NOT_VENT, FLAG_DURATION)
    }

    /** Vent window W = time to vent + shield raise time + buffer (plan §8.1). */
    private fun ventWindow(): Float {
        val raise = ship.shield?.let {
            if (it.arc > 0f) it.unfoldTime * (config.safeShieldArc / it.arc).coerceIn(0f, 1f) else 0f
        } ?: 0f
        return ship.fluxTracker.timeToVent + raise + config.bufferTime
    }

    /** Why the ship can't vent now: mid-burst, during a non-toggle system, overloaded or phased; null if it can. */
    private fun ventBlocker(): String? {
        if (ship.fluxTracker.isOverloadedOrVenting) return "overloaded or venting"
        if (ship.isPhased) return "phased"
        ship.usableWeapons.firstOrNull { it.isInBurst }?.let { return "${it.id} mid-burst" }
        val system = ship.system
        if (system != null && !system.specAPI.isToggle &&
            (system.isOn || system.isChargeup || system.state == ShipSystemAPI.SystemState.ACTIVE)) {
            return "system ${system.id} active"
        }
        // a damper in the right-click slot (Vanguard) forbids venting while it is on
        val right = ship.phaseCloak
        if (right != null && right.specAPI?.isPhaseCloak != true && right.isOn && right.specAPI?.isVentingAllowed == false) {
            return "system ${right.id} active"
        }
        return null
    }

    private fun publish() {
        ship.setCustomData(TRACE, latest?.reason)
        ship.setCustomData(ACTIVE_VENT, activeVent)
    }

    companion object {
        const val HOLD = "HOLD"
        const val BACK_OFF = "BACK_OFF"
        /** Custom-data keys: the latest decision's reason line, and the vent CAT ordered (a [VentChoice]). */
        const val TRACE = "CAT_VENT_TRACE"
        const val ACTIVE_VENT = "CAT_ACTIVE_VENT"
        /** Predictions older than this count as missing (plan §9). */
        const val MAX_AGE = 0.5f
        private const val FLAG_DURATION = 0.1f
        /** How long a vent order may take to show up as venting before it counts as refused. */
        private const val ORDER_GRACE = 0.3f
    }
}
