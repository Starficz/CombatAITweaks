package org.starficz.combataitweaks.sim

import org.starficz.combataitweaks.decide.MoveDecider
import org.starficz.combataitweaks.decide.MoveInputs
import org.starficz.combataitweaks.decide.MoveKind
import org.starficz.combataitweaks.decide.MoveOption
import org.starficz.combataitweaks.decide.MoveRule
import org.starficz.combataitweaks.decide.MoveState

// Unit tests for the movement decision (plan §8.2) and the branches it reads (plan §7).

private fun opt(
    name: String, hull: Float = 0f, armor: Float = 0f, overloadsIn: Float = -1f, flux: Float = 0.3f,
    hard: Float = 0.1f,
): MoveOption {
    val kind = when {
        name.startsWith("ADVANCE") -> MoveKind.ADVANCE
        name.startsWith("BACK_OFF") -> MoveKind.BACK_OFF
        else -> MoveKind.HOLD
    }
    return MoveOption(name, kind, name.endsWith("+SYSTEM"), hull, armor, overloadsIn, flux, hard, escape = name.contains("~"))
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

    // clear range: reach 700 + radii (50 + 50) + the 10 s horizon x (300 - 150) faster
    val chaser = ship("c", owner = 1, x = 1200f, reach = 700f, radius = 50f, maxSpeed = 300f)
    val slow = ship("s", x = 0f, maxSpeed = 150f, radius = 50f)
    val clear = World(listOf(slow, chaser), SimParams(200)).branch("s", OwnPlan.Hold())
    near(clear.clearRange, 700f + 100f + 10f * 150f, 1f, "branch: clear range = reach + radii + closing over the horizon")
}
