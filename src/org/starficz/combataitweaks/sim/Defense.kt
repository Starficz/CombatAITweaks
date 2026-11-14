package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How the protected ship uses its shield in one resolution. */
enum class ShieldPlan {
    /** Vanilla-like: the shield is up whenever it can be (not venting, not overloaded). */
    NORMAL,
    /**
     * Vent at t = 0 and keep the shield down for the whole horizon. The vent decision reads this only over its own
     * vent window W (time to vent + shield raise + buffer, from the live flux), so how long the vent would last at
     * the snapshot's flux doesn't matter; that flux can be out of date (a ship at 0 flux in the snapshot would
     * otherwise not "vent" at all and its shield would block everything).
     */
    VENT_NOW,
}

/** What the hits did to the protected ship, per simulation step (index i = state at (i + 1) * DT). */
class DefenseOutcome(val steps: Int) {
    val flux = FloatArray(steps)
    val hardFlux = FloatArray(steps)
    val shieldUp = BooleanArray(steps)
    /** Cumulative hull lost by the ship itself (modules excluded). */
    val hullLost = FloatArray(steps)
    /** Cumulative hull lost by its modules. */
    val moduleHullLost = FloatArray(steps)
    /**
     * Largest drop of regional armor (vanilla's 21-cell armor value at a hit point) so far, in armor-rating units:
     * 0 = untouched, the armor rating = stripped bare somewhere.
     */
    val armorLost = FloatArray(steps)
    /** Cumulative flux the shield actually took. */
    val shieldFlux = FloatArray(steps)
    /**
     * Cumulative flux every hit (except conservative ones) would put on the shield if it blocked it, whatever the
     * shield really did. v1's thresholds read this until milestone 3 replaces them.
     */
    val potentialFlux = FloatArray(steps)
    val potentialHardFlux = FloatArray(steps)
    /** First overload: start time and duration (-1 = none). */
    var overloadStart = -1f
    var overloadDuration = 0f
    /** First time hull (not armor) took damage, or -1. */
    var firstHullDamage = -1f
    /**
     * Times (in order) of the hits the shield covered: taken on it, or held back by a conservative shooter until it
     * drops. With the shield down they would land on armor (plan §13.9).
     */
    val shieldHitTimes = ArrayList<Float>()

    fun at(time: Float): Int = ((time / DT).toInt() - 1).coerceIn(0, steps - 1)
}

/**
 * Applies a branch's [hits] to the protected ship's defences in time order (plan §6.3 to §6.5a).
 *
 * @param facing the protected ship's facing per step (its branch timeline).
 * @param omniFacing where an omni shield points per step (NaN = keep its current offset from the ship's facing).
 * @param moduleTimelines positions of the ship's modules per step, by module id.
 */
