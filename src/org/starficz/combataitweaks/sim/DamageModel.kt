package org.starficz.combataitweaks.sim

/*
 * Data for the damage half of the simulation (plan §6). Everything is captured once per batch on the main thread and
 * immutable afterwards. Times are seconds after the snapshot; angles are degrees, 0 = +x, counter-clockwise;
 * ship-local coordinates have the ship facing +x.
 */

/** Mirrors the game's DamageType; the multipliers live in [DamageRules] because mods can change them. */
enum class DamageKind { KINETIC, HIGH_EXPLOSIVE, FRAGMENTATION, ENERGY, OTHER }

/** How a hit arrives: vanilla's damage-taken stats differ for beams, missiles and other projectiles. */
enum class Delivery { PROJECTILE, BEAM, MISSILE }

/** A stat bonus as vanilla's StatBonus computes it: (x + x * percent / 100 + flat) * mult. */
class Bonus(val percent: Float = 0f, val flat: Float = 0f, val mult: Float = 1f) {
    fun apply(x: Float): Float = (x + x * percent / 100f + flat) * mult

    companion object { val NONE = Bonus() }
}

/** Game-wide damage rules, read from settings and the DamageType enum. */
class DamageRules(
    /** Indexed by [DamageKind.ordinal]. */
    val shieldMult: FloatArray = floatArrayOf(2f, 0.5f, 0.25f, 1f, 1f),
    val armorMult: FloatArray = floatArrayOf(0.5f, 2f, 0.25f, 1f, 1f),
    val hullMult: FloatArray = floatArrayOf(1f, 1f, 1f, 1f, 1f),
    /** Beam hit strength = DPS x this (setting `dpsToHitStrengthMult`). */
    val dpsToHitStrengthMult: Float = 0.5f,
)

/**
 * One hit on the protected ship, before the protected ship's own defences.
 * The shooter's damage bonuses (weapon type, beam, vs this hull size) are already applied.
 */
class Hit(
    var time: Float,
    /** Damage of this hit; for beams, the damage of one tick. */
    var damage: Float,
    /** Hit strength against armor; for beams the beam's DPS (the armor rule applies `dpsToHitStrengthMult`). */
    var hitStrength: Float,
    var emp: Float,
    var kind: DamageKind,
    var delivery: Delivery,
    /** Beam damage on shields is soft flux unless the weapon forces hard flux. */
    var softFlux: Boolean,
    /** From an ammo-limited weapon that waits for the shield to drop instead of firing into it. */
    var conservative: Boolean,
    /** World point the hit comes from: the shooter, or where the projectile was just before impact. */
    var fromX: Float,
    var fromY: Float,
    /** Shooter ship id, or "in flight" for shots already fired. */
    var sourceId: String,
) {
    internal fun set(
        time: Float, damage: Float, hitStrength: Float, emp: Float, kind: DamageKind, delivery: Delivery,
        softFlux: Boolean, conservative: Boolean, fromX: Float, fromY: Float, sourceId: String,
    ): Hit {
        this.time = time; this.damage = damage; this.hitStrength = hitStrength; this.emp = emp; this.kind = kind
        this.delivery = delivery; this.softFlux = softFlux; this.conservative = conservative
        this.fromX = fromX; this.fromY = fromY; this.sourceId = sourceId
        return this
    }
}

/**
 * Reusable [Hit]s and float arrays for one worker thread's branch: what a damage run needs only while it runs
 * (garbage otherwise: enemy beams alone make ten hits a second per beam per branch; threat rankings, firing flux,
 * missile tracking and armor grid copies were most of the rest). The caller resets it when the last run's scratch
 * is no longer read: everything obtained since is then handed out again. Only for runs whose hits nobody keeps
 * ([DamageResult.hits] is empty then); outcomes never come from here.
 */
class Scratch {
    private val pool = ArrayList<Hit>(1024)
    private var used = 0
    /** Arrays by size (a slab each: the steps, and each armor grid's cell count); few sizes, so a linear lookup. */
    private class Slab(val size: Int) {
        val arrays = ArrayList<FloatArray>(64)
        var used = 0
    }
    private var slabs = arrayOfNulls<Slab>(8)
    private var slabCount = 0

    fun reset() {
        used = 0
        for (k in 0 until slabCount) slabs[k]!!.used = 0
    }

    /** A float array of [size] filled with [fill], valid until the next [reset]. */
    fun floats(size: Int, fill: Float = 0f): FloatArray {
        val slab = slab(size)
        val a: FloatArray
        if (slab.used < slab.arrays.size) {
            a = slab.arrays[slab.used]
            java.util.Arrays.fill(a, fill)
        } else {
            a = FloatArray(size)
            if (fill != 0f) java.util.Arrays.fill(a, fill)
            slab.arrays += a
        }
        slab.used++
        return a
    }

