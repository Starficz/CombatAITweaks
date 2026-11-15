package org.starficz.combataitweaks.decide

// Pure: no game API, compiled into the unit tests with `sim` (test/run-unit-tests.ps1).

/** The movement states (plan §8.2). The names are published as BEHAVIOR_STATE. */
enum class MoveState { ADVANCE, HOLD, BACK_OFF, DISENGAGE, VENTING }

/** What a movement option does with the ship. */
enum class MoveKind { HOLD, ADVANCE, BACK_OFF }

/**
 * What an option trades over the horizon (plan §13.3), in shares of each ship so sizes compare:
 * - damage: cost (hull x 3 + armor) lost / the ship's worth (max hull x 3 + armor rating). Permanent.
 * - flux: net flux gained / max flux, its own firing included; an overload counts as 1. Recoverable.
 *
 * Taken is the ship's own; dealt is summed over the enemies it fires at, from its fire and its side's.
 */
class Trade(val dealtDamage: Float, val takenDamage: Float, val dealtFlux: Float, val takenFlux: Float)

/** One option's predicted outcome over the rest of the horizon (plan §8.2). */
class MoveOption(
    val name: String,
    val kind: MoveKind,
    /** Uses the ship's mobility system now. */
    val system: Boolean,
    val hullLost: Float,
    /** Worst regional armor drop, in armor points. */
    val armorLost: Float,
    /** Seconds until the ship overloads, or -1 when it doesn't within the horizon. */
    val overloadsIn: Float,
    /** Flux and hard flux levels (0..1) at the end of the horizon. */
    val endFlux: Float,
    val endHardFlux: Float,
    /**
     * Judged as "do it for the reaction time, then escape" (named `HOLD~`, `ADVANCE~`): ok means the ship can still
     * get out once it changes its mind. False: kept up for the whole horizon (plain `HOLD`, back-offs).
     */
    val escape: Boolean = false,
    /** What it trades (plan §13.3); null when not predicted (then it counts as favourable). */
    val trade: Trade? = null,
    /**
     * The standoff `HOLD` (plan §13.4): it keeps out of reach for the whole horizon, so its own branch is both its
     * safety and its flux. Wins over `HOLD~` and plain `HOLD` (then kept for the vent) in judging the `HOLD` state.
     */
    val standoff: Boolean = false,
    /**
     * Uses the ship's defensive system now (plan §11b, `+DEFENSE`): a variant of the option with the same name
     * without the suffix. Picked over it only when it clearly saves something ([MoveRule.withDefense]).
     */
    val defense: Boolean = false,
) {
    val cost get() = MoveRule.cost(armorLost, hullLost)
}

class MoveInputs(
    val options: List<MoveOption>,
    val venting: Boolean,
    /** While venting: the movement option the vent decision chose (plan §8.1), if CAT ordered the vent. */
    val ventOption: String?,
    val fluxLevel: Float,
    /** Hull lost over the horizon above this makes an option not ok. */
    val hullLimit: Float,
    /** The state driven last time (before hysteresis). */
    val previous: MoveState?,
    /** How far the ship is outside the range `ADVANCE` closes to (0 when at or inside it). */
    val advanceGap: Float,
    val forceAdvanceIfFluxBelowLevel: Float,
    val backoffIfFluxAboveLevel: Float,
    val backoffIfHardfluxAboveLevel: Float,
    val reengageIfFluxDropsBelowLevel: Float,
    /** The ship burns its system to advance only while the advance keeps flux at or below this. */
    val maxFluxLevelRaiseToHarass: Float,
)

class MoveChoice(
    val state: MoveState,
    /** The option to drive; null when there is none of the needed kind. */
    val option: MoveOption?,
    /** One line with the numbers behind the choice, for the decision trace (plan §8.6). */
    val reason: String,
)

