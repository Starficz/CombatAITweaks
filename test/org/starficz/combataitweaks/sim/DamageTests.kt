package org.starficz.combataitweaks.sim

import org.starficz.combataitweaks.decide.MoveRule
import org.starficz.combataitweaks.decide.ShieldInputs
import org.starficz.combataitweaks.decide.ShieldRule
import org.starficz.combataitweaks.decide.Trade
import kotlin.math.abs
import kotlin.math.max

// Unit tests for the damage half of `sim` (plan §10, armor and damage rows). Hand values are worked out in comments.

private val rules = DamageRules()

/** A 10 x 10 grid of 10-unit cells centred on the ship, every cell at rating / 15 (vanilla's full cell). */
private fun armorInfo(rating: Float = 150f, filled: Float = rating / 15f, w: Int = 10, h: Int = 10) = ArmorInfo(
    width = w, height = h, cells = FloatArray(w * h) { filled }, rating = rating,
    originX = -w * 5f, originY = -h * 5f, axisIX = 10f, axisIY = 0f, axisJX = 0f, axisJY = 10f,
    armorDamageTakenMult = 1f, kineticArmorDamageTakenMult = 1f, effectiveArmorBonus = Bonus.NONE,
    minArmorFraction = 0.05f, maxArmorDamageReduction = 0.85f, hullDamageTakenMult = 1f,
)

/** A square ship +-40 around its centre. */
private val squareBounds = floatArrayOf(40f, -40f, 40f, 40f, 40f, 40f, -40f, 40f, -40f, 40f, -40f, -40f, -40f, -40f, 40f, -40f)

private fun defense(
    shield: ShieldKind = ShieldKind.FRONT, arc: Float = 90f, shieldOn: Boolean = true, unfoldTime: Float = 0.5f,
    flux: Float = 0f, hard: Float = 0f, maxFlux: Float = 1000f, dissipation: Float = 100f, ventRate: Float = 250f,
    venting: Boolean = false, overload: Float = 0f, upkeep: Float = 0f, armor: ArmorInfo? = armorInfo(),
    hull: Float = 1000f, hullClass: HullClass = HullClass.FRIGATE, modules: List<SimDefense> = emptyList(),
    id: String = "P", radius: Float = 50f, owner: Int = 0,
) = SimDefense(
    shipId = id, hullClass = hullClass, isDrone = false, collisionRadius = radius, hitRadius = radius, timeMult = 1f,
    shield = shield, shieldArc = arc, shieldOffset = 0f, shieldOn = shieldOn, shieldActiveArc = if (shieldOn) arc else 0f,
    unfoldTime = unfoldTime, upkeep = upkeep, fluxPerDamage = 1f, shieldDamageTakenMult = 1f,
    shieldKindMult = FloatArray(5) { 1f }, shieldDeliveryMult = FloatArray(3) { 1f },
    flux = flux, hardFlux = hard, maxFlux = maxFlux, dissipation = dissipation, hardFluxDissipationFraction = 0f,
    ventRate = ventRate, isVenting = venting, overloadLeft = overload, overloadTimeMod = Bonus.NONE,
    kindMult = FloatArray(5) { 1f }, deliveryMult = FloatArray(3) { 1f },
    armor = armor, rayCells = DefenseResolver.rayCells(armor, squareBounds, 40f), hull = hull, maxHull = hull,
    modules = modules, owner = owner,
)

private fun still(steps: Int = 200, x: Float = 0f, y: Float = 0f, facing: Float = 0f) = Timeline(steps).apply {
    for (i in 0 until steps) { this.x[i] = x; this.y[i] = y; this.facing[i] = facing }
}

private fun hit(
    time: Float, damage: Float, kind: DamageKind, fromX: Float = 500f, fromY: Float = 0f,
    delivery: Delivery = Delivery.PROJECTILE, soft: Boolean = false, conservative: Boolean = false,
    strength: Float = damage,
) = Hit(time, damage, strength, 0f, kind, delivery, soft, conservative, fromX, fromY, "E")

private fun resolve(def: SimDefense, hits: List<Hit>, plan: ShieldPlan = ShieldPlan.NORMAL, steps: Int = 200) =
    DefenseResolver(def, rules, still(steps), FloatArray(steps) { Float.NaN }, emptyMap())
        .resolve(hits.sortedBy { it.time }, plan)

private fun gun(
    damage: Float = 100f, range: Float = 800f, arc: Float = 30f, cooldown: Float = 1f, burst: Int = 1,
    burstDelay: Float = 0.1f, fluxCost: Float = 0f, ammo: Int = -1, mode: FireMode = FireMode.PROJECTILE,
    speed: Float = 1000f, chargeUp: Float = 0f, burstDuration: Float = 0f, conservative: Boolean = false,
    missile: MissileInfo? = null, noAim: Boolean = false,
) = SimWeapon(
    mountX = 0f, mountY = 0f, slotAngle = 0f, arc = arc, turnRate = 90f, angle = 0f, range = range, mode = mode,
    damage = damage, emp = 0f, kind = DamageKind.ENERGY, softFlux = mode != FireMode.PROJECTILE && missile == null,
    chargeUp = chargeUp, burstSize = burst, burstDelay = burstDelay, burstDuration = burstDuration,
    cooldown = cooldown, fluxCost = fluxCost, phase = WeaponPhase.READY, phaseTimeLeft = 0f, shotsLeftInBurst = 0,
    disabledFor = 0f, ammo = ammo, maxAmmo = ammo.coerceAtLeast(0), ammoPerSecond = 0f, reloadSize = 0f,
    speed = speed, spread = 0f, conservative = conservative, noAim = noAim, missile = missile, sustainedDps = damage,
)

private fun shooter(
    vararg weapons: SimWeapon, flux: Float = 0f, maxFlux: Float = 10000f, dissipation: Float = 100f,
    silence: Float = 0f, id: String = "E",
) = SimShooter(id, 1, flux, 0f, maxFlux, dissipation, 1f, silence, FloatArray(6) { 1f }, weapons.toList())

/** Fires one shooter sitting at (x, 0) facing the protected ship at the origin, for [steps] steps. */
private fun fireAt(s: SimShooter, x: Float = 600f, steps: Int = 400): FireControl {
    val fc = FireControl(defense(), 0, still(steps))
    fc.fire(s, still(steps, x = x, facing = 180f))
    return fc
}

