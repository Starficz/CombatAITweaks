package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A shooter's flux as its weapons see it: whether it can fire at all this step, and what it can afford. */
interface FluxGate {
    /** Venting or overloaded: nothing fires. */
    fun silenced(): Boolean
    /** Whether [cost] more flux stays within the max. */
    fun affords(cost: Float): Boolean
    fun spend(cost: Float)
    /** Flux level (0..1) and the flux still free under the max, for fire discipline ([FireDiscipline]). */
    fun level(): Float
    fun available(): Float
}

/**
 * A shooter's own flux budget, apart from its defences (plan §5.6): it dissipates down to its hard flux, and the
 * shooter is silent while venting or overloaded at the snapshot.
 */
class ShooterFlux(
    private val shooter: SimShooter,
    /** Silent over this time too (overloaded in a branch, plan §13.8). */
    private val quiet: Reaction? = null,
    /** Hard flux its shield took from the protected ship's side by each step (plan §13.8), on top of its own. */
    private val pressure: FloatArray? = null,
    /** Flux its weapons spend per step, recorded here when given. */
    private val spent: FloatArray? = null,
) : FluxGate {
    private var flux = shooter.flux
    private var time = 0f
    private var step = 0
    private var pressed = 0f

    /** Moves to step [i]: dissipation over the step, before its weapons fire. */
    fun tick(i: Int) {
        step = i
        time = (i + 1) * DT
        flux = max(shooter.hardFlux, flux - shooter.dissipation * DT * shooter.timeMult)
        pressed = pressure?.get(i) ?: 0f
    }

    override fun silenced() = time < shooter.silenceTime || (quiet != null && quiet.quietAt(time))
    override fun affords(cost: Float) = flux + pressed + cost <= shooter.maxFlux
    override fun spend(cost: Float) {
        flux += cost
        if (spent != null) spent[step] += cost
    }
    override fun level() = (flux + pressed) / shooter.maxFlux.coerceAtLeast(1f)
    override fun available() = shooter.maxFlux - flux - pressed
}

/**
 * Fires every hostile shooter's weapons at one target along one branch's timelines (plan §6.1 and §5.6).
 *
 * Step i covers the time up to (i + 1) * DT and uses the poses of frame i. A weapon starts a burst when the target
 * is in range and inside its arc, its turret is on target, it has ammo and its ship can afford the flux and isn't
 * venting or overloaded. Projectile damage is scaled by the chance the spread cone hits the target; beams deliver
 * ticks every 0.1 s. Guided missiles are launched into [launched] for the missile simulation.
 *
 * Also the target of a [Volley]: then a shooter's weapons engage it only on the steps autofire picks it.
 */
