package org.starficz.combataitweaks.decide

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.MissileAPI
import com.fs.starfarer.api.combat.ShieldAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags
import org.starficz.combataitweaks.predictor.DamageOutlook
import org.starficz.combataitweaks.runtime.CombatScan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shield flicker (plan §13.9), every frame: drops the shield while the branch the ship is flying predicts no hit
 * it would block before it could be fully up again, and dropping it drains enough more flux ([ShieldRule.gain]):
 * hard flux, and the upkeep the shield costs while on. Vanilla's shield AIs turn the shield off
 * while DO_NOT_USE_SHIELDS is set; vanilla's own danger check clears that flag too (low hull under threat). When it
 * stops being safe, CAT clears the flag and raises the shield itself.
 *
 * A live check backs up the prediction. These keep the shield up:
 * - a hit on the shield within the last [RECENT_HIT] s (its hard flux rose): someone is shooting at it now;
 * - an enemy beam passing over the ship, charging ones included;
 * - an enemy shot or missile that could reach it within the window.
 */
class ShieldController(private val ship: ShipAPI) {
    /** True while CAT holds the shield down. */
    var dropping = false
        private set
    var latest: ShieldChoice? = null
        private set
    private var lastHard = 0f
    private var lastShieldHit = -1000f

    /**
     * [outlook] is the branch the ship is flying (or holding); null when there is no fresh one. [holdUp]: a shield
     * system CAT turned on (the fortress shield) needs the shield up.
     */
    fun apply(now: Float, outlook: DamageOutlook?, holdUp: Boolean = false) {
        val shield = ship.shield
        val hard = ship.fluxTracker.hardFlux
        if (hard > lastHard + 1f) lastShieldHit = now
        lastHard = hard
        val window = window(shield)
        val fresh = outlook?.takeIf { now - it.startTime <= VentController.MAX_AGE }
        val choice = ShieldRule.decide(ShieldInputs(
            nextShieldHit = fresh?.nextShieldHit(now),
            window = window,
            liveThreat = if (shield != null) liveThreat(now, window) else null,
            blockedBy = if (holdUp) "shield system on" else blocker(shield),
            flux = ship.fluxTracker.currFlux,
            hardFlux = hard,
            maxFlux = ship.fluxTracker.maxFlux,
            dissipation = ship.mutableStats.fluxDissipation.modifiedValue * timeMult(),
            upkeep = (shield?.upkeep ?: 0f) * timeMult(),
            hardDissipationShare = ship.mutableStats.hardFluxDissipationFraction.modifiedValue.coerceIn(0f, 1f),
        ))
        latest = choice
        if (choice.drop) {
            ship.aiFlags.setFlag(AIFlags.DO_NOT_USE_SHIELDS, FLAG_DURATION)
            dropping = true
        } else if (dropping) {
            ship.aiFlags.unsetFlag(AIFlags.DO_NOT_USE_SHIELDS)
            if (shield != null && shield.isOff && !ship.fluxTracker.isOverloadedOrVenting) shield.toggleOn()
            dropping = false
        }
        ship.setCustomData(TRACE, choice.reason)
        ship.setCustomData(DROPPING, dropping)
    }

    /** Unfold time (engine seconds) + margin; omni shields may also have to turn to the threat. */
    private fun window(shield: ShieldAPI?): Float {
        if (shield == null) return 0f
        val unfold = shield.unfoldTime / min(1f, timeMult()).coerceAtLeast(0.1f)
        return unfold + MARGIN + (if (shield.type == ShieldAPI.ShieldType.OMNI) OMNI_MARGIN else 0f)
    }

    private fun timeMult() = ship.mutableStats.timeMult.modifiedValue

    private fun blocker(shield: ShieldAPI?): String? = when {
        shield == null || shield.type == ShieldAPI.ShieldType.NONE || shield.type == ShieldAPI.ShieldType.PHASE ->
            "no shield"
        ship.fluxTracker.isOverloadedOrVenting -> "overloaded or venting"
        else -> null
    }

