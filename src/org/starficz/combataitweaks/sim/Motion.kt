package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** Mutable position, velocity and rotation of one ship while it is simulated. */
class Body(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var facing: Float,
    var angularVelocity: Float,
) {
    constructor(ship: SimShip) : this(ship.x, ship.y, ship.vx, ship.vy, ship.facing, ship.angularVelocity)

    val speed get() = length(vx, vy)
}

/** Engine limits for one step, after mobility-system or profile overrides. */
class EngineLimits(
    val maxSpeed: Float,
    val acceleration: Float,
    val deceleration: Float,
    val strafeFactor: Float,
    val turnAcceleration: Float,
    val maxTurnRate: Float,
) {
    constructor(ship: SimShip) : this(
        ship.maxSpeed, ship.acceleration, ship.deceleration, ship.hullClass.strafeFactor,
        ship.turnAcceleration, ship.maxTurnRate,
    )
}

/**
 * Ship movement matching the vanilla engine controller (decompiled: the class implementing ShipEngineControllerAPI).
 * - forward thrust adds acceleration, backward thrust adds deceleration, strafing adds acceleration x strafe factor;
 * - braking pushes against the velocity at deceleration;
 * - above max speed, speed bleeds off at 2 x max(acceleration, deceleration) per second instead of being clamped.
 * All rates run on the ship's own clock (dt already scaled by its time mult).
 */
object Motion {

    /**
     * Largest acceleration available toward the unit world direction (ux, uy) for a ship facing [facing].
     * The direction is split into forward and lateral parts; the combined vector is scaled so neither axis exceeds
     * its limit.
     */
    fun maxAccelToward(limits: EngineLimits, facing: Float, ux: Float, uy: Float): Float {
        val rad = facing * DEG_TO_RAD
        val fx = cos(rad); val fy = sin(rad)
        val forward = ux * fx + uy * fy
        val lateral = -ux * fy + uy * fx
        val forwardLimit = if (forward >= 0f) limits.acceleration else limits.deceleration
        val lateralLimit = limits.acceleration * limits.strafeFactor
        val byForward = if (abs(forward) > 1e-4f) forwardLimit / abs(forward) else Float.MAX_VALUE
        val byLateral = if (abs(lateral) > 1e-4f) lateralLimit / abs(lateral) else Float.MAX_VALUE
        return min(byForward, byLateral)
    }

    /**
     * Acceleration from fixed engine inputs (accelerate / backwards / strafe), as a world vector written into [out].
     */
    fun inputAccel(limits: EngineLimits, facing: Float, forward: Boolean, back: Boolean, left: Boolean, right: Boolean, out: FloatArray) {
        val rad = facing * DEG_TO_RAD
        val fx = cos(rad); val fy = sin(rad)
        var ax = 0f; var ay = 0f
        if (forward) { ax += fx * limits.acceleration; ay += fy * limits.acceleration }
        else if (back) { ax -= fx * limits.deceleration; ay -= fy * limits.deceleration }
        val strafe = limits.acceleration * limits.strafeFactor
        // left of facing is facing + 90
        if (left) { ax += -fy * strafe; ay += fx * strafe }
        else if (right) { ax -= -fy * strafe; ay -= fx * strafe }
        out[0] = ax; out[1] = ay
    }

    /**
     * Advances [body] by [dt] seconds (already scaled by time mult) with world acceleration (ax, ay), or braking.
     */
    fun step(body: Body, limits: EngineLimits, ax: Float, ay: Float, brake: Boolean, dt: Float) {
        body.vx += ax * dt
        body.vy += ay * dt

        if (brake) {
            val speed = body.speed
            if (speed > 0f) {
                val dv = min(speed, limits.deceleration * dt)
                body.vx -= body.vx / speed * dv
                body.vy -= body.vy / speed * dv
            }
        }

        val speed = body.speed
        if (speed > limits.maxSpeed) {
            val bleed = min(speed - limits.maxSpeed, 2f * max(limits.acceleration, limits.deceleration) * dt)
            val newSpeed = speed - bleed
            body.vx *= newSpeed / speed
            body.vy *= newSpeed / speed
        }

        body.x += body.vx * dt
        body.y += body.vy * dt
    }

    /**
     * Thrusts so the velocity approaches (desiredVx, desiredVy) as fast as the engines allow without overshooting.
     */
    fun steerToVelocity(body: Body, limits: EngineLimits, desiredVx: Float, desiredVy: Float, dt: Float) {
        val dvx = desiredVx - body.vx
        val dvy = desiredVy - body.vy
        val dv = length(dvx, dvy)
        if (dv < 1f) {
            step(body, limits, 0f, 0f, false, dt)
            return
        }
        val ux = dvx / dv; val uy = dvy / dv
        val accel = min(Motion.maxAccelToward(limits, body.facing, ux, uy), dv / dt)
        step(body, limits, ux * accel, uy * accel, false, dt)
    }

    /**
     * Turns toward [targetFacing] at the ship's turn acceleration, braking early so it doesn't overshoot.
     */
    fun turnToward(body: Body, limits: EngineLimits, targetFacing: Float, dt: Float) {
        val needed = shortestRotation(body.facing, targetFacing)
        val turnAccel = limits.turnAcceleration
        if (turnAccel <= 0f) return
        val w = body.angularVelocity
        val stoppingDist = w * w / (2f * turnAccel)
        val movingToward = sign(needed) == sign(w) && abs(w) > 0.1f
        val change = turnAccel * dt
        body.angularVelocity = if (movingToward && abs(needed) <= stoppingDist) {
            if (abs(w) <= change) 0f else w - sign(w) * change
        } else {
            // close enough and nearly still: settle instead of oscillating
            if (abs(needed) < 0.5f && abs(w) <= change) needed / dt * 0.5f else w + sign(needed) * change
        }
        body.angularVelocity = body.angularVelocity.coerceIn(-limits.maxTurnRate, limits.maxTurnRate)
        body.facing = normalizeAngle(body.facing + body.angularVelocity * dt)
    }

    /** Lets the rotation die down when there is nothing to turn toward. */
    fun damp(body: Body, limits: EngineLimits, dt: Float) {
        val w = body.angularVelocity
        val change = limits.turnAcceleration * dt
        body.angularVelocity = if (abs(w) <= change) 0f else w - sign(w) * change
        body.facing = normalizeAngle(body.facing + body.angularVelocity * dt)
    }
}