class FireControl(
    val target: SimDefense,
    private val targetOwner: Int,
    val targetTimeline: Timeline,
) {
    private val steps = targetTimeline.steps

    val hits = ArrayList<Hit>()
    val launched = ArrayList<InFlightMissile>()

    /** Per step: the strongest and second-strongest shooter with guns on the target (DPS and bearing from it). */
    val topDps = FloatArray(steps)
    val topBearing = FloatArray(steps)
    val secondDps = FloatArray(steps)
    val secondBearing = FloatArray(steps)

    /** DPS a [Gunnery] has on this target in the step it is running, flushed into the ranking at the step's end. */
    internal var pendingDps = 0f
    internal var pendingBearing = 0f

    private val self: (Int) -> FireControl? = { this }

    /** Flux each shooter fired by [fire] spent per step, by shooter id (its own flux, for its defences). */
    val firingFlux = HashMap<String, FloatArray>()

    /**
     * The shooter's damage-dealing system ([SimShooter.systemThreat]), worst case: used at the first step it is
     * ready (and the ship can act) with the target inside its threat range and arc, one hit of its damage per use,
     * arriving after the travel at its threat speed; again after each cycle while uses last.
     */
    fun fireSystem(shooter: SimShooter, timeline: Timeline, quiet: Reaction? = null) {
        val sys = shooter.systemThreat ?: return
        if (shooter.owner == targetOwner || shooter.shipId == target.shipId) return
        val vsSize = shooter.damageVsSize.getOrElse(target.hullClass.ordinal) { 1f }
        var next = max(sys.readyIn, shooter.silenceTime)
        var uses = sys.uses
        for (i in 0 until steps) {
            if (uses <= 0) break
            val time = (i + 1) * DT
            if (time < next || (quiet != null && quiet.quietAt(time))) continue
            val sx = timeline.x[i]; val sy = timeline.y[i]
            val dx = targetTimeline.x[i] - sx; val dy = targetTimeline.y[i] - sy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > sys.range + target.hitRadius) continue
            if (sys.arc < 360f && abs(shortestRotation(timeline.facing[i] + sys.angle, angleOf(dx, dy))) > sys.arc / 2f) continue
            val instant = sys.speed >= 10000f
            val travel = if (instant) 0f else max(0f, dist - target.hitRadius) / max(sys.speed, 1f)
            hits += Hit(
                time = time + travel, damage = sys.damage * vsSize, hitStrength = sys.damage * vsSize,
                emp = sys.emp * vsSize, kind = sys.kind,
                delivery = if (instant) Delivery.PROJECTILE else Delivery.MISSILE,
                softFlux = false, conservative = false, fromX = sx, fromY = sy, sourceId = shooter.shipId,
            )
            uses--
            next = time + sys.cycle
        }
    }

    /**
     * Every weapon of [shooter] at this target whenever it can, on its own flux: under vanilla's fire [discipline]
     * when given (plan §13.8), else until its flux is full.
     * @param quiet when it is silent in this branch (overloaded).
     * @param pressure hard flux the target's side put on its shield by each step.
     */
    fun fire(
        shooter: SimShooter, timeline: Timeline, quiet: Reaction? = null, discipline: FireDiscipline? = null,
        pressure: FloatArray? = null,
    ) {
        if (shooter.owner == targetOwner || shooter.shipId == target.shipId || shooter.weapons.isEmpty()) return
        val maxRange = shooter.weapons.maxOf { it.range } + target.hitRadius + 150f
        if (!ever(timeline) { d2 -> d2 <= maxRange * maxRange }) return

        val gunnery = Gunnery(shooter, timeline)
        val spent = FloatArray(steps)
        val gate = ShooterFlux(shooter, quiet, pressure, spent)
        val enabled = if (discipline != null) BooleanArray(shooter.weapons.size) { true } else null
        for (i in 0 until steps) {
            gate.tick(i)
            if (discipline != null && i % FireDiscipline.EVERY_STEPS == 0) {
                discipline.toggle(shooter.weapons, gate, target.hullClass, enabled!!)
            }
            gunnery.step(i, gate, self, enabled)
        }
        firingFlux[shooter.shipId] = spent
    }

    /** Beams deal their damage in 0.1 s ticks (engine time). */
    internal fun beamTick(
        w: SimWeapon, st: Gunnery.WeaponState, shooter: SimShooter, time: Float,
        mx: Float, my: Float, dist: Float, onTarget: Boolean, vsSize: Float,
    ) {
        st.beamTick += DT
        if (st.beamTick < BEAM_TICK - 1e-4f) return
        val tick = st.beamTick
        st.beamTick = 0f
        if (!onTarget) return
        val perSecond = shooter.timeMult * vsSize
        val travel = if (w.speed > 0f) dist / w.speed else 0f
        hits += Hit(
            time = time + travel,
            damage = w.damage * perSecond * tick,
            hitStrength = w.damage * vsSize,
            emp = w.emp * perSecond * tick,
            kind = w.kind, delivery = Delivery.BEAM, softFlux = w.softFlux, conservative = w.conservative,
            fromX = mx, fromY = my, sourceId = shooter.shipId,
        )
    }

    internal fun shoot(
        w: SimWeapon, shooter: SimShooter, timeline: Timeline, i: Int, time: Float,
        mx: Float, my: Float, worldAngle: Float, dist: Float, onTarget: Boolean, vsSize: Float,
    ) {
        val missile = w.missile
        if (w.mode == FireMode.GUIDED_MISSILE && missile != null) {
            val vx = velocityX(timeline, i); val vy = velocityY(timeline, i)
            val a = worldAngle * DEG_TO_RAD
            launched += InFlightMissile(
                x = mx, y = my,
                vx = vx + cos(a) * missile.launchSpeed, vy = vy + sin(a) * missile.launchSpeed,
                facing = normalizeAngle(worldAngle), angularVelocity = 0f, owner = shooter.owner,
                damage = w.damage * vsSize, emp = w.emp * vsSize, kind = w.kind, softFlux = w.softFlux,
                info = missile, flightTime = -time, targetId = target.shipId, conservative = w.conservative,
                sourceId = shooter.shipId,
            )
            return
        }
        if (!onTarget) return
        val chance = if (w.spread > 0f) {
            val spreadRadius = dist * tan(w.spread / 2f * DEG_TO_RAD)
            if (spreadRadius <= target.hitRadius) 1f else (target.hitRadius / spreadRadius).coerceIn(0f, 1f)
        } else 1f
        val travel = if (w.speed > 0f) dist / w.speed else 0f
        hits += Hit(
            time = time + travel,
            damage = w.damage * vsSize * chance,
            hitStrength = w.damage * vsSize,
            emp = w.emp * vsSize * chance,
            kind = w.kind,
            delivery = if (w.missile != null) Delivery.MISSILE else Delivery.PROJECTILE,
            softFlux = w.softFlux, conservative = w.conservative,
            fromX = mx, fromY = my, sourceId = shooter.shipId,
        )
    }

    internal fun flushDps(i: Int) {
        if (pendingDps > 0f) rankThreat(i, pendingDps, pendingBearing)
        pendingDps = 0f
    }

    private fun rankThreat(i: Int, dps: Float, bearing: Float) {
        if (dps > topDps[i]) {
            secondDps[i] = topDps[i]; secondBearing[i] = topBearing[i]
            topDps[i] = dps; topBearing[i] = bearing
        } else if (dps > secondDps[i]) {
            secondDps[i] = dps; secondBearing[i] = bearing
        }
    }

    /**
     * Where an omni shield points per step, following vanilla's omni shield AI (translated): at the top threat,
     * pulled toward the second one by half the angle between them, at most 0.3 x (arc - 10). NaN when nothing shoots.
     */
    fun omniFacing(arc: Float): FloatArray = FloatArray(steps) { i ->
        if (topDps[i] <= 0f) Float.NaN
        else if (secondDps[i] <= 0f) topBearing[i]
        else {
            val diff = shortestRotation(topBearing[i], secondBearing[i])
            val pull = min((arc - 10f) * 0.6f, abs(diff)) * 0.5f
            normalizeAngle(topBearing[i] + pull * if (diff >= 0f) 1f else -1f)
        }
    }

    private inline fun ever(timeline: Timeline, test: (Float) -> Boolean): Boolean {
        for (i in 0 until steps) {
            val dx = timeline.x[i] - targetTimeline.x[i]
            val dy = timeline.y[i] - targetTimeline.y[i]
            if (test(dx * dx + dy * dy)) return true
        }
        return false
    }

    companion object {
        const val BEAM_TICK = 0.1f

        /** Velocity at frame [i] from the timeline's positions. */
        fun velocityX(timeline: Timeline, i: Int): Float = velocity(timeline.x, timeline.steps, i)
        fun velocityY(timeline: Timeline, i: Int): Float = velocity(timeline.y, timeline.steps, i)

        private fun velocity(v: FloatArray, steps: Int, i: Int): Float = when {
            steps < 2 -> 0f
            i <= 0 -> (v[1] - v[0]) / DT
            else -> (v[i] - v[i - 1]) / DT
        }
    }
}

