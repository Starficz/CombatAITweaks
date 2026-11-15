package org.starficz.combataitweaks.decide

import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipCommand
import com.fs.starfarer.api.combat.ShipSystemAPI
import org.starficz.combataitweaks.capture.ProfileStat
import org.starficz.combataitweaks.capture.SystemProfile
import org.starficz.combataitweaks.capture.SystemProfiles
import org.starficz.combataitweaks.capture.canUseNow
import org.starficz.combataitweaks.capture.captureDefenseSystem
import org.starficz.combataitweaks.capture.plannedDefenseSystem
import org.starficz.combataitweaks.sim.SystemPlan

/**
 * CAT's use of the ship's defensive system (plan §11b): what each movement option assumes about it, and the commands.
 * CAT decides it and vanilla's own use is blocked, so the simulation can count on a use: CAT makes it.
 * - Plain options keep it off: a toggle that is on turns off now ([plainPlan]).
 * - `+DEFENSE` options use it now, or keep a toggle on ([defensePlan]).
 * - [apply] uses it while the driven option is a `+DEFENSE` one, and otherwise blocks it.
 *
 * Main thread. Only for systems with a defensive profile (capture/SystemProfiles, `use` defensive).
 */
class DefenseController(private val ship: ShipAPI) {
    private val found: Pair<ShipSystemAPI, SystemProfile>? = SystemProfiles.defensive(ship)
    private val sys: ShipSystemAPI? get() = found?.first
    /** When CAT last turned a toggle on (it stays on at least [MIN_TOGGLE_ON], no flapping). */
    private var onSince = -1000f

    /** Whether the ship has a defensive system CAT decides. */
    val owned: Boolean get() = found != null

    private fun timeMult() = ship.mutableStats.timeMult.modifiedValue.coerceAtLeast(0.01f)

    /** On and not turning off. */
    private fun ShipSystemAPI.running() = isOn && state != ShipSystemAPI.SystemState.OUT

    /** The system in the plain options: kept off (a toggle that is on turns off now); null: as captured. */
    fun plainPlan(): SystemPlan? {
        val s = sys ?: return null
        if (!s.specAPI.isToggle || !s.running()) return null
        return SystemPlan(ship.captureDefenseSystem(s).endingIn(s.chargeDownDur / timeMult()))
    }

    /** The system in the `+DEFENSE` options: used now, or a toggle that is on kept on; null when it can't be. */
    fun defensePlan(): SystemPlan? {
        val (s, profile) = found ?: return null
        if (s.specAPI.isToggle && s.running()) return SystemPlan(ship.captureDefenseSystem(s))
        if (!ship.canUseNow(s)) return null
        return SystemPlan(ship.plannedDefenseSystem(s, profile))
    }

    /**
     * How long before the trouble the system is used (plan §11b, as late as still covers it): its charge-up, a
     * [BUFFER] against mispredicted timing, and the [LATENCY] of the decision (tick, then the command next frame).
     */
    fun lead(): Float {
        val s = sys ?: return 0f
        return s.chargeUpDur / timeMult() + BUFFER + LATENCY
    }

    /** Whether the shield must stay up: a shield system (the fortress shield) is on. */
    fun holdsShield(): Boolean {
        val (s, profile) = found ?: return false
        return s.isOn && profile[ProfileStat.SHIELD_DAMAGE_TAKEN] != null && s.specAPI.isShieldAllowed
    }

    /**
     * Every frame. [use]: the driven option is a `+DEFENSE` one. Uses the system (lowering the shield first when the
     * system forbids it) or keeps vanilla from using it; a toggle turns on or off to match, staying on at least
     * [MIN_TOGGLE_ON].
     */
    fun apply(use: Boolean, now: Float) {
        val s = sys ?: return
        ship.setCustomData(USING, use)
        // the right-click slot answers to the shield/phase command, the ship system to its own
        val command = if (s === ship.phaseCloak) ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK else ShipCommand.USE_SYSTEM
        if (s.specAPI.isToggle) {
            val running = s.running()
            val want = use || (running && now - onSince < MIN_TOGGLE_ON)
            val canFlip = if (want) s.state == ShipSystemAPI.SystemState.IDLE && ship.canUseNow(s)
                          else s.state == ShipSystemAPI.SystemState.ACTIVE
            if (want != running && canFlip) {
                ship.giveCommand(command, null, 0)
                if (want) onSince = now
            } else ship.blockCommandForOneFrame(command)
            return
        }
        if (use && ship.canUseNow(s)) {
            val shield = ship.shield
            if (!s.specAPI.isShieldAllowed && shield != null && shield.isOn) shield.toggleOff()
            ship.giveCommand(command, null, 0)
        } else if (!s.isOn) {
            ship.blockCommandForOneFrame(command)
        }
    }

    companion object {
        /** Custom-data key: whether CAT wants the defensive system used now (the trace, the checks). */
        const val USING = "CAT_DEFENSE_USING"
        /** Margin for mispredicted hit times (the trouble arriving earlier than predicted). */
        const val BUFFER = 0.35f
        /** Decision tick (up to 0.1 s) and the command landing next frame. */
        const val LATENCY = 0.1f
        /** A toggle CAT turned on stays on this long (its charge-up and charge-down make flapping costly). */
        const val MIN_TOGGLE_ON = 3f
    }
}