    /** See the class comment: a recent shield hit, an enemy beam over the ship, a shot or missile inbound. */
    private fun liveThreat(now: Float, window: Float): String? {
        if (now - lastShieldHit < RECENT_HIT) {
            return if (Explain.enabled) "shield hit %.1fs ago".format(now - lastShieldHit) else "shield hit"
        }
        val engine = Global.getCombatEngine() ?: return null
        // one pass over the battle per frame, shared by every CAT ship
        val scan = CombatScan.of(engine)
        val r = ship.collisionRadius
        val owner = ship.owner
        val sx = ship.location.x; val sy = ship.location.y
        val svx = ship.velocity.x; val svy = ship.velocity.y
        for (k in scan.beams.indices) {
            val beam = scan.beams[k]
            val source = beam.source ?: continue
            if (source.owner == owner) continue
            if (beam.damageTarget === ship || passesNear(beam.from.x - sx, beam.from.y - sy, beam.to.x - sx,
                    beam.to.y - sy, r + BEAM_PAD)) return if (Explain.enabled) "beam from ${source.hullSpec.hullId}" else "beam"
        }
        val range = MAX_SHOT_SPEED * window + r
        val range2 = range * range
        val xs = scan.shotX; val ys = scan.shotY; val vxs = scan.shotVx; val vys = scan.shotVy; val owners = scan.shotOwner
        for (k in 0 until scan.shotCount) {
            if (owners[k] == owner) continue
            val dx = xs[k] - sx; val dy = ys[k] - sy
            if (dx * dx + dy * dy > range2) continue
            if (arrives(dx, dy, vxs[k] - svx, vys[k] - svy, r, window)) return "shot inbound"
        }
        for (k in 0 until scan.missileCount) {
            if (scan.missileOwner[k] == owner) continue
            // guided missiles turn: any within reach of the window counts
            val reach = scan.missileSpeed[k] * window + r + MISSILE_PAD
            val dx = scan.missileX[k] - sx; val dy = scan.missileY[k] - sy
            if (dx * dx + dy * dy <= reach * reach) return "missile inbound"
        }
        return null
    }

    /** Whether the segment (ax, ay)-(bx, by), relative to the ship, passes within [d] of its centre. */
    private fun passesNear(ax: Float, ay: Float, bx: Float, by: Float, d: Float): Boolean {
        val ex = bx - ax; val ey = by - ay
        val len2 = ex * ex + ey * ey
        val t = if (len2 < 1f) 0f else (-(ax * ex + ay * ey) / len2).coerceIn(0f, 1f)
        val cx = ax + ex * t; val cy = ay + ey * t
        return cx * cx + cy * cy <= d * d
    }

    /** Whether a shot at relative position (dx, dy) moving at (vx, vy) passes within [r] of the ship in [t] s. */
    private fun arrives(dx: Float, dy: Float, vx: Float, vy: Float, r: Float, t: Float): Boolean {
        val v2 = vx * vx + vy * vy
        if (v2 < 1f) return dx * dx + dy * dy <= r * r
        val tc = (-(dx * vx + dy * vy) / v2).coerceIn(0f, t)
        val cx = dx + vx * tc; val cy = dy + vy * tc
        return sqrt(cx * cx + cy * cy) <= r + SHOT_PAD
    }

    companion object {
        /** Custom-data keys: the latest decision's reason, and whether CAT holds the shield down. */
        const val TRACE = "CAT_SHIELD_TRACE"
        const val DROPPING = "CAT_SHIELD_DROPPING"
        private const val FLAG_DURATION = 0.1f
        /** Prediction and reaction slack on top of the unfold time. */
        const val MARGIN = 0.4f
        const val OMNI_MARGIN = 0.15f
        private const val MAX_SHOT_SPEED = 2000f
        private const val SHOT_PAD = 30f
        private const val MISSILE_PAD = 100f
        private const val BEAM_PAD = 50f
        /** Seconds after a hit on the shield during which it stays up. */
        const val RECENT_HIT = 0.5f
    }
}