private fun missileInfo(
    maxSpeed: Float = 500f, accel: Float = 1000f, turn: Float = 120f, flight: Float = 10f, mirv: MirvInfo? = null,
) = MissileInfo(
    maxSpeed = maxSpeed, acceleration = accel, maxTurnRate = turn, turnAcceleration = turn * 2f, launchSpeed = 50f,
    maxFlightTime = flight, flameoutTime = 1f, fadeTime = 0.5f, armingTime = 0f,
    noCollisionWhileFading = false, reduceDamageWhileFading = true, mirv = mirv,
)

private fun missile(
    x: Float, y: Float = 0f, vx: Float = 0f, vy: Float = 0f, facing: Float = 180f, info: MissileInfo = missileInfo(),
    damage: Float = 500f, target: String? = "P",
) = InFlightMissile(x, y, vx, vy, facing, 0f, 1, damage, 0f, DamageKind.HIGH_EXPLOSIVE, false, info, 0f, target)

/** Ship systems that deal damage (drone strike, EMP emitter), from their AI hints. */
private fun systemThreats() {
    // a weaponless shooter 1000 away facing the target, its 1200-damage system ready, range 1500, speed 500
    fun sys(readyIn: Float = 0f, cycle: Float = 20f, uses: Int = Int.MAX_VALUE, arc: Float = 360f, angle: Float = 0f,
            range: Float = 1500f, speed: Float = 500f) =
        SimShooter("E", 1, 0f, 0f, 1000f, 100f, 1f, 0f, FloatArray(6) { 1f }, emptyList(),
            SystemThreat(readyIn, cycle, uses, 1200f, 0f, DamageKind.ENERGY, range, arc, angle, speed))
    fun fire(s: SimShooter, x: Float = 1000f, facing: Float = 180f): FireControl {
        val fc = FireControl(defense(), 0, still(200))
        fc.fireSystem(s, still(200, x = x, facing = facing))
        return fc
    }
    val ready = fire(sys())
    check(ready.hits.size == 1, "system: one hit per use within the horizon") { "${ready.hits.size}" }
    // used at the first step (0.05 s), travel (1000 - 50) / 500 = 1.9 s
    near(ready.hits.first().time, 0.05f + 1.9f, 0.01f, "system: arrives after the travel at its threat speed")
    near(ready.hits.first().damage, 1200f, 0.01f, "system: one hit of its threat damage")
    check(ready.hits.first().delivery == Delivery.MISSILE, "system: a travelling threat hits as a missile")
    near(fire(sys(readyIn = 3f)).hits.first().time, 3f + 1.9f, 0.06f, "system: waits until it is ready")
    check(fire(sys(range = 800f)).hits.isEmpty(), "system: nothing outside its threat range (+ the target's radius)")
    check(fire(sys(arc = 90f), facing = 0f).hits.isEmpty(), "system: nothing outside its threat arc")
    check(fire(sys(arc = 90f, angle = 180f), facing = 0f).hits.size == 1, "system: the arc is centred on facing + threat angle")
    check(fire(sys(cycle = 4f)).hits.size == 3, "system: used again after each cycle") { "${fire(sys(cycle = 4f)).hits.size}" }
    check(fire(sys(cycle = 4f, uses = 1)).hits.size == 1, "system: no more uses than its ammo")
    near(fire(sys(speed = 20000f)).hits.first().time, 0.05f, 0.001f, "system: an instant threat lands when used")
}

internal fun damage() {
    systemThreats()
    var worst = 0.0
    for (i in 0 until 3600) {
        val a = i * Math.PI / 1800.0
        for (r in floatArrayOf(0.5f, 7f, 3000f)) {
            val x = (Math.cos(a) * r).toFloat(); val y = (Math.sin(a) * r).toFloat()
            var d = abs(fastAtan2(y, x) - Math.atan2(y.toDouble(), x.toDouble()))
            if (d > Math.PI) d = 2 * Math.PI - d
            worst = maxOf(worst, d)
        }
    }
    check(worst < 0.002, "math: fast atan2 within 0.002 rad of Math.atan2") { "worst $worst" }
    armor()
    shields()
    fireControl()
    missiles()
    endToEnd()
    autofire()
    ownFire()
    tradeRule()
    reactions()
    shieldFlicker()
}

/** Shield flicker (plan §13.9): the shield-covered hit times, and the drop rule. */
private fun shieldFlicker() {
    // a front shield facing +x: a hit from the front is covered, one from behind isn't
    val o = resolve(defense(), listOf(
        hit(1f, 100f, DamageKind.KINETIC),
        hit(2f, 100f, DamageKind.KINETIC, fromX = -500f),
        hit(3f, 100f, DamageKind.KINETIC, conservative = true),
    ))
    check(o.shieldHitTimes == listOf(1f, 3f), "flicker: covered hits recorded, held conservative ones too, rear not") {
        "${o.shieldHitTimes}"
    }

    // a ship with plenty to drain: 1000 flux, 400 of it hard, dissipation 300, upkeep 150
    fun drop(next: Float?, window: Float = 0.7f, live: String? = null, blocked: String? = null) =
        ShieldRule.decide(ShieldInputs(next, window, live, blocked, 1000f, 400f, 5000f, 300f, 150f)).drop
    check(drop(Float.MAX_VALUE), "flicker: nothing predicted -> drop")
    check(drop(1.5f), "flicker: next shield hit 0.8 s after the window -> drop (gains 120)")
    check(!drop(1.0f), "flicker: only 0.3 s down -> keep (gains 45, under 1% of max flux)")
    check(!drop(0.6f), "flicker: next shield hit within the window -> keep")
    check(!drop(null), "flicker: no prediction -> keep")
    check(!drop(5f, live = "beam"), "flicker: a live threat -> keep")
    check(!drop(5f, blocked = "overloaded"), "flicker: blocked -> keep")

    // the gain: 2 s down drains min(1000, 600) = 600; up drains soft only, min(600, 150 x 2) = 300 -> 300
    near(ShieldRule.gain(ShieldInputs(null, 0.7f, null, null, 1000f, 400f, 5000f, 300f, 150f), 2f), 300f, 0.5f,
        "flicker: gain = drained down - drained up (upkeep and hard flux)")
    // no hard flux, little soft flux: it drains just as well with the shield up -> nothing to gain
    check(!ShieldRule.decide(ShieldInputs(Float.MAX_VALUE, 0.7f, null, null, 100f, 0f, 3500f, 250f, 100f)).drop,
        "flicker: little soft flux and good net dissipation -> keep")
    // no hard flux but upkeep slows draining a full bar: worth dropping
    check(ShieldRule.decide(ShieldInputs(Float.MAX_VALUE, 0.7f, null, null, 2000f, 0f, 3500f, 250f, 100f)).drop,
        "flicker: soft flux only, but upkeep slows its drain -> drop")
    // upkeep over dissipation: flux rises with the shield up
    near(ShieldRule.gain(ShieldInputs(null, 0.7f, null, null, 0f, 0f, 3500f, 50f, 100f), 1f), 50f, 0.5f,
        "flicker: upkeep over dissipation counts as flux saved")
}