/**
 * One shooter's weapons stepped through time: turrets tracking, the fire cycle (charge, burst, cooldown, beams),
 * ammo, and the flux each shot costs from a [FluxGate]. Each step every weapon engages the [FireControl] the caller
 * names for it (or none), so one shooter can spread its weapons over several targets ([Volley]).
 */
class Gunnery(private val shooter: SimShooter, private val timeline: Timeline) {
    internal class WeaponState(w: SimWeapon) {
        var phase = w.phase
        var timeLeft = w.phaseTimeLeft
        var shotsLeft = if (w.phase == WeaponPhase.FIRING) w.shotsLeftInBurst.coerceAtLeast(1) else 0
        var angle = w.angle
        var ammo = w.ammo
        var ammoAccrued = 0f
        var beamTick = 0f
    }

    private val weapons = shooter.weapons
    private val states = Array(weapons.size) { WeaponState(weapons[it]) }
    private val dtShip = DT * shooter.timeMult
    private val maxRange = if (weapons.isEmpty()) 0f else weapons.maxOf { it.range }
    private val touched = ArrayList<FireControl>(4)

    /** The turret's current aim of weapon [k], ship-local. */
    fun angle(k: Int): Float = states[k].angle

    /**
     * Runs every weapon through step [i]; [sinkOf] gives the target weapon k engages this step (null: none).
     * [enabled]: weapons switched off by fire discipline start no new shots (a burst under way finishes).
     */
    fun step(i: Int, gate: FluxGate, sinkOf: (Int) -> FireControl?, enabled: BooleanArray? = null) {
        val time = (i + 1) * DT
        val silenced = gate.silenced()
        val sx = timeline.x[i]; val sy = timeline.y[i]; val sf = timeline.facing[i]
        var trig = false
        var cosF = 1f; var sinF = 0f
        var lastSink: FireControl? = null
        var far = true
        var px = 0f; var py = 0f
        var vsSize = 1f

        for (k in states.indices) {
            val w = weapons[k]
            val st = states[k]
            regenAmmo(w, st)
            if (time < w.disabledFor) continue

            val sink = sinkOf(k)
            if (sink !== lastSink) {
                lastSink = sink
                far = true
                if (sink != null) {
                    px = sink.targetTimeline.x[i]; py = sink.targetTimeline.y[i]
                    val cdx = px - sx; val cdy = py - sy
                    val reach = maxRange + sink.target.hitRadius + 150f
                    far = cdx * cdx + cdy * cdy > reach * reach
                    vsSize = shooter.damageVsSize.getOrElse(sink.target.hullClass.ordinal) { 1f }
                    if (!far && sink.pendingDps == 0f && sink !in touched) touched += sink
                }
            }

            var mx = sx; var my = sy
            var dist = 0f
            var inRange = false
            var aimed = false
            var inArc = false
            if (!far && sink != null) {
                if (!trig) { cosF = cos(sf * DEG_TO_RAD); sinF = sin(sf * DEG_TO_RAD); trig = true }
                val hitRadius = sink.target.hitRadius
                mx = sx + w.mountX * cosF - w.mountY * sinF
                my = sy + w.mountX * sinF + w.mountY * cosF
                val dx = px - mx; val dy = py - my
                dist = sqrt(dx * dx + dy * dy)
                inRange = dist <= w.range + hitRadius
                val localBearing = shortestRotation(sf, angleOf(dx, dy))
                val angularRadius = if (dist > hitRadius) asin(hitRadius / dist) * RAD_TO_DEG else 90f
                val offSlot = shortestRotation(w.slotAngle, localBearing)
                inArc = w.noAim || abs(offSlot) <= w.arc / 2f + angularRadius

                // the turret tracks the target within its arc
                val desired = w.slotAngle + offSlot.coerceIn(-w.arc / 2f, w.arc / 2f)
                val turn = shortestRotation(st.angle, desired)
                val maxTurn = w.turnRate * dtShip
                st.angle = normalizeAngle(st.angle + turn.coerceIn(-maxTurn, maxTurn))
                aimed = w.noAim || abs(shortestRotation(st.angle, localBearing)) <= angularRadius + 1f
            }
            val canEngage = inRange && inArc && !silenced && (enabled == null || enabled[k])

            if (canEngage && aimed) {
                sink!!.pendingDps += w.sustainedDps
                sink.pendingBearing = angleOf(sx - px, sy - py)
            }

            // Run the weapon's state machine through this step's time. A phase that ends mid-step hands the rest
            // of the step to the next phase, so cycle times don't round up to whole steps.
            var budget = dtShip
            var transitions = 0
            cycle@ while (transitions++ < 8) {
                when (st.phase) {
                    WeaponPhase.READY -> {
                        if (!canEngage || !aimed || st.ammo == 0) break@cycle
                        val cost = if (w.mode == FireMode.BEAM) w.fluxCost * budget else w.fluxCost
                        if (!gate.affords(cost)) break@cycle
                        if (w.mode != FireMode.BEAM) gate.spend(cost)
                        if (w.chargeUp > 0f) {
                            st.phase = WeaponPhase.CHARGING; st.timeLeft = w.chargeUp
                        } else startFiring(w, st)
                    }
                    WeaponPhase.CHARGING -> {
                        if (silenced) { st.phase = WeaponPhase.READY; break@cycle }
                        if (st.timeLeft > budget + EPS) { st.timeLeft -= budget; break@cycle }
                        budget = max(0f, budget - st.timeLeft)
                        startFiring(w, st)
                    }
                    WeaponPhase.FIRING -> {
                        if (silenced) { endBurst(w, st); break@cycle }
                        when (w.mode) {
                            FireMode.BEAM -> {
                                val cost = w.fluxCost * budget
                                if (!canEngage || !gate.affords(cost)) {
                                    st.phase = WeaponPhase.READY; break@cycle
                                }
                                gate.spend(cost)
                                sink!!.beamTick(w, st, shooter, time, mx, my, dist, aimed, vsSize)
                                break@cycle
                            }
                            FireMode.BURST_BEAM -> {
                                if (sink != null) sink.beamTick(w, st, shooter, time, mx, my, dist, aimed && inRange, vsSize)
                                if (st.timeLeft > budget + EPS) { st.timeLeft -= budget; break@cycle }
                                budget = max(0f, budget - st.timeLeft)
                                endBurst(w, st)
                            }
                            else -> {
                                while (st.shotsLeft > 0 && st.timeLeft <= budget + EPS) {
                                    budget = max(0f, budget - st.timeLeft)
                                    if (st.ammo != 0) {
                                        // the shot leaves part way through the step
                                        val shotTime = time - budget / shooter.timeMult
                                        sink?.shoot(w, shooter, timeline, i, shotTime, mx, my, sf + st.angle, dist,
                                            aimed && inRange, vsSize)
                                        if (st.ammo > 0) st.ammo--
                                    }
                                    st.shotsLeft--
                                    st.timeLeft = w.burstDelay.coerceAtLeast(1e-3f)
                                }
                                if (st.shotsLeft > 0) { st.timeLeft -= budget; break@cycle }
                                endBurst(w, st)
                            }
                        }
                    }
                    WeaponPhase.COOLDOWN -> {
                        if (st.timeLeft > budget + EPS) { st.timeLeft -= budget; break@cycle }
                        budget = max(0f, budget - st.timeLeft)
                        st.phase = WeaponPhase.READY
                    }
                }
            }
        }
        for (s in touched) s.flushDps(i)
        touched.clear()
    }

