package org.starficz.combataitweaks.sim

/** The damage half of a batch: who can shoot, what is already in flight, and the defences of the ships involved. */
class DamageInputs(
    val shooters: List<SimShooter>,
    val shots: List<InFlightShot>,
    val missiles: List<InFlightMissile>,
    /**
     * Captured defences by ship id (modules inside): the protected ships, and the enemy ships near them that their
     * fire is resolved against (plan §13.2).
     */
    val defenses: Map<String, SimDefense>,
    val rules: DamageRules = DamageRules(),
) {
    val shooterById: Map<String, SimShooter> = shooters.associateBy { it.shipId }
}

/** The protected ship's own fire in a branch (plan §13.2). */
class OwnFire(
    /** The ship's own target, which its weapons prefer; null for none. */
    val shipTargetId: String?,
    /** Resolve what the fire does to each enemy (the trade); false: only its flux cost on the ship. */
    val resolveTargets: Boolean,
    /** Hits from the ship's side on each enemy on base paths, by enemy id ([SideFire]); the ship's own are skipped. */
    val sideHits: Map<String, List<Hit>> = emptyMap(),
)

/** What one enemy the protected ship fired at takes in a branch, from it and its side (plan §13.3). */
class TargetOutcome(
    val defense: SimDefense,
    val outcome: DefenseOutcome,
    /** Hits and missiles from the protected ship itself. */
    val ownShots: Int,
)

/** What one branch does to the protected ship (plan §6.6), and what its own fire does (§13). */
class DamageResult(
    /** Every hit, in time order, before the protected ship's defences. */
    val hits: List<Hit>,
    /** The shield used as vanilla would; the ship's own weapons fire on its flux. */
    val normal: DefenseOutcome,
    /** Venting at t = 0 instead (nothing fires); null when not asked for. */
    val ventNow: DefenseOutcome?,
    /** Where an omni shield points per step (NaN = its current offset). */
    val omniFacing: FloatArray,
    val defense: SimDefense,
    /** Each enemy the ship fired at; null when the trade wasn't asked for. */
    val dealt: List<TargetOutcome>? = null,
) {
    /** This result with [ventNow] replaced (a branch re-run with reactions keeps the first run's vent, §13.8). */
    fun withVentNow(ventNow: DefenseOutcome?) = DamageResult(hits, normal, ventNow, omniFacing, defense, dealt)
}

object DamageSim {
    /** An enemy counts as a target when it comes this much closer than the ship's longest weapon range + its radius. */
    private const val REACH_PAD = 150f

    /**
     * Fires everything hostile at [protectedId] along one branch's [timelines] and resolves the hits against its
     * defences, with the ship's own fire ([own]) on its flux. Enemies fire under vanilla's fire discipline, with the
     * [coupling] of a second run: quiet while overloaded, and the pressure on their flux (plan §13.8). Returns null
     * when the ship has no captured defences or no timeline.
     */
    fun run(
        protectedId: String,
        owner: Int,
        timelines: Map<String, Timeline>,
        inputs: DamageInputs,
        withVent: Boolean,
        own: OwnFire? = null,
        coupling: Coupling = Coupling.NONE,
    ): DamageResult? {
        val defense = inputs.defenses[protectedId] ?: return null
        val timeline = timelines[protectedId] ?: return null

        val (fire, missiles, hits) = incoming(defense, owner, timeline, timelines, inputs, coupling, disciplined = true)

        // vanilla's omni shield AI turns to incoming missiles first, else to the ships shooting it
        val omni = if (defense.shield == ShieldKind.OMNI) {
            val ships = fire.omniFacing(defense.shieldArc)
            FloatArray(timeline.steps) { i ->
                val m = missiles.missileBearing[i]
                if (!m.isNaN()) m else ships[i]
            }
        } else FloatArray(timeline.steps) { Float.NaN }

        val resolver = DefenseResolver(defense, inputs.rules, timeline, omni, moduleTimelines(defense, timelines))
        val targets = if (own != null) ownTargets(protectedId, owner, timeline, timelines, inputs) else emptyList()
        val shooter = inputs.shooterById[protectedId]
        val volley = if (shooter != null && targets.isNotEmpty()) {
            Volley(shooter, timeline, targets, targets.indexOfFirst { it.target.shipId == own!!.shipTargetId },
                FireDiscipline(defense.hullClass, defense.dissipation, defense.upkeep))
        } else null
        val normal = resolver.resolve(hits, ShieldPlan.NORMAL, volley?.let { v -> { i, gate -> v.step(i, gate) } })

        val dealt = when {
            own == null || !own.resolveTargets -> null
            volley == null -> emptyList()
            else -> targets.mapNotNull { resolveTarget(it, protectedId, own, fire, timelines, inputs) }
        }

        return DamageResult(
            hits = hits,
            normal = normal,
            // venting, the ship can't defend itself: enemies fire everything until full (the worst case, plan §13.8)
            ventNow = if (withVent) {
                resolver.resolve(incoming(defense, owner, timeline, timelines, inputs, coupling, false).third,
                    ShieldPlan.VENT_NOW)
            } else null,
            omniFacing = omni,
            defense = defense,
            dealt = dealt,
        )
    }

