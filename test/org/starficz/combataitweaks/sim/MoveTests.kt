package org.starficz.combataitweaks.sim

import org.starficz.combataitweaks.decide.MoveDecider
import org.starficz.combataitweaks.decide.MoveInputs
import org.starficz.combataitweaks.decide.MoveKind
import org.starficz.combataitweaks.decide.MoveOption
import org.starficz.combataitweaks.decide.MoveRule
import org.starficz.combataitweaks.decide.MoveState
import org.starficz.combataitweaks.decide.Trade

// Unit tests for the movement decision (plan §8.2) and the branches it reads (plan §7).

private fun opt(
    name: String, hull: Float = 0f, armor: Float = 0f, overloadsIn: Float = -1f, flux: Float = 0.3f,
    hard: Float = 0.1f, trade: Trade? = null,
): MoveOption {
    val kind = when {
        name.startsWith("ADVANCE") -> MoveKind.ADVANCE
        name.startsWith("BACK_OFF") -> MoveKind.BACK_OFF
        else -> MoveKind.HOLD
    }
    return MoveOption(name, kind, name.endsWith("+SYSTEM"), hull, armor, overloadsIn, flux, hard, escape = name.contains("~"),
        trade = trade, standoff = name == "STANDOFF")
}

private fun moveInputs(
    vararg options: MoveOption, venting: Boolean = false, ventOption: String? = null, flux: Float = 0.3f,
    previous: MoveState? = null, gap: Float = 0f,
) = MoveInputs(
    options = options.toList(), venting = venting, ventOption = ventOption, fluxLevel = flux, hullLimit = 100f,
    previous = previous, advanceGap = gap,
    forceAdvanceIfFluxBelowLevel = 0.7f, backoffIfFluxAboveLevel = 0.85f, backoffIfHardfluxAboveLevel = 0.75f,
    reengageIfFluxDropsBelowLevel = 0.4f, maxFluxLevelRaiseToHarass = 0.3f,
)

/** The usual set: HOLD (keep still), HOLD~ / ADVANCE~ (do it, then escape), BACK_OFF. */
private fun set(
    hold: MoveOption = opt("HOLD"), holdEsc: MoveOption = opt("HOLD~"), advEsc: MoveOption = opt("ADVANCE~"),
    backOffs: List<MoveOption> = listOf(opt("BACK_OFF")),
) = arrayOf(hold, holdEsc, advEsc, *backOffs.toTypedArray())