    private fun startFiring(w: SimWeapon, st: WeaponState) {
        st.phase = WeaponPhase.FIRING
        st.shotsLeft = w.burstSize.coerceAtLeast(1)
        st.timeLeft = if (w.mode == FireMode.BURST_BEAM) w.burstDuration.coerceAtLeast(DT) else 0f
        st.beamTick = 0f
    }

    private fun endBurst(w: SimWeapon, st: WeaponState) {
        st.phase = WeaponPhase.COOLDOWN
        st.timeLeft = w.cooldown
        st.shotsLeft = 0
    }

    private fun regenAmmo(w: SimWeapon, st: WeaponState) {
        if (st.ammo < 0 || w.ammoPerSecond <= 0f || st.ammo >= w.maxAmmo) return
        st.ammoAccrued += w.ammoPerSecond * dtShip
        val chunk = if (w.reloadSize > 0f) w.reloadSize else 1f
        while (st.ammoAccrued >= chunk && st.ammo < w.maxAmmo) {
            st.ammoAccrued -= chunk
            st.ammo = min(w.maxAmmo, st.ammo + chunk.toInt().coerceAtLeast(1))
        }
    }

    private companion object {
        /** Float slack for phase ends, so a 1 s cooldown is 20 steps and not 19 or 21. */
        const val EPS = 1e-4f
    }
}
