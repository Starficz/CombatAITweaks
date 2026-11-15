package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Missiles and projectiles heading for the protected ship (plan §6.1 missiles, §6.2).
 *
 * - Guided missiles steer at a lead point (the intercept with the target's current velocity), accelerate while their
 *   velocity is within 90 degrees of their facing and coast once their powered flight ends.
 * - MIRVs split by vanilla's MirvAI rule: closer than split range + the target's collision radius, facing within
 *   0.1 x the target's angular size + max(0, arc - 1.5) of it, and past their minimum time. No split at end of life.
 * - DEMs run DEMScript's states (wait, turn, signal, fire) with their own parameters.
 * - Mines drift and go off within their range.
 * - Projectiles in flight are swept against the target's path.
 */
class MissileSim(
    private val target: SimDefense,
    private val targetOwner: Int,
    private val timeline: Timeline,
    /** Where its hits come from; null: new ones. */
    private val pool: Scratch? = null,
    /** The target's point defense (plan §14); null: none modelled (venting, or not captured). */
    pd: PointDefense? = null,
) {
    private val pdFire = pd?.takeIf { it.weapons.isNotEmpty() }?.let { PdFire(it, timeline, pool) }

    /**
     * Missiles point defense destroyed before they arrived, and missiles that hit, in this run (tests, trace); DEMs
     * aren't counted (their payload fires from range).
     */
    var intercepted = 0
        private set
    var arrived = 0
        private set

    private val steps = timeline.steps

    val hits = ArrayList<Hit>()

    /** Per step: bearing from the target to the nearest powered missile within 600 units closing on it, or NaN. */
    /** Only for omni shields ([FireControl.omniFacing] combines them); empty otherwise. */
    private val tracksMissiles = target.shield == ShieldKind.OMNI
    val missileBearing = if (!tracksMissiles) FloatArray(0)
        else pool?.floats(steps, Float.NaN) ?: FloatArray(steps) { Float.NaN }
    private val missileDistance = if (!tracksMissiles) FloatArray(0)
        else pool?.floats(steps, Float.MAX_VALUE) ?: FloatArray(steps) { Float.MAX_VALUE }

    private class Flight(val m: InFlightMissile) {
        var x = m.x; var y = m.y
        var vx = m.vx; var vy = m.vy
        var facing = m.facing
        var angVel = m.angularVelocity
        var flight = m.flightTime
        var dem = m.demState
        var demElapsed = m.demElapsed
        var pvx = m.vx; var pvy = m.vy
    }

    fun run(missiles: List<InFlightMissile>) {
        val queue = ArrayDeque<InFlightMissile>()
        for (m in missiles) {
            if (m.owner == targetOwner) continue
            if (!m.info.isMine && m.targetId != null && m.targetId != target.shipId) continue
            queue += m
        }
        val subShots = ArrayList<InFlightShot>()
        while (queue.isNotEmpty()) {
            val before = hits.size
            val m = queue.removeFirst()
            val stopped = intercepted
            simulate(Flight(m), queue, subShots)
            // the counts leave DEMs out: their drones fire from range, the missile itself never hits
            if (m.info.dem != null) intercepted = stopped
            else if (hits.size > before) arrived++
        }
        if (subShots.isNotEmpty()) shoot(subShots, IN_FLIGHT)
    }

    private fun simulate(f: Flight, queue: ArrayDeque<InFlightMissile>, subShots: MutableList<InFlightShot>) {
        val m = f.m
        val info = m.info
        val lifespan = info.maxFlightTime + info.flameoutTime
        val track = pdFire?.track(m.hitpoints, m.radius)
        for (i in 0 until steps) {
            if (f.flight < 0f) { f.flight += DT; continue }
            // a triggered DEM flies on until its sequence ends (DEMScript sets its flight time to 10000)
            if (f.flight >= lifespan && f.dem == DemState.WAIT) return
            if (track != null && track.destroyedAt(i)) { intercepted++; return }
            f.pvx = f.vx; f.pvy = f.vy
            val tx = timeline.x[i]; val ty = timeline.y[i]
            val tvx = FireControl.velocityX(timeline, i)
            val tvy = FireControl.velocityY(timeline, i)
            val dx = tx - f.x; val dy = ty - f.y
            val dist = sqrt(dx * dx + dy * dy)
            val time = (i + 1) * DT
            val powered = f.flight <= info.maxFlightTime

            val mirv = info.mirv
            if (mirv != null && powered && f.flight >= mirv.minTimeToSplit &&
                dist < mirv.splitRange + target.collisionRadius && facingWithinSplit(f, mirv, dx, dy, dist)) {
                split(f, mirv, i, tx, ty, queue, subShots)
                return
            }

            val done = when {
                info.isMine -> mine(f, dist, time)
                info.dem != null && f.dem != DemState.WAIT -> dem(f, info.dem, dist, dx, dy, time, i)
                else -> {
                    if (info.dem != null && f.flight >= info.dem.minDelay &&
                        dist - target.hitRadius < info.dem.triggerDistance) {
                        f.dem = DemState.TURN_TO_TARGET
                        false
                    } else guided(f, powered, tx, ty, tvx, tvy, dist, time)
                }
            }
            if (done) return
            track?.engage(i, f.x, f.y, f.vx, f.vy, (f.vx - f.pvx) / DT, (f.vy - f.pvy) / DT)
            if (tracksMissiles && powered && dist < 600f && dist < missileDistance[i]) {
                missileDistance[i] = dist
                missileBearing[i] = angleOf(f.x - tx, f.y - ty)
            }
            f.flight += DT
        }
    }

    /** MirvAI's facing tolerance: 0.1 x the target's angular size in degrees, plus max(0, arc - 1.5). */
    private fun facingWithinSplit(f: Flight, mirv: MirvInfo, dx: Float, dy: Float, dist: Float): Boolean {
        val angularSize = 2f * target.collisionRadius / (2f * Math.PI.toFloat() * max(dist, 1f)) * 360f
        val tolerance = angularSize * 0.1f + max(0f, mirv.arc - 1.5f)
        return abs(shortestRotation(f.facing, angleOf(dx, dy))) < tolerance
    }

    private fun split(
        f: Flight, mirv: MirvInfo, i: Int, tx: Float, ty: Float,
        queue: ArrayDeque<InFlightMissile>, subShots: MutableList<InFlightShot>,
    ) {
        val base = f.facing + mirv.arcOffset
        val n = mirv.numShots.coerceAtLeast(1)
        // spawned at the end of step i: simulated from step i + 1
        val start = -((i + 1) * DT)
        val sub = mirv.sub
        for (k in 0 until n) {
            // vanilla picks random angles inside the arc unless evenSpread; the even spread is their expected layout
            val angle = if (n == 1) base else base - mirv.arc / 2f + mirv.arc / (n - 1) * k
            val a = angle * DEG_TO_RAD
            val ux = cos(a); val uy = sin(a)
            val subMissile = sub.missile
            if (subMissile != null) {
                queue += InFlightMissile(
                    x = f.x, y = f.y,
                    vx = f.vx + ux * mirv.spreadSpeed, vy = f.vy + uy * mirv.spreadSpeed,
                    // missile submunitions face the target when spawned
                    facing = normalizeAngle(angleOf(tx - f.x, ty - f.y)), angularVelocity = 0f,
                    owner = f.m.owner, damage = sub.damage, emp = sub.emp, kind = sub.kind, softFlux = false,
                    info = subMissile, flightTime = start, targetId = target.shipId,
                    conservative = f.m.conservative, sourceId = f.m.sourceId,
                )
            } else {
                val speed = if (sub.speed > 0f) sub.speed else mirv.spreadSpeed
                subShots += InFlightShot(
                    x = f.x, y = f.y, vx = f.vx + ux * speed, vy = f.vy + uy * speed,
                    owner = f.m.owner, damage = sub.damage, hitStrength = sub.damage, emp = sub.emp,
                    kind = sub.kind, delivery = Delivery.PROJECTILE, softFlux = false,
                    timeLeft = SUBMUNITION_LIFE, delay = (i + 1) * DT,
                )
            }
        }
    }

    private fun mine(f: Flight, dist: Float, time: Float): Boolean {
        f.x += f.vx * DT; f.y += f.vy * DT
        if (dist <= target.hitRadius + f.m.info.mineRange * 1.1f) {
            hit(f, time, 1f, Delivery.MISSILE)
            return true
        }
        return false
    }

    private fun guided(
        f: Flight, powered: Boolean, tx: Float, ty: Float, tvx: Float, tvy: Float, dist: Float, time: Float,
    ): Boolean {
        val m = f.m
        val info = m.info
        if (!powered) {
            f.x += f.vx * DT; f.y += f.vy * DT
        } else {
            if (m.targetId != null) {
                val lead = leadTime(f, info.maxSpeed, tx, ty, tvx, tvy)
                turnToward(f, angleOf(tx + tvx * lead - f.x, ty + tvy * lead - f.y), info.maxTurnRate, info.turnAcceleration)
            } else {
                dampTurn(f, info.turnAcceleration)
            }
            val a = f.facing * DEG_TO_RAD
            val fx = cos(a); val fy = sin(a)
            val speed = sqrt(f.vx * f.vx + f.vy * f.vy)
            // flying more than 90 degrees off its facing: the dot product says it without an atan2 per step
            val backwards = speed > 1f && fx * f.vx + fy * f.vy < 0f
            if (backwards) {
                val brake = min(speed, info.acceleration * DT)
                f.vx -= f.vx / speed * brake; f.vy -= f.vy / speed * brake
            } else {
                f.vx += fx * info.acceleration * DT; f.vy += fy * info.acceleration * DT
            }
            capSpeed(f, info.maxSpeed)
            f.x += f.vx * DT; f.y += f.vy * DT
        }

        if (dist > target.hitRadius + MISSILE_RADIUS) return false
        if (f.flight < info.armingTime) return false
        var scale = 1f
        if (!powered) {
            val sinceFlameout = f.flight - info.maxFlightTime
            if (sinceFlameout > info.flameoutTime - info.fadeTime && info.fadeTime > 0f) {
                val fade = ((info.flameoutTime - sinceFlameout) / info.fadeTime).coerceIn(0f, 1f)
                if (info.noCollisionWhileFading && fade < 1f) return true
                if (info.reduceDamageWhileFading) scale = fade
            }
        }
        hit(f, time, scale, Delivery.MISSILE)
        return true
    }

    /** DEMScript after triggering: turn to the target, hold position in its preferred band, paint it, then fire. */
    private fun dem(f: Flight, p: DemInfo, dist: Float, dx: Float, dy: Float, time: Float, i: Int): Boolean {
        val bearing = angleOf(dx, dy)
        when (f.dem) {
            DemState.TURN_TO_TARGET -> {
                demMove(f, p, dist, bearing)
                if (abs(shortestRotation(f.facing, bearing)) <= p.targetingArc / 2f) {
                    f.dem = DemState.SIGNAL; f.demElapsed = 0f
                }
            }
            DemState.SIGNAL -> {
                demMove(f, p, dist, bearing)
                f.demElapsed += DT
                if (f.demElapsed >= p.targetingTime) { f.dem = DemState.FIRE; f.demElapsed = 0f }
            }
            DemState.FIRE -> {
                val surface = dist - target.hitRadius
                // bomb-pumped: the whole payload at once, nothing can cut it
                if (!p.payloadCutWithMissile) { demPayload(f, p, time, surface, f.demElapsed, Float.MAX_VALUE); return true }
                // otherwise this step's ticks only, holding station: point defense killing it cuts the rest
                demMove(f, p, dist, bearing)
                demPayload(f, p, time, surface, f.demElapsed, f.demElapsed + DT)
                f.demElapsed += DT
                return f.demElapsed >= p.payloadStart + p.payloadDuration - 1e-4f
            }
            else -> return true
        }
        return false
    }

    /** The payload ticks due from [from] to [to] seconds into the firing (the ones before are already fired). */
    private fun demPayload(f: Flight, p: DemInfo, time: Float, surface: Float, from: Float, to: Float) {
        val m = f.m
        if (surface > p.payloadRange) return
        if (!p.payloadIsBeam) {
            if (from <= 0f) hits += pool.hit(
                time = time, damage = p.payloadDamage, hitStrength = p.payloadDamage, emp = p.payloadEmp,
                kind = p.payloadKind, delivery = Delivery.PROJECTILE, softFlux = false,
                conservative = m.conservative, fromX = f.x, fromY = f.y, sourceId = m.sourceId,
            )
            return
        }
        // beam payload: DPS over its full-power time, in beam ticks; the ones already fired are skipped
        val end = min(p.payloadStart + p.payloadDuration, to)
        var t = p.payloadStart
        while (t < end - 1e-4f) {
            val tick = min(FireControl.BEAM_TICK, p.payloadStart + p.payloadDuration - t)
            if (t >= from - 1e-4f) hits += pool.hit(
                time = time + (t - from), damage = p.payloadDamage * tick, hitStrength = p.payloadDamage,
                emp = p.payloadEmp * tick, kind = p.payloadKind, delivery = Delivery.BEAM,
                softFlux = p.payloadSoftFlux, conservative = m.conservative, fromX = f.x, fromY = f.y,
                sourceId = m.sourceId,
            )
            t += tick
        }
    }

    /** A triggered DEM's station keeping: boosted turning, closing or backing to its preferred band, braking in it. */
    private fun demMove(f: Flight, p: DemInfo, dist: Float, bearing: Float) {
        val info = f.m.info
        turnToward(f, bearing, info.maxTurnRate + p.turnRateBoost, info.turnAcceleration + p.turnRateBoost * 2f)
        val surface = dist - target.hitRadius
        val a = f.facing * DEG_TO_RAD
        val speed = sqrt(f.vx * f.vx + f.vy * f.vy)
        when {
            surface < p.preferredMin -> { f.vx -= cos(a) * info.acceleration * DT; f.vy -= sin(a) * info.acceleration * DT }
            surface > p.preferredMax -> { f.vx += cos(a) * info.acceleration * DT; f.vy += sin(a) * info.acceleration * DT }
            speed > info.maxSpeed * p.allowedDriftFraction && speed > 0f -> {
                val brake = min(speed, info.acceleration * DT)
                f.vx -= f.vx / speed * brake; f.vy -= f.vy / speed * brake
            }
        }
        capSpeed(f, info.maxSpeed)
        f.x += f.vx * DT; f.y += f.vy * DT
    }

    private fun hit(f: Flight, time: Float, scale: Float, delivery: Delivery) {
        val m = f.m
        hits += pool.hit(
            time = time, damage = m.damage * scale, hitStrength = m.damage * scale, emp = m.emp * scale,
            kind = m.kind, delivery = delivery, softFlux = m.softFlux, conservative = m.conservative,
            fromX = f.x, fromY = f.y, sourceId = m.sourceId,
        )
    }

    /**
     * Lead time for a missile's aim point: where it would meet the target if the target kept its velocity, solved with
     * the missile's max speed and capped at 10 s ahead. Vanilla's MissileAI also aims at a lead point; its exact
     * formula (decompiled helper) couldn't be read reliably, so this is the plain intercept.
     */
    private fun leadTime(f: Flight, speed: Float, tx: Float, ty: Float, tvx: Float, tvy: Float): Float {
        val dx = tx - f.x; val dy = ty - f.y
        val s = max(speed, 1f)
        val a = tvx * tvx + tvy * tvy - s * s
        val b = 2f * (dx * tvx + dy * tvy)
        val c = dx * dx + dy * dy
        var t = if (abs(a) < 1e-3f) (if (abs(b) > 1e-3f) -c / b else 0f) else {
            val disc = b * b - 4f * a * c
            if (disc < 0f) 0f else {
                val r = sqrt(disc)
                val t1 = (-b - r) / (2f * a); val t2 = (-b + r) / (2f * a)
                when {
                    t1 > 0f && t2 > 0f -> min(t1, t2)
                    t1 > 0f -> t1
                    t2 > 0f -> t2
                    else -> 0f
                }
            }
        }
        return t.coerceIn(0f, 10f)
    }

    private fun turnToward(f: Flight, desired: Float, maxRate: Float, accel: Float) {
        val acc = max(accel, 1f)
        val need = shortestRotation(f.facing, desired)
        val stopping = f.angVel * f.angVel / (2f * acc)
        val towards = sign(need) == sign(f.angVel) && abs(f.angVel) > 0.1f
        if (towards && abs(need) <= stopping) {
            val change = acc * DT
            f.angVel = if (abs(f.angVel) <= change) 0f else f.angVel - sign(f.angVel) * change
        } else {
            f.angVel += sign(need) * acc * DT
        }
        f.angVel = f.angVel.coerceIn(-maxRate, maxRate)
        f.facing = normalizeAngle(f.facing + f.angVel * DT)
    }

    private fun dampTurn(f: Flight, accel: Float) {
        val change = accel * DT
        f.angVel = if (abs(f.angVel) <= change) 0f else f.angVel - sign(f.angVel) * change
        f.facing = normalizeAngle(f.facing + f.angVel * DT)
    }

    private fun capSpeed(f: Flight, maxSpeed: Float) {
        val speed = sqrt(f.vx * f.vx + f.vy * f.vy)
        if (speed > maxSpeed && speed > 0f) { f.vx *= maxSpeed / speed; f.vy *= maxSpeed / speed }
    }

    /** Sweeps each shot against the target's path; a hit is recorded where the shot first touches its hit circle. */
    fun shoot(shots: List<InFlightShot>, sourceId: String = IN_FLIGHT) {
        val r = target.hitRadius + 10f
        val rSq = r * r
        val x0 = timeline.x[0]; val y0 = timeline.y[0]
        for (shot in shots) {
            if (shot.owner == targetOwner) continue
            val speed = length(shot.vx, shot.vy)
            val reach = (speed + 1500f) * (shot.timeLeft + shot.delay) + r
            val ox = shot.x - x0; val oy = shot.y - y0
            if (ox * ox + oy * oy > reach * reach) continue
            for (i in 0 until steps) {
                val tStart = i * DT
                if (tStart + DT <= shot.delay) continue
                val tFlight = max(0f, tStart - shot.delay)
                if (tFlight > shot.timeLeft) break
                val sx = if (i == 0) x0 else timeline.x[i - 1]
                val sy = if (i == 0) y0 else timeline.y[i - 1]
                val ex = timeline.x[i]; val ey = timeline.y[i]
                val px = shot.x + shot.vx * tFlight
                val py = shot.y + shot.vy * tFlight
                val vRelX = shot.vx - (ex - sx) / DT
                val vRelY = shot.vy - (ey - sy) / DT
                val dsx = px - sx; val dsy = py - sy
                val c = dsx * dsx + dsy * dsy - rSq
                if (c <= 0f) { record(shot, max(tStart, shot.delay), px, py, sourceId); break }
                val dot = dsx * vRelX + dsy * vRelY
                if (dot > 0f) continue
                val a = vRelX * vRelX + vRelY * vRelY
                if (a < 1e-4f) continue
                val disc = dot * dot - a * c
                if (disc < 0f) continue
                val tau = (-dot - sqrt(disc)) / a
                if (tau in 0f..DT && tFlight + tau <= shot.timeLeft) {
                    record(shot, max(tStart, shot.delay) + tau, px + shot.vx * tau, py + shot.vy * tau, sourceId)
                    break
                }
            }
        }
    }

    private fun record(shot: InFlightShot, time: Float, x: Float, y: Float, sourceId: String) {
        hits += pool.hit(
            time = time, damage = shot.damage, hitStrength = shot.hitStrength, emp = shot.emp, kind = shot.kind,
            delivery = shot.delivery, softFlux = shot.softFlux, conservative = false,
            fromX = x, fromY = y, sourceId = sourceId,
        )
    }

    companion object {
        /** Added to the target's hit radius for missile contact. */
        const val MISSILE_RADIUS = 15f
        /** How long a ballistic MIRV submunition flies when its range isn't known. */
        const val SUBMUNITION_LIFE = 3f
    }
}