internal fun moveRule() {
    val calm = MoveRule.decide(moveInputs(*set()))
    check(calm.state == MoveState.ADVANCE && calm.option?.name == "ADVANCE~", "move: nothing threatening -> ADVANCE") { calm.reason }

    // the key case: keeping still would overload late, but the ship can still get out after advancing a moment
    val late = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", overloadsIn = 8f))))
    check(late.state == MoveState.ADVANCE, "move: a late threat it can still escape doesn't stop ADVANCE") { late.reason }

    val noEscapeAdvancing = MoveRule.decide(moveInputs(*set(advEsc = opt("ADVANCE~", overloadsIn = 5f))))
    check(noEscapeAdvancing.state == MoveState.HOLD && noEscapeAdvancing.option?.name == "HOLD~",
        "move: advancing would pass the point of no return -> HOLD") { noEscapeAdvancing.reason }

    val noEscape = MoveRule.decide(moveInputs(*set(holdEsc = opt("HOLD~", overloadsIn = 4f), advEsc = opt("ADVANCE~", overloadsIn = 3f))))
    check(noEscape.state == MoveState.BACK_OFF && noEscape.option?.name == "BACK_OFF",
        "move: even holding a moment loses the escape -> BACK_OFF") { noEscape.reason }
    check(noEscape.reason.contains("HOLD~ ovl 4.0s"), "move: the reason line lists each option's numbers") { noEscape.reason }
    // without the trace the rule skips the numbers, and decides the same
    org.starficz.combataitweaks.decide.Explain.enabled = false
    val terse = MoveRule.decide(moveInputs(*set(holdEsc = opt("HOLD~", overloadsIn = 4f), advEsc = opt("ADVANCE~", overloadsIn = 3f))))
    org.starficz.combataitweaks.decide.Explain.enabled = true
    check(terse.state == noEscape.state && terse.option?.name == noEscape.option?.name && terse.reason == "BACK_OFF BACK_OFF",
        "move: without explanations the same decision, a short reason") { terse.reason }

    // flux limits read keeping still (v1's hold-branch flux ratio)
    val warm = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", flux = 0.8f))))
    check(warm.state == MoveState.HOLD, "move: keeping still ends above forceAdvance flux -> HOLD, not ADVANCE") { warm.reason }
    val hotAdvance = MoveRule.decide(moveInputs(*set(), opt("ADVANCE", flux = 0.8f)))
    check(hotAdvance.state == MoveState.HOLD, "move: closing in and staying ends above forceAdvance flux -> HOLD") { hotAdvance.reason }
    val hardFlux = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", flux = 0.8f, hard = 0.8f))))
    check(hardFlux.state == MoveState.BACK_OFF, "move: hard flux above its level -> BACK_OFF") { hardFlux.reason }

    // nothing ok (it can't get clear): stand when that clearly costs less than running, else run
    val outrun = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", hull = 300f), holdEsc = opt("HOLD~", hull = 500f), advEsc = opt("ADVANCE~", hull = 900f), backOffs = listOf(opt("BACK_OFF", hull = 600f)))))
    check(outrun.state == MoveState.DISENGAGE,
        "move: nothing ok -> runs even when standing would cost less (player ships would rather not die)") { outrun.reason }
    val trapped = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", hull = 900f), holdEsc = opt("HOLD~", hull = 900f), advEsc = opt("ADVANCE~", hull = 2000f), backOffs = listOf(opt("BACK_OFF", hull = 300f), opt("BACK_OFF+SYSTEM", hull = 150f)))))
    check(trapped.state == MoveState.DISENGAGE && trapped.option?.name == "BACK_OFF+SYSTEM",
        "move: nothing ok, running costs less -> DISENGAGE on the cheapest back-off") { trapped.reason }

    val sticky = MoveRule.decide(moveInputs(*set(), flux = 0.5f, previous = MoveState.DISENGAGE))
    check(sticky.state == MoveState.DISENGAGE, "move: DISENGAGE holds until flux is under the reengage level") { sticky.reason }
    val reengage = MoveRule.decide(moveInputs(*set(), flux = 0.3f, previous = MoveState.DISENGAGE))
    check(reengage.state == MoveState.ADVANCE, "move: DISENGAGE ends once flux drops under the reengage level") { reengage.reason }

    // the system is kept for when it matters: used only when it clearly saves something
    val stuck = opt("HOLD~", overloadsIn = 2f)
    val stuckAdv = opt("ADVANCE~", overloadsIn = 2f)
    val small = MoveRule.decide(moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF", armor = 60f), opt("BACK_OFF+SYSTEM", armor = 30f)))))
    check(small.option?.name == "BACK_OFF", "move: a system back-off saving under 50 isn't worth the system") { small.reason }
    val big = MoveRule.decide(moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF", armor = 400f), opt("BACK_OFF+SYSTEM", armor = 50f)))))
    check(big.option?.name == "BACK_OFF+SYSTEM", "move: a system back-off that saves a lot uses the system") { big.reason }
    val onlySys = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", hull = 900f), holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF", overloadsIn = 5f), opt("BACK_OFF+SYSTEM")))))
    check(onlySys.state == MoveState.BACK_OFF && onlySys.option?.name == "BACK_OFF+SYSTEM",
        "move: only the system back-off is ok -> uses it") { onlySys.reason }

    // 4b directions: the cheapest wins; on a tie vanilla's own back-off (flown by flags), then the lowest end flux
    val tie = MoveRule.decide(moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF@+35", flux = 0.2f), opt("BACK_OFF", flux = 0.4f), opt("BACK_OFF@-35", flux = 0.1f)))))
    check(tie.option?.name == "BACK_OFF", "move: equal-cost back-offs -> vanilla's direction") { tie.reason }
    val ally = MoveRule.decide(moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF"),
        opt("BACK_OFF@ally", flux = 0.5f), opt("BACK_OFF@0", flux = 0.1f)))))
    check(ally.option?.name == "BACK_OFF@ally", "move: equal-cost back-offs -> toward the friendly ships first") { ally.reason }
    val allyCostly = MoveRule.decide(moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF"),
        opt("BACK_OFF@ally", armor = 80f)))))
    check(allyCostly.option?.name == "BACK_OFF", "move: toward the friendly ships only when it costs no more") { allyCostly.reason }
    val flank = MoveRule.decide(moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF", armor = 200f), opt("BACK_OFF@+70", armor = 20f), opt("BACK_OFF@-70", armor = 80f)))))
    check(flank.state == MoveState.BACK_OFF && flank.option?.name == "BACK_OFF@+70",
        "move: a cheaper back-off direction wins") { flank.reason }
    val onlyFlank = MoveRule.decide(moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv, backOffs = listOf(opt("BACK_OFF", overloadsIn = 6f), opt("BACK_OFF@-35")))))
    check(onlyFlank.state == MoveState.BACK_OFF && onlyFlank.option?.name == "BACK_OFF@-35",
        "move: straight back overloads, a side direction doesn't -> BACK_OFF that way") { onlyFlank.reason }

    val far = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", flux = 0.2f)), opt("ADVANCE~+SYSTEM"), gap = 800f))
    check(far.option?.name == "ADVANCE~+SYSTEM", "move: far from range at low flux -> the system closes the gap") { far.reason }
    val near = MoveRule.decide(moveInputs(*set(hold = opt("HOLD", flux = 0.2f)), opt("ADVANCE~+SYSTEM"), gap = 100f))
    check(near.option?.name == "ADVANCE~", "move: already near range -> no system to advance") { near.reason }

    // the standoff (plan §13.4): HOLD is judged by it and flies it; keeping still stays for the vent only
    val stillOverloads = opt("HOLD", overloadsIn = 3f)
    val standoff = MoveRule.decide(moveInputs(opt("STANDOFF"), stillOverloads, opt("ADVANCE~", overloadsIn = 4f), opt("BACK_OFF")))
    check(standoff.state == MoveState.HOLD && standoff.option?.name == "STANDOFF",
        "move: the standoff is ok while keeping still overloads -> HOLD on the standoff") { standoff.reason }
    val standoffHot = MoveRule.decide(moveInputs(opt("STANDOFF", flux = 0.9f), opt("HOLD"), opt("ADVANCE~", overloadsIn = 4f), opt("BACK_OFF")))
    check(standoffHot.state == MoveState.BACK_OFF, "move: the standoff's own end flux is its flux limit") { standoffHot.reason }
    val standoffCaught = MoveRule.decide(moveInputs(opt("STANDOFF", overloadsIn = 6f), opt("HOLD~"), opt("HOLD"),
        opt("ADVANCE~", overloadsIn = 4f), opt("BACK_OFF")))
    check(standoffCaught.state == MoveState.BACK_OFF, "move: a standoff that gets caught isn't ok, whatever HOLD~ says") { standoffCaught.reason }
    val advanceFlux = MoveRule.decide(moveInputs(opt("STANDOFF", flux = 0.8f), opt("HOLD", flux = 0.3f), opt("ADVANCE~")))
    check(advanceFlux.state == MoveState.ADVANCE, "move: the advance flux fallback is keeping still, not the standoff") { advanceFlux.reason }

    // kiting: the standoff deals damage for free, so closing in must beat its trade
    fun kiteCase(standoffTrade: Trade, advanceTrade: Trade) = MoveRule.decide(moveInputs(
        opt("STANDOFF", trade = standoffTrade), opt("HOLD"), opt("ADVANCE~"), opt("ADVANCE", trade = advanceTrade), opt("BACK_OFF")))
    val kite = kiteCase(Trade(0.10f, 0f, 0.30f, 0.10f), Trade(0.12f, 0.05f, 0.35f, 0.30f))
    check(kite.state == MoveState.HOLD && kite.option?.name == "STANDOFF",
        "move: kiting for free beats advancing into the enemy's guns") { kite.reason }
    val kill = kiteCase(Trade(0.10f, 0f, 0.30f, 0.10f), Trade(0.40f, 0.02f, 0.50f, 0.20f))
    check(kill.state == MoveState.ADVANCE, "move: advancing that deals much more net damage still advances") { kill.reason }
    val same = kiteCase(Trade(0.82f, 0f, 0f, 0f), Trade(0.82f, 0f, 0f, 0f))
    check(same.state == MoveState.ADVANCE, "move: nothing reaches it closer in either -> ADVANCE, not a standoff tie") { same.reason }
    val outranged = kiteCase(Trade(0f, 0f, 0f, -0.05f), Trade(0f, 0f, 0.20f, 0.20f))
    check(outranged.state == MoveState.ADVANCE, "move: a standoff that deals nothing doesn't stop an even flux trade") { outranged.reason }
    val pelted = kiteCase(Trade(0.10f, 0.05f, 0.30f, 0.10f), Trade(0.10f, 0.08f, 0.30f, 0.30f))
    check(pelted.state == MoveState.ADVANCE, "move: a standoff that takes damage isn't kiting (the trade rule decides)") { pelted.reason }

    val venting = MoveRule.decide(moveInputs(*set(), venting = true, ventOption = "HOLD"))
    check(venting.state == MoveState.VENTING && venting.option?.name == "HOLD", "move: venting drives the vent's option") { venting.reason }

    // hysteresis: switching needs 0.5 s of the new state winning, except into DISENGAGE or VENTING
    val gate = MoveDecider()
    val advance = moveInputs(*set())
    val backOff = moveInputs(*set(holdEsc = stuck, advEsc = stuckAdv))
    check(gate.decide(0f, advance).state == MoveState.ADVANCE, "move gate: the first decision applies at once")
    check(gate.decide(0.1f, backOff).state == MoveState.ADVANCE, "move gate: a new state doesn't switch at once")
    check(gate.decide(0.3f, advance).state == MoveState.ADVANCE, "move gate: the old state winning again resets the wait")
    gate.decide(0.4f, backOff)
    check(gate.decide(0.8f, backOff).state == MoveState.ADVANCE, "move gate: still waiting 0.4 s after the reset")
    check(gate.decide(0.95f, backOff).state == MoveState.BACK_OFF, "move gate: switches after 0.5 s of winning")
    val trap = moveInputs(*set(hold = opt("HOLD", hull = 900f), holdEsc = opt("HOLD~", hull = 900f), advEsc = opt("ADVANCE~", hull = 900f), backOffs = listOf(opt("BACK_OFF", hull = 900f))))
    check(gate.decide(1.0f, trap).state == MoveState.DISENGAGE, "move gate: DISENGAGE applies at once")
}

