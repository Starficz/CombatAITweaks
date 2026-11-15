package org.starficz.combataitweaks.sim

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The predicted battlefield for one batch.
 *
 * The **base world** is simulated once: every ship keeps its current engine inputs for a few seconds, then coasts,
 * turning toward its nearest enemy. A **branch** re-simulates one protected ship doing one thing (its [OwnPlan]) while
 * every enemy that commits to killing it pursues it; everyone else is shared with the base world.
 *
 * @param targetingRadius distance from a ship's centre to the protected ship's edge, as vanilla measures it
 * (SettingsAPI.getTargetingRadius), keyed (protected ship id, other ship id). Missing pairs fall back to the protected
 * ship's collision radius.
 * @param ownTargetRadius the other way round: from the protected ship's centre to another ship's edge, keyed
 * (protected ship id, other ship id); only its targets are needed. Missing pairs fall back to the other's collision
 * radius.
 */
class World(
    val ships: List<SimShip>,
    val params: SimParams,
    private val targetingRadius: Map<String, Map<String, Float>> = emptyMap(),
    private val ownTargetRadius: Map<String, Map<String, Float>> = emptyMap(),
) {
    val byId: Map<String, SimShip> = ships.associateBy { it.id }
    private val roots = ships.filter { !it.isModule }
    private val modulesByParent = ships.filter { it.isModule }.groupBy { it.parentId!! }

    val base: Map<String, Timeline> = simulateBase()

    private companion object {
        const val NEAREST_EVERY = 4
        /** P counts as an enemy's possible target when at most this much farther than its nearest other choice. */
        const val TARGET_MARGIN = 400f
        /** Re-picks checked (on base paths) every this many seconds over the first half of the commit window. */
        const val REPICK_EVERY = 2f
        /**
         * A standoff pushed farther than this beyond its target's own standoff range (another hostile behind the
         * ship) keeps the target's range instead: no point on the line is out of reach, so the sim shows the cost.
         */
        const val MAX_STANDOFF_PUSH = 1000f
    }

    fun targetingRadius(protectedShip: SimShip, other: SimShip): Float =
        targetingRadius[protectedShip.id]?.get(other.id) ?: protectedShip.collisionRadius

    // ---------------------------------------------------------------------------------------------------------- base

    private fun simulateBase(): Map<String, Timeline> {
        val steps = params.steps
        val timelines = HashMap<String, Timeline>(ships.size * 2)
        ships.forEach { timelines[it.id] = Timeline(steps) }

        val rootTimelines = Array(roots.size) { timelines[roots[it].id]!! }
        val nearestOf = IntArray(roots.size) { -1 }
        val bodies = roots.map { Body(it) }
        val limits = roots.map { EngineLimits(it) }
        val accel = FloatArray(2)

        // facing targets: non-fighter, non-module ships
        val targetIdx = roots.indices.filter { !roots[it].isFighter }.toIntArray()

        for (i in 0 until steps) {
            val t = i * DT
            for (k in roots.indices) {
                val ship = roots[k]
                val dt = DT * ship.timeMult
                if (t < params.coastAfter) {
                    Motion.inputAccel(limits[k], bodies[k].facing, ship.engineForward, ship.engineBack,
                        ship.engineLeft, ship.engineRight, accel)
                    Motion.step(bodies[k], limits[k], accel[0], accel[1], false, dt)
                } else {
                    Motion.step(bodies[k], limits[k], 0f, 0f, false, dt)
                }
            }
            // each ship faces its nearest enemy, re-picked every NEAREST_EVERY steps (an O(n^2) search)
            val repick = i % NEAREST_EVERY == 0
            for (k in roots.indices) {
                val ship = roots[k]
                val body = bodies[k]
                if (repick) {
                    var best = Float.MAX_VALUE
                    nearestOf[k] = -1
                    for (j in targetIdx) {
                        if (roots[j].owner == ship.owner) continue
                        val dx = bodies[j].x - body.x
                        val dy = bodies[j].y - body.y
                        val d = dx * dx + dy * dy
                        if (d < best) { best = d; nearestOf[k] = j }
                    }
                }
                val nearest = nearestOf[k]
                val dt = DT * ship.timeMult
                if (nearest >= 0 && ship.turnAcceleration > 0f) {
                    Motion.turnToward(body, limits[k], angleOf(bodies[nearest].x - body.x, bodies[nearest].y - body.y), dt)
                } else {
                    Motion.damp(body, limits[k], dt)
                }
                val tl = rootTimelines[k]
                tl.x[i] = body.x; tl.y[i] = body.y; tl.facing[i] = body.facing
            }
        }
        for (root in roots) placeModules(root, timelines)
        return timelines
    }

    /** Writes the timelines of [parent]'s modules from the parent's timeline. */
    private fun placeModules(parent: SimShip, timelines: MutableMap<String, Timeline>) {
        val modules = modulesByParent[parent.id] ?: return
        val p = timelines[parent.id] ?: return
        for (module in modules) {
            val tl = timelines[module.id] ?: continue
            for (i in 0 until tl.steps) {
                val a = (p.facing[i] + module.moduleOffsetAngle) * DEG_TO_RAD
                tl.x[i] = p.x[i] + module.moduleOffsetDist * cos(a)
                tl.y[i] = p.y[i] + module.moduleOffsetDist * sin(a)
                tl.facing[i] = normalizeAngle(p.facing[i] + module.moduleFacingOffset)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------- branch

    /** Which enemies commit to killing [protectedShip], with the centre-to-centre range each closes to. */
    fun committedTo(protectedShip: SimShip): Map<SimShip, Float> {
        val result = LinkedHashMap<SimShip, Float>()
        for (enemy in roots) {
            val carrier = enemy.carrierId?.let { byId[it] }
            val tr = targetingRadius(protectedShip, enemy)
            if (enemy.owner == protectedShip.owner) continue
            if (!enemy.isFighter && !mayTarget(enemy, protectedShip)) continue
            if (Pursuit.commits(enemy, protectedShip, carrier, tr, params)) {
                result[enemy] = Pursuit.holdDistance(enemy, protectedShip, tr)
            }
        }
        return result
    }

    /**
     * Whether [p] is, or could become, [enemy]'s target within the commit window. Translation of obfuscated code:
     * vanilla's ship AI (BasicShipAI.pickManeuver and the pickers in combat.ai.N) re-picks its target each time a
     * maneuver ends (every 2-4 s): an order or target override first, else the nearest enemy ship (the first
     * picker prefers ships at most two sizes smaller within 2200 and can keep a flank target it is already on;
     * the fallbacks are all nearest-first). Flux and overloads change how hard it presses, not whom it picks.
     *
     * Conservative: its live target, or within [TARGET_MARGIN] of its nearest other enemy ship (any size) at the
     * snapshot or at a re-pick over the first half of the window, everyone on base paths.
     */
    fun mayTarget(enemy: SimShip, p: SimShip): Boolean {
        if (enemy.maneuverTargetId == p.id) return true
        val eTl = base[enemy.id] ?: return true
        val pTl = base[p.id] ?: return true
        val last = ((params.commitWindow / 2f) / DT).toInt().coerceIn(0, params.steps - 1)
        val every = (REPICK_EVERY / DT).toInt().coerceAtLeast(1)
        var i = -1
        while (i <= last) {
            val ex = if (i < 0) enemy.x else eTl.x[i]; val ey = if (i < 0) enemy.y else eTl.y[i]
            val px = if (i < 0) p.x else pTl.x[i]; val py = if (i < 0) p.y else pTl.y[i]
            val dp = length(px - ex, py - ey)
            var nearest = Float.MAX_VALUE
            for (other in roots) {
                if (other === p || other.owner == enemy.owner || other.isFighter) continue
                val oTl = base[other.id] ?: continue
                val ox = if (i < 0) other.x else oTl.x[i]; val oy = if (i < 0) other.y else oTl.y[i]
                val d = length(ox - ex, oy - ey)
                if (d < nearest) nearest = d
            }
            if (dp <= nearest + TARGET_MARGIN) return true
            i = if (i < 0) 0 else i + every
        }
        return false
    }

    /**
     * Re-simulates [protectedId] following [plan] while every committed enemy pursues it, backing off as in
     * [reactions] (plan §13.8). Returns the base world unchanged when the ship isn't in this world.
     */
    fun branch(protectedId: String, plan: OwnPlan, reactions: Map<String, Reaction> = emptyMap()): BranchResult {
        val p = byId[protectedId] ?: return BranchResult(base, emptyMap())
        if (p.isModule) return BranchResult(base, emptyMap())
        val steps = params.steps
        val committed = committedTo(p)
        val pursuers = committed.map { (ship, hold) ->
            val reaction = reactions[ship.id]?.takeIf { it.moves }
            if (reaction == null) Pursuit.Pursuer(ship, hold)
            else Pursuit.Pursuer(ship, hold, reaction = reaction,
                backOffHold = Pursuit.backOffDistance(ship, p, targetingRadius(p, ship)))
        }
        val committedIds = committed.keys.mapTo(HashSet()) { it.id }

        // hostile ships the protected ship can face that keep their base paths
        val otherHostiles = roots.filter { it.owner != p.owner && !it.isFighter && it.id !in committedIds }
            .map { base[it.id]!! }

        val phased = PhasedLimits(plan, EngineLimits(p))
        val pTimeline = Timeline(steps)
        val pursuerTimelines = pursuers.map { Timeline(steps) }

        // a Then plan does its first part, then its escape; each part is one of the simple plans
        val first = if (plan is OwnPlan.Then) plan.first else plan
        val after = (plan as? OwnPlan.Then)?.then
        val switchAt = (plan as? OwnPlan.Then)?.switchAt ?: Float.MAX_VALUE

        // the ship the plan moves relative to (approached, held off or backed away from), and where it is each step
        fun relativeTo(part: OwnPlan?) = when (part) {
            is OwnPlan.Approach -> part.targetId
            is OwnPlan.Standoff -> part.targetId
            is OwnPlan.BackAway -> part.fromId
            else -> null
        }
        val otherId = relativeTo(first) ?: relativeTo(after)
        val other = otherId?.let { byId[it] }?.takeIf { !it.isModule && it.id != p.id }
        val otherPursuer = other?.let { o -> pursuers.indexOfFirst { it.ship.id == o.id } } ?: -1
        val otherState = FloatArray(4)
        val closes = first is OwnPlan.Approach || first is OwnPlan.Standoff
        val ownRange = if (closes && other != null) {
            Pursuit.approachDistance(p, ownTargetRadius[p.id]?.get(other.id) ?: other.collisionRadius)
        } else 0f
        val approachRange = if (first is OwnPlan.Approach) ownRange else 0f
        // advancing and the standoff reuse the pursuit movement (arrive at the hold point, face with the best guns),
        // without the enemy-style system use: the protected ship's own system comes from the plan's phases
        val mover = if (closes && other != null) {
            Pursuit.Pursuer(p, ownRange, useSystem = false, limitsOverride = { t -> phased.at(t * p.timeMult) })
        } else null
        val standoff = if (first is OwnPlan.Standoff && other != null) {
            StandoffRange(p, other, ownRange, pursuers, committedIds)
        } else null
        var standoffRange = 0f
        val body = mover?.body ?: Body(p)

        for (i in 0 until steps) {
            val t = i * DT
            val dt = DT * p.timeMult
            val limits = phased.at(t * p.timeMult)
            if (other != null) {
                if (otherPursuer >= 0) {
                    val b = pursuers[otherPursuer].body
                    otherState[0] = b.x; otherState[1] = b.y; otherState[2] = b.vx; otherState[3] = b.vy
                } else stateOnTimeline(other, base[other.id]!!, i, otherState)
            }
            var faced = false
            when (val part = if (t < switchAt) first else after!!) {
                is OwnPlan.Hold -> Motion.step(body, limits, 0f, 0f, true, dt)
                is OwnPlan.Thrust -> thrust(body, limits, part.dirX, part.dirY, dt)
                is OwnPlan.BackAway ->
                    if (other != null) thrust(body, limits, body.x - otherState[0], body.y - otherState[1], dt)
                    else Motion.step(body, limits, 0f, 0f, true, dt)
                is OwnPlan.Approach, is OwnPlan.Standoff ->
                    if (mover != null) {
                        if (standoff != null) {
                            mover.hold = standoff.at(i, body, otherState[0], otherState[1])
                            if (i == 0) standoffRange = mover.hold
                        }
                        mover.step(t, otherState[0], otherState[1], otherState[2], otherState[3])
                        faced = true
                    } else Motion.step(body, limits, 0f, 0f, true, dt)
                is OwnPlan.Then -> Motion.step(body, limits, 0f, 0f, true, dt)   // not nested
            }

            for (k in pursuers.indices) pursuers[k].step(t, body.x, body.y, body.vx, body.vy)

            // the protected ship turns toward its nearest hostile (pursuers at their new positions, others on base paths)
            if (!faced) faceNearestHostile(p, body, limits, pursuers, otherHostiles, i, dt)

            pTimeline.x[i] = body.x; pTimeline.y[i] = body.y; pTimeline.facing[i] = body.facing
            for (k in pursuers.indices) {
                val pursuer = pursuers[k]
                val tl = pursuerTimelines[k]
                tl.x[i] = pursuer.body.x; tl.y[i] = pursuer.body.y; tl.facing[i] = pursuer.body.facing
            }
        }

        // copy-on-write: only the re-simulated ships and their modules get new timelines
        val timelines = HashMap(base)
        timelines[p.id] = pTimeline
        for ((k, pursuer) in pursuers.withIndex()) timelines[pursuer.ship.id] = pursuerTimelines[k]
        for (moved in listOf(p) + committed.keys) {
            modulesByParent[moved.id]?.forEach { timelines[it.id] = Timeline(steps) }
            placeModules(moved, timelines)
        }
        val clearRange = committed.keys.filter { !it.isFighter }
            .maxOfOrNull { Pursuit.clearDistance(it, p, targetingRadius(p, it), params) } ?: 0f
        return BranchResult(timelines, committed.mapKeys { it.key.id }, approachRange, clearRange, standoffRange)
    }

    /**
     * The standoff's range to its target each step (plan §13.4). On the line from the target through the ship: the
     * target's standoff distance ([Pursuit.standoffDistance]; nothing for a friendly or harmless target) or the
     * ship's own range [ownRange] if farther, pushed out until no other hostile non-fighter's gun reach covers the
     * point. Committed enemies are where they are in this branch, the rest on their base paths.
     */
    private inner class StandoffRange(
        private val p: SimShip, private val target: SimShip, private val ownRange: Float,
        pursuers: List<Pursuit.Pursuer>, committedIds: Set<String>,
    ) {
        private val targetRange = if (target.owner != p.owner && target.gunReach > 0f) {
            Pursuit.standoffDistance(target, targetingRadius(p, target))
        } else 0f
        private val chasers = pursuers.filter { !it.ship.isFighter && it.ship.id != target.id && it.ship.gunReach > 0f }
        private val chaserRanges = FloatArray(chasers.size) {
            Pursuit.standoffDistance(chasers[it].ship, targetingRadius(p, chasers[it].ship))
        }
        private val others = roots.filter {
            it.owner != p.owner && !it.isFighter && it.id != target.id && it.id !in committedIds && it.gunReach > 0f
        }
        private val otherRanges = FloatArray(others.size) { Pursuit.standoffDistance(others[it], targetingRadius(p, others[it])) }
        private val otherTimelines = others.map { base[it.id]!! }

        /** The range at step [i], with the ship at [body] and its target at (tx, ty). */
        fun at(i: Int, body: Body, tx: Float, ty: Float): Float {
            val want = max(ownRange, targetRange)
            var ux = body.x - tx; var uy = body.y - ty
            val len = length(ux, uy)
            if (len > 1f) { ux /= len; uy /= len } else { ux = 1f; uy = 0f }
            var d = want
            // each hostile rules out an interval of the line; a few passes settle overlapping ones
            for (pass in 0 until 3) {
                var moved = false
                for (k in chasers.indices) {
                    val b = chasers[k].body
                    val pushed = pushOut(d, ux, uy, b.x - tx, b.y - ty, chaserRanges[k])
                    if (pushed > d) { d = pushed; moved = true }
                }
                for (k in others.indices) {
                    val tl = otherTimelines[k]
                    val hx = if (i == 0) others[k].x else tl.x[i - 1]
                    val hy = if (i == 0) others[k].y else tl.y[i - 1]
                    val pushed = pushOut(d, ux, uy, hx - tx, hy - ty, otherRanges[k])
                    if (pushed > d) { d = pushed; moved = true }
                }
                if (!moved) break
            }
            return if (d > want + MAX_STANDOFF_PUSH) want else d
        }

        /**
         * The point at [d] along (ux, uy) from the target, moved outward past a hostile at (hx, hy) relative to the
         * target whose reach is [range]: the far root of |u d - h| = range when d lies inside, else d.
         */
        private fun pushOut(d: Float, ux: Float, uy: Float, hx: Float, hy: Float, range: Float): Float {
            val b = ux * hx + uy * hy
            val c = hx * hx + hy * hy - range * range
            val disc = b * b - c
            if (disc <= 0f) return d
            val root = sqrt(disc)
            return if (d > b - root && d < b + root) b + root else d
        }
    }

    /** Thrusts as hard as the engines allow toward world direction (dx, dy); brakes if it has no length. */
    private fun thrust(body: Body, limits: EngineLimits, dx: Float, dy: Float, dt: Float) {
        val len = length(dx, dy)
        if (len > 1e-3f) {
            val ux = dx / len; val uy = dy / len
            val a = Motion.maxAccelToward(limits, body.facing, ux, uy)
            Motion.step(body, limits, ux * a, uy * a, false, dt)
        } else {
            Motion.step(body, limits, 0f, 0f, true, dt)
        }
    }

    /** Turns [body] toward its nearest hostile: pursuers at their new positions, others on their base paths. */
    private fun faceNearestHostile(
        p: SimShip, body: Body, limits: EngineLimits, pursuers: List<Pursuit.Pursuer>, otherHostiles: List<Timeline>,
        i: Int, dt: Float,
    ) {
        var best = Float.MAX_VALUE
        var fx = 0f; var fy = 0f
        for (k in pursuers.indices) {
            val pursuer = pursuers[k]
            if (pursuer.ship.isFighter) continue
            val dx = pursuer.body.x - body.x; val dy = pursuer.body.y - body.y
            val d = dx * dx + dy * dy
            if (d < best) { best = d; fx = dx; fy = dy }
        }
        for (k in otherHostiles.indices) {
            val tl = otherHostiles[k]
            val dx = tl.x[i] - body.x; val dy = tl.y[i] - body.y
            val d = dx * dx + dy * dy
            if (d < best) { best = d; fx = dx; fy = dy }
        }
        if (best < Float.MAX_VALUE && p.turnAcceleration > 0f) Motion.turnToward(body, limits, angleOf(fx, fy), dt)
        else Motion.damp(body, limits, dt)
    }

    /**
     * Where [ship] is at the start of step [i] on its base timeline (frame i - 1, or the snapshot), with the velocity
     * of the step before, into out = (x, y, vx, vy).
     */
    private fun stateOnTimeline(ship: SimShip, tl: Timeline, i: Int, out: FloatArray) {
        if (i == 0) {
            out[0] = ship.x; out[1] = ship.y; out[2] = ship.vx; out[3] = ship.vy
            return
        }
        val px = if (i >= 2) tl.x[i - 2] else ship.x
        val py = if (i >= 2) tl.y[i - 2] else ship.y
        out[0] = tl.x[i - 1]; out[1] = tl.y[i - 1]
        out[2] = (tl.x[i - 1] - px) / DT; out[3] = (tl.y[i - 1] - py) / DT
    }

    /** The protected ship's engine limits over its plan's phases, built once per branch (looked up every step). */
    private class PhasedLimits(plan: OwnPlan, base: EngineLimits) {
        private val ends = FloatArray(plan.phases.size)
        private val limits = Array(plan.phases.size) { k ->
            val phase = plan.phases[k]
            EngineLimits(
                phase.maxSpeed ?: base.maxSpeed,
                phase.acceleration ?: base.acceleration,
                phase.deceleration ?: base.deceleration,
                base.strafeFactor, base.turnAcceleration, base.maxTurnRate,
            )
        }
        private val base = base

        init {
            var end = 0f
            for (k in plan.phases.indices) {
                end += plan.phases[k].duration
                ends[k] = end
            }
        }

        /** Limits at [shipTime] seconds into the plan (the ship's own base limits after the last phase). */
        fun at(shipTime: Float): EngineLimits {
            for (k in ends.indices) if (shipTime < ends[k]) return limits[k]
            return base
        }
    }
}