/** Autofire target choice (plan §13.1): ship target first, else least turn; STRIKE skips frigates. */
private fun autofire() {
    // P at the origin facing +x; A straight ahead at 500, B at 600 up 200 (about 18 degrees off), both in a 90 arc
    fun run(weapon: SimWeapon, shipTarget: Int, bX: Float = 600f, aClass: HullClass = HullClass.FRIGATE): Pair<Int, Int> {
        val a = FireControl(defense(id = "A", owner = 1, hullClass = aClass), 1, still(200, x = 500f))
        val b = FireControl(defense(id = "B", owner = 1, hullClass = HullClass.DESTROYER), 1, still(200, x = bX, y = 200f))
        val shooter = SimShooter("P", 0, 0f, 0f, 10000f, 100f, 1f, 0f, FloatArray(6) { 1f }, listOf(weapon))
        val v = Volley(shooter, still(200), listOf(a, b), shipTarget)
        val gate = ShooterFlux(shooter)
        for (i in 0 until 200) { gate.tick(i); v.step(i, gate) }
        return a.hits.size to b.hits.size
    }
    val g = gun(range = 800f, arc = 90f)
    val least = run(g, -1)
    check(least.first > 0 && least.second == 0, "autofire: no ship target -> the one needing the least turn") { "$least" }
    val ship = run(g, 1)
    check(ship.first == 0 && ship.second > 0, "autofire: the ship's target when in range and arc") { "$ship" }
    val outOfRange = run(g, 1, bX = 1500f)
    check(outOfRange.first > 0 && outOfRange.second == 0, "autofire: the ship's target out of range -> another") {
        "$outOfRange"
    }
    val strike = gun(range = 800f, arc = 90f).let {
        SimWeapon(it.mountX, it.mountY, it.slotAngle, it.arc, it.turnRate, it.angle, it.range, it.mode, it.damage,
            it.emp, it.kind, it.softFlux, it.chargeUp, it.burstSize, it.burstDelay, it.burstDuration, it.cooldown,
            it.fluxCost, it.phase, it.phaseTimeLeft, it.shotsLeftInBurst, it.disabledFor, it.ammo, it.maxAmmo,
            it.ammoPerSecond, it.reloadSize, it.speed, it.spread, it.conservative, it.noAim, it.missile,
            it.sustainedDps, strike = true)
    }
    val s = run(strike, -1)
    check(s.first == 0 && s.second > 0, "autofire: STRIKE skips the frigate") { "$s" }
    val sd = run(strike, -1, aClass = HullClass.DESTROYER)
    check(sd.first > 0, "autofire: STRIKE takes a destroyer") { "$sd" }
}

/** The ship's own fire on its own flux (plan §13.2): shots stop when the flux bar can't take them. */
private fun ownFire() {
    // no dissipation, max 1000, 300 flux a shot every 0.5 s: three shots fit, the fourth doesn't
    val def = defense(maxFlux = 1000f, dissipation = 0f)
    val target = FireControl(defense(id = "A", owner = 1), 1, still(200, x = 500f))
    val shooter = SimShooter("P", 0, 0f, 0f, 1000f, 0f, 1f, 0f, FloatArray(6) { 1f },
        listOf(gun(range = 800f, arc = 90f, cooldown = 0.5f, fluxCost = 300f)))
    val v = Volley(shooter, still(200), listOf(target), 0)
    val out = DefenseResolver(def, rules, still(200), FloatArray(200) { Float.NaN }, emptyMap())
        .resolve(emptyList(), ShieldPlan.NORMAL, { i, gate -> v.step(i, gate) })
    check(target.hits.size == 3, "own fire: fires until the flux bar is full") { "${target.hits.size}" }
    near(out.flux[199], 900f, 1f, "own fire: its firing flux stays on the ship")

    // overloaded: nothing fires
    val overloaded = defense(maxFlux = 1000f, dissipation = 0f, overload = 20f)
    val t2 = FireControl(defense(id = "A", owner = 1), 1, still(200, x = 500f))
    val v2 = Volley(shooter, still(200), listOf(t2), 0)
    DefenseResolver(overloaded, rules, still(200), FloatArray(200) { Float.NaN }, emptyMap())
        .resolve(emptyList(), ShieldPlan.NORMAL, { i, gate -> v2.step(i, gate) })
    check(t2.hits.isEmpty(), "own fire: nothing fires while overloaded")

    // vanilla's fire discipline: max 1000, dissipation 50. An 80 flux/s gun (over half the dissipation) stops near
    // 0.2 flux; a 20 flux/s one (within half) keeps firing
    fun disciplined(fluxCost: Float): Pair<Int, Float> {
        val d = defense(maxFlux = 1000f, dissipation = 50f)
        val t = FireControl(defense(id = "A", owner = 1), 1, still(200, x = 500f))
        val sh = SimShooter("P", 0, 0f, 0f, 1000f, 50f, 1f, 0f, FloatArray(6) { 1f },
            listOf(gun(range = 800f, arc = 90f, cooldown = 0.25f, fluxCost = fluxCost)))
        val v = Volley(sh, still(200), listOf(t), 0, FireDiscipline(HullClass.FRIGATE, 50f, 0f))
        val o = DefenseResolver(d, rules, still(200), FloatArray(200) { Float.NaN }, emptyMap())
            .resolve(emptyList(), ShieldPlan.NORMAL, { i, gate -> v.step(i, gate) })
        return t.hits.size to o.flux.max()
    }
    val costly = disciplined(20f)   // 80 flux/s, net +30/s
    check(costly.second < 0.25f * 1000f, "discipline: a flux-hungry gun stops near the 0.2 threshold") { "${costly.second}" }
    check(costly.first > 0, "discipline: it fires while flux is low") { "${costly.first}" }
    val neutral = disciplined(5f)   // 20 flux/s, under half the dissipation
    check(neutral.first >= 38, "discipline: a flux-neutral gun fires all the time") { "${neutral.first}" }

    // a whole branch: P fires at E while E fires back; E's outcome is resolved for the trade
    val p = ship("P", owner = 0, maxSpeed = 0f, accel = 0f, decel = 0f, turnAccel = 0f, maxTurn = 0f)
    val e = ship("E", owner = 1, x = 700f, facing = 180f, maxSpeed = 0f, accel = 0f, decel = 0f, engagement = 500f,
        reach = 800f)
    val world = World(listOf(p, e), SimParams(200))
    val branch = world.branch("P", OwnPlan.Hold())
    val pShooter = SimShooter("P", 0, 0f, 0f, 1000f, 100f, 1f, 0f, FloatArray(6) { 1f },
        listOf(gun(range = 800f, arc = 60f, fluxCost = 50f)))
    val inputs = DamageInputs(listOf(shooter(gun(range = 800f, arc = 60f, fluxCost = 50f)), pShooter),
        emptyList(), emptyList(), mapOf("P" to defense(maxFlux = 5000f), "E" to defense(id = "E", owner = 1)))
    val r = DamageSim.run("P", 0, branch.timelines, inputs, withVent = false, own = OwnFire("E", true))
    check(r != null && r.dealt != null && r.dealt!!.size == 1, "trade: the enemy it fires at is resolved") {
        "${r?.dealt?.size}"
    }
    val dealt = r?.dealt?.firstOrNull() ?: return
    check(dealt.outcome.flux[199] > 0f, "trade: its shield takes the ship's fire")
    val noTrade = DamageSim.run("P", 0, branch.timelines, inputs, withVent = false, own = OwnFire("E", false))
    check(noTrade?.dealt == null, "trade: not resolved unless asked for")
    check(noTrade != null && abs(noTrade.normal.flux[199] - r.normal.flux[199]) < 1f,
        "trade: the ship's own firing flux is on it either way")
    val noOwn = DamageSim.run("P", 0, branch.timelines, inputs, withVent = false)
    check(noOwn != null && noOwn.normal.flux[199] < r.normal.flux[199],
        "trade: without its own fire the ship ends with less flux") { "${noOwn?.normal?.flux?.get(199)} vs ${r.normal.flux[199]}" }
}