    private fun slab(size: Int): Slab {
        for (k in 0 until slabCount) if (slabs[k]!!.size == size) return slabs[k]!!
        if (slabCount == slabs.size) slabs = slabs.copyOf(slabCount * 2)
        return Slab(size).also { slabs[slabCount++] = it }
    }

    internal fun obtain(
        time: Float, damage: Float, hitStrength: Float, emp: Float, kind: DamageKind, delivery: Delivery,
        softFlux: Boolean, conservative: Boolean, fromX: Float, fromY: Float, sourceId: String,
    ): Hit {
        if (used < pool.size) {
            return pool[used++].set(time, damage, hitStrength, emp, kind, delivery, softFlux, conservative, fromX, fromY, sourceId)
        }
        val h = Hit(time, damage, hitStrength, emp, kind, delivery, softFlux, conservative, fromX, fromY, sourceId)
        pool += h
        used++
        return h
    }

    companion object {
        private val local = ThreadLocal.withInitial { Scratch() }

        /** This thread's pool. */
        fun local(): Scratch = local.get()
    }
}

/** A hit from [this] pool, or a new one without a pool. */
internal fun Scratch?.hit(
    time: Float, damage: Float, hitStrength: Float, emp: Float, kind: DamageKind, delivery: Delivery,
    softFlux: Boolean, conservative: Boolean, fromX: Float, fromY: Float, sourceId: String,
): Hit = this?.obtain(time, damage, hitStrength, emp, kind, delivery, softFlux, conservative, fromX, fromY, sourceId)
    ?: Hit(time, damage, hitStrength, emp, kind, delivery, softFlux, conservative, fromX, fromY, sourceId)

// ---------------------------------------------------------------------------------------------------------- weapons

enum class FireMode { PROJECTILE, BEAM, BURST_BEAM, GUIDED_MISSILE }

enum class WeaponPhase { READY, CHARGING, FIRING, COOLDOWN }

/** A missile's flight model (in flight, or the one a launcher fires). */
class MissileInfo(
    val maxSpeed: Float,
    val acceleration: Float,
    val maxTurnRate: Float,
    val turnAcceleration: Float,
    val launchSpeed: Float,
    /** Powered flight time; after it the missile coasts for [flameoutTime]. */
    val maxFlightTime: Float,
    val flameoutTime: Float,
    val fadeTime: Float,
    val armingTime: Float,
    val noCollisionWhileFading: Boolean,
    val reduceDamageWhileFading: Boolean,
    val isMine: Boolean = false,
    val mineRange: Float = 0f,
    val mirv: MirvInfo? = null,
    val dem: DemInfo? = null,
    /** Hitpoints (the missile's hull spec) and collision radius, for point defense; 0 hitpoints = unknown. */
    val hitpoints: Float = 0f,
    val radius: Float = 15f,
)

/** A MIRV's split rules (vanilla MirvAI) and what it splits into. Random ranges are replaced by their midpoints. */
class MirvInfo(
    val splitRange: Float,
    val minTimeToSplit: Float,
    val numShots: Int,
    val arc: Float,
    val arcOffset: Float,
    val spreadSpeed: Float,
    val evenSpread: Boolean,
    val sub: SubmunitionInfo,
)

class SubmunitionInfo(
    val damage: Float,
    val emp: Float,
    val kind: DamageKind,
    /** Guided submunitions (missiles) home in; others fly straight at [speed] along their spread angle. */
    val missile: MissileInfo?,
    val speed: Float,
)

/** A DEM's parameters (DEMScript). For DEMs in flight these are the values it rolled; for launchers the midpoints. */
class DemInfo(
    val minDelay: Float,
    val triggerDistance: Float,
    val preferredMin: Float,
    val preferredMax: Float,
    val turnRateBoost: Float,
    val targetingArc: Float,
    val targetingTime: Float,
    val firingTime: Float,
    val allowedDriftFraction: Float,
    /** Payload: a beam ([payloadDamage] per second, see [payloadDuration]) or a burst of projectiles. */
    val payloadIsBeam: Boolean,
    val payloadDamage: Float,
    val payloadEmp: Float,
    val payloadKind: DamageKind,
    val payloadSoftFlux: Boolean,
    /**
     * Beam payloads, at full power: seconds after firing starts until the damage begins, and how long it lasts. A burst
     * beam ramps up and down (damage follows brightness squared, so each ramp is worth a third of its length): it
     * starts 2/3 into its charge-up and lasts its burst + a third of both ramps.
     */
    val payloadStart: Float = 0f,
    val payloadDuration: Float = firingTime,
    /** The payload weapon's range, from the DEM to the target's edge. */
    val payloadRange: Float = Float.MAX_VALUE,
    /**
     * Vanilla removes the drone, and so the payload, when the missile dies while firing (DEMScript's cleanup), unless
     * the DEM is bomb-pumped (the missile is gone at once, the drone fires on): then false.
     */
    val payloadCutWithMissile: Boolean = true,
)

