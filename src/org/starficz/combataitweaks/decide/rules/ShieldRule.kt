package org.starficz.combataitweaks.decide

import kotlin.math.max
import kotlin.math.min

// Pure: no game API, compiled into the unit tests with `sim` (test/run-unit-tests.ps1).

class ShieldInputs(
    /** Seconds until the next predicted hit the shield would block (Float.MAX_VALUE: none); null: no prediction. */
    val nextShieldHit: Float?,
    /** Seconds the shield needs to be fully up again if raised next frame: unfold time + margin. */
    val window: Float,
    /** Why the live check says no (an enemy beam on the ship, a shot about to arrive), or null. */
    val liveThreat: String?,
    /** Why the shield can't come down (overloaded, venting, no shield), or null. */
    val blockedBy: String?,
    val flux: Float,
    val hardFlux: Float,
    val maxFlux: Float,
    /** Flux per second (ship time scaled to engine time by the caller). */
    val dissipation: Float,
    /** Shield upkeep, flux per second while it is on. */
    val upkeep: Float,
    /** Share of hard flux that dissipates with the shield up (hull mods, skills); usually 0. */
    val hardDissipationShare: Float = 0f,
)

class ShieldChoice(val drop: Boolean, val reason: String, val gain: Float = 0f)

/**
 * Shield flicker (plan §13.9): drop the shield now when, raised again next frame, it would be fully up before
 * the next hit it would block, and dropping it is worth it: over the time it could stay down, more flux drains with
 * it down (all flux at full dissipation) than up (soft flux only, at dissipation - upkeep).
 */
object ShieldRule {
    /** Time down counted for the gain at most (and when no hit is predicted). */
    const val GAIN_HORIZON = 2f
    /** Smallest gain worth dropping for: this much flux, or [MIN_GAIN_SHARE] of max flux. */
    const val MIN_GAIN = 25f
    const val MIN_GAIN_SHARE = 0.01f

    fun decide(i: ShieldInputs): ShieldChoice {
        i.blockedBy?.let { return ShieldChoice(false, "KEEP: $it") }
        val next = i.nextShieldHit ?: return ShieldChoice(false, "KEEP: no prediction")
        i.liveThreat?.let { return ShieldChoice(false, "KEEP: $it") }
        if (next <= i.window) {
            return ShieldChoice(false, if (!Explain.enabled) "KEEP: shield hit within the window"
                else "KEEP: shield hit in %.2fs, needs %.2fs".format(next, i.window))
        }
        val down = min(GAIN_HORIZON, next - i.window)
        val gain = gain(i, down)
        val min = max(MIN_GAIN, MIN_GAIN_SHARE * i.maxFlux)
        if (gain < min) {
            return ShieldChoice(false, if (!Explain.enabled) "KEEP: gains too little"
                else "KEEP: gains %.0f flux in %.1fs (< %.0f)".format(gain, down, min), gain)
        }
        if (!Explain.enabled) return ShieldChoice(true, "DROP", gain)
        val gap = if (next == Float.MAX_VALUE) "none predicted" else "next shield hit in %.2fs".format(next)
        return ShieldChoice(true, "DROP: $gap, gains %.0f flux in %.1fs".format(gain, down), gain)
    }

    /** Flux drained over [t] seconds with the shield down, minus with it up. */
    fun gain(i: ShieldInputs, t: Float): Float {
        val downDrain = min(i.flux, i.dissipation * t)
        val net = i.dissipation - i.upkeep
        val upDrain = if (net < 0f) net * t
                      else min(i.flux - i.hardFlux + i.hardFlux * i.hardDissipationShare, net * t)
        return downDrain - upDrain
    }
}