/**
 * The movement decision (plan §8.2). Over the horizon an option is ok with no overload and hull lost within the
 * limit; its cost is hull x 3 + armor. `ADVANCE` has two branches, one per question:
 * - safety reads the "then escape" variant (`ADVANCE~`): doing it for the reaction time and then backing off must
 *   still be ok (the ship re-decides every tick, so that is the real risk);
 * - the flux limits read the plain one (`ADVANCE`: close to range and stay), the flux it builds if it keeps doing it.
 *
 * `HOLD` is the standoff (plan §13.4, `STANDOFF`): just outside every enemy's gun reach, so its one branch answers
 * both. Without one (no target) it falls back to `HOLD~` for safety and keeping still (`HOLD`) for flux.
 *
 * | state       | when                                                                  | drives                     |
 * |-------------|-----------------------------------------------------------------------|----------------------------|
 * | `ADVANCE`   | `ADVANCE~` ok, `ADVANCE` end flux <= forceAdvance, end hard flux <= its level, `ADVANCE` trades favourably ([favourable]) | `ADVANCE~` (+system to close a gap at low flux) |
 * | `HOLD`      | `STANDOFF` ok, its end flux <= backoff level, end hard flux <= its level | `STANDOFF`          |
 * | `BACK_OFF`  | some back-off ok                                                      | the cheapest ok one        |
 * | `DISENGAGE` | nothing ok; kept until flux < reengage level                          | the cheapest back-off      |
 *
 * Nothing ok means running, even from a ship it can't outrun: player ships would rather not die, and running toward
 * friendly ships ([ALLY_BACK_OFF], preferred on a tie) gives them the chance to bail it out, which the worst case
 * doesn't count.
 * | `VENTING`   | while venting                                                         | the vent's option          |
 */
object MoveRule {
    /** A system variant must cost at most this fraction of the plain option, and save [SYSTEM_MIN_SAVING]. */
    const val SYSTEM_GAIN = 0.8f
    const val SYSTEM_MIN_SAVING = 50f
    /** The system is used to advance only from this far outside the advance range. */
    const val ADVANCE_SYSTEM_GAP = 300f
    /** The back-off flown by vanilla's flags; the 4b directions are named `BACK_OFF@<degrees>`. */
    const val VANILLA_BACK_OFF = "BACK_OFF"
    /** The back-off toward the nearby friendly ships. */
    const val ALLY_BACK_OFF = "BACK_OFF@ally"
    /** Shares below this are nothing (a stray hit, flux noise). */
    const val TRADE_EPS = 0.01f
    /** Suffix of an option's variant with the ship's defensive system used now (plan §11b). */
    const val DEFENSE_SUFFIX = "+DEFENSE"

    fun cost(armor: Float, hull: Float) = VentRule.cost(armor, hull)

    fun ok(o: MoveOption, hullLimit: Float) = o.overloadsIn < 0f && o.hullLost <= hullLimit

    /**
     * Whether a trade is worth making (plan §13.3), any of:
     * - free: the ship takes nothing (closing in from out of reach);
     * - damage: it deals more damage share than it takes (permanent loss must be a win);
     * - flux: it deals at least the flux share it takes (an even flux trade is enough, or nobody engages first while
     *   shields absorb everything).
     */
    fun favourable(t: Trade?): Boolean {
        if (t == null) return true
        if (t.takenDamage <= TRADE_EPS && t.takenFlux <= TRADE_EPS) return true
        if (t.dealtDamage > TRADE_EPS && t.dealtDamage > t.takenDamage) return true
        return t.dealtFlux > TRADE_EPS && t.dealtFlux >= t.takenFlux
    }

    /**
     * Kiting (plan §13.4): the standoff [hold] deals damage or flux while taking no damage, closing in ([advance],
     * kept up the whole horizon) takes more, and it doesn't beat its trade: more net damage share (dealt - taken), or more
     * net flux share at no worse net damage. Then holding at the standoff wins over advancing into the enemy's guns.
     */
    fun kitesBetter(hold: MoveOption, advance: MoveOption?): Boolean {
        if (!hold.standoff) return false
        val s = hold.trade ?: return false
        if (s.takenDamage > TRADE_EPS || (s.dealtDamage <= TRADE_EPS && s.dealtFlux <= TRADE_EPS)) return false
        val a = advance?.trade ?: return true
        // closing in costs nothing more (nothing reaches it there either): no reason to hang back
        if (a.takenDamage <= s.takenDamage + TRADE_EPS && a.takenFlux <= s.takenFlux + TRADE_EPS) return false
        val dS = s.dealtDamage - s.takenDamage; val dA = a.dealtDamage - a.takenDamage
        val fS = s.dealtFlux - s.takenFlux; val fA = a.dealtFlux - a.takenFlux
        val advanceBetter = dA > dS + TRADE_EPS || (dA >= dS - TRADE_EPS && fA > fS + TRADE_EPS)
        return !advanceBetter
    }

    /** The state the rule wants now, with its option (before hysteresis). */
    fun decide(i: MoveInputs): MoveChoice = choose(wanted(i), i)

