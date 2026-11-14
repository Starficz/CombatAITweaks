package org.starficz.combataitweaks.sim

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The enemy plan "commit and kill": which enemies commit to a protected ship, how close each one wants to get, and
 * how it flies there.
 */
object Pursuit {

    /** Vanilla presses up to 200 units closer when the target has more flux; the worst case always does. */
    const val PRESS_IN = 200f
    /** Within this distance of its hold point an enemy matches the target's velocity instead of chasing the point. */
    const val HOLD_BAND = 50f

    /**
     * Seconds until [enemy] could bring a weapon to bear on [target] if the target stood still: the gap to weapon
     * reach (less the system's burst distance) at the enemy's cruise speed plus the system's speed hint (double for
     * phase ships at low hard flux), or faster if it is already closing faster, plus the time until it can act again.
     * Inputs as vanilla's threat evaluator; unlike it, the target isn't assumed to flee: each branch's pursuit
     * decides whether the enemy really arrives.
     */
    fun timeToReach(enemy: SimShip, target: SimShip, targetingRadius: Float, window: Float): Float {
        val dx = target.x - enemy.x
        val dy = target.y - enemy.y
        val dist = length(dx, dy)
        val reach = enemy.weaponReach + enemy.collisionRadius + targetingRadius
        val sys = enemy.system
        val sysReady = sys != null && sys.readyIn < window && enemy.silenceTime < 3f
        val gap = dist - reach - (if (sysReady) sys!!.burstPotential else 0f)
        if (gap <= 0f) return enemy.silenceTime

        val ux = if (dist > 0f) dx / dist else 0f
        val uy = if (dist > 0f) dy / dist else 0f
        val closingNow = (enemy.vx - target.vx) * ux + (enemy.vy - target.vy) * uy
        var enemySpeed = enemy.cruiseSpeed + (if (sysReady) sys!!.activeSpeedIncrease else 0f)
        if (enemy.isPhaseShip && enemy.hardFluxLevel < 0.2f) enemySpeed *= 2f
        val closeSpeed = max(closingNow, enemySpeed)
        return gap / max(closeSpeed, 1f) + enemy.silenceTime
    }

    /**
     * Whether [enemy] commits to killing [target]: it is hostile, a fighter's carrier has the target within wing
     * range, and it can bring a weapon to bear within the commit window.
     */
    fun commits(enemy: SimShip, target: SimShip, carrier: SimShip?, targetingRadius: Float, params: SimParams): Boolean {
        if (enemy.owner == target.owner || enemy.isModule) return false
        if (enemy.weaponReach <= 0f) return false
        if (enemy.isFighter && carrier != null) {
            if (length(target.x - carrier.x, target.y - carrier.y) > enemy.wingRange + target.collisionRadius) return false
        }
        return timeToReach(enemy, target, targetingRadius, params.commitWindow) <= params.commitWindow
    }

    /**
     * Centre-to-centre distance [enemy] closes to on [target]. If vanilla already fights the target, its live
     * MANEUVER_RANGE_FROM_TARGET; otherwise vanilla's rule: engagement range + both radii, pressed in by 200, never
     * closer than 200 + radii.
     */
    fun holdDistance(enemy: SimShip, target: SimShip, targetingRadius: Float): Float {
        val live = enemy.maneuverRange
        if (enemy.maneuverTargetId == target.id && live != null && live > 0f) return live
        val radii = enemy.collisionRadius + targetingRadius
        return max(200f + radii, enemy.engagementRange + radii - PRESS_IN)
    }

    /**
     * Centre-to-centre distance [enemy] holds on [target] while it backs off (plan §13.8): vanilla doesn't press in
     * then, so its engagement range + both radii (or its live range if that is farther).
     */
    fun backOffDistance(enemy: SimShip, target: SimShip, targetingRadius: Float): Float =
        max(holdDistance(enemy, target, targetingRadius), enemy.engagementRange + enemy.collisionRadius + targetingRadius)

    /**
     * Centre-to-centre distance the protected ship [ship] closes to on [target] when it advances: its own engagement
     * range + radii, pressed in like an enemy's ([holdDistance] without vanilla's live range, which CAT's own range
     * flags would distort).
     */
    fun approachDistance(ship: SimShip, targetRadius: Float): Float {
        val radii = ship.collisionRadius + targetRadius
        return max(200f + radii, ship.engagementRange + radii - PRESS_IN)
    }

    /**
     * Distance from [target] beyond which [enemy] would no longer commit to it (see [timeToReach]): its reach and
     * radii, its burst distance, plus how much faster it is than the target over the commit window.
     */
    fun clearDistance(enemy: SimShip, target: SimShip, targetingRadius: Float, params: SimParams): Float {
        val sys = enemy.system
        val sysReady = sys != null && sys.readyIn < params.commitWindow && enemy.silenceTime < 3f
        var speed = enemy.cruiseSpeed + (if (sysReady) sys!!.activeSpeedIncrease else 0f)
        if (enemy.isPhaseShip && enemy.hardFluxLevel < 0.2f) speed *= 2f
        val reach = enemy.weaponReach + enemy.collisionRadius + targetingRadius +
            (if (sysReady) sys!!.burstPotential else 0f)
        return reach + max(0f, speed - target.cruiseSpeed) * max(0f, params.commitWindow - enemy.silenceTime)
    }