/** Enemies backing off under the ship's fire, and going quiet while overloaded (plan §13.8). */
private fun reactions() {
    // thresholds, as vanilla's StrafeTargetManeuverV2
    fun traits(personality: String, phase: Boolean = false, cr: Float = 0f, onFlux: Boolean = true) =
        BackOffTraits.forPersonality(personality).let { (b, s, f) -> BackOffTraits(b, s, f, phase, cr, onFlux, true) }
    near(traits("steady").hardFluxThreshold(HullClass.FRIGATE, HullClass.FRIGATE), 0.5f, 1e-4f,
        "back-off: steady vs its own size at 0.5")
    near(traits("steady").hardFluxThreshold(HullClass.FRIGATE, HullClass.CRUISER), 0.2f, 1e-4f,
        "back-off: steady frigate vs a cruiser drops to its 0.2 floor")
    near(traits("aggressive").hardFluxThreshold(HullClass.FRIGATE, HullClass.CAPITAL), 0.35f, 1e-4f,
        "back-off: aggressive frigate vs a capital 0.8 - 3 x 0.15")
    near(traits("steady").hardFluxThreshold(HullClass.CRUISER, HullClass.FRIGATE), 0.5f, 1e-4f,
        "back-off: a smaller target doesn't lower it")
    near(traits("cautious", phase = true, cr = 0.1f).hardFluxThreshold(HullClass.FRIGATE, HullClass.FRIGATE), 0.5f,
        1e-4f, "back-off: phase + 0.1 and the CR offset")

    // a whole branch: P holds and fires at E, a steady frigate closing in; E's shield fills with hard flux
    val steps = 200
    fun run(eTraits: BackOffTraits?, eSilence: Float = 0f, target: String? = "P"): Triple<World, BranchResult, DamageResult?> {
        val p = ship("P", owner = 0, maxSpeed = 0f, accel = 0f, decel = 0f, turnAccel = 0f, maxTurn = 0f, reach = 800f)
        val e = ship("E", owner = 1, x = 900f, facing = 180f, engagement = 500f, reach = 600f, maxSpeed = 120f,
            accel = 200f, decel = 200f, backOff = eTraits, silence = eSilence, maneuverTarget = target)
        val world = World(listOf(p, e), SimParams(steps))
        val branch = world.branch("P", OwnPlan.Hold())
        val pShooter = SimShooter("P", 0, 0f, 0f, 1000f, 100f, 1f, 0f, FloatArray(6) { 1f },
            listOf(gun(damage = 100f, range = 800f, arc = 60f, cooldown = 0.2f)))
        val inputs = DamageInputs(listOf(shooter(gun(range = 600f, arc = 60f, cooldown = 0.5f)), pShooter),
            emptyList(), emptyList(), mapOf("P" to defense(maxFlux = 5000f), "E" to defense(id = "E", owner = 1)))
        return Triple(world, branch, DamageSim.run("P", 0, branch.timelines, inputs, withVent = false,
            own = OwnFire("E", true)))
    }
    val (world, branch, first) = run(traits("steady"))
    val coupling = if (first != null) Reactions.find(world, "P", branch.committed.keys, first) else Coupling.NONE
    val found = coupling.reactions
    val r = found["E"]
    check(r != null && r.cause == Reaction.Cause.FLUX && !r.retreat && r.from > 0f,
        "back-off: a frigate past its hard flux threshold stops pressing in (no retreat)") { "${r?.cause} ${r?.from}" }
    if (r != null && first != null) {
        val hardAt = first.dealt!!.first().outcome.let { it.hardFlux[it.at(r.from)] / 1000f }
        check(hardAt > 0.5f, "back-off: it starts where its hard flux passes 0.5") { "$hardAt" }
        val again = world.branch("P", OwnPlan.Hold(), found)
        fun dist(b: BranchResult, i: Int) = b.timelines["E"]!!.let { length(it.x[i], it.y[i]) }
        // pursuing it holds 500 + radii - 200 = 400; backing off, its engagement range + radii = 600
        near(dist(branch, steps - 1), 400f, 60f, "back-off: pursuing, it presses in to 400")
        near(dist(again, steps - 1), 600f, 60f, "back-off: backing off, it holds 600 without pressing in")
    }

    // fighting another ship: it would back off from that one, so no move predicted
    val (w4, b4, d4) = run(traits("steady"), target = null)
    val other = if (d4 != null) Reactions.find(w4, "P", b4.committed.keys, d4).reactions["E"] else null
    check(other == null || !other.moves, "back-off: not when its target is another ship") { "${other?.cause}" }

    // automated ships (no back-off while not venting) and DO_NOT_BACK_OFF: no reaction on flux
    val (w2, b2, d2) = run(traits("steady", onFlux = false))
    val auto = if (d2 != null) Reactions.find(w2, "P", b2.committed.keys, d2).reactions["E"] else null
    check(d2 != null && auto?.cause != Reaction.Cause.FLUX, "back-off: automated ships don't back off on flux") {
        "${auto?.cause}"
    }
    check(auto == null || auto.cause == Reaction.Cause.OVERLOAD, "back-off: only once they overload")

    // the ship overloaded with 3 s more to go: enemies press instead
    if (first != null) {
        val overloaded = DefenseOutcome(steps).apply { overloadStart = 0f; overloadDuration = 10f }
        val pressed = DamageResult(first.hits, overloaded, null, first.omniFacing, first.defense, first.dealt)
        val late = Reactions.find(world, "P", branch.committed.keys, pressed).reactions["E"]
        check(late == null || late.cause != Reaction.Cause.FLUX || late.from >= 7f - 1e-3f,
            "back-off: not on flux while the ship stays overloaded 3 s more") { "${late?.cause} ${late?.from}" }
    }

    // overloaded for 3 s ("in danger"): a frigate retreats, at any range
    val (w3, _, _) = run(traits("steady"))
    val danger = Reaction(Reaction.Cause.OVERLOAD, 0f, 3f, true, true, Float.MAX_VALUE, 0f, 3f)
    val back = w3.branch("P", OwnPlan.Hold(), mapOf("E" to danger)).timelines["E"]!!
    val at3 = (3f / DT).toInt() - 1
    check(length(back.x[at3], back.y[at3]) > 900f, "back-off: retreating, it ends farther than it started") {
        "${length(back.x[at3], back.y[at3])}"
    }

    check(coupling.pressure["E"]?.let { it.max() > 100f } == true, "pressure: the hard flux P put on E is passed on") {
        "${coupling.pressure["E"]?.max()}"
    }

    // enemies fire under vanilla's discipline: a 40 flux/s gun, starting at 400 of 1000 flux with no dissipation, vs a
    // frigate like itself, stops near 0.2 + 0.07 + 0.25 (slower target) = 0.52; pressure on its flux bar stops it
    // sooner
    fun enemyFlux(pressure: FloatArray?): Pair<Int, Float> {
        val fc = FireControl(defense(), 0, still(200))
        val s = shooter(gun(cooldown = 0.25f, fluxCost = 10f), flux = 400f, maxFlux = 1000f, dissipation = 0f)
        fc.fire(s, still(200, x = 600f, facing = 180f), discipline = FireDiscipline(HullClass.FRIGATE, 0f, 0f,
            FireDiscipline.SLOWER_TARGET), pressure = pressure)
        var f = 400f; var peak = 0f
        for (v in fc.firingFlux["E"]!!) { f += v; peak = max(peak, f) }
        return fc.hits.size to peak
    }
    val calm = enemyFlux(null)
    check(calm.second in 480f..650f, "enemy discipline: stops near 0.52 flux") { "${calm.second}" }
    val pressedHard = enemyFlux(FloatArray(200) { 400f })
    check(pressedHard.first < calm.first, "enemy discipline: pressure on its shield makes it fire less") {
        "${pressedHard.first} vs ${calm.first}"
    }

    // venting, the ship can't defend itself: the vent outcome has enemies fire everything, the normal one doesn't
    run {
        val p = ship("P", owner = 0, maxSpeed = 0f, accel = 0f, decel = 0f, turnAccel = 0f, maxTurn = 0f)
        val e = ship("E", owner = 1, x = 600f, facing = 180f, maxSpeed = 0f, accel = 0f, decel = 0f)
        val world = World(listOf(p, e), SimParams(steps))
        val b = world.branch("P", OwnPlan.Hold())
        val costly = shooter(gun(range = 800f, arc = 60f, cooldown = 0.25f, fluxCost = 50f), flux = 400f,
            maxFlux = 1000f, dissipation = 0f)
        val inputs = DamageInputs(listOf(costly), emptyList(), emptyList(),
            mapOf("P" to defense(maxFlux = 100000f, hull = 100000f, armor = null)))
        val d = DamageSim.run("P", 0, b.timelines, inputs, withVent = true)!!
        val ventOnDisciplined = DefenseResolver(d.defense, rules, b.timelines["P"]!!, d.omniFacing, emptyMap())
            .resolve(d.hits, ShieldPlan.VENT_NOW)
        check(d.ventNow!!.hullLost[steps - 1] > ventOnDisciplined.hullLost[steps - 1] * 1.3f,
            "vent: enemies fire everything at a venting ship") {
            "${d.ventNow!!.hullLost[steps - 1]} vs ${ventOnDisciplined.hullLost[steps - 1]}"
        }
    }

    // an overloaded enemy's weapons go quiet
    val quiet = Reaction(Reaction.Cause.OVERLOAD, 1f, 3f, true, true, Float.MAX_VALUE, 1f, 3f)
    val fc = FireControl(defense(), 0, still(200))
    fc.fire(shooter(gun(cooldown = 0.25f, speed = 1000f)), still(200, x = 600f, facing = 180f), quiet)
    check(fc.hits.none { it.time - 0.6f > 1.1f && it.time - 0.6f < 2.9f },
        "quiet: no shots while overloaded") { fc.hits.joinToString { "%.2f".format(it.time) } }
    check(fc.hits.any { it.time > 3.5f }, "quiet: fires again after")
}