class DefenseResolver(
    private val defense: SimDefense,
    private val rules: DamageRules,
    private val timeline: Timeline,
    private val omniFacing: FloatArray,
    private val moduleTimelines: Map<String, Timeline>,
) {
    /**
     * @param ownFire the ship's own weapons, run each step after that step's hits on the ship's flux: they fire only
     * while it isn't venting or overloaded, and each shot's flux cost (soft) must fit under the max.
     * @param ownFiringFlux flux the ship's weapons spend per step when they are simulated apart (an enemy firing at
     * the protected ship on its own budget, [FireControl.firingFlux]): added as soft flux while it can fire, capped
     * at the max.
     */
    fun resolve(
        hits: List<Hit>, plan: ShieldPlan, ownFire: ((Int, FluxGate) -> Unit)? = null,
        ownFiringFlux: FloatArray? = null,
    ): DefenseOutcome {
        val steps = timeline.steps
        val out = DefenseOutcome(steps)
        val state = State(defense, rules)
        val modules = defense.modules.map { State(it, rules) }
        if (plan == ShieldPlan.VENT_NOW) state.startVent()

        val held = ArrayList<Hit>()
        var next = 0
        val tm = defense.timeMult
        for (i in 0 until steps) {
            val stepEnd = (i + 1) * DT
            val wasUp = state.shieldUpNow()
            while (next < hits.size && hits[next].time < stepEnd) {
                val hit = hits[next++]
                applyHit(hit, i, state, modules, held, out)
            }
            if (ownFire != null) ownFire(i, state)
            if (ownFiringFlux != null && !state.silenced()) {
                state.flux = min(defense.maxFlux, state.flux + ownFiringFlux[i])
            }
            state.advance(DT * tm)
            out.flux[i] = state.flux
            out.hardFlux[i] = state.hardFlux
            out.shieldUp[i] = state.shieldUpNow()
            out.hullLost[i] = defense.hull - state.hull
            out.moduleHullLost[i] = modules.sumOf { (it.def.hull - it.hull).toDouble() }.toFloat()
            out.armorLost[i] = state.armorLostMax
            out.shieldFlux[i] = state.shieldFluxTaken
            out.potentialFlux[i] = state.potentialFlux
            out.potentialHardFlux[i] = state.potentialHardFlux
            if (state.overloadedAt >= 0f && out.overloadStart < 0f) {
                out.overloadStart = state.overloadedAt
                out.overloadDuration = state.overloadTotal / tm
            }
            // the shield just dropped (overload, vent): conservative shots held for this moment now fire
            if (wasUp && !state.shieldUpNow() && held.isNotEmpty()) {
                val release = ArrayList(held)
                held.clear()
                for (h in release) applyHit(h, i, state, modules, held, out, forceUnheld = true)
            }
        }
        return out
    }

    private fun applyHit(
        hit: Hit, i: Int, state: State, modules: List<State>, held: MutableList<Hit>, out: DefenseOutcome,
        forceUnheld: Boolean = false,
    ) {
        val px = timeline.x[i]; val py = timeline.y[i]
        val facing = timeline.facing[i]
        val bearing = angleOf(hit.fromX - px, hit.fromY - py)

        state.shieldFacing = when (defense.shield) {
            ShieldKind.OMNI -> if (omniFacing[i].isNaN()) normalizeAngle(facing + defense.shieldOffset) else omniFacing[i]
            else -> normalizeAngle(facing + defense.shieldOffset)
        }

        val shieldFlux = state.shieldFluxOf(hit)
        if (!hit.conservative) {
            state.potentialFlux += shieldFlux
            if (!hit.softFlux) state.potentialHardFlux += shieldFlux
        }

        if (state.shieldCovers(bearing, 0f)) {
            if (!forceUnheld) out.shieldHitTimes += hit.time
            if (hit.conservative && !forceUnheld) {
                held += hit
                return
            }
            state.takeOnShield(hit, shieldFlux, hit.time)
            return
        }

        // modules in the way take it first
        for (module in modules) {
            if (module.hull <= 0f) continue
            val mt = moduleTimelines[module.def.shipId] ?: continue
            val mx = mt.x[i]; val my = mt.y[i]
            if (!segmentHitsCircle(hit.fromX, hit.fromY, px, py, mx, my, module.def.collisionRadius * 0.8f)) continue
            module.takeOnArmor(hit, angleOf(hit.fromX - mx, hit.fromY - my) - mt.facing[i])
            return
        }

        val before = state.hull
        state.takeOnArmor(hit, bearing - facing)
        if (state.hull < before && out.firstHullDamage < 0f) out.firstHullDamage = hit.time
    }

    /** Whether the segment from (ax, ay) to (bx, by) passes within [r] of (cx, cy) before reaching (bx, by). */
    private fun segmentHitsCircle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, r: Float): Boolean {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-6f) return false
        val t = (((cx - ax) * dx + (cy - ay) * dy) / len2).coerceIn(0f, 1f)
        val qx = ax + dx * t - cx; val qy = ay + dy * t - cy
        return qx * qx + qy * qy <= r * r
    }

    /** One ship's (or module's) mutable defences. */
    private class State(val def: SimDefense, val rules: DamageRules) : FluxGate {
        var flux = def.flux
        var hardFlux = def.hardFlux
        var hull = def.hull
        var shieldFacing = 0f
        var venting = def.isVenting
        var overloadLeft = def.overloadLeft
        var overloadedAt = -1f
        var overloadTotal = 0f
        /** 0..1 unfold progress of the shield; the active arc is arc x progress. */
        var unfold = when {
            def.shield == ShieldKind.NONE -> 0f
            def.shieldOn && def.shieldArc > 0f -> (def.shieldActiveArc / def.shieldArc).coerceIn(0f, 1f)
            else -> 0f
        }
        var time = 0f
        var shieldFluxTaken = 0f
        var potentialFlux = 0f
        var potentialHardFlux = 0f
        var armorLostMax = 0f
        val grid = def.armor?.let { ArmorGrid(it, rules) }

        /** Shield held down for the whole resolution (venting from t = 0). */
        var ventHeld = false

        fun startVent() {
            venting = true
            ventHeld = true
            unfold = 0f
        }

        fun canShield() = def.shield != ShieldKind.NONE && !venting && overloadLeft <= 0f

        // the ship's own weapons: they can't fire while venting or overloaded; firing costs soft flux
        override fun silenced() = venting || overloadLeft > 0f
        override fun affords(cost: Float) = flux + cost <= def.maxFlux
        override fun spend(cost: Float) { flux += cost }
        override fun level() = flux / def.maxFlux.coerceAtLeast(1f)
        override fun available() = def.maxFlux - flux
        fun shieldUpNow() = canShield() && unfold > 0f

        fun shieldCovers(bearing: Float, pad: Float, ignoreArc: Boolean = false): Boolean {
            if (!shieldUpNow()) return false
            if (ignoreArc) return true
            val active = def.shieldArc * unfold
            return abs(shortestRotation(shieldFacing, bearing)) <= active / 2f + pad
        }

        fun shieldFluxOf(hit: Hit): Float {
            if (def.shield == ShieldKind.NONE) return 0f
            val d = hit.damage * def.shieldKindMult[hit.kind.ordinal] * def.shieldDeliveryMult[hit.delivery.ordinal]
            return d * rules.shieldMult[hit.kind.ordinal] * def.shieldDamageTakenMult * def.fluxPerDamage
        }

        fun takeOnShield(hit: Hit, amount: Float, at: Float) {
            if (amount <= 0f) return
            shieldFluxTaken += amount
            val total = flux + amount
            if (total > def.maxFlux) {
                val overflow = total - def.maxFlux
                flux = def.maxFlux
                if (!hit.softFlux) hardFlux = def.maxFlux
                overload(overflow, at)
            } else {
                flux = total
                if (!hit.softFlux) hardFlux = min(hardFlux + amount, def.maxFlux)
            }
        }

        /** Vanilla's FluxTracker.beginOverload: hull-size base, plus overflow / 25, capped at 15, then the stat mod. */
        private fun overload(overflow: Float, at: Float) {
            if (venting || overloadLeft > 0f) return
            var base = when (def.hullClass) {
                HullClass.FIGHTER -> 10f
                HullClass.FRIGATE -> 4f
                HullClass.DESTROYER -> 6f
                HullClass.CRUISER -> 8f
                HullClass.CAPITAL -> 10f
                HullClass.OTHER -> 5f
            }
            if (def.isDrone) base = 3f
            if (overflow > 0f) base = min(base + overflow / 25f, 15f)
            overloadLeft = def.overloadTimeMod.apply(base)
            overloadTotal = overloadLeft
            if (overloadedAt < 0f) overloadedAt = at
            unfold = 0f
        }

        /** [localAngle]: where the hit comes from, relative to the ship's facing. */
        fun takeOnArmor(hit: Hit, localAngle: Float) {
            val ratio = def.kindMult[hit.kind.ordinal] * def.deliveryMult[hit.delivery.ordinal]
            val damage = hit.damage * ratio
            val strength = hit.hitStrength * ratio
            val beam = hit.delivery == Delivery.BEAM
            val g = grid
            val cell = if (g != null) def.rayCells[rayIndex(localAngle)] else -1
            val hullDamage = if (g != null && cell >= 0) {
                val i = cell / g.height; val j = cell % g.height
                val hullHit = g.hit(i, j, damage, strength, hit.kind, beam)
                val lost = g.armorAround(i, j, g.info.cells) - g.armorAround(i, j)
                if (lost > armorLostMax) armorLostMax = lost
                hullHit
            } else {
                damage * rules.hullMult[hit.kind.ordinal] * (def.armor?.hullDamageTakenMult ?: 1f)
            }
            hull = max(0f, hull - hullDamage)
        }

        /** Flux, vent, overload and shield unfolding over [dt] seconds of this ship's time. */
        fun advance(dt: Float) {
            time += dt
            if (overloadLeft > 0f) {
                overloadLeft -= dt
                // vanilla: half dissipation while overloaded, hard flux included (the shield is down)
                flux = max(0f, flux - def.dissipation * 0.5f * dt)
                hardFlux = min(hardFlux, flux)
                if (overloadLeft <= 0f) overloadLeft = 0f
                return
            }
            if (venting) {
                flux = max(0f, flux - def.ventRate * dt)
                hardFlux = min(hardFlux, flux)
                if (flux <= 0f && !ventHeld) venting = false
                return
            }
            if (def.shield != ShieldKind.NONE) {
                unfold = if (def.unfoldTime > 0f) min(1f, unfold + dt / def.unfoldTime) else 1f
                flux = min(def.maxFlux, flux + def.upkeep * dt)
            }
            // dissipation drains soft flux first; hard flux only with the shield down or by the dissipation stat
            val drained = max(0f, flux - def.dissipation * dt)
            if (drained < hardFlux) {
                if (!shieldUpNow()) hardFlux = drained
                else {
                    val fraction = def.hardFluxDissipationFraction.coerceIn(0f, 1f)
                    hardFlux -= (hardFlux - drained) * fraction
                }
                flux = hardFlux
            } else {
                flux = drained
            }
        }
    }

    companion object {
        fun rayIndex(localAngle: Float): Int {
            val a = normalizeAngle(localAngle)
            val idx = ((a + SimDefense.RAY_STEP / 2f) / SimDefense.RAY_STEP).toInt()
            return ((idx % SimDefense.RAYS) + SimDefense.RAYS) % SimDefense.RAYS
        }

        /**
         * For each ray direction, the cell a hit from far away along that direction lands on: the outermost point of
         * the ship's bounds on that ray. [segments] are ship-local (x1, y1, x2, y2) quads, facing +x.
         */
        fun rayCells(armor: ArmorInfo?, segments: FloatArray, fallbackRadius: Float): IntArray {
            val result = IntArray(SimDefense.RAYS) { -1 }
            if (armor == null) return result
            for (k in 0 until SimDefense.RAYS) {
                val a = k * SimDefense.RAY_STEP * DEG_TO_RAD
                val ux = cos(a); val uy = sin(a)
                var best = -1f
                var s = 0
                while (s + 3 < segments.size) {
                    val t = rayHitsSegment(ux, uy, segments[s], segments[s + 1], segments[s + 2], segments[s + 3])
                    if (t > best) best = t
                    s += 4
                }
                if (best < 0f) best = fallbackRadius
                // step slightly inside so the point is on the ship's own cells, not the padding
                var r = best - 1f
                var cell: Int? = null
                while (r > 0f && cell == null) {
                    cell = ArmorGrid.cellAt(armor, ux * r, uy * r)
                    r -= 5f
                }
                result[k] = cell ?: -1
            }
            return result
        }

        /** Distance along the unit ray from the origin at which it crosses segment (x1, y1)-(x2, y2), or -1. */
        private fun rayHitsSegment(ux: Float, uy: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val ex = x2 - x1; val ey = y2 - y1
            val det = ux * (-ey) - uy * (-ex)
            if (abs(det) < 1e-6f) return -1f
            // origin + t*u = p1 + s*e  ->  t*u - s*e = p1
            val t = (x1 * (-ey) - y1 * (-ex)) / det
            val s = (ux * y1 - uy * x1) / det
            if (t < 0f || s < 0f || s > 1f) return -1f
            return t
        }
    }
}