internal fun moveBranches() {
    // ADVANCE closes to the ship's own engagement range (pressed in by 200) of its target and holds there
    val p = ship("p", x = 0f, engagement = 700f, radius = 50f, maxSpeed = 200f, accel = 200f, decel = 200f)
    val target = ship("t", owner = 1, x = 2000f, reach = 0f, radius = 50f, maxSpeed = 0f)
    val world = World(listOf(p, target), SimParams(200))
    val adv = world.branch("p", OwnPlan.Approach("t"))
    near(adv.approachRange, 600f, 0.1f, "branch: ADVANCE range = engagement + radii - 200")
    val tl = adv.timelines["p"]!!
    near(2000f - tl.x[tl.steps - 1], 600f, 60f, "branch: ADVANCE settles at its range from the target")

    // BACK_OFF moves straight away from the ship it backs off from
    val back = world.branch("p", OwnPlan.BackAway("t")).timelines["p"]!!
    check(back.x[back.steps - 1] < -500f && kotlin.math.abs(back.y[back.steps - 1]) < 1f,
        "branch: BACK_OFF moves away from the ship it backs off from") { "end ${back.x[back.steps - 1]}, ${back.y[back.steps - 1]}" }

    // then-escape: advance toward the target for 1 s, then thrust away from it
    val thenEscape = world.branch("p", OwnPlan.Then(OwnPlan.Approach("t"), 1f, OwnPlan.BackAway("t"))).timelines["p"]!!
    val at1s = thenEscape.x[19]
    check(at1s > 50f && thenEscape.x[thenEscape.steps - 1] < at1s - 300f,
        "branch: then-escape advances first, then backs away") { "x at 1 s $at1s, end ${thenEscape.x[thenEscape.steps - 1]}" }

    standoffBranches()

    // clear range: reach 700 + radii (50 + 50) + the 10 s horizon x (300 - 150) faster
    val chaser = ship("c", owner = 1, x = 1200f, reach = 700f, radius = 50f, maxSpeed = 300f)
    val slow = ship("s", x = 0f, maxSpeed = 150f, radius = 50f)
    val clear = World(listOf(slow, chaser), SimParams(200)).branch("s", OwnPlan.Hold())
    near(clear.clearRange, 700f + 100f + 10f * 150f, 1f, "branch: clear range = reach + radii + closing over the horizon")
}