/** The trade rule (plan §13.3). */
private fun tradeRule() {
    check(MoveRule.favourable(null), "trade rule: not predicted -> favourable")
    check(MoveRule.favourable(Trade(0f, 0f, 0f, 0f)), "trade rule: taking nothing is free")
    check(MoveRule.favourable(Trade(0.10f, 0.05f, 0f, 0.3f)), "trade rule: more damage share dealt than taken")
    check(!MoveRule.favourable(Trade(0.05f, 0.10f, 0.2f, 0.3f)), "trade rule: losing both ways -> no")
    check(MoveRule.favourable(Trade(0f, 0f, 0.30f, 0.30f)), "trade rule: an even flux trade is enough")
    check(!MoveRule.favourable(Trade(0f, 0f, 0.2f, 0.4f)), "trade rule: losing the flux trade -> no")
}

private fun armor() {
    val g = ArmorGrid(armorInfo(), rules)
    near(g.armorAround(5, 5), 150f, 0.01f, "armor: a full grid sums to the armor rating")

    // HE 500 dmg / 500 strength: m = 2, so 1000 vs armor 150 -> reduction 150 / 1150 = 0.1304, 869.6 through.
    // Inner cells get 57.97 (overflow 47.97 x 9 = 431.7), outer 28.99 (overflow 18.99 x 12 = 227.9).
    // Overflow 659.6 x hull mult (1 / m = 0.5) = 329.8 hull.
    near(g.hit(5, 5, 500f, 500f, DamageKind.HIGH_EXPLOSIVE, beam = false), 329.8f, 0.5f,
        "armor: HE hit overflows to hull at hull mult / armor mult")
    near(g.cells[g.index(5, 5)], 0f, 1e-4f, "armor: the hit cell is stripped")

    // kinetic 500: m = 0.5, 250 vs 150 -> reduction 0.375, 156.25 through; inner 10.42 (overflow 0.417 x 9 = 3.75),
    // outer 5.21 (none). Hull = 3.75 x (1 / 0.5) = 7.5.
    val k = ArmorGrid(armorInfo(), rules)
    near(k.hit(5, 5, 500f, 500f, DamageKind.KINETIC, beam = false), 7.5f, 0.05f,
        "armor: kinetic barely gets through full armor")

    // stripped grid: armor floored at 150 x 0.05 = 7.5. HE 100: 200 vs 7.5 -> reduction 0.0361, 192.77 all overflow.
    val bare = ArmorGrid(armorInfo(filled = 0f), rules)
    near(bare.hit(5, 5, 100f, 100f, DamageKind.HIGH_EXPLOSIVE, beam = false), 96.39f, 0.05f,
        "armor: minimum armor still blocks a little on a stripped grid")

    // beam: hit strength = DPS x 0.5, so a 100 DPS energy beam tick of 10 is 10 vs 150 at strength 50
    // -> reduction min(150 / 200, 0.85) = 0.75, 2.5 through, nothing to hull
    val b = ArmorGrid(armorInfo(), rules)
    near(b.hit(5, 5, 10f, 100f, DamageKind.ENERGY, beam = true), 0f, 1e-4f, "armor: weak beam ticks don't reach hull")
    near(b.lastArmorDamage, 2.5f, 0.01f, "armor: beam hit strength is DPS x dpsToHitStrengthMult")

    // huge armor: reduction caps at 0.85
    val thick = ArmorGrid(armorInfo(rating = 15000f), rules)
    thick.hit(5, 5, 100f, 100f, DamageKind.ENERGY, beam = false)
    near(thick.lastArmorDamage, 15f, 0.01f, "armor: damage reduction caps at maxArmorDamageReduction")

    // a hit at the grid edge spills its off-grid share straight to hull
    val edge = ArmorGrid(armorInfo(), rules)
    check(edge.hit(0, 0, 50f, 50f, DamageKind.ENERGY, beam = false) > 0f, "armor: off-grid shares go to hull")

    val cell = ArmorGrid.cellAt(armorInfo(), 35f, 0f)
    check(cell != null && cell / 10 == 8 && cell % 10 == 5, "armor: cellAt maps ship-local points to cells") { "$cell" }
    val rays = DefenseResolver.rayCells(armorInfo(), squareBounds, 40f)
    val front = rays[DefenseResolver.rayIndex(0f)]
    val back = rays[DefenseResolver.rayIndex(180f)]
    check(front / 10 == 8 && back / 10 == 1, "armor: hits from the front land on front cells, from behind on rear ones") {
        "front $front back $back"
    }
}