    /**
     * Everything hostile fired at the protected ship along the branch, in time order: guns and systems, missiles
     * launched and in flight, shots in flight. [disciplined]: enemies under vanilla's fire discipline (plan §13.8),
     * else firing everything until full.
     */
    private fun incoming(
        defense: SimDefense, owner: Int, timeline: Timeline, timelines: Map<String, Timeline>, inputs: DamageInputs,
        coupling: Coupling, disciplined: Boolean,
    ): Triple<FireControl, MissileSim, List<Hit>> {
        val fire = FireControl(defense, owner, timeline)
        for (shooter in inputs.shooters) {
            if (shooter.owner == owner) continue
            val tl = timelines[shooter.shipId] ?: continue
            val quiet = coupling.reactions[shooter.shipId]
            val def = inputs.defenses[shooter.shipId]
            val discipline = if (disciplined) {
                FireDiscipline(def?.hullClass ?: HullClass.OTHER, shooter.dissipation, def?.upkeep ?: 0f,
                    FireDiscipline.SLOWER_TARGET)
            } else null
            fire.fire(shooter, tl, quiet, discipline, coupling.pressure[shooter.shipId])
            fire.fireSystem(shooter, tl, quiet)
        }
        val missiles = MissileSim(defense, owner, timeline)
        missiles.run(if (fire.launched.isEmpty()) inputs.missiles else inputs.missiles + fire.launched)
        missiles.shoot(inputs.shots)

        val hits = ArrayList<Hit>(fire.hits.size + missiles.hits.size)
        hits += fire.hits
        hits += missiles.hits
        hits.sortBy { it.time }
        return Triple(fire, missiles, hits)
    }

    /** One [FireControl] per enemy with captured defences that comes within the ship's weapon reach. */
    private fun ownTargets(
        protectedId: String, owner: Int, timeline: Timeline, timelines: Map<String, Timeline>, inputs: DamageInputs,
    ): List<FireControl> {
        val shooter = inputs.shooterById[protectedId] ?: return emptyList()
        if (shooter.weapons.isEmpty()) return emptyList()
        val reach = shooter.weapons.maxOf { it.range } + REACH_PAD
        val result = ArrayList<FireControl>()
        for ((id, def) in inputs.defenses) {
            if (id == protectedId || def.owner == owner) continue
            val tl = timelines[id] ?: continue
            val r = reach + def.hitRadius
            var near = false
            var i = 0
            while (i < tl.steps && !near) {
                val dx = tl.x[i] - timeline.x[i]; val dy = tl.y[i] - timeline.y[i]
                near = dx * dx + dy * dy <= r * r
                i += 4
            }
            if (near) result += FireControl(def, def.owner, tl)
        }
        return result
    }

    /**
     * What [sink]'s enemy takes in the branch: the protected ship's hits and missiles, its side's hits, and its own
     * firing flux (from shooting at the protected ship, under its fire discipline). Null when the protected ship never
     * fired at it.
     */
    private fun resolveTarget(
        sink: FireControl, protectedId: String, own: OwnFire, incoming: FireControl,
        timelines: Map<String, Timeline>, inputs: DamageInputs,
    ): TargetOutcome? {
        if (sink.hits.isEmpty() && sink.launched.isEmpty()) return null
        val def = sink.target
        val all = ArrayList<Hit>(sink.hits)
        if (sink.launched.isNotEmpty()) {
            val m = MissileSim(def, def.owner, sink.targetTimeline)
            m.run(sink.launched)
            all += m.hits
        }
        own.sideHits[def.shipId]?.forEach { if (it.sourceId != protectedId) all += it }
        all.sortBy { it.time }
        val omni = if (def.shield == ShieldKind.OMNI) sink.omniFacing(def.shieldArc)
                   else FloatArray(sink.targetTimeline.steps) { Float.NaN }
        val outcome = DefenseResolver(def, inputs.rules, sink.targetTimeline, omni, moduleTimelines(def, timelines))
            .resolve(all, ShieldPlan.NORMAL, ownFiringFlux = incoming.firingFlux[def.shipId])
        return TargetOutcome(def, outcome, sink.hits.size + sink.launched.size)
    }

    private fun moduleTimelines(defense: SimDefense, timelines: Map<String, Timeline>): Map<String, Timeline> {
        if (defense.modules.isEmpty()) return emptyMap()
        val result = HashMap<String, Timeline>()
        for (module in defense.modules) timelines[module.shipId]?.let { result[module.shipId] = it }
        return result
    }
}

/**
 * The fire of every ship on one side at the enemies with captured defences, everyone on their base paths, by autofire
 * (plan §13.3: allies' fire counts in the trade). Computed once per batch per side; branches add it to what the
 * protected ship itself does. Approximation: the enemies are on their base paths here, not their branch paths.
 */
object SideFire {
    fun compute(owner: Int, timelines: Map<String, Timeline>, inputs: DamageInputs): Map<String, List<Hit>> {
        val sinks = ArrayList<FireControl>()
        for ((id, def) in inputs.defenses) {
            if (def.owner == owner) continue
            val tl = timelines[id] ?: continue
            sinks += FireControl(def, def.owner, tl)
        }
        if (sinks.isEmpty()) return emptyMap()
        for (shooter in inputs.shooters) {
            if (shooter.owner != owner || shooter.weapons.isEmpty()) continue
            val tl = timelines[shooter.shipId] ?: continue
            val def = inputs.defenses[shooter.shipId]
            val volley = Volley(shooter, tl, sinks, -1,
                def?.let { FireDiscipline(it.hullClass, it.dissipation, it.upkeep) }
                    ?: FireDiscipline(HullClass.OTHER, shooter.dissipation, 0f))
            val gate = ShooterFlux(shooter)
            for (i in 0 until tl.steps) {
                gate.tick(i)
                volley.step(i, gate)
            }
        }
        val result = HashMap<String, List<Hit>>()
        for (sink in sinks) {
            val hits = ArrayList<Hit>(sink.hits)
            if (sink.launched.isNotEmpty()) {
                val m = MissileSim(sink.target, sink.target.owner, sink.targetTimeline)
                m.run(sink.launched)
                hits += m.hits
            }
            if (hits.isNotEmpty()) result[sink.target.shipId] = hits
        }
        return result
    }
}