enum class DemState { WAIT, TURN_TO_TARGET, SIGNAL, FIRE, DONE }

/** One weapon of a shooter. Values are live (rate-of-fire, range and damage bonuses applied). */
class SimWeapon(
    val mountX: Float,
    val mountY: Float,
    /** Slot centre angle and arc, ship-local. */
    val slotAngle: Float,
    val arc: Float,
    /** Degrees per second (ship time). */
    val turnRate: Float,
    /** Current aim, ship-local. */
    val angle: Float,
    /** Range the AI fires at (fade range included for projectiles). */
    val range: Float,
    val mode: FireMode,
    /** Per shot; per second for beams and burst beams. */
    val damage: Float,
    val emp: Float,
    val kind: DamageKind,
    val softFlux: Boolean,
    val chargeUp: Float,
    val burstSize: Int,
    val burstDelay: Float,
    /** Burst beams: how long a burst lasts. */
    val burstDuration: Float,
    /** Time after a burst before the next can start. */
    val cooldown: Float,
    /** Flux per burst; per second for continuous beams. */
    val fluxCost: Float,
    val phase: WeaponPhase,
    val phaseTimeLeft: Float,
    val shotsLeftInBurst: Int,
    val disabledFor: Float,
    /** Shots left; -1 = unlimited. */
    val ammo: Int,
    val maxAmmo: Int,
    val ammoPerSecond: Float,
    val reloadSize: Float,
    /** Projectile or beam speed. */
    val speed: Float,
    /** Full spread cone, degrees. */
    val spread: Float,
    val conservative: Boolean,
    /** Guided missiles fire at anything in range, whatever their launcher's arc (DO_NOT_AIM). */
    val noAim: Boolean,
    /** The missile it fires: guided ones are flown by the missile simulation, unguided ones (rockets, torpedoes)
     *  fly straight like projectiles but still hit as missiles. */
    val missile: MissileInfo?,
    /** Sustained DPS, for ranking threats (omni shield facing). */
    val sustainedDps: Float,
    /** Autofire (plan §13.1): `STRIKE` weapons skip frigates unless also `USE_VS_FRIGATES`. */
    val strike: Boolean = false,
    val vsFrigates: Boolean = false,
)

/** A ship or fighter that can shoot, with its flux budget (plan §5.6). */
class SimShooter(
    val shipId: String,
    val owner: Int,
    val flux: Float,
    val hardFlux: Float,
    val maxFlux: Float,
    val dissipation: Float,
    val timeMult: Float,
    /** Seconds (engine time) until it can fire again: venting or overloaded. */
    val silenceTime: Float,
    /** Damage multiplier against each [HullClass] (vanilla's damageToFrigates and so on), by ordinal. */
    val damageVsSize: FloatArray,
    val weapons: List<SimWeapon>,
    /** Its ship system, when the system's AI hints say it deals damage. */
    val systemThreat: SystemThreat? = null,
)

/**
 * A ship system that deals damage (the Tempest's drone strike, the Omen's EMP emitter...), from its AI hints: the
 * hints vanilla's own vent-safety check reads (decompiled threat evaluator: the system counts when it is ready
 * within the window, its damage is `threatDamage`, and with `threatSpeed` < 10000 it arrives after the distance at
 * that speed). Range, arc and angle as vanilla's threat module checks them: within `threatRange` + the target's
 * radius, inside `facing + threatAngle +- threatArc / 2`.
 */
class SystemThreat(
    /** Seconds (engine time) until it can be used: cooldown, an active use running out, or reloading. */
    val readyIn: Float,
    /** Seconds from one use to the next (charge up + active + charge down + cooldown). */
    val cycle: Float,
    /** Uses left (ammo); Int.MAX_VALUE for systems without ammo. */
    val uses: Int,
    val damage: Float,
    val emp: Float,
    val kind: DamageKind,
    val range: Float,
    val arc: Float,
    val angle: Float,
    /** Travel speed of the damage; 10000 or more = instant. */
    val speed: Float,
)