    private fun wanted(i: MoveInputs): MoveState {
        if (i.venting) return MoveState.VENTING
        if (i.previous == MoveState.DISENGAGE && i.fluxLevel > i.reengageIfFluxDropsBelowLevel) return MoveState.DISENGAGE
        val stand = stand(i)
        val advance = judged(i, MoveKind.ADVANCE)
        val advanceFlux = keepingOn(i, MoveKind.ADVANCE) ?: stand ?: advance
        val hold = judged(i, MoveKind.HOLD)
        val holdFlux = if (hold?.standoff == true) hold else stand ?: hold
        val holdDefense = hold?.let { defenseOf(i, it) }
        val holdOk = (hold != null && ok(hold, i.hullLimit) && fluxBelow(holdFlux!!, i.backoffIfFluxAboveLevel, i)) ||
            (holdDefense != null && ok(holdDefense, i.hullLimit) && fluxBelow(holdDefense, i.backoffIfFluxAboveLevel, i))
        val fullAdvance = keepingOn(i, MoveKind.ADVANCE)
        if (advance != null && ok(advance, i.hullLimit) && fluxBelow(advanceFlux!!, i.forceAdvanceIfFluxBelowLevel, i) &&
            favourable(fullAdvance?.trade) && !(holdOk && kitesBetter(hold!!, fullAdvance))) {
            return MoveState.ADVANCE
        }
        if (holdOk) return MoveState.HOLD
        if (i.options.any { it.kind == MoveKind.BACK_OFF && ok(it, i.hullLimit) }) return MoveState.BACK_OFF
        return MoveState.DISENGAGE
    }

    private fun fluxBelow(o: MoveOption, level: Float, i: MoveInputs) =
        o.endFlux <= level && o.endHardFlux <= i.backoffIfHardfluxAboveLevel

    /** Plain `HOLD`: keeping still for the whole horizon. */
    private fun stand(i: MoveInputs) = keepingOn(i, MoveKind.HOLD)

    /** The plain option of a kind: kept up for the whole horizon (no escape, no system, not the standoff). */
    private fun keepingOn(i: MoveInputs, kind: MoveKind) =
        i.options.firstOrNull { it.kind == kind && !it.system && !it.defense && !it.escape && !it.standoff }

    /**
     * The option a state is judged by: the standoff, else its "then escape" variant when there is one, else the
     * plain one.
     */
    private fun judged(i: MoveInputs, kind: MoveKind): MoveOption? {
        val candidates = i.options.filter { it.kind == kind && !it.system && !it.defense }
        return candidates.firstOrNull { it.standoff } ?: candidates.firstOrNull { it.escape } ?: candidates.firstOrNull()
    }

    /** [base]'s `+DEFENSE` variant, or null. */
    fun defenseOf(i: MoveInputs, base: MoveOption): MoveOption? =
        i.options.firstOrNull { it.defense && it.name == base.name + DEFENSE_SUFFIX }

    /**
     * [base], or its `+DEFENSE` variant when the variant is ok and [base] isn't, or it clearly saves: at most
     * [SYSTEM_GAIN] of [base]'s cost and [SYSTEM_MIN_SAVING] less (the system's uses are kept for when they matter).
     */
    fun withDefense(i: MoveInputs, base: MoveOption?): MoveOption? {
        if (base == null) return null
        val d = defenseOf(i, base) ?: return base
        return if (better(d, base, i.hullLimit)) d else base
    }

    /**
     * Whether the defensive variant [d] is worth using over [base] (see [withDefense]): ok where [base] isn't, or
     * (both ok, or neither) clearly cheaper.
     */
    private fun better(d: MoveOption, base: MoveOption, hullLimit: Float): Boolean {
        val dOk = ok(d, hullLimit); val baseOk = ok(base, hullLimit)
        if (dOk != baseOk) return dOk
        return d.cost <= base.cost * SYSTEM_GAIN && base.cost - d.cost >= SYSTEM_MIN_SAVING
    }

    /** [state] with the option it drives and the reason line. */
    fun choose(state: MoveState, i: MoveInputs): MoveChoice {
        val option = when (state) {
            MoveState.ADVANCE -> advanceOption(i)
            MoveState.HOLD -> {
                // the option HOLD is judged by with its defensive system first: that may be what makes HOLD ok
                val held = judged(i, MoveKind.HOLD)
                val d = held?.let { defenseOf(i, it) }
                if (held != null && d != null && better(d, held, i.hullLimit)) d
                else withDefense(i, held?.takeIf { ok(it, i.hullLimit) } ?: stand(i) ?: held)
            }
            MoveState.BACK_OFF -> pickBackOff(i.options.filter { it.kind == MoveKind.BACK_OFF && ok(it, i.hullLimit) },
                i.hullLimit) ?: pickBackOff(i.options.filter { it.kind == MoveKind.BACK_OFF }, i.hullLimit)
            MoveState.DISENGAGE -> pickBackOff(i.options.filter { it.kind == MoveKind.BACK_OFF }, i.hullLimit)
            MoveState.VENTING -> i.options.firstOrNull { it.name == i.ventOption && !it.system && !it.defense }
                ?: pickBackOff(i.options.filter { it.kind == MoveKind.BACK_OFF && !it.system && !it.defense })
        }
        val head = if (option != null) "$state ${option.name}" else "$state (no option)"
        return MoveChoice(state, option, if (Explain.enabled) head + ": " + table(i) else head)
    }