private fun shields() {
    // kinetic 100 on the shield: 100 x 2 = 200 hard flux; hard flux doesn't dissipate with the shield up
    val k = resolve(defense(), listOf(hit(0.01f, 100f, DamageKind.KINETIC)))
    near(k.flux[0], 200f, 0.01f, "shield: kinetic does double flux")
    near(k.flux[40], 200f, 0.01f, "shield: hard flux stays while the shield is up")
    near(k.hullLost[199], 0f, 0f, "shield: blocked hits do nothing to hull")

    // a soft-flux beam tick dissipates
    val soft = resolve(defense(), listOf(hit(0.01f, 100f, DamageKind.ENERGY, delivery = Delivery.BEAM, soft = true)))
    near(soft.flux[0], 95f, 0.01f, "shield: soft flux dissipates at once")
    near(soft.hardFlux[0], 0f, 0f, "shield: beam flux is soft")

    // from behind the front shield: armor
    val rear = resolve(defense(), listOf(hit(0.01f, 500f, DamageKind.HIGH_EXPLOSIVE, fromX = -500f)))
    check(rear.armorLost[199] > 0f && rear.flux[0] == 0f, "shield: hits outside the arc land on armor") {
        "armor ${rear.armorLost[199]} flux ${rear.flux[0]}"
    }

    // overload: 900 + 200 = 1100 > 1000, overflow 100 -> frigate 4 + 100 / 25 = 8 s
    val over = resolve(defense(flux = 900f, hard = 900f),
        listOf(hit(0.01f, 100f, DamageKind.KINETIC), hit(1f, 500f, DamageKind.HIGH_EXPLOSIVE)))
    near(over.overloadStart, 0.01f, 0.001f, "shield: overload starts when flux passes max")
    near(over.overloadDuration, 8f, 0.01f, "shield: overload time is hull base plus overflow / 25")
    check(over.armorLost[199] > 0f, "shield: hits during an overload land on armor")
    near(over.flux[over.at(5f)], 1000f - 50f * 5f, 1f, "shield: flux drains at half dissipation while overloaded")

    // venting: the shield is down until the vent ends (500 flux at 250 / s = 2 s)
    val hits = listOf(hit(1f, 500f, DamageKind.HIGH_EXPLOSIVE), hit(3f, 500f, DamageKind.HIGH_EXPLOSIVE))
    val vent = resolve(defense(flux = 500f), hits, ShieldPlan.VENT_NOW)
    val stay = resolve(defense(flux = 500f), hits, ShieldPlan.NORMAL)
    near(vent.flux[vent.at(2f)], 0f, 1f, "vent: flux drains at the vent rate")
    check(vent.armorLost[vent.at(2f)] > 0f && stay.armorLost[199] == 0f, "vent: hits while venting land on armor")
    check(!vent.shieldUp[vent.at(3f)] && vent.hullLost[199] > vent.hullLost[vent.at(2f)],
        "vent: the shield stays down for the whole horizon (the decision measures its own window)")
    // a snapshot taken at 0 flux still vents: the live flux may have risen since
    val zero = resolve(defense(flux = 0f), hits, ShieldPlan.VENT_NOW)
    check(zero.armorLost[zero.at(2f)] > 0f, "vent: venting from a 0-flux snapshot still drops the shield")

    // conservative shots wait for the shield to drop, then land
    val held = resolve(defense(), listOf(hit(0.5f, 500f, DamageKind.HIGH_EXPLOSIVE, conservative = true)))
    near(held.armorLost[199], 0f, 0f, "conservative: held while the shield covers it")
    near(held.potentialFlux[199], 0f, 0f, "conservative: not counted as shield flux")
    val released = resolve(defense(flux = 950f, hard = 950f), listOf(
        hit(0.5f, 500f, DamageKind.HIGH_EXPLOSIVE, conservative = true), hit(1f, 100f, DamageKind.KINETIC)))
    check(released.armorLost[199] > 0f, "conservative: fires once the shield drops") { "${released.armorLost[199]}" }

    // unfolding: shield off, 0.5 s to unfold a 90 degree arc; at 0.1 s the arc is 18, a hit 40 degrees off misses it
    val unfold = resolve(defense(shieldOn = false),
        listOf(hit(0.1f, 500f, DamageKind.HIGH_EXPLOSIVE, fromX = 383f, fromY = 321f)))
    check(unfold.armorLost[199] > 0f, "shield: a shield still unfolding covers only part of its arc")

    // no shield: everything on armor
    val bare = resolve(defense(shield = ShieldKind.NONE), listOf(hit(0.01f, 500f, DamageKind.HIGH_EXPLOSIVE)))
    check(bare.armorLost[199] > 0f && bare.flux[0] == 0f, "shield: shieldless ships take hits on armor")

    // a module between the shooter and the ship takes the hit
    val module = defense(id = "M", shield = ShieldKind.NONE, radius = 40f)
    val withModule = defense(shield = ShieldKind.NONE, modules = listOf(module))
    val moduleTimeline = still(200, x = 80f)
    val m = DefenseResolver(withModule, rules, still(200), FloatArray(200) { Float.NaN }, mapOf("M" to moduleTimeline))
        .resolve(listOf(hit(0.01f, 2000f, DamageKind.HIGH_EXPLOSIVE)), ShieldPlan.NORMAL)
    check(m.moduleHullLost[199] > 0f && m.hullLost[199] == 0f && m.armorLost[199] == 0f,
        "modules: a module in the way takes the hit") { "module ${m.moduleHullLost[199]} hull ${m.hullLost[199]}" }
}