/** The standoff path (plan §13.4): out of every hostile's gun reach, at the ship's own range when that is farther. */
private fun standoffBranches() {
    fun minDistance(a: Timeline, b: Timeline) = (0 until a.steps).minOf { length(a.x[it] - b.x[it], a.y[it] - b.y[it]) }

    // a slow enemy with short guns closes in: the ship keeps its own range (700 + radii - 200 = 600) while it is
    // farther than the enemy's standoff (300 + radii + 100 margin = 500), backing off as it comes
    val p = ship("p", x = 0f, engagement = 700f, reach = 700f, maxSpeed = 200f, accel = 200f, decel = 200f)
    val slow = ship("e", owner = 1, x = 900f, facing = 180f, reach = 300f, engagement = 300f, maxSpeed = 80f, accel = 80f, decel = 80f)
    val kite = World(listOf(p, slow), SimParams(200)).branch("p", OwnPlan.Standoff("e"))
    check("e" in kite.committed, "standoff: the slow enemy commits and closes in")
    near(kite.standoffRange, 600f, 0.1f, "standoff: outranging it, the ship's own range")
    val kp = kite.timelines["p"]!!; val ke = kite.timelines["e"]!!
    check(minDistance(kp, ke) >= 500f, "standoff: never inside the slow enemy's reach") { "closest ${minDistance(kp, ke)}" }
    // it closes from 900 to its 600 first, then gives ground as the enemy comes on
    val peak = kp.x.max()
    check(peak > 200f && kp.x[kp.steps - 1] < peak - 150f, "standoff: closes in, then backs off as the enemy closes") {
        "peak x $peak, end x ${kp.x[kp.steps - 1]}"
    }

    // outranged: it waits at the enemy's standoff (900 + 100 + 100 = 1100), closing in from far away
    val longGuns = ship("e", owner = 1, x = 3000f, facing = 180f, reach = 900f, engagement = 900f, maxSpeed = 0f)
    val wait = World(listOf(p, longGuns), SimParams(200)).branch("p", OwnPlan.Standoff("e"))
    near(wait.standoffRange, 1100f, 0.1f, "standoff: outranged, just outside the enemy's gun reach")
    val wp = wait.timelines["p"]!!
    near(3000f - wp.x[wp.steps - 1], 1100f, 60f, "standoff: closes in from far away to the edge of reach")

    // missiles don't count: their ammo runs out, and a standoff beyond them would never engage
    val missileBoat = ship("e", owner = 1, x = 3000f, facing = 180f, reach = 2000f, gunReach = 600f, engagement = 600f,
        maxSpeed = 0f)
    val noMissiles = World(listOf(p, missileBoat), SimParams(200)).branch("p", OwnPlan.Standoff("e"))
    near(noMissiles.standoffRange, 800f, 0.1f, "standoff: gun reach, not missile reach")

    // another hostile near the line: pushed out past its reach. Target at 0 (standoff 700), the ship at -2000, the
    // other at (-1000, 300) with standoff 700: d = 1000 + sqrt(1000^2 - (1000^2 + 300^2 - 700^2)) = 1632
    val far = ship("p", x = -2000f, engagement = 700f, maxSpeed = 200f, accel = 200f, decel = 200f)
    val t = ship("t", owner = 1, x = 0f, reach = 500f, maxSpeed = 0f)
    val side = ship("h", owner = 1, x = -1000f, y = 300f, reach = 500f, maxSpeed = 0f)
    val pushed = World(listOf(far, t, side), SimParams(200)).branch("p", OwnPlan.Standoff("t"))
    near(pushed.standoffRange, 1632.5f, 1f, "standoff: pushed out past another hostile's reach")
    // right behind the ship, far enough that no point on the line is out of reach: the target's own standoff
    val behind = ship("h", owner = 1, x = -1500f, reach = 500f, maxSpeed = 0f)
    val sandwiched = World(listOf(far, t, behind), SimParams(200)).branch("p", OwnPlan.Standoff("t"))
    near(sandwiched.standoffRange, 700f, 1f, "standoff: no safe point on the line -> the target's standoff (the sim shows the cost)")

    // a faster enemy: the standoff can't keep it off, which the branch shows
    val fast = ship("e", owner = 1, x = 900f, facing = 180f, reach = 300f, engagement = 300f, maxSpeed = 300f, accel = 300f, decel = 300f)
    val slowP = ship("p", x = 0f, engagement = 700f, maxSpeed = 100f, accel = 100f, decel = 100f)
    val caught = World(listOf(slowP, fast), SimParams(200)).branch("p", OwnPlan.Standoff("e"))
    val cp = caught.timelines["p"]!!; val ce = caught.timelines["e"]!!
    check(minDistance(cp, ce) < 450f, "standoff: a faster enemy still gets into reach") { "closest ${minDistance(cp, ce)}" }
}