/** A projectile already in flight (or a DEM drone's beam ticks, which wait [delay] seconds). */
class InFlightShot(
    val x: Float, val y: Float,
    val vx: Float, val vy: Float,
    val owner: Int,
    val damage: Float,
    val hitStrength: Float,
    val emp: Float,
    val kind: DamageKind,
    val delivery: Delivery,
    val softFlux: Boolean,
    val timeLeft: Float,
    val delay: Float = 0f,
)

class InFlightMissile(
    val x: Float, val y: Float,
    val vx: Float, val vy: Float,
    val facing: Float,
    val angularVelocity: Float,
    val owner: Int,
    val damage: Float,
    val emp: Float,
    val kind: DamageKind,
    val softFlux: Boolean,
    val info: MissileInfo,
    /** Seconds flown so far; negative for missiles that launch later. */
    val flightTime: Float,
    val targetId: String?,
    val conservative: Boolean = false,
    val demState: DemState = DemState.WAIT,
    val demElapsed: Float = 0f,
    val sourceId: String = IN_FLIGHT,
    /** Hitpoints left (a missile in flight may be damaged already) and collision radius, for point defense. */
    val hitpoints: Float = info.hitpoints,
    val radius: Float = info.radius,
)

const val IN_FLIGHT = "in flight"

// --------------------------------------------------------------------------------------------------------- defences

enum class ShieldKind { NONE, FRONT, OMNI }

/**
 * A copy of an armor grid with the ship-local geometry of its cells. Cell (i, j)'s lower corner is
 * origin + i * axisI + j * axisJ in ship-local coordinates (captured from ArmorGridAPI.getLocation).
 */
class ArmorInfo(
    val width: Int,
    val height: Int,
    /** Cell armor, index i * height + j. */
    val cells: FloatArray,
    /** Armor rating with armor bonuses: the base of the minimum-armor floor. */
    val rating: Float,
    val originX: Float, val originY: Float,
    val axisIX: Float, val axisIY: Float,
    val axisJX: Float, val axisJY: Float,
    val armorDamageTakenMult: Float,
    val kineticArmorDamageTakenMult: Float,
    val effectiveArmorBonus: Bonus,
    val minArmorFraction: Float,
    val maxArmorDamageReduction: Float,
    val hullDamageTakenMult: Float,
)

/** Everything about the protected ship (or one of its modules) that decides what a hit does to it. */
class SimDefense(
    val shipId: String,
    val hullClass: HullClass,
    val isDrone: Boolean,
    val collisionRadius: Float,
    /** Radius hits are tested against (shield radius, even without a shield). */
    val hitRadius: Float,
    val timeMult: Float,

    val shield: ShieldKind,
    val shieldArc: Float,
    /** Shield facing relative to the ship's facing, now. */
    val shieldOffset: Float,
    val shieldOn: Boolean,
    /** Current unfolded arc. */
    val shieldActiveArc: Float,
    val unfoldTime: Float,
    val upkeep: Float,
    /** Includes the absorption mult, not the shield damage-taken mult. */
    val fluxPerDamage: Float,
    val shieldDamageTakenMult: Float,
    /** Shield damage-taken mults by [DamageKind] and by [Delivery]. */
    val shieldKindMult: FloatArray,
    val shieldDeliveryMult: FloatArray,

    val flux: Float,
    val hardFlux: Float,
    val maxFlux: Float,
    val dissipation: Float,
    val hardFluxDissipationFraction: Float,
    val ventRate: Float,
    val isVenting: Boolean,
    val overloadLeft: Float,
    val overloadTimeMod: Bonus,

    /** Armor and hull damage-taken mults by [DamageKind] and by [Delivery]. */
    val kindMult: FloatArray,
    val deliveryMult: FloatArray,
    val armor: ArmorInfo?,
    /** For each ship-local direction in [RAY_STEP] degree steps: the armor cell a hit from there lands on (-1 = none). */
    val rayCells: IntArray,
    val hull: Float,
    val maxHull: Float,

    /** Modules on this ship (armor modules, the Orthrus shell): hits that cross them land there first. */
    val modules: List<SimDefense> = emptyList(),
    val owner: Int = 0,
) {
    /** What the whole ship is worth in the cost unit (hull x 3 + armor): the base of its damage share (§13.3). */
    val worth: Float get() = maxHull * 3f + (armor?.rating ?: 0f)

    companion object {
        const val RAY_STEP = 5
        const val RAYS = 360 / RAY_STEP
    }
}