private fun fireControl() {
    // 100 per shot every 1 s: shots at t = 0, 1, ... 20 (21), 600 away at speed 1000 = 0.6 s travel
    val fc = fireAt(shooter(gun()))
    check(fc.hits.size == 21, "fire: refire delay sets the rate") { "${fc.hits.size} hits" }
    near(fc.hits.first().time, 0.6f, 0.01f, "fire: hits arrive after the travel time")
    near(fc.hits.sumOf { it.damage.toDouble() }.toFloat(), 2100f, 0.1f, "fire: damage per shot is kept")

    check(fireAt(shooter(gun()), x = 2000f).hits.isEmpty(), "fire: out of range never fires")
    check(fireAt(shooter(gun(ammo = 3))).hits.size == 3, "fire: stops when out of ammo")

    // 100 flux per shot, 300 max, 10 dissipation: 3 shots, then one per 10 s
    val capped = fireAt(shooter(gun(fluxCost = 100f, cooldown = 0.1f), maxFlux = 300f, dissipation = 10f))
    check(capped.hits.size in 4..6, "fire: the flux budget limits sustained fire") { "${capped.hits.size} hits" }

    val silenced = fireAt(shooter(gun(), silence = 5f))
    check(silenced.hits.first().time >= 5f, "fire: no shots while overloaded or venting") { "${silenced.hits.first().time}" }

    // a 100 DPS beam for 20 s: about 2000 in 0.1 s ticks
    val beam = fireAt(shooter(gun(mode = FireMode.BEAM, speed = 10000f, cooldown = 0f)))
    near(beam.hits.sumOf { it.damage.toDouble() }.toFloat(), 2000f, 30f, "fire: beams deal DPS over time")
    check(beam.hits.all { it.delivery == Delivery.BEAM && it.softFlux && it.hitStrength == 100f },
        "fire: beam ticks are soft flux with DPS as hit strength")

    // burst beam: 1 s bursts of 100 DPS, 1 s cooldown -> about half the time
    val burst = fireAt(shooter(gun(mode = FireMode.BURST_BEAM, speed = 10000f, burstDuration = 1f, cooldown = 1f)))
    near(burst.hits.sumOf { it.damage.toDouble() }.toFloat(), 1000f, 60f, "fire: burst beams fire for their burst")

    // a turret 90 degrees off target needs a second to turn at 90 deg/s
    val turret = SimShooter("E", 1, 0f, 0f, 10000f, 100f, 1f, 0f, FloatArray(6) { 1f }, listOf(SimWeapon(
        0f, 0f, 0f, 360f, 90f, 90f, 800f, FireMode.PROJECTILE, 100f, 0f, DamageKind.ENERGY, false, 0f, 1, 0.1f, 0f,
        1f, 0f, WeaponPhase.READY, 0f, 0, 0f, -1, 0, 0f, 0f, 1000f, 0f, false, false, null, 100f)))
    val turned = fireAt(turret)
    check(turned.hits.first().time > 1.4f, "fire: turrets turn onto the target first") { "${turned.hits.first().time}" }

    // threat ranking for omni shields
    val omni = fireAt(shooter(gun()))
    near(omni.omniFacing(120f)[10], 0f, 0.01f, "fire: an omni shield faces the only shooter")

    // guided missiles launch into the missile simulation
    val launcher = fireAt(shooter(gun(mode = FireMode.GUIDED_MISSILE, missile = missileInfo(), ammo = 2, noAim = true)))
    check(launcher.launched.size == 2 && launcher.hits.isEmpty(), "fire: missile launchers launch missiles")
    val rockets = fireAt(shooter(gun(missile = missileInfo(), speed = 500f)))
    check(rockets.launched.isEmpty() && rockets.hits.isNotEmpty() && rockets.hits.all { it.delivery == Delivery.MISSILE },
        "fire: unguided rockets fly straight but hit as missiles")
}

