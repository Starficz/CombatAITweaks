package org.starficz.combataitweaks.predictor

import org.starficz.combataitweaks.sim.DT
import org.starficz.combataitweaks.sim.OwnPlan
import org.starficz.combataitweaks.sim.Reaction
import org.starficz.combataitweaks.sim.Timeline
import kotlin.math.ceil

object Constants {
    const val TIME_STEP = DT
    /** Horizon H (plan §7): every branch is simulated this far ahead. */
    const val PREDICTION_DURATION = 10f
    val TOTAL_FUTURE_STATES = ceil(PREDICTION_DURATION / TIME_STEP).toInt()
}

/**
 * One option a CAT ship wants predicted (plan §7): a name the decisions read it back by, what the ship does, and
 * whether to also resolve venting now. What its fire does to the enemies (the trade, plan §13.3) is resolved for
 * every option.
 */
class OptionRequest(val name: String, val plan: OwnPlan, val withVent: Boolean)

class RequestResult(
    val name: String,
    /** What the ship did in this branch (a `BACK_OFF@` option's world direction is in its [OwnPlan.Thrust]). */
    val plan: OwnPlan,
    val outlook: DamageOutlook,
    val shipTimeline: Timeline,
    /** Enemies that committed to killing the ship in this branch, with the range each closes to. */
    val committed: Map<String, Float>,
    /** Committed enemies' pursuit paths in this branch, for the overlay. */
    val pursuitTimelines: Map<String, Timeline>,
    /** `ADVANCE` options: the centre-to-centre range the ship closes to. */
    val approachRange: Float,
    /** Distance at which no committed enemy would commit any more (0 when none commits). */
    val clearRange: Float,
    /** How enemies reacted to the ship's fire in this branch (plan §13.8), by enemy id. */
    val reactions: Map<String, Reaction> = emptyMap(),
)

class SimulationOutput(
    val enemyTimelines: Map<String, Timeline>,
    /** Per CAT ship, its options by name. */
    val results: Map<String, Map<String, RequestResult>>,
)
