package org.starficz.combataitweaks.sim

/** Simulation step in seconds. Frame i of a [Timeline] holds the state at (i + 1) * DT after the snapshot. */
const val DT = 0.05f

enum class HullClass(val strafeFactor: Float) {
    // strafe factors of the vanilla engine controller
    FIGHTER(0.75f), FRIGATE(1f), DESTROYER(0.75f), CRUISER(0.5f), CAPITAL(0.25f), OTHER(1f)
}

/**
 * A ship system as the movement model sees it: when it can next be used and how it moves the ship. Values come from
 * the system's AI hints ([ShipSystemSpecAPI.getAIHints2]) for enemies.
 */
class SystemInfo(
    /** Seconds until the system can be activated; 0 = now. Float.MAX_VALUE when it can't be used at all. */
    val readyIn: Float,
    /** Seconds the effect lasts once activated (charge up + active). */
    val activeTime: Float,
    /** Seconds from the end of the effect until it can be used again (charge down + cooldown). */
    val cooldown: Float,
    /** Distance the system can jump or dash (burn drive style), spread over [activeTime]. */
    val burstPotential: Float,
    /** Extra max speed while active. */
    val activeSpeedIncrease: Float,
    /** Seconds of the effect still to come when it is already on at the snapshot (charge up + active); 0 = off. */
    val activeLeft: Float = 0f,
)

/** Everything the movement model needs about one ship, captured once per batch. Immutable. */
class SimShip(
    val id: String,
    val owner: Int,
    val hullClass: HullClass,
    val collisionRadius: Float,

    val x: Float, val y: Float,
    val vx: Float, val vy: Float,
    val facing: Float,
    val angularVelocity: Float,

    /** Engine controller's effective values (include the zero-flux boost while it is active). */
    val maxSpeed: Float,
    val acceleration: Float,
    val deceleration: Float,
    val turnAcceleration: Float,
    val maxTurnRate: Float,
    val timeMult: Float,
    /** Max speed without the zero-flux boost: what vanilla's threat evaluator calls cruise speed. */
    val cruiseSpeed: Float,

    /** Current engine inputs, used for ships that keep their current plan. */
    val engineForward: Boolean,
    val engineBack: Boolean,
    val engineLeft: Boolean,
    val engineRight: Boolean,

    /** Seconds until the ship can act again (venting or overloaded). It can still move meanwhile. */
    val silenceTime: Float,
    val hardFluxLevel: Float,
    val isPhaseShip: Boolean,
    val system: SystemInfo?,

    /** Longest range of a weapon that can fire at ships (radii not included). */
    val weaponReach: Float,
    /** Range this ship tries to hold from a target, before radii (vanilla's weapon-group rule, see [Engagement]). */
    val engagementRange: Float,
    /** Facing relative to the bearing to its target that brings the most guns to bear, in degrees. */
    val bestFacingOffset: Float,
    /** Vanilla intent: the ship it is fighting and the centre-to-centre range it is holding, if any. */
    val maneuverTargetId: String?,
    val maneuverRange: Float?,

    /** Fighters only: their carrier, and how far from it the wing will engage. */
    val carrierId: String?,
    val wingRange: Float,

    /** Modules ride on their parent at a fixed offset. */
    val parentId: String?,
    val moduleOffsetDist: Float,
    val moduleOffsetAngle: Float,
    val moduleFacingOffset: Float,

    /** When its AI backs off under flux ([Reactions]); null: never (fighters, modules, no vanilla AI). */
    val backOff: BackOffTraits? = null,
    /** Longest range of a non-missile weapon that can fire at ships: the reach a standoff keeps out of (plan §13.4). */
    val gunReach: Float = weaponReach,
) {
    val isFighter get() = hullClass == HullClass.FIGHTER
    val isModule get() = parentId != null
}