private fun missiles() {
    // a guided missile 1000 away at 500 max speed: about 2 s to the 65 unit contact radius
    val ms = MissileSim(defense(), 0, still(400))
    ms.run(listOf(missile(1000f)))
    check(ms.hits.size == 1, "missile: a guided missile hits a still target")
    near(ms.hits.first().time, 1.95f, 0.3f, "missile: arrival time from speed and acceleration")
    check(!ms.missileBearing[5].isNaN() || !ms.missileBearing[30].isNaN(), "missile: incoming missiles are tracked")

    // a moving target: lead pursuit still connects
    val moving = Timeline(400).apply { for (i in 0 until 400) { x[i] = 0f; y[i] = (i + 1) * DT * 150f; facing[i] = 90f } }
    val lead = MissileSim(defense(), 0, moving)
    lead.run(listOf(missile(1000f, info = missileInfo(maxSpeed = 400f))))
    check(lead.hits.size == 1, "missile: lead pursuit catches a crossing target")

    // missiles aimed at someone else are ignored; friendly ones too
    val other = MissileSim(defense(), 0, still(400))
    other.run(listOf(missile(1000f, target = "X"), missile(1000f).let {
        InFlightMissile(it.x, it.y, it.vx, it.vy, it.facing, 0f, 0, 500f, 0f, it.kind, false, it.info, 0f, "P")
    }))
    check(other.hits.isEmpty(), "missile: other targets and friendly missiles are ignored")

    // an unguided rocket (no turning, no target) flying straight at the ship
    val rocketInfo = MissileInfo(400f, 500f, 0f, 0f, 50f, 3f, 3f, 0.5f, 0.5f, false, false)
    val rocket = MissileSim(defense(), 0, still(200))
    rocket.run(listOf(InFlightMissile(0f, 1100f, 0f, -50f, 270f, 0f, 1, 4000f, 0f, DamageKind.HIGH_EXPLOSIVE, false,
        rocketInfo, 0.1f, null)))
    check(rocket.hits.size == 1, "missile: an unguided rocket on course hits") { "${rocket.hits.size}" }

    // MIRV: splits within split range + radius, five guided submunitions all hit
    val sub = SubmunitionInfo(100f, 0f, DamageKind.HIGH_EXPLOSIVE, missileInfo(maxSpeed = 600f), 600f)
    val mirvInfo = missileInfo(mirv = MirvInfo(600f, 0f, 5, 20f, 0f, 50f, true, sub))
    val mirv = MissileSim(defense(), 0, still(400))
    mirv.run(listOf(missile(1500f, info = mirvInfo)))
    check(mirv.hits.size == 5 && mirv.hits.all { it.damage == 100f }, "mirv: splits into its submunitions") {
        "${mirv.hits.size} hits ${mirv.hits.map { it.damage }}"
    }

    // MIRV facing away (just launched sideways) doesn't split until it turns
    val sideways = MissileSim(defense(), 0, still(400))
    sideways.run(listOf(missile(500f, facing = 90f, info = mirvInfo)))
    check(sideways.hits.size == 5, "mirv: splits once it faces the target")

    // projectiles in flight
    val shots = MissileSim(defense(), 0, still(100))
    shots.shoot(listOf(
        InFlightShot(500f, 0f, -1000f, 0f, 1, 100f, 100f, 0f, DamageKind.KINETIC, Delivery.PROJECTILE, false, 1f),
        InFlightShot(500f, 200f, -1000f, 0f, 1, 100f, 100f, 0f, DamageKind.KINETIC, Delivery.PROJECTILE, false, 1f),
        InFlightShot(500f, 0f, -1000f, 0f, 1, 100f, 100f, 0f, DamageKind.KINETIC, Delivery.PROJECTILE, false, 0.2f),
    ))
    check(shots.hits.size == 1, "shots: only the one on course and in range hits") { "${shots.hits.size}" }
    near(shots.hits.first().time, 0.44f, 0.02f, "shots: hit time where it meets the hit circle")
    near(shots.hits.first().fromX, 60f, 2f, "shots: the hit comes from the side it arrives on")
}

private fun endToEnd() {
    val p = ship("P", owner = 0, maxSpeed = 0f, accel = 0f, decel = 0f, turnAccel = 0f, maxTurn = 0f)
    val e = ship("E", owner = 1, x = 900f, facing = 180f, engagement = 500f, reach = 800f)
    val world = World(listOf(p, e), SimParams(200))
    val branch = world.branch("P", OwnPlan.Hold())
    val inputs = DamageInputs(listOf(shooter(gun(range = 800f, arc = 60f))), emptyList(), emptyList(),
        mapOf("P" to defense(flux = 500f)))
    val result = DamageSim.run("P", 0, branch.timelines, inputs, withVent = true)
    check(result != null && result.hits.isNotEmpty(), "damage: a committed enemy closes and shoots") {
        "${result?.hits?.size}"
    }
    if (result == null) return
    check(result.normal.flux[199] > 0f, "damage: the shield takes its fire")
    check(result.ventNow != null && result.ventNow!!.armorLost[199] > 0f,
        "damage: venting instead puts the same fire on armor")
    check(result.hits.zipWithNext().all { (a, b) -> a.time <= b.time }, "damage: hits are in time order")
    check(abs(result.hits.first().fromX - 500f) < 400f, "damage: hits come from where the shooter is")
}
