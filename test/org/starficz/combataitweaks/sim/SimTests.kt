package org.starficz.combataitweaks.sim

import kotlin.math.abs
import kotlin.math.min

// Plain-JVM unit tests for the pure `sim` package. Run with test/run-unit-tests.ps1 (no game needed).

internal var passed = 0
internal var failed = 0

internal fun check(ok: Boolean, name: String, detail: () -> String = { "" }) {
    if (ok) passed++ else failed++
    println((if (ok) "ok    " else "FAIL  ") + name + (if (!ok) "  -- " + detail() else ""))
}

internal fun near(actual: Float, expected: Float, tolerance: Float, name: String) =
    check(abs(actual - expected) <= tolerance, name) { "expected $expected +- $tolerance, got $actual" }

internal fun ship(
    id: String, owner: Int = 0, x: Float = 0f, y: Float = 0f, vx: Float = 0f, vy: Float = 0f, facing: Float = 0f,
    hull: HullClass = HullClass.FRIGATE, radius: Float = 50f,
    maxSpeed: Float = 150f, accel: Float = 100f, decel: Float = 50f, turnAccel: Float = 60f, maxTurn: Float = 30f,
    reach: Float = 700f, engagement: Float = 700f, bestOffset: Float = 0f, system: SystemInfo? = null,
    forward: Boolean = false, back: Boolean = false, left: Boolean = false, right: Boolean = false,
    silence: Float = 0f, parent: String? = null, offDist: Float = 0f, offAngle: Float = 0f,
    carrier: String? = null, wingRange: Float = 0f, maneuverTarget: String? = null, maneuverRange: Float? = null,
    backOff: BackOffTraits? = null, gunReach: Float = reach,
) = SimShip(
    id, owner, hull, radius, x, y, vx, vy, facing, 0f,
    maxSpeed, accel, decel, turnAccel, maxTurn, 1f, maxSpeed,
    forward, back, left, right,
    silence, 0f, false, system,
    reach, engagement, bestOffset, maneuverTarget, maneuverRange,
    carrier, wingRange, parent, offDist, offAngle, 0f,
    backOff = backOff, gunReach = gunReach,
)

private fun weapon(
    group: Int = 0, size: WeaponSize = WeaponSize.MEDIUM, kind: WeaponKind = WeaponKind.BALLISTIC,
    hardpoint: Boolean = false, angle: Float = 0f, arc: Float = 120f, range: Float = 700f, forward: Float = 50f,
    lowAmmo: Boolean = false, pd: Boolean = false, pdOnly: Boolean = false, dps: Float = 100f,
) = WeaponInfo(group, size, kind, hardpoint, angle, arc, range, forward, true, lowAmmo, pd, pdOnly, dps)