    /**
     * Per-ship state while closing on a target: committed enemies, and the protected ship's own `ADVANCE` branch.
     * @param useSystem fire the mobility system from its AI hints (enemies); the protected ship's own system is
     * modelled by [limitsOverride] instead.
     * @param limitsOverride engine limits per time on the shared clock, replacing the ship's own.
     */
    class Pursuer(
        val ship: SimShip,
        val hold: Float,
        private val useSystem: Boolean = true,
        private val limitsOverride: ((Float) -> EngineLimits)? = null,
        /** How it reacts to the target's fire in this branch ([Reactions]), and the range it holds backing off. */
        private val reaction: Reaction? = null,
        private val backOffHold: Float = hold,
    ) {
        val body = Body(ship)
        private var retreatDone = false
        private val base = EngineLimits(ship)
        private var systemStart = -1f
        private var systemReadyAt = ship.system?.readyIn ?: Float.MAX_VALUE

        fun isSystemActive(t: Float): Boolean {
            val sys = ship.system ?: return false
            return systemStart >= 0f && t < systemStart + sys.activeTime
        }

        // the two limits a pursuer can have (system active or not), built once: limitsAt runs every step
        private val plainLimits = limitsWith(false)
        private val activeLimits = limitsWith(true)

        private fun limitsWith(systemActive: Boolean): EngineLimits {
            var maxSpeed = base.maxSpeed
            var accelMult = 1f
            val sys = ship.system
            if (systemActive && sys != null && sys.activeSpeedIncrease > 0f) {
                maxSpeed += sys.activeSpeedIncrease
                accelMult = 2f
            }
            if (ship.isPhaseShip && ship.hardFluxLevel < 0.2f) maxSpeed *= 2f
            return EngineLimits(maxSpeed, base.acceleration * accelMult, base.deceleration * accelMult,
                base.strafeFactor, base.turnAcceleration, base.maxTurnRate)
        }

        /** Engine limits at time [t], with the mobility system and phase speed applied. */
        fun limitsAt(t: Float): EngineLimits {
            limitsOverride?.let { return it(t) }
            return if (isSystemActive(t)) activeLimits else plainLimits
        }

        /**
         * One step toward the hold point on the target (position tx, ty, velocity tvx, tvy) at time [t] (seconds since
         * the snapshot, on the shared clock).
         */
        fun step(t: Float, tx: Float, ty: Float, tvx: Float, tvy: Float) {
            val dt = DT * ship.timeMult
            val dx = body.x - tx
            val dy = body.y - ty
            val dist = length(dx, dy)
            val ux = if (dist > 1f) dx / dist else 1f
            val uy = if (dist > 1f) dy / dist else 0f

            // backing off (plan §13.8): small ships thrust straight away facing the target until clear of its reach,
            // bigger ones hold their range without pressing in
            val backing = reaction != null && reaction.backsOffAt(t)
            if (backing && reaction!!.retreat && !retreatDone) {
                if (dist >= reaction.clearAt) retreatDone = true
                else {
                    val limits = limitsAt(t)
                    Motion.steerToVelocity(body, limits, ux * limits.maxSpeed, uy * limits.maxSpeed, dt)
                    Motion.turnToward(body, limits, angleOf(tx - body.x, ty - body.y) + ship.bestFacingOffset, dt)
                    return
                }
            }
            val hold = if (backing && !reaction!!.retreat) backOffHold else hold

            // aim point: on the line from the target toward us, at the hold distance, led by the target's velocity
            val limits0 = limitsAt(t)
            val lead = min(3f, max(0f, dist - hold) / max(limits0.maxSpeed, 1f))
            val aimX = tx + ux * hold + tvx * lead
            val aimY = ty + uy * hold + tvy * lead
            val toX = aimX - body.x
            val toY = aimY - body.y
            val toDist = length(toX, toY)

            // mobility system: fire it when the gap is worth it
            val sys = ship.system
            if (useSystem && sys != null && !isSystemActive(t) && t >= systemReadyAt && t >= ship.silenceTime) {
                val moves = sys.burstPotential > 0f || sys.activeSpeedIncrease > 0f
                val worthIt = if (sys.burstPotential > 0f) toDist > sys.burstPotential * 0.5f else toDist > 300f
                if (moves && worthIt) {
                    systemStart = t
                    systemReadyAt = t + sys.activeTime + sys.cooldown
                }
            }
            val limits = limitsAt(t)

            // arrive: approach speed that can still stop at the hold point, on top of the target's own velocity
            var desiredVx = tvx
            var desiredVy = tvy
            if (toDist > HOLD_BAND) {
                val approach = min(limits.maxSpeed, sqrt(2f * limits.deceleration * toDist))
                desiredVx += toX / toDist * approach
                desiredVy += toY / toDist * approach
            }
            Motion.steerToVelocity(body, limits, desiredVx, desiredVy, dt)

            // burst systems: the jump itself, spread over the active time
            if (sys != null && isSystemActive(t) && sys.burstPotential > 0f && toDist > 1f) {
                val jump = min(toDist, sys.burstPotential / max(sys.activeTime, DT) * dt)
                body.x += toX / toDist * jump
                body.y += toY / toDist * jump
            }

            // face the target with the best gun offset
            val bearing = angleOf(tx - body.x, ty - body.y)
            Motion.turnToward(body, limits, bearing + ship.bestFacingOffset, dt)
        }
    }
}