/** Positions and facings over the prediction horizon. Frame i is (i + 1) * DT after the snapshot. */
class Timeline(val steps: Int) {
    val x = FloatArray(steps)
    val y = FloatArray(steps)
    val facing = FloatArray(steps)

    fun copyFrom(other: Timeline) {
        System.arraycopy(other.x, 0, x, 0, steps)
        System.arraycopy(other.y, 0, y, 0, steps)
        System.arraycopy(other.facing, 0, facing, 0, steps)
    }
}

class SimParams(
    /** Frames to simulate. */
    val steps: Int,
    /**
     * An enemy commits to killing a ship when it could bring a weapon to bear on it within this many seconds if the
     * ship stood still. Defaults to the horizon: whether it really arrives, and how late, comes from each branch's
     * pursuit, so an enemy arriving near the end only fires for the seconds left (no cliff at a fixed window).
     */
    val commitWindow: Float = steps * DT,
    /** Ships that keep their current plan hold their engine inputs this long, then coast. */
    val coastAfter: Float = 3f,
)

/** One movement phase of the protected ship: max speed / accel / decel overrides for a duration. */
class MotionPhase(
    val duration: Float,
    val maxSpeed: Float? = null,
    val acceleration: Float? = null,
    val deceleration: Float? = null,
)

/** What the protected ship does in one branch. */
sealed class OwnPlan {
    abstract val phases: List<MotionPhase>

    /** Brake to a stop and stay. */
    class Hold(override val phases: List<MotionPhase> = emptyList()) : OwnPlan()

    /** Thrust toward a fixed world direction for the whole horizon. */
    class Thrust(val dirX: Float, val dirY: Float, override val phases: List<MotionPhase> = emptyList()) : OwnPlan()

    /**
     * Close to the ship's own engagement range of [targetId] (vanilla's rule, pressed in as vanilla does) and hold
     * there, facing it with the best guns (plan §7 `ADVANCE`).
     */
    class Approach(val targetId: String, override val phases: List<MotionPhase> = emptyList()) : OwnPlan()

    /**
     * Thrust straight away from ship [fromId] where it is in this branch, as vanilla's back-off does (plan §7
     * `BACK_OFF`, milestone 4a).
     */
    class BackAway(val fromId: String, override val phases: List<MotionPhase> = emptyList()) : OwnPlan()

    /**
     * The standoff (plan §13.4 `HOLD`): keep [targetId] at the closest range just outside every hostile's gun reach
     * (or at the ship's own engagement range when that is farther), facing it with the best guns; back off as they
     * close, close in as they leave.
     */
    class Standoff(val targetId: String, override val phases: List<MotionPhase> = emptyList()) : OwnPlan()

    /**
     * [first] until [switchAt] seconds (shared clock), then [then]: an option judged by whether the ship can still
     * get out once it changes its mind (plan §8.2, "then escape"). Uses [first]'s phases throughout. The parts are
     * simple plans, not nested [Then]s.
     */
    class Then(val first: OwnPlan, val switchAt: Float, val then: OwnPlan) : OwnPlan() {
        override val phases get() = first.phases
    }
}

class BranchResult(
    /** Every ship's timeline in this branch (re-simulated ones replaced, the rest shared with the base world). */
    val timelines: Map<String, Timeline>,
    /** Enemies that committed to killing the protected ship, with the centre-to-centre range each one closes to. */
    val committed: Map<String, Float>,
    /** [OwnPlan.Approach] only: the centre-to-centre range the ship closes to; 0 otherwise. */
    val approachRange: Float = 0f,
    /**
     * The distance at which none of the committed (non-fighter) enemies would commit any more: weapon reach and
     * radii, burst distance, plus how much faster than the protected ship each closes over the commit window.
     * 0 when nothing commits.
     */
    val clearRange: Float = 0f,
    /** [OwnPlan.Standoff] only: the centre-to-centre range to the target it wants at the start; 0 otherwise. */
    val standoffRange: Float = 0f,
)