fun main() {
    kinematics()
    engagement()
    pursuit()
    world()
    damage()
    targeting()
    ventRule()
    moveRule()
    moveBranches()
    println()
    println("UNIT TESTS: $passed ok, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}

private fun kinematics() {
    val frigate = EngineLimits(maxSpeed = 300f, acceleration = 100f, deceleration = 50f, strafeFactor = 1f,
        turnAcceleration = 60f, maxTurnRate = 30f)
    near(Motion.maxAccelToward(frigate, 0f, 1f, 0f), 100f, 0.01f, "kinematics: forward thrust uses acceleration")
    near(Motion.maxAccelToward(frigate, 0f, -1f, 0f), 50f, 0.01f, "kinematics: backward thrust uses deceleration")
    val cruiser = EngineLimits(300f, 100f, 50f, HullClass.CRUISER.strafeFactor, 60f, 30f)
    near(Motion.maxAccelToward(cruiser, 0f, 0f, 1f), 50f, 0.01f, "kinematics: cruiser strafes at half acceleration")
    // 45 degrees on a frigate: both axes at their limit of 100, so the combined vector is 100 * sqrt(2)
    near(Motion.maxAccelToward(frigate, 0f, 0.70710677f, 0.70710677f), 141.42f, 0.5f, "kinematics: diagonal thrust saturates both axes")
    // facing matters: same world direction, ship turned around, is backwards thrust
    near(Motion.maxAccelToward(frigate, 180f, 1f, 0f), 50f, 0.01f, "kinematics: world direction is resolved against facing")

    val out = FloatArray(2)
    val body = Body(0f, 0f, 0f, 0f, 0f, 0f)
    repeat(20) {
        Motion.inputAccel(frigate, body.facing, true, false, false, false, out)
        Motion.step(body, frigate, out[0], out[1], false, DT)
    }
    near(body.vx, 100f, 1f, "kinematics: 1 s of forward input reaches acceleration x 1 s")

    val back = Body(0f, 0f, 0f, 0f, 0f, 0f)
    repeat(20) {
        Motion.inputAccel(frigate, back.facing, false, true, false, false, out)
        Motion.step(back, frigate, out[0], out[1], false, DT)
    }
    near(back.vx, -50f, 0.5f, "kinematics: 1 s of backward input reaches deceleration x 1 s")

    val fast = Body(0f, 0f, 400f, 0f, 0f, 0f)
    repeat(5) { Motion.step(fast, frigate, 0f, 0f, false, DT) }
    near(fast.speed, 350f, 0.5f, "kinematics: above max speed, speed bleeds at 2 x max(accel, decel)")
    repeat(40) { Motion.step(fast, frigate, 0f, 0f, false, DT) }
    near(fast.speed, 300f, 0.5f, "kinematics: bleed stops at max speed")

    val braking = Body(0f, 0f, 100f, 0f, 0f, 0f)
    repeat(20) { Motion.step(braking, frigate, 0f, 0f, true, DT) }
    near(braking.speed, 50f, 0.5f, "kinematics: braking removes deceleration x 1 s")

    val turner = Body(0f, 0f, 0f, 0f, 0f, 0f)
    repeat(400) { Motion.turnToward(turner, frigate, 90f, DT) }
    check(abs(shortestRotation(turner.facing, 90f)) < 1f && abs(turner.angularVelocity) < 1f,
        "kinematics: turn settles within 1 degree") { "facing ${turner.facing}, w ${turner.angularVelocity}" }
    var maxOvershoot = 0f
    val turner2 = Body(0f, 0f, 0f, 0f, 0f, 0f)
    repeat(400) { Motion.turnToward(turner2, frigate, 90f, DT); maxOvershoot = maxOf(maxOvershoot, shortestRotation(90f, turner2.facing)) }
    check(maxOvershoot < 3f, "kinematics: turn overshoots by under 3 degrees") { "overshoot $maxOvershoot" }
}

private fun engagement() {
    // forward guns: radius 100, mounted 50 forward -> forward offset 0.6*100 - 50 = 10
    val guns = listOf(weapon(range = 700f), weapon(range = 900f))
    near(Engagement.baseRange(guns, 100f), 690f, 0.01f, "engagement: shortest weapon of the group, less the mount offset")

    // a heavier missile group is primary, but vanilla caps the range at the best gun group's
    val mixed = listOf(
        weapon(group = 0, size = WeaponSize.LARGE, kind = WeaponKind.MISSILE, hardpoint = true, arc = 10f, range = 2500f),
        weapon(group = 1, size = WeaponSize.SMALL, range = 800f, forward = 60f),
    )
    near(Engagement.baseRange(mixed, 100f), 800f, 0.01f, "engagement: missile primary group is capped by gun range")

    val missilesOnly = listOf(weapon(kind = WeaponKind.MISSILE, range = 2500f, arc = 10f, hardpoint = true))
    near(Engagement.baseRange(missilesOnly, 100f), 2000f, 0.01f, "engagement: no gun group falls back to 2000")

    // broadside guns don't face forward: no mount offset
    val broadside = listOf(weapon(angle = 90f, arc = 60f, range = 900f), weapon(angle = 90f, arc = 60f, range = 1000f))
    near(Engagement.baseRange(broadside, 100f), 900f, 0.01f, "engagement: broadside group range without forward offset")

    // weights: large hardpoint ballistic = 7 * 2 + 1 = 15; small PD turret energy = 1 * 0.25
    near(Engagement.weight(weapon(size = WeaponSize.LARGE, hardpoint = true, arc = 10f)), 15f, 0.01f, "engagement: large hardpoint ballistic weight")
    near(Engagement.weight(weapon(size = WeaponSize.SMALL, kind = WeaponKind.ENERGY, pd = true)), 0.25f, 0.01f, "engagement: small PD weight")
    // a rear turret (arc 90 centred on 180) is 135 degrees off forward at its nearest edge
    near(Engagement.weight(weapon(size = WeaponSize.MEDIUM, kind = WeaponKind.ENERGY, angle = 180f, arc = 90f)), 3f * 45f / 180f, 0.01f,
        "engagement: off-forward weapons weigh less")

    // the heavier group wins primary even if it's shorter
    val twoGroups = listOf(
        weapon(group = 0, size = WeaponSize.LARGE, range = 600f),
        weapon(group = 1, size = WeaponSize.SMALL, range = 1200f),
    )
    near(Engagement.baseRange(twoGroups, 100f), 590f, 0.01f, "engagement: highest-scoring group sets the range")

    near(Engagement.weaponReach(listOf(weapon(range = 700f), weapon(range = 1200f, pdOnly = true))), 700f, 0.01f,
        "engagement: PD-only weapons don't count for reach")

    near(Engagement.bestFacingOffset(broadside), -90f, 0.01f, "facing: broadside guns on the left want the target on the left")
    near(Engagement.bestFacingOffset(guns), 0f, 0.01f, "facing: forward guns face the target")
}

private fun pursuit() {
    // commit timing, the target standing still: gap = 2000 - (700 + 50 + 50) = 1200 at 150 -> 8 s
    val target = ship("p", owner = 0, maxSpeed = 100f)
    val enemy = ship("e", owner = 1, x = 2000f, maxSpeed = 150f)
    near(Pursuit.timeToReach(enemy, target, 50f, 10f), 8f, 0.01f, "pursuit: time to reach a ship standing still")
    val burner = ship("b", owner = 1, x = 2000f, maxSpeed = 150f,
        system = SystemInfo(0f, 2f, 10f, 1000f, 0f))
    near(Pursuit.timeToReach(burner, target, 50f, 10f), 200f / 150f, 0.01f, "pursuit: burst potential shortens the gap")
    val params = SimParams(steps = 200)
    near(params.commitWindow, 10f, 1e-3f, "pursuit: the commit window is the horizon")
    check(Pursuit.commits(enemy, target, null, 50f, params), "pursuit: an enemy that could reach within the horizon commits")
    val farEnemy = ship("e3", owner = 1, x = 3000f, maxSpeed = 150f)
    check(!Pursuit.commits(farEnemy, target, null, 50f, params), "pursuit: an enemy that can't reach within the horizon doesn't")
    check(Pursuit.commits(burner, target, null, 50f, params), "pursuit: a burn-drive enemy within reach commits")
    check(!Pursuit.commits(ship("f", owner = 0, x = 500f), target, null, 50f, params), "pursuit: friendly ships never commit")

    // hold distance: engagement 700 + radii 100 - press-in 200 = 600; live maneuver range wins when it targets us
    near(Pursuit.holdDistance(enemy, target, 50f), 600f, 0.01f, "pursuit: hold distance from engagement range")
    val engaged = ship("e2", owner = 1, maneuverTarget = "p", maneuverRange = 950f)
    near(Pursuit.holdDistance(engaged, target, 50f), 950f, 0.01f, "pursuit: live maneuver range is used when it already targets us")

    // a pursuer settles at its hold distance on a stationary target
    run {
        val pursuer = Pursuit.Pursuer(ship("e", owner = 1, x = 3000f, facing = 180f, maxSpeed = 150f, accel = 100f, decel = 80f), 800f)
        repeat(600) { pursuer.step(it * DT, 0f, 0f, 0f, 0f) }
        val d = length(pursuer.body.x, pursuer.body.y)
        check(abs(d - 800f) <= 50f && pursuer.body.speed < 20f, "pursuit: settles within 50 of its hold distance") { "distance $d, speed ${pursuer.body.speed}" }
    }

    // a slower pursuer never catches a faster ship running away
    run {
        val runner = Body(0f, 0f, 0f, 0f, 180f, 0f)
        val runnerLimits = EngineLimits(200f, 100f, 100f, 1f, 60f, 30f)
        val pursuer = Pursuit.Pursuer(ship("e", owner = 1, x = 1500f, facing = 180f, maxSpeed = 150f, accel = 100f, decel = 80f), 600f)
        var minDist = Float.MAX_VALUE
        repeat(400) {
            val a = Motion.maxAccelToward(runnerLimits, runner.facing, -1f, 0f)
            Motion.step(runner, runnerLimits, -a, 0f, false, DT)
            pursuer.step(it * DT, runner.x, runner.y, runner.vx, runner.vy)
            minDist = min(minDist, length(pursuer.body.x - runner.x, pursuer.body.y - runner.y))
        }
        check(minDist > 1000f, "pursuit: a slower enemy never closes on a faster one running") { "closest $minDist" }
    }

    // a burst system closes the gap by about its burst distance in its active time
    run {
        val slow = ship("e", owner = 1, x = 3000f, facing = 180f, maxSpeed = 50f, accel = 50f, decel = 50f,
            system = SystemInfo(0f, 1f, 20f, 1000f, 0f))
        val pursuer = Pursuit.Pursuer(slow, 600f)
        repeat(30) { pursuer.step(it * DT, 0f, 0f, 0f, 0f) }
        val closed = 3000f - length(pursuer.body.x, pursuer.body.y)
        check(closed in 950f..1150f, "pursuit: burst system closes about its burst distance") { "closed $closed" }
    }
}

private fun world() {
    val params = SimParams(steps = 200)
    val p = ship("p", owner = 0, maxSpeed = 120f)
    // gap = 900 - (700 + 50 + 50) = 100 at a closing speed of 30 -> about 3.3 s, inside the 6 s commit window
    val near = ship("near", owner = 1, x = 900f, facing = 180f, maxSpeed = 150f)
    val far = ship("far", owner = 1, x = 9000f, maxSpeed = 100f)
    val friend = ship("friend", owner = 0, x = -500f)
    val module = ship("mod", owner = 1, parent = "near", offDist = 40f, offAngle = 90f)
    val world = World(listOf(p, near, far, friend, module), params)

    val hold = world.branch("p", OwnPlan.Hold())
    check(hold.committed.keys == setOf("near"), "world: only the reachable enemy commits") { "committed ${hold.committed.keys}" }
    check(hold.timelines["far"] === world.base["far"], "world: non-committed ships share base timelines")
    check(hold.timelines["near"] !== world.base["near"], "world: committed enemies are re-simulated")
    val nearTl = hold.timelines["near"]!!
    val end = params.steps - 1
    near(length(nearTl.x[end] - hold.timelines["p"]!!.x[end], nearTl.y[end] - hold.timelines["p"]!!.y[end]),
        hold.committed["near"]!!, 60f, "world: the committed enemy ends near its hold distance")
    val modTl = hold.timelines["mod"]!!
    near(length(modTl.x[end] - nearTl.x[end], modTl.y[end] - nearTl.y[end]), 40f, 0.5f, "world: modules ride on their re-simulated parent")

    // backing off from the enemy keeps more distance than holding
    val away = world.branch("p", OwnPlan.Thrust(-1f, 0f))
    val pAway = away.timelines["p"]!!; val nAway = away.timelines["near"]!!
    val holdDist = length(nearTl.x[end] - hold.timelines["p"]!!.x[end], nearTl.y[end] - hold.timelines["p"]!!.y[end])
    val awayDist = length(nAway.x[end] - pAway.x[end], nAway.y[end] - pAway.y[end])
    check(awayDist >= holdDist - 1f, "world: thrusting away never ends closer than holding") { "away $awayDist, hold $holdDist" }
}

/** Commit by vanilla's target choice (nearest enemy ship): only enemies that may pick P come for it. */
private fun targeting() {
    val p = ship("P", owner = 0)
    val e = ship("E", owner = 1, x = 1000f, facing = 180f)
    fun may(vararg others: SimShip, enemy: SimShip = e) =
        World(listOf(p, enemy) + others, SimParams(200)).mayTarget(enemy, p)
    check(may(), "targeting: P is the only enemy ship -> it may be the target")
    check(!may(ship("N", owner = 0, x = 1000f, y = 300f)), "targeting: a much nearer friendly takes it")
    check(may(ship("N", owner = 0, x = 1000f, y = 700f)), "targeting: one nearly as far -> P still may be")
    check(may(ship("N", owner = 0, x = 1000f, y = 300f), enemy = ship("E", owner = 1, x = 1000f, maneuverTarget = "P")),
        "targeting: its live target is P")
    val committed = World(listOf(p, e, ship("N", owner = 0, x = 1000f, y = 300f)), SimParams(200)).committedTo(p)
    check(committed.isEmpty(), "targeting: an enemy busy with a nearer ship doesn't commit to P") {
        "${committed.keys.map { it.id }}"
    }
}