    /** `ADVANCE`, or its system variant to close a real gap while keeping still would leave flux low. */
    private fun advanceOption(i: MoveInputs): MoveOption? {
        val plain = judged(i, MoveKind.ADVANCE)
        val sys = i.options.firstOrNull { it.kind == MoveKind.ADVANCE && it.system }
        val flux = (stand(i) ?: sys)?.endFlux ?: 1f
        if (sys != null && ok(sys, i.hullLimit) && i.advanceGap > ADVANCE_SYSTEM_GAP &&
            flux <= i.maxFluxLevelRaiseToHarass) return sys
        return plain
    }

    /**
     * The cheapest back-off; on a tie toward the friendly ships ([ALLY_BACK_OFF]), then vanilla's own direction
     * ([VANILLA_BACK_OFF]), then the lowest end flux. A system variant only when it clearly saves something (keep the
     * system for later). The same for a `+DEFENSE` variant ([better]).
     */
    fun pickBackOff(candidates: List<MoveOption>, hullLimit: Float = Float.MAX_VALUE): MoveOption? {
        val plain = candidates.filter { !it.system && !it.defense }.minWithOrNull(compareBy<MoveOption>(
            { it.cost },
            { when (it.name) { ALLY_BACK_OFF -> 0; VANILLA_BACK_OFF -> 1; else -> 2 } },
            { it.endFlux },
        ))
        val sys = candidates.filter { it.system }.minByOrNull { it.cost }
        val moving = when {
            plain == null -> sys
            sys == null -> plain
            sys.cost <= plain.cost * SYSTEM_GAIN && plain.cost - sys.cost >= SYSTEM_MIN_SAVING -> sys
            else -> plain
        }
        val d = candidates.filter { it.defense }.minByOrNull { it.cost } ?: return moving
        return if (moving == null || better(d, moving, hullLimit)) d else moving
    }

    /**
     * Every option in a few numbers: "ADVANCE ovl 4.2s, HOLD hull 310>80, BACK_OFF cost 85 flux 0.62", with the trade
     * where predicted: "trade d 0.05/0.01 f 0.62/0.40" (damage and flux shares, dealt/taken).
     */
    fun table(i: MoveInputs): String = i.options.joinToString(", ") { o ->
        val head = when {
            o.overloadsIn >= 0f -> "%s ovl %.1fs".format(o.name, o.overloadsIn)
            o.hullLost > i.hullLimit -> "%s hull %.0f>%.0f".format(o.name, o.hullLost, i.hullLimit)
            else -> "%s cost %.0f flux %.2f".format(o.name, o.cost, o.endFlux)
        }
        val t = o.trade ?: return@joinToString head
        head + " trade d %.2f/%.2f f %.2f/%.2f".format(t.dealtDamage, t.takenDamage, t.dealtFlux, t.takenFlux)
    }
}

/**
 * [MoveRule] with hysteresis (plan §8.2): a new state must win for [delay] seconds in a row before the ship switches
 * to it, except into `DISENGAGE` or `VENTING`, and out of `VENTING` (the vent is over).
 */
class MoveDecider(private val delay: Float = 0.5f) {
    var state: MoveState? = null
        private set
    private var pending: MoveState? = null
    private var pendingSince = 0f

    fun decide(now: Float, i: MoveInputs): MoveChoice {
        val wanted = MoveRule.decide(i)
        val current = state
        val immediate = current == null || wanted.state == current || wanted.state == MoveState.DISENGAGE ||
            wanted.state == MoveState.VENTING || current == MoveState.VENTING
        if (immediate) {
            state = wanted.state
            pending = null
            return wanted
        }
        if (pending != wanted.state) {
            pending = wanted.state
            pendingSince = now
        }
        if (now - pendingSince >= delay) {
            state = wanted.state
            pending = null
            return wanted
        }
        val held = MoveRule.choose(current!!, i)
        return MoveChoice(held.state, held.option, if (!Explain.enabled) held.reason
            else held.reason + " (wants %s for %.1fs)".format(wanted.state, now - pendingSince))
    }

    fun reset() {
        state = null
        pending = null
    }
}
