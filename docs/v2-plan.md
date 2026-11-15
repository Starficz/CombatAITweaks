# Combat AI Tweaks v2: plan

Status: in progress, milestones 1 to 4 done, milestone 5 (damage trade) first pass built (2026-11-14), see §11c and
§13.7. Researched against game build 0.98.5a-RC4.

Vanilla behaviour is described from translations of obfuscated code, named by its readable class. Those
obfuscated names are only used to find the logic, never compiled against. The audit of v1's assumptions is in
`v1-audit.md`; its findings are folded into this plan. Statements marked **(verify)** are
not confirmed yet and are checked in the milestone that needs them.

---

## 1. Goal and scope

A CAT ship must never get caught out by micro positioning: venting, lingering or advancing where enemies that
commit to killing it can do so.

Every decision answers one question: **"For each thing I could do next, what is the most damage I take if every
enemy able to reach me commits to killing me, flying and fighting the way vanilla's AI does?"**

- Enemies are simulated pursuing to **their own optimal weapon range**, computed the way vanilla does (§5.2). Not
  ramming, not drifting.
- Out of scope: fleet-level positioning, objectives, battle lines, escorts, phase-ship AI.

CAT is unreleased, so v2 replaces v1 outright. New files go in new packages; each milestone deletes the v1 code
it supersedes. Ships keep the existing enable tag and config dialog.

## 2. Code layout

| Package | Thread | Contents |
|---|---|---|
| `capture` | main | turns the engine into an immutable `WorldSnapshot` (§3) |
| `sim` | worker | pure model: kinematics, enemy plan, weapons, damage (§4–§7). **No `Global`, no `Misc`, no API objects**: `Misc` calls `Global.getSettings()` when it loads, which breaks tests outside the game. Its own small math file. |
| `decide` | main | reads sim results, sets flags and commands (§8) |
| `runtime` | both | scheduling, worker pool, combat plugin, listener, overlay (§9) |

Units and time:
- simulation step `dt = 0.05 s`;
- frame `i` holds the state at `t = (i + 1) · dt`;
- everything is relative to snapshot time;
- angles are degrees, 0 = +x, counter-clockwise.

## 3. Capture: `WorldSnapshot`

Taken once per prediction batch on the main thread. Everything copied, nothing referenced.

**Each ship (non-fighter, alive, not a hulk)**
- Identity: `id`, `owner`, `hullSize`, `collisionRadius`, `shieldRadius`.
- Bounds as ship-local segments, for hit points (§6.5).
- Motion: `location`, `velocity`, `facing`, `angularVelocity`.
- Engine: `maxSpeed`, `acceleration`, `deceleration`, `turnAcceleration`, `maxTurnRate` (engine controller
  effective values); the zero-flux boost (`zeroFluxEngineBoost` setting) separately; whether the boost is active
  now; `zeroFluxMinimumFluxLevel`; `timeMult`.
- Current engine inputs (accelerate, backwards, strafe left/right).
- Flux: current and hard flux, max flux, dissipation, hard-flux dissipation fraction, `isVenting` / time to vent,
  `isOverloaded` / overload time left.
- Shield: type (NONE, FRONT, OMNI, PHASE), arc, current facing, unfold time, `fluxPerPointOfDamage`, damage-taken
  mults (shield and each damage type, base and modified).
- Armor: the full grid copy, cell size, the ship-local centre of every cell, `armorRating`. Stat mults:
  `armorDamageTakenMult`, kinetic armor mult, `effectiveArmorBonus`, `minArmorFraction`,
  `maxArmorDamageReduction`, `hullDamageTakenMult`. Also `hitpoints`.
- System: spec id; state, cooldown left, ammo; charge up, active and down durations; whether it's a toggle.
  AI hints from `getAIHints2()`: `activeSpeedIncrease`, `burstMovementPotential`, `threatDamage`,
  `threatSpeed`, `threatRange`. Phase cloak present and its state.
- Vanilla intent: `MANEUVER_TARGET` (ship id), `MANEUVER_RANGE_FROM_TARGET` (Float).
- The computed **engagement range** (§5.2) and **best facing offset** (§5.5).
- Modules: parent id plus offset as in v1.

**Each weapon** (skipping decorative, hidden-slot and no-damage weapons)
- Mount in ship coordinates, slot angle, arc, turn rate, `isHardpoint`.
- Range, plus `projectileFadeRange` × 0.8 for the reach check.
- Damage per shot: `weapon.damage.baseDamage` (for beams, DPS) × the ship's bonuses, added the way vanilla adds
  them to every shot it fires (`computeExtraDamageComponents`): the weapon-type damage stat, plus the beam stat
  for beams, as one modifier. Verified: a projectile weapon's `getDamage()` is the projectile spec's damage
  object, without ship bonuses; a beam's is the live ray's, scaled by its current brightness (0 when idle).
  `derivedStats` is the spec's base. The attacker's damage-vs-hull-size stat is applied per target.
- Hit strength (beam: DPS × `dpsToHitStrengthMult` = 0.5), damage type, EMP (`damage.fluxComponent`), soft flux
  (beam and not `isForceHardFlux`).
- Timing: charge-up, burst size, burst duration, cooldown from `getRefireDelay()` (live, with rate-of-fire
  stats; `getCooldown()` is the spec's base for projectile weapons), current state and time left in it, disabled
  time.
- Ammo, max ammo, ammo per second, reload size.
- Flux cost per shot / per second; projectile speed; max spread with recoil mult.
- AI hints (`PD`, `PD_ONLY`, `DO_NOT_AIM`, `GUIDED_POOR`, `STRIKE`, `DO_NOT_CONSERVE`), weapon type and size.
- Missile data as in v1: engine spec, flight time, DEM and MIRV parameters.

**Fighters** (alive, not landing or landed, wing not returning)
- Motion and engine as for ships, plus weapons as above.
- `wingRange` (`FighterWingAPI.getRange()`) and the carrier id.

**Projectiles and missiles in flight**: as v1. Location, velocity, owner, damage, hit strength, type, flight
time left, guidance target, DEM state.

**Settings** read once: `maxArmorDamageReduction` (0.85), `minArmorFraction` (0.05),
`dpsToHitStrengthMult` (0.5), `zeroFluxEngineBoost` (50), map width and height.

## 4. Kinematics (`sim/Motion`)

One model for every ship and fighter. It matches the vanilla engine controller (the obfuscated class implementing
`ShipEngineControllerAPI`; v1 already carries these fixes):

- **Thrust axes.**
  - Forward thrust adds `acceleration`.
  - Backward thrust adds `deceleration`.
  - Strafing adds `acceleration` × the strafe factor: frigate 1, destroyer 0.75, cruiser 0.5, capital 0.25,
    fighter 0.75.
  - A requested direction is split into forward and lateral parts. The combined vector is scaled so neither
    axis exceeds its limit (v1's `calcAccel`).
- **Braking** (the decelerate command) pushes against the velocity at `deceleration`.
- **Over max speed**, speed bleeds off at `2 · max(acceleration, deceleration)` per second instead of being
  clamped. Vanilla does this, and it matters for burn drive exits.
- **Max speed** = `maxSpeed`, plus `zeroFluxEngineBoost` while the boost applies. The boost applies only while
  flux ≤ `zeroFluxMinimumFluxLevel` and the ship isn't firing **(verify the exact firing rule)**.
- **Turning.** Turn acceleration up to `maxTurnRate`, braking early so the ship doesn't overshoot (v1's
  facing controller).
- **Time dilation.** Every rate is scaled by `timeMult`.

## 5. Enemy plan: "commit and kill" (`sim/Pursuit`)

### 5.1 Who commits

An enemy (ship or fighter) commits to the protected ship P when it could bring a weapon to bear on P within the
horizon `H` if P stood still:

```
reach  = engagementRange(enemy, P) (§5.2) + its weapon reach margin
gap    = distance(enemy, P) − reach − burstMovementPotential (if its system is ready, has ammo, and the enemy can act within 3 s)
closeV = max(current closing speed,
             enemy cruise speed (without the zero-flux boost unless active) + activeSpeedIncrease (if the system is ready))
         × 2 for phase ships with hard flux < 0.2
tReach = max(0, gap) / max(closeV, 1) + time until the enemy can act again (venting or overloaded)
commits when tReach ≤ H
```

The inputs are those of vanilla's own vent-safety check (the obfuscated threat evaluator `A` in
`com.fs.starfarer.combat.ai`). It is only the filter: whether a committed enemy really arrives, and how late, comes
from each branch's pursuit of P's simulated path. An enemy arriving at time t fires for only H − t, so its damage
fades to nothing toward the horizon instead of switching on at a fixed window.

Changed after milestone 4: this was vanilla's rule with a 6 s window and P assumed to flee at cruise speed. Enemies
crossing that window made the worst case jump between "every option overloads in about 8 s" and "nothing happens"
from one batch to the next.

**Only enemies that may target P** (added in milestone 5, `World.mayTarget`). Assuming every enemy in reach flies at
P was too harsh. Vanilla's target choice (translation of obfuscated code: `BasicShipAI.pickManeuver` and the pickers
in `combat.ai.N`) runs each time a maneuver ends, every 2–4 s:
1. an order's target or the target override;
2. else the nearest enemy ship. The first picker takes ships within 2200 at most two sizes smaller, and can keep a
   flank target it is already on. The fallbacks are all nearest-first: cruisers and capitals the nearest cruiser or
   capital, frigates the nearest ship or fighter, everyone else the nearest ship.

Flux and overloads don't change whom it picks, only how hard it presses: `pickManeuver` checks the target overloaded
or venting for over 5 s, its own flux over 0.7 and the target's over 0.8. Conservative version: an enemy may target P
when P is its live target (`VanillaTarget`), or P is within 400 of its nearest other enemy ship (any size, fighters
excluded) at the snapshot or at a re-pick every 2 s over the first half of the window, everyone on base paths.
Non-committed enemies still shoot P whenever it is in their range and arc.

- **Fighters** commit only when P is within `wingRange` of their carrier's position, now or as simulated
  **(verify that wings are tied to the carrier this way)**.
- **Enemies that don't commit** follow their current plan with no thrust (the v1 base model) and still fire if
  P comes into their range and arc. They are not ignored.

### 5.2 Engagement range: where an enemy wants to be

**If the enemy already targets P** (`MANEUVER_TARGET` = P), its live `MANEUVER_RANGE_FROM_TARGET` value is the
centre-to-centre distance it is trying to hold. Use it as is.

**Otherwise, compute it the way vanilla's attack module does** (`AttackAIModule`, weapon-group scan):

1. **Score each weapon group** by summing a weight per weapon that has ammo and isn't `NO_MANUAL_FIRE`:

   | Factor | Weight |
   |---|---|
   | Size | small 1, medium 3, large 7 |
   | Hardpoint | × 2 |
   | Ballistic | + 1 |
   | Missile | + 2 |
   | `PD_ONLY` | × 0.1 |
   | `PD` | × 0.25 |
   | Weapon can't point forward (0° outside its arc) | × (180 − angle from forward) / 180, and × 0.5 more for PD |
   | Missile with ammo ≤ 5 bursts and not `DO_NOT_CONSERVE` | − 2 × its weight |

2. **Group range** = the minimum over the group's weapons of `max(100, range − forwardOffset)`. For
   forward-facing weapons, `forwardOffset = 0.6 · collisionRadius − slot.x`, floored at 0.
3. **Primary range** = the range of the highest-scoring group (a tie goes to the shorter range).
4. **Gun range** = the range of the best-scoring group that isn't missiles only (2000 if there is none).
5. **Base range** = min(primary range, gun range).
6. **Hold distance** = base range + the enemy's collision radius + `getTargetingRadius(enemy.location, P,
   false)`. Vanilla adds the target radius scaled by that targeting radius.
7. **Pressing in.** Vanilla moves up to 200 units closer when the target's flux level is above its own:
   `200 · min(1, Δflux · k)`, with k = 4, 2 or 1 depending on how much bigger the target's hull is. The worst
   case uses the full **−200**.
8. **Floor**: never closer than `200 + radii`.

The same function gives **P's own** engagement range for the "advance" option (§7).

### 5.3 Movement toward the hold point

Every step, a committed enemy:

- **Picks an aim point** on the line from P towards the enemy, at the hold distance from P's simulated position,
  led by P's velocity times the enemy's estimated arrival time (the gap divided by its max speed).
- **Steers to arrive and stop there**:
  - thrust toward the aim point while `distance > v² / (2 · deceleration)`;
  - then brake;
  - once in the band (hold distance ± 50), match P's velocity.
- **Moves along whatever axes its facing allows** (§4).

### 5.4 Mobility systems (from the AI hints, no hardcoded system ids)

A system is used when it is ready, has ammo, and the gap to the hold distance exceeds `0.5 · burstMovementPotential`:

- **`burstMovementPotential` > 0** (burn drive and the like): the ship moves that far along the aim direction,
  spread evenly over the system's active time.
- **`activeSpeedIncrease` > 0**: max speed + increase, acceleration × 2, for the active time
  **(verify the acceleration factor per system)**.
- **Afterwards**: the cooldown, during which the system can't be used again in the simulation.
- **Phase cloak**: speed × 2 while hard flux < 0.2 and moving in (vanilla's own approximation). It can't fire
  while phased; it fires once in range.

### 5.5 Facing: bringing guns to bear

The best facing offset is computed once per ship at capture:
- Try offsets from −180° to 180° in 10° steps.
- Score each by the summed DPS of the weapons that can aim at a target dead ahead at that offset. Turrets cover
  arc plus slot angle; hardpoints cover ±10°.
- Pick the best. This reproduces vanilla preferring broadsides for broadside ships.

In the simulation, a committed enemy turns toward `bearing to P + bestOffset` with its turn model (§4).

### 5.6 Silence and flux budget

- **Venting or overloaded**: no firing and no system use until the remaining time runs out. The ship can still fly
  (vanilla backs off while venting), so pursuit continues.
- **Enemy flux.** Every shot adds its flux cost; soft flux dissipates at dissipation. A weapon only fires while
  `flux + cost ≤ maxFlux`. Once capped, fire is limited to what dissipation sustains (flux-neutral fire). The
  worst case doesn't assume enemies vent mid-fight, but it can't assume infinite fire either.
- Damage dealt *to* enemies isn't modelled; P's own fire isn't simulated.

## 6. Damage (`sim/Damage`)

### 6.1 Weapons on committed and non-committed enemies

A weapon cycle state machine (v1's, cleaned up):
- charge-up, burst, cooldown;
- ammo with regeneration (`ammoPerSecond`, `reloadSize`);
- disabled time;
- the flux budget (§5.6).

**A weapon fires when**
- P is within range + P's shield radius;
- the turret can turn onto the bearing (slot arc, turn rate from its current angle);
- the weapon isn't silenced.

`PD_ONLY` weapons never fire at ships.

**Each shot**
- Hits P at fire time + travel time (distance / projectile speed; beams use beam speed).
- Damage is scaled by the spread hit chance (v1's spread cone vs P's shield radius).
- Beams deliver damage in ticks every 0.1 s with hit strength DPS × 0.5 and soft flux.

**Conservative weapons** (ammo-limited, no regen, not `DO_NOT_CONSERVE`) hold fire while P's shield covers them
and fire when it drops (v1 logic, kept).

**Missiles** (details in `v1-audit.md` §4):
- Guided missiles aim at vanilla's lead point (intercept with lead mult `1.5 − 0.5 × guidanceBonus`) instead of
  straight at the target.
- MIRVs use vanilla's split conditions (`splitRange` + target radius, a facing tolerance, `minTimeToSplit`; no
  split at end of life). Submunitions come from `SettingsAPI.getMissileSpec()` / `getProjectileSpec()`.
- DEMs in flight use their own rolled parameters, read from the `DEMScript` instance.

### 6.2 Already in flight

- Projectiles: swept-circle hits against P's path (v1).
- Missiles guided at P: simulated as above.
- Unguided missiles and mines: simulated on their own paths.

### 6.3 P's shield

- **Facing.**
  - Front shield: P's facing.
  - Omni shield: verified against vanilla's shield AI (translated). It points at the top threat, pulled toward
    the second-strongest by half the angle between them, at most 0.3 × (arc − 10)°. Incoming missiles take
    priority. The sim ranks threats by the sustained DPS each shooter has on P at that step.
- **Coverage.** A hit is blocked when its bearing from P's centre is within ± arc/2 of the shield facing, and
  the shield is up: not venting, not overloaded, past its unfold time after being raised.
- **Shield flux** = damage × `DamageType.getShieldMult()` × `shield.getFluxPerPointOfDamage()` (which includes
  absorption) × the **modified** `shieldDamageTakenMult`, the same formula vanilla's own estimators use. Hard
  flux, or soft for beams.

### 6.4 P's flux and overload

- Soft flux dissipates first at dissipation; hard flux only with shields down or via the hard-flux dissipation
  stat. During a vent, both drain at the vent rate.
- **Overload** when flux passes max: the shield is down for the overload duration. Verified (`FluxTracker`,
  translated):
  - the base is frigate 4 s, destroyer 6, cruiser 8, capital and fighter 10, drones 3;
  - the overflow / 25 is added, capped at 15 s;
  - then `overloadTimeMod`;
  - flux drains at half dissipation meanwhile, hard flux included.
- **Damage-taken stats** (verified in `Ship.applyDamage`): shield hits use the per-delivery (beam, missile,
  projectile) and per-type shield damage-taken stats; armor and hull hits use the non-shield ones. Both scale
  damage and hit strength.

### 6.5 P's armor and hull: vanilla's grid formula

From vanilla's armor grid damage code (the obfuscated class implementing `ArmorGridAPI`):

1. **Hit point**: where the ray from the attacker (or the projectile's path) meets P's bounds. Hit cell: the
   grid cell whose centre is nearest that point.
2. **Type mult** `m = type.armorMult × armorDamageTakenMult` (× the kinetic armor mult for kinetic).
   Armor mults: high explosive 2, kinetic 0.5, energy 1, fragmentation 0.25. Both damage and hit strength are
   multiplied by `m`; beams also use the `dpsToHitStrengthMult` hit strength.
3. **Effective armor** at the hit cell = Σ (cell × weight) over the 21 cells: centre and inner ring weight 1,
   the outer 12 weight 0.5. Then `effectiveArmorBonus` is applied, floored at `armorRating ×
   minArmorFraction`.
4. **Reduction factor** `1 − min(armor / (armor + hitStrength), maxArmorDamageReduction)`.
5. **Spread**: damage × factor goes to the cells, 1/15 to each of the inner 9 and 1/30 to each of the outer 12.
6. **Hull**: each cell's overflow beyond its armor goes to hull × `type.hullMult / m × hullDamageTakenMult`.

The simulation keeps P's own armor grid copy, so stripping a side really matters for the next hit.

### 6.5a P's modules

Hits whose path crosses a live module of P (an armor module, the Orthrus shell) land on that module first, using
its own armor grid and hull from capture. Once that hull is gone, later hits pass through to P.

Module poses are captured live every batch, never cached per hull: the Orthrus armor switch moves its shell and
changes weapon arcs (`v1-audit.md` §6).

### 6.6 Output per branch

A timeline of hits, plus per-step state of P:
- shield flux (hard and soft);
- flux level;
- overload start and duration;
- armor cells;
- hull.

Readers ask for any window: armor lost, hull lost, peak flux, the overload time if any, and the time to first
hull damage.

## 7. P's options (`sim/Options`)

Each option is one branch. The enemy plan (§5) reacts to P's simulated path. Horizon `H = 10 s` (tunable). P
does not fire in the simulation, and its shield follows §6.3 except where the option says otherwise.

| Option | P's movement |
|---|---|
| `HOLD` | brake to a stop, face its current target |
| `ADVANCE` | close to its own engagement range of its current target (§5.2), face the target |
| `BACK_OFF(d)` | thrust in direction `d`, facing the strongest threat (backing up uses deceleration, §4) |
| `VENT(o)` | vent now (shield down for the vent time, flux draining), moving per option `o` ∈ {`HOLD`, best `BACK_OFF`} |
| `…+SYSTEM` | the same with P's mobility system used at t = 0, where it has one and it's ready (§5.4 rules) |

**Back-off directions**: away from the DPS-weighted centre of committed enemies, and ±35°, ±70° from it.
Directions that would come within 300 units of the map edge within H are dropped.

**Milestone 4a** drives P only through vanilla flags, so `BACK_OFF` uses only the vanilla direction (away from
its maneuver target, facing it), except automated ships, which need direct movement for any back-off (§8.4).
**Milestone 4b** adds the chosen direction through direct movement commands for every ship.

## 8. Decisions (`decide`)

Each runs every 0.1 s on the latest results. Limits come from the per-variant config, where the existing fields
are reused.

### 8.1 Vent

Considered when flux > `minVentLevel` and venting is allowed: no weapon mid-burst, no non-toggle system active.

**Vent window** `W = time to vent + shield raise time + bufferTime`, where shield raise time = unfold time ×
min(1, `safeShieldArc` / arc).

**Vent when either holds:**
- **Safe**: the best `VENT(o)` keeps armor lost in W ≤ `maxArmorDamageLevelToVent × armorRating` and hull lost
  ≤ `maxHullDamageLevelToVent × hitpoints` (0 below `stopVentingBelowHullLevel`).
- **Better than staying**: the best `VENT(o)` loses at most 0.8 × what the best option without venting loses in
  the same window, and hull > `stopVentingBelowHullLevel`.

The chosen `o` is passed to the movement decision for the vent's duration.

As built (milestone 3):
- `VENT(o)` is each branch's defence resolved with the shield down from t = 0 for the whole horizon. The decision
  reads it over W, which it computes from the live flux. The snapshot's flux can be out of date: a ship
  predicted at 0 flux would otherwise not "vent" at all, and its shield would block everything.
- The rule is pure (`decide/rules/VentRule.kt`, unit-tested); `decide/VentController` gathers the inputs and acts.
- The vent runs before v1's no-enemies fallback: nothing threatening is the best time to vent.
- A blocked vent names its blocker (weapon mid-burst, system active, ...) in the trace line.
- W shrinks as flux dissipates, so a vent that is unsafe now can become safe while the ship waits. That is
  intended.

### 8.2 Engage, hold, back off, disengage

Over H, each option has:
- `ok`: no overload and hull lost ≤ the hull limit;
- `cost` = hull lost × 3 + armor lost (weights tunable);
- `endFlux`.

| State | When | Option driven |
|---|---|---|
| `ADVANCE` | `ADVANCE` is ok and `endFlux` ≤ `forceAdvanceIfFluxBelowLevel` | `ADVANCE` |
| `HOLD` | `HOLD` is ok and `endFlux` ≤ `backoffIfFluxAboveLevel` | `HOLD` |
| `BACK_OFF` | some `BACK_OFF(d)` (± system) is ok | the cheapest ok one |
| `DISENGAGE` | nothing is ok | the cheapest back-off; stays until flux < `reengageIfFluxDropsBelowLevel` |
| `VENTING` | while venting | the option chosen in §8.1 |

Hysteresis: a state must win for 0.5 s before switching, except into `DISENGAGE` or `VENTING`.

As built (milestone 4):
- `ok` also needs the end hard flux at or below `backoffIfHardfluxAboveLevel` for `ADVANCE` and `HOLD` (v1's
  hard-flux rule). The hull limit is the vent's: `maxHullDamageLevelToVent` × hitpoints, or none at all below
  `stopVentingBelowHullLevel`. `disengageIfFluxAboveLevel` is no longer used.
- Among back-offs the cheapest wins; on a tie vanilla's own direction, then the lowest end flux. A system variant
  only when it costs at most 0.8× the plain one and saves 50 or more, or is the only ok one.
- `ADVANCE+SYSTEM` only to close a gap of more than 300 beyond the advance range, with end flux at or below
  `maxFluxLevelRaiseToHarass` (v1's "poke" rule).
- The rule and its hysteresis are pure (`decide/rules/MoveRule.kt`, unit-tested); `decide/MoveController` builds
  the requests, reads the results and acts.

Changed after milestone 4:
- **`ADVANCE` and `HOLD` are judged by "then escape" branches** (`ADVANCE~`, `HOLD~`): do it for the reaction time
  τ = 0.7 s (the 0.5 s hysteresis plus about 0.2 s of decision tick and prediction age), then thrust straight away
  from the threat. The ship re-decides every tick, so the real risk of an option is whether it can still get out
  afterwards, not doing it for the whole horizon. This is v1's idea (hold and back-off branches; advance while
  backing off stays safe), with the escape checked from where the ship will be.
- The plain `HOLD` (keep still the whole horizon) stays: the vent's hold option, and the flux limits for `ADVANCE`
  and `HOLD` (v1's hold-branch flux ratio).
- **Nothing ok means running**, even from a ship it can't outrun: player ships would rather not die, and friendly
  ships may bail them out, which the worst case doesn't count. (A "stand when running costs more" fallback was
  tried and dropped for this reason.)
- **Back off toward friends**: `BACK_OFF@ally` thrusts toward the DPS-weighted centre of friendly ships within 3000
  (not when already within 300 of it). It is asked for whenever backing off is in play and wins ties among back-offs.
- State changes per minute are not a target: re-deciding often is the point of re-planning. The measure is the
  outcome (hull lost, mirror matches).

### 8.3 Mobility system

Use the system when the driven option is a `…+SYSTEM` variant. Keep v1's rule for toggles (on while the driven
option is a system variant). Otherwise block vanilla's use only where v1 did (`enforceOverride` evaluators).

### 8.4 Output

| State | Flags (each set for 0.1 s and refreshed) |
|---|---|
| `ADVANCE` | `DO_NOT_BACK_OFF`, `BACK_OFF_MAX_RANGE` = P's engagement range (the lever that actually brings it closer), and `HARASS_MOVE_IN` (only matters for cautious or timid officers and harass orders) |
| `HOLD` | the standoff (§13.4): `BACK_OFF_MIN_RANGE` = `BACK_OFF_MAX_RANGE` = the standoff range + 100, without `DO_NOT_BACK_OFF` (so vanilla backs up as enemies close). Without a standoff prediction: `DO_NOT_BACK_OFF` and both ranges = the distance when it started holding (otherwise vanilla presses in) |
| `BACK_OFF` | `BACK_OFF`, `DO_NOT_PURSUE` |
| `DISENGAGE` | `BACK_OFF`, `DO_NOT_PURSUE`, `BACK_OFF_MIN_RANGE` = the distance where no enemy commits |
| `VENTING` | if safe, `DO_NOT_BACK_OFF` + `DO_NOT_BACK_OFF_EVEN_WHILE_VENTING`, else `BACK_OFF` |
| every state except an ordered vent | `DO_NOT_VENT`, which switches off vanilla's own vent logic (`v1-audit.md` §1, Venting) |

**Target and range, handed over directly** (added 2026-11-14, `runtime/VanillaManeuver`). For `ADVANCE` and `HOLD`
CAT gives vanilla's AI the maneuver itself: `StrafeTargetManeuverV2(ship, target, false, 0, range, flockingAI, ai,
true)` through the public `BasicShipAI.setManeuver`, as `pickManeuver` builds it (translation of obfuscated code; the
constructor is found by shape, the range is surface to surface). It is handed over again as soon as the AI's
`sequence` runs anything else. The target override alone wasn't enough: `pickManeuver` ignores it when an order's
response brings its own maneuver. CAT's target is vanilla's target or the ship target only within 3000; otherwise
the nearest enemy ship. Vanilla's own pick can be a ship across the map: its first picker looks within 2200 only.
In `movecases` the advance check was flaky for that reason (vanilla's target was the parked enemy 12.7 km away
while the Lasher sat at 2200); it now closes to 640–810 of a 597 approach range.

**Automated ships** (the player's AI-core ships too) ignore `BACK_OFF` unless venting
(`backingOffWhileNotVentingAllowed = false`), so `BACK_OFF` and `DISENGAGE` need direct movement for them from
milestone 4a.

**SCY contract.** Keep publishing `USE_MOVEMENT_SYSTEM_BACKOFF`, `USE_MOVEMENT_SYSTEM_ADVANCE` and
`BEHAVIOR_STATE` (`"VANILLA_FALLBACK"` when no enemy is around) in the ship's custom data. SCY's `ArmorSwitchAI`
reads them.

This is how `StrafeTargetManeuverV2` reads them (decompiled):
- `BACK_OFF` forces a back-off;
- `DO_NOT_BACK_OFF` cancels vanilla's own back-off;
- overloaded or venting ships back off unless `DO_NOT_BACK_OFF`;
- `BACK_OFF_MIN_RANGE` / `MAX_RANGE` clamp the range it holds.

**Direct control (milestone 4b; automated ships from 4a)**:
- Block the movement command types we don't want, then give the ones we do. Vanilla keeps turning, shielding and
  firing.
- This works (confirmed in `v1-audit.md` §1): CAT's listener runs after the AI each frame, and blocks filter the
  AI's queued commands too. A block removes every command of that type, ours included, so never block a type we
  are also giving.

### 8.5 Standing down

CAT gives no orders, and vanilla flies alone, while P:
- is retreating;
- has a direct fleet order other than a combat assignment (**(verify)** how to read it through
  `CombatTaskManagerAPI` / `getAssignmentFor`);
- is flown by the player.

Options P can't physically carry out are dropped:
- no thrust options while flamed out or with all engines disabled;
- no system options while the system is unavailable;
- no vent options while venting is blocked.

### 8.6 Decision trace

Every decision records a one-line reason with its numbers in P's custom data, for example
`BACK_OFF(-35°): ADVANCE overloads 4.2s, HOLD hull -310, best ok cost 85`. Also recorded:
- the option table (cost, ok, end flux, overload time);
- the committed enemies and their arrival times.

The overlay shows the reason line. The battle check writes every state change and vent with its reason to the
run's results, so a bad decision can be read back afterwards instead of guessed at.

## 9. Runtime

- **Workers**: `cores − 2`, at least 1, low priority.
- **Batch rate**: at most 10 per second. A new batch starts no sooner than 0.1 s after the last one started.
- **Per CAT ship per batch**: the base world once, then branches HOLD, ADVANCE, 5 × BACK_OFF, the VENT variants
  (only while venting is considered) and the system variants. About 8–14 branches.
- **Copy-on-write timelines.** Only P and its committed enemies are re-simulated per branch; everyone else is
  shared. Non-committed enemies keep their base paths.
- **Budget.**
  - A CAT ship with no committed enemy skips the option branches (its base world is enough).
  - Branches are capped per batch; ships whose results are oldest go first.
  - The battle check reports predictor milliseconds per batch and main-thread capture time. The desktop stress
    battle (2 × 250 FP per side) must keep capture under 1 ms per frame on average.
- **Results** are tagged with the snapshot time. Decisions use the newest; anything older than 0.5 s counts as
  missing, and the ship falls back to vanilla.
- **Garbage** (added 2026-11-14). The user's game runs Shenandoah (concurrent, `compact`, 10 ms pause target, 2 GB
  heap): allocation rate costs GC threads and allocation pacing that slows every allocating thread, the main thread
  too. CAT made 55–58% of combat-time allocation (~35 MB/s in a 60 FP `aibattle`), 93% of it on the workers.
  - **Lean runs** when the trace is off (`TraceRecorder.enabled()`, checked per batch): `DamageSim.run(pool =
    Scratch.local())` takes every `Hit` and every run-only float array (omni threat ranking, firing flux, missile
    tracking, armor grid copies, omni facings) from a per-thread `Scratch`, reset at the start of each branch and
    before a reaction re-run. Nothing suspends inside a branch, so a branch stays on one thread. Lean results keep no
    hits and no detail arrays (`shieldUp`, `moduleHullLost`, `shieldFlux`, `potentialFlux`; `potentialFluxEnd` stays
    for the overlay). A unit test checks lean and reused-scratch runs predict bit for bit what a full run does.
  - Always: threat ranking only for omni shields that something has guns on; firing flux kept only for the ship's
    own targets; fire discipline sorts each weapon list once; shield hit times unboxed (`FloatList`) and kept only
    up to the first after 3 s; one shared all-NaN facing array.
  - Per predicted branch, 86 KB before and 36–40 KB after (two battles); combat-time CAT allocation 15–19 MB/s
    against ~35. What is left is mostly what the results keep (timelines and outcomes); reusing those across batches
    is the next lever.
- **Main thread** (same date). About 5% of main-thread samples in `aibattle`. `runtime/CombatScan` walks the
  projectiles, missiles, beams and ships once per frame for every CAT ship (the shield live check, the target pick,
  the ally and threat centres; ship DPS cached for 1 s), which cut the shield check from 1.1% to 0.3–0.5%. The rules
  only format their reasons with numbers while the trace is on (`decide/Explain`). The biggest piece left is the
  batch snapshot (`PredictorManager.advance`, 1–2%).
- **Overlay** (dev mode):
  - P's options, coloured by cost;
  - each committed enemy's pursuit path and hold circle;
  - P's state ring;
  - a text line with the chosen option and its window numbers.

## 10. Tests

| Test | Where | Passes when |
|---|---|---|
| **Unit**: kinematics | plain JVM, `sim` only | forward, backward and strafe acceleration match §4 hand-computed values to 1%; speed bleed above max; turn stops within 1° |
| **Unit**: engagement range | same | vanilla's weapon-group rules give the expected range for hand-built loadouts (all-guns, missiles-only, broadside, PD-heavy) |
| **Unit**: pursuit | same | a slower enemy never reaches a faster backing-off ship; a burn-drive enemy closes by its `burstMovementPotential`; an enemy settles within ± 50 of its hold distance |
| **Unit**: armor | same | grid damage matches §6.5 hand values; HE vs kinetic ratios; overflow to hull |
| **Known-answer scenarios** | game, spawned ships | high flux and nothing commits → vents; torpedo inbound → doesn't vent; burn-drive cruiser at 2000 → doesn't vent; outgunned frigate → `BACK_OFF`; nothing threatening → `ADVANCE` |
| **Worst case made real** | game, P against 1–3 Reckless enemies forced onto P | the actual damage over each 2 s window (`DamageListener`) is within ±25% of the prediction when both are above a floor; the per-source breakdown explains any gap |
| **Bound violations** | `aibattle` real battle | windows where actual damage > the worst case are under 2%, each attributed to a source; no "safe" vent takes more than its limit |
| **Mirror matches** | desktop, `--shards 3` | reported only: win rate and hull lost per FP for CAT vs vanilla, sides swapped |

## 11. Milestones

Each leaves the mod loading and `aibattle` passing.

1. **Capture, kinematics, engagement range, pursuit.** `capture`, `sim/Motion`, `sim/Pursuit`, the unit tests,
   and an overlay showing each committed enemy's pursuit path and hold circle.
   - Deletes: v1 `CombatSimulation` and the motion half of `CombatStateCapture`.
   - The v1 decision code keeps running on the new movement until milestone 3.
2. **Damage model.** Weapons, flux budget, P's shield, flux and overload, the armor grid, projectiles in flight,
   missiles, fighters. Plus the "worst case made real" scenario group.
   - Deletes: `DamageSimulation`, the rest of `CombatStateCapture`, `Models`.
3. **Vent decision** (§8.1) with the vent scenarios and the vent audit in `aibattle`. Deletes: `VentLogic`.
4. **Movement decision.**
   - 4a: options, states and flags (§8.2–§8.4).
   - 4b: direct back-off directions, if the verification passes.

   Deletes: `BehaviorLogic`, `MobilitySystemLogic`, `evaluators`, `CombatAIv2`.
5. **Damage trade** (§13): autofire replicated for every weapon, P's own fire and flux, enemy defences, and
   `ADVANCE` only on a winning trade measured in shares of each ship; `HOLD` becomes "the closest P can keep
   without taking damage".
6. **Tuning.** `H`, τ and cost weights, from the bound violations and mirror matches.

## 11a. Known pessimism (deliberate)

The worst case ignores several things, and each makes CAT more cautious than reality:
- P's point defense shooting missiles and fighters down;
- allies blocking shots or drawing fire;
- collisions;
- enemies splitting their attention.

When the tuning milestone finds ships too cautious (missile-heavy enemies are the likely case), the first lever
is an expected PD interception factor on missiles, before touching `H`.

## 11b. Ship systems (offensive and defensive)

Agreed 2026-11-15. Before this, the damage sim froze every ship's weapons as captured: an enemy system active at the
snapshot stayed on for the whole horizon (over-prediction), and one idle but ready never fired (under-prediction:
a Lasher or Hammerhead with its Accelerated Ammo Feeder ready doubles its ballistic fire mid-vent).

**One system model for every ship.** Each ship system gets an on/off timeline per branch and a profile:
- **Effect:** factors on its ship's stats: fire rate, damage, flux cost and range per weapon type (built); damage taken
  per layer, shield upkeep (next, for defensive systems).
- **Restrictions:** no firing, no shield, no venting. Read from the system spec (`isFiringAllowed`,
  `isShieldAllowed`, `isVentingAllowed`), not the profile.
- **Timing:** charge-up, active, charge-down, cooldown, charges and regen, from the live system.

**Who uses it, when:**
1. **Enemies (built):** worst case: on as soon as it is ready and a weapon it strengthens could fire (in range with
   the boost, in arc, aimed), counted at full strength over the whole on-time. No branches added. For the vent-now
   run "as soon as ready" already lands in the vent, which starts at t = 0.
2. **P, as vanilla would: dropped (agreed 2026-11-15).** Vanilla's damper AI (translation of obfuscated
   `combat.ai.system.O00O`) times the damper from vanilla's internal threat estimate (`combat.ai.C`), which the sim
   can't reproduce for future moments, and it fights CAT's vent (it sets `DO_NOT_VENT` while the damper is on).
   Instead CAT decides P's defensive systems and blocks vanilla's own use, as the mobility systems do
   (`enforce_override`): the sim can count on a use because CAT makes it. P's own offensive systems still count only
   when already on at the snapshot, without charge-down: vanilla decides those.
3. **`+DEFENSE` options (next):** like `+SYSTEM`: the option with the system used now, on the options where it
   matters (likely `HOLD`, `STANDOFF`, `BACK_OFF`: the damper blocks venting and the shield, the fortress blocks
   firing). Each batch asks "now or not now"; batches re-ask every fraction of a second, which is the timing search.
   "Not now" assumes no use within H (conservative). About +2–3 branches, only for ships with such a system.

**Profiles, extendable** (`capture/SystemProfiles.kt`), first match wins:
1. code: `SystemProfiles.register(...)`, for systems a table can't describe;
2. `data/config/combataitweaks/system_profiles.csv`, merged across mods (`getMergedSpreadsheetDataForMod`): any mod
   ships the same path with rows for its own systems;
3. learned: an offensive system with no profile is read off its ship's stats the first time it is seen fully on
   (effect level 1), keeping only what strengthens the ship, so a learned profile can only make CAT more careful.
   A stopgap, and it stays one: it misses scripts that hardcode their own modifier id, effects that aren't weapon
   stats, and every use before the first full one. Dry-running the script on detached stats, as vanilla's system AIs
   do (`combat.ai.system.A`), was considered and set aside: too iffy. Systems that matter get a CSV row.

Values are the stats script's own modifiers: `x2` (modifyMult), `+50%` (modifyPercent), `+40` (modifyFlat). Systems
apply their stats under the id "<system id> effect" (translation of obfuscated code: `combat.systems.F`), so the
capture takes the system's own modifiers out of the ship's stats and puts the profile's in: the factors are exact
for that ship, whatever else it has. A system that is off may leave modifiers under its id (Lidar Array's passive
range bonus); those stay. Adding a stat is one `ProfileStat` entry plus whatever reads it. The mobility profiles
(maneuvering jets, plasma jets, SCY thrusters and armor switch) moved into the same CSV (`decide/MobilityPhases`).

**Built 2026-11-15 (enemy offensive systems):**
- `sim/Systems.kt`: `WeaponBoost` per weapon, `ShooterSystem` timing, `SystemClock` per branch run; `Gunnery`
  applies the boost (`autoUseSystem` for enemies' fire at P, off for P's and its side's autofire).
- CSV rows: `ammofeed` (ballistic fire rate x2, flux x0.5), `highenergyfocus` (energy damage +50%), `lidararray`
  (ballistic and energy fire rate x3, range +100%; its passive bonus is left out while it is on).
- Left out: Fast Missile Racks (no stats script: it reloads), Targeting Feed (boosts the carrier's fighters), Entropy
  Amplifier (a debuff on the target: with the defensive work).
- **Fire rate fix found on the way** (translation of obfuscated code: the projectile and burst beam weapon trackers,
  `combat.entities.ship.A.J` and `A.G`): the ship's fire-rate stat isn't in `getRefireDelay()`; it runs the weapon's
  clock faster (projectile weapons: charge, burst delay, cooldown; burst beams: the cooldown). Above x1 no delay
  goes under `minRefireDelay` (0.05 s), and one already at or under it isn't sped up: the Heavy Needler's 0.05 s
  burst keeps its spacing under the feeder, only its cooldown halves. The capture ignored every fire-rate bonus
  before (hullmods and skills too); it now reads the stat.
- Tests: unit 310 (`systemBoosts`: use at once, cooldown, waiting until ready, charges and regen, own fire only
  counting a use already on, flux, damage, range, the refire floor). `ventcases` feeder case on a real
  `hammerhead_Elite`: ballistics boosted (x2, x0.5), others not, system ready; the sim's ballistic fire over 7 s
  matches the fire cycle (6000 with the system, 5100 without); with the system on, the captured weapons equal the
  system-off capture. `movecases`, `aibattle` and `worstcase` pass.
- Not yet seen in game: a learned profile (no offensive system without a row came up).

**Built 2026-11-15 (defence, first part: a system already on at the snapshot):** before this, the defence capture
read the damage-taken mults and upkeep as they were, so a damper on at the snapshot counted for the whole horizon
(x0.33 armor and hull damage on a frigate: badly optimistic), and its restrictions didn't exist in the sim.
- Capture (`capture/SystemCapture.kt`): the system on now (the right-click slot first, then the ship system, as
  `DamperFieldStats.getDamper`; not a phase cloak). The ship's shield, armor and hull damage-taken mults and upkeep are
  captured without its `"<id> effect"` modifiers; `SimDefense.system` (`DefenseSystem`) holds the live values, its
  on-time, the spec's restrictions (`isShieldAllowed`, `isVentingAllowed`, `isFiringAllowed`) and its flux per second.
  No profile needed: it reads what the system does now, so it works for any system that uses its id.
- Sim (`DefenseResolver`): the effect counts until the system ends; for the protected ship without its charge-down
  (conservative), for an enemy it fires at with it (worst case for the trade). Restrictions last the whole on-time:
  no shield (and the shield unfolds from nothing afterwards), the ship's own fire silenced, and `VENT_NOW` waits for
  the system to end (dissipation only until then). The system's flux per second is added while on (hard if the spec
  says so).
- A toggle that is on (fortress shield) reports no end, so it stays on for the horizon, effect and restrictions.
  For P that is no longer an assumption: CAT decides it (below), so the plain options turn it off and `+DEFENSE`
  keeps it on.

**Built 2026-11-15 (defence, second part: CAT decides P's defensive system):**
- Profiles: `use` defensive rows for `damper` (armor and hull x0.33, x0.5 on cruisers and capitals), `damper_omega`
  (x0.5) and `fortressshield` (shield damage x0.1, upkeep x0); new columns `shield_damage_taken`,
  `armor_damage_taken`, `hull_damage_taken`, `shield_upkeep`. A cell can hold values per hull size
  (`x0.33; cruiser capital: x0.5`, `SystemProfile.forSize`). `SystemProfiles.defensive(ship)` finds it in either slot.
- Branches (`MoveController.options`): `HOLD`, `STANDOFF` and `BACK_OFF` get a `+DEFENSE` variant when the system can
  be used now (idle, a charge, not venting) or is a toggle that is on. Each request carries a `SystemPlan` that
  replaces the captured system for P in that branch: `+DEFENSE` the planned use (`plannedDefenseSystem`: the profile on
  the ship's system-off values, effect from the end of the charge-up, restrictions and flux over the whole use, a
  toggle for the horizon); every other option keeps it off (a toggle that is on turns off now: its charge-down's
  restrictions, no effect). Up to 3 branches more, only for ships with such a system. A quiet batch (no enemy commits)
  still runs the damage once per distinct system plan: torpedoes with no ship near are where the damper matters.
- Decision (`MoveRule`): a `+DEFENSE` variant is driven when it is ok and its plain option isn't, or it clearly saves
  (the `+SYSTEM` rule: at most 0.8 of the cost and 50 less), and it can make `HOLD` ok. Never while venting, never on
  `ADVANCE`. Standing down (no enemy ship near) CAT still compares `HOLD` with `HOLD+DEFENSE`.
- Control (`DefenseController`): uses the system while a `+DEFENSE` option is driven (lowering the shield first for a
  damper), blocks vanilla's use otherwise; a toggle flips to match and stays on at least 3 s. The shield flicker keeps
  the shield up while a shield system is on. The vent blocker knows a damper in the right-click slot.
- Tests: unit 339 (the rule: used when it saves a lot or is the only ok one, kept for later under 50, never while
  venting or when calm; the resolver: a planned use protects only after its charge-up, an enemy's from the start, a
  branch's plan replaces or drops the captured system, a toggle turning off protects no more). `movecases` damper
  case: a CAT Centurion pinned, two Reapers from 1200, then 85% hard flux: CAT uses the damper (standing down: no ship
  near; the prediction without it 1617 hull, it lost 440), and the damper never came on without CAT choosing it over
  the whole run. `ventcases`, `aibattle`, `worstcase` pass.
- As late as still covers the hits (2026-11-15): a `+DEFENSE` variant is driven only once its plain option's first
  armor or hull loss (or overload) is within the lead: the charge-up + 0.35 s against mispredicted timing + 0.1 s of
  decision latency (`DefenseController.lead`); until then the plain option is driven, and the variant still makes the
  state ok. In the damper case it came on 0.70 s before the first hit (it had been 3.1 s).
- Next refinements: the fortress shield not yet seen in game; enemy defensive systems in P's trade (only one already
  on counts).
- Tests: unit 327 (`defenseSystems`: effect window, charge-down for P and the worst case, armor, the shield blocked
  and unfolding after, the held vent, a fortress-like toggle's shield mult and upkeep, system flux, silenced fire).
  `ventcases` damper case: an own-side Centurion (damper as the ship system) and Vanguard (damper in the right-click
  slot): idle no system captured; on, the system carries the live x0.33, the ship keeps x1.00, 3.25 s left (0.25
  charge-down), no shield, vent or firing. `movecases`, `aibattle`, `worstcase` pass.

## 11b2. Tuning (milestone 6)

Started 2026-11-15, paused the same day for more hardware: `docs/tuning-handoff.md` has the state, how to run it and
the plan. A setting is judged by CAT's effect on the same fights, against vanilla.
- **Harness: the `tunebattle` group** (`TuneBattle`, `TuneBattleDriver`). One battle holds rounds. Each round spawns
  two seeded fleets (`FleetFactoryV3`, `params.random` from the seed; factions picked by the seed), the player's with
  CAT or without, fights until one side has no ships or the cap (240 s), writes one `TUNE` line per round (per side:
  ships and FP fielded and lost, FP-weighted hull and armor share lost, a destroyed ship counting fully), then clears
  the field. No boot, no post-battle dialog between rounds. Settings: `CAT_TUNE_SEEDS`, `CAT_TUNE_MODES`
  (`cat,vanilla`; vanilla rounds don't depend on CAT's settings, so they are run once per seed), `CAT_TUNE_FP`,
  `CAT_TUNE_SECONDS`, `CAT_TUNE_SLICES` (games sharing the seeds with `--shards`: groups `tunebattle`,
  `tunebattle2` ...).
- **Tunables:** `sim/Tuning`, each `-Dcat.tune.<name>` at launch (`CAT_TUNE_SET`), default the shipped value: `horizon`
  (H), `reactionTime` (τ), `hullWeight`, `systemGain`, `systemMinSaving`, `defenseBuffer`, and the config defaults
  (vent limits, flux back-off and advance levels). The run logs them (`TUNE settings`).
- **Report:** `tools/tune_report.py <runs>` pairs each seed's rounds: CAT - vanilla own loss (lower is better: survival
  first), enemy loss, exchange (enemy loss - own loss).
- Battles aren't deterministic even with the same fleets (AI timing): repeats of one setting measure the noise
  before a difference counts.

## 11c. Progress

**Milestone 1: done, 2026-11-13.**

Files:
- `sim/` (`SimMath`, `Model`, `Motion`, `Engagement`, `Pursuit`, `World`) and `capture/MotionCapture`.
- v1's `CombatSimulation` deleted. The v1 damage simulation runs on `sim` timelines through `PredictedWorld`.

Tests:
- `test/run-unit-tests.ps1`: 39 pass.
- `aibattle`: 12 pass.

Measured in the small battle (1 × 60 FP per side), 2 s ahead:
- **Enemies that commit:** 1–2 per CAT ship per sample.
- **Worst-case distance bound**: violated (enemy more than 100 closer than predicted) in 3.6% of checks where the
  CAT ship really held, 7.1% overall. v1's drift model was 21.2% and 28.1%; median slack +266 vs 0.
- **Position error** is higher than drift (median 176 vs 64 for enemies actually targeting the CAT ship). That is
  expected: pursuit is pessimistic by design.
- **CPU**: the movement model is about 3% of CAT's 22% share.

Known interim effect: v1's decision thresholds now see enemies that close in, so ships disengage much more until
milestones 3 and 4 replace the decisions.

**Milestone 3: done, 2026-11-14.**

- `decide/rules/VentRule` + `decide/VentController` replace v1's `VentLogic`; CAT sets `DO_NOT_VENT` whenever
  it has fresh predictions and decides not to vent. While venting, a safe vent chosen in place holds
  (`DO_NOT_BACK_OFF` + `…_EVEN_WHILE_VENTING`), any other backs off.
- Tests: unit 111 pass (vent rule cases). New group `ventcases` (Eagle pinned, strict 2% armor limit):
  - calm → vents;
  - Reaper inbound → no vent, "unsafe" (predicted 1000 armor, 2856 hull);
  - Hyperion (phase teleporter) at 2000 → no vent at first;
  - control → vents.
- `aibattle` vent audit: 5 of 5 vents decided by CAT, all safe, none over their limits; 0 started by vanilla.
- Bugs found on the way:
  - unguided rockets were dropped when their AI named another target;
  - a 0-flux snapshot made "vent now" keep the shield up.
- Scenario lessons:
  - a burn-drive Dominator at 2000 can't reach the Eagle within its 3.7 s vent window, so that isn't a known
    answer;
  - at 1500 it only needs ~200 units to its hold range, so it doesn't burn (same as vanilla's AI).

**Battle trace** (built with milestone 3): `runtime/TraceRecorder` writes JSON lines to
`saves/common/cat_trace/<battle>/part_NNN.data` in dev mode or with `-Dcat.trace=true` (on by default in the
checks). It records actual ships, every hit and its result, missiles, the predictor's inputs and the hold
predictions. `tools/trace_report.py <folder>` compares them:
- damage per window, by shooter and delivery, with the worst under-predicted windows;
- pursuit error;
- missiles that hit but were never in the inputs;
- every vent with its reason.

The first `aibattle` trace found unmodelled damage: the Omen's EMP Emitter (EMP arcs, about 1000 a hit).
Damage-dealing ship systems are a gap; their AI hints carry `threatDamage` / `threatRange`.

**Milestone 4: done, 2026-11-14.**

Files: `decide/rules/MoveRule.kt` (pure rule + hysteresis), `decide/MoveController`, `decide/SystemProfiles`;
`sim` adds `OwnPlan.Approach` / `BackAway`, the advance range and the clear range. v1's `BehaviorLogic`,
`MobilitySystemLogic` and `evaluators/` are deleted. `CombatAIv2` stays (SCY compiles against its keys) as the thin
listener that wires the two controllers.

Options per CAT ship (plan §7): `HOLD`, `BACK_OFF` (vanilla's direction: straight away from the target), `ADVANCE`
(to its own engagement range of its target), the `+SYSTEM` variants of the last two when a known mobility system is
ready, and the 4b directions `BACK_OFF@0/±35/±70` (away from the DPS-weighted centre of the committed enemies). Budget:
- the 4b directions only while backing off is in play (backing off, or `HOLD` not ok) and vanilla's own back-off
  isn't already ok at no cost;
- no `ADVANCE` while flux keeps the ship disengaged;
- a ship nothing commits to runs one damage sim, shared by all its options.

Direct movement (§8.4) works: thrust commands plus blocks of the opposing commands. It flies every 4b direction, and
vanilla's direction for automated ships.

Mobility profiles were checked against the stats scripts. v1 had SCY's thrusters at ×2.5 acceleration (really ×3,
+200%) and the armor switch at ×2 (really ×2.5, +150%).

Tests:
- unit 138 pass (rule cases, hysteresis, 4b tie-breaks, advance range, back-off path, clear range);
- new group `movecases` 12 pass:
  - an unarmed Lasher 2200 away → `ADVANCE` 100%, closes in;
  - a Dominator 1300 from a Brawler → backs off within 1 s ("HOLD overloads in 6.8 s");
  - the same against an automated Warden → backs off by direct thrust, about 169/s away;
- `ventcases` 9 pass; `aibattle` 14 pass (vent audit: every vent by CAT, all safe).

Findings:
- An unarmed civilian (an ox) is not a scenario target: vanilla doesn't engage it, so the `ADVANCE` flags had
  nothing to act on.
- **Flapping**: in the small battle some ships change state 20–26 times a minute. The worst case jumps between
  "every option overloads in about 8 s" and "nothing happens" from one batch to the next, as enemies cross the 6 s
  commit window. `DISENGAGE` skips the hysteresis and only sticks above the reengage flux. First lever for milestone 5.
- v1's fallback rule (no enemy within 3000 → burn the mobility system to travel) can leave the system on cooldown
  just as enemies arrive.
- CPU: 8.8 batches per second (cap 10), 20 ms and 33 branches per batch, CAT about 40–44% of CPU samples (about one
  core). The batch cap is the lever.
- Tool: `trace_report.py` now lists each CAT ship's time and hull lost per state, its changes per minute, and each
  change with its reason line.

**After milestone 4 (2026-11-14):**
- **Damage-dealing ship systems** are modelled. The Tempest's drone strike put 416 armor and 42 hull into a
  Centurion during a vent predicted at 37 armor. An enemy whose system's AI hints give `threatDamage` adds one hit per
  use, as vanilla's vent-safety check reads those hints:
  - when the system is ready and the target is within `threatRange` + its radius and the threat arc;
  - arriving after the travel at `threatSpeed`;
  - again after each cycle, while ammo lasts.

  The `ventcases` group adds a Tempest phase: the 1200 hit is predicted and the vent refused.
- **Vanilla maneuvers on CAT's target:** `ShipAIPlugin.setTargetOverride` is set every frame (vanilla takes it at its
  next maneuver pick, then clears it, as its `DarkenedGazeSystemAI` does). Before, the range flags acted on whatever
  vanilla happened to target, and with no target `ADVANCE` did nothing.
- **Bug (from the milestone 4 rewrite):** the vent decision ran on the 0.05–0.1 s decision tick, so the 0.1 s
  `DO_NOT_VENT` flag could lapse for a frame and vanilla vented on its own (caught by `ventcases`). It is
  re-asserted every frame now.

**Milestone 2: done, 2026-11-14.**

Files:
- `sim/` adds `DamageModel`, `Armor`, `Defense`, `FireControl`, `Missiles`, `Damage`; `capture/DamageCapture`.
- v1's `DamageSimulation`, `CombatStateCapture` and `Models` deleted. `predictor/DamageOutlook` answers v1's three
  questions (flux to shield, vent damage, vent vs stay) from the new model until milestones 3 and 4.
- Horizon now H = 10 s (was v1's 20 s); batches capped at 10 per second (§9).

Tests:
- `test/run-unit-tests.ps1`: 99 pass (armor hand values, shield/flux/overload/vent, conservative fire, modules,
  fire control, missiles/MIRV/shots, end to end).
- `aibattle`: 12 pass. New group `worstcase`: 6 pass.

Measured in `worstcase` (Dominator held in place vs reckless Hammerhead DEM, Enforcer, Medusa; 2 s windows):
- **Bound:** actual raw damage exceeded 1.25 × the prediction in 1–3% of windows (4 runs). Damage arrived
  where none was predicted in at most 2 windows of about 140.
- **Pessimism:** actual / predicted raw damage median 0.5 (p10 0.3, p90 1.0). By shooter, the Hammerhead and
  Enforcer are close; the Medusa is about 2.8× over. It's a flux-heavy energy ship whose real flux comes mostly
  from P's return fire, which the worst case leaves out (§11a). DEM payloads are about 2× over: PD kills DEMs.
- Predicted hull loss is 3–4× the real one, because the extra raw damage turns into predicted overloads.

Fixed on the way: unguided missiles (Reapers) were labelled projectiles, skipping the missile damage-taken stats.

CPU (laptop, small battle): CAT 27–32% of samples, from 44% before the batch cap, the 10 s horizon and hot-path
fixes. Interim behaviour: DISENGAGE samples fell from about 900 to about 200 per run with the 10 s window.

Next levers for milestone 5, in order: an enemy flux model that includes P's return fire (or vanilla's
flux-based fire holding), then the PD factor on missiles and DEMs.

## 13. Damage trade (milestone 5)

Agreed 2026-11-14. Until now the sim only counts damage P takes, so `ADVANCE` was "the most aggressive option that
stays safe". With P's own fire simulated, P advances only when it deals more than it takes.

### 13.1 Autofire, as vanilla does it

Most weapons are on autofire, for P and for everyone else. Vanilla's autofire AI (translation of obfuscated code:
`com.fs.starfarer.combat.ai.P`, which implements `AutofireAIPlugin`):

- **Target, re-chosen every 0.25–0.5 s per weapon:**
  1. keep the current target while it is valid and in range and arc (30° slack);
  2. the ship's own target (`getShipTarget`) whenever it is in range and arc;
  3. PD weapons: a missile in range and arc first (random pick); `PD_ALSO` only after ships;
  4. else the enemy ship in range (range + its radius) and arc (lead point inside the arc plus an angular-size
     margin) needing the least turn from the weapon's current angle;
  5. `STRIKE` weapons skip fighters (unless also PD / ANTI_FTR) and frigates (unless `USE_VS_FRIGATES`); missile
     weapons with ammo skip fighters.
- **Fires when:**
  - aimed within tolerance (angle to the lead point less a margin for the target's angular size);
  - in range: range + target radius, less 0.4 × radius for non-fading projectiles over 300 range;
  - no friendly ship in the line of fire;
  - flux cost ≤ flux available. It fires until the flux bar is full. **(verify)** whether the ship AI holds fire to
    save flux on top of this.
  - not at phased targets (mostly);
  - `USE_LESS_VS_SHIELDS` weapons hold while the target's shield covers them and ammo is at most 75%;
  - regenerating-ammo weapons conserve ammo.

Sim (`sim/Autofire`, pure):
- Every non-committed ship's weapons choose targets by these rules, re-chosen every 0.4 s of sim time. Committed
  enemies target P by definition.
- P's weapons do the same.
- Left out at first:
  - the friendly line-of-fire check;
  - PD against missiles (that is the PD factor of §11a, later).

### 13.2 P's fire, P's flux, enemy defences

- **Capture:** the defences (shield, flux, armor grid, hull) of every enemy ship within P's weapon reach plus the
  horizon's travel, as P's are captured now.
- **One pass per branch:**
  1. enemies' hits on P, as now;
  2. P's shots by autofire along the branch's paths;
  3. P's defence resolved with the incoming hits and P's firing flux, each shot dropped when the flux isn't
     available;
  4. each target's defence resolved with the shots P really fired.
- P's end flux now includes its own firing, so the flux limits mean what v1 meant them to.
- Not in this milestone: enemies reacting to P's fire (an overloaded enemy stops firing). That coupling is the next
  lever against the pessimism of §11a.

### 13.3 The trade, in shares

Two currencies, both as shares of each ship so sizes compare:
- **Damage share lost** = cost (hull lost × 3 + armor lost) / (max hull × 3 + armor rating). Permanent.
- **Flux pressure** = net flux gained over the horizon (end flux − start flux, its own firing included) / max flux.
  Recoverable by venting.

Taken is P's own share. Dealt is the sum over the enemies P fires at of what each takes in this branch, from every
ship on P's side (P and its allies, by autofire along their paths). Allies already pressing a shield make
advancing on it pay.

`ADVANCE` needs:
- `ADVANCE~` safe (the escape check, unchanged);
- the full `ADVANCE` within the flux limits;
- the full `ADVANCE` trading at least one way:
  - damage: dealt / taken > 1 (permanent loss must be a win);
  - flux: dealt / taken ≥ 1 (an even flux trade is enough).

Why the flux trade: shields absorb almost everything early in a fight, so damage shares are about 0 on both sides
and nobody would engage first (the worst case also has every committed enemy focus P, so allies don't help a pure
damage rule). An even flux trade engages; the flux limits back P off to vent when its own flux climbs, and the
escape check keeps it from being caught out.

### 13.4 `HOLD` as standoff

`HOLD` becomes "the closest P can keep without taking damage". It never closes inside an enemy's reach, so its
full branch covers its own safety and replaces both `HOLD` and `HOLD~`. Keeping still stays as a branch for the vent
decision only.
- the branch keeps P just outside every committed enemy's reach (their weapon reach + both radii), backing off as
  they close, facing its target;
- when P outranges them it deals damage for free (kiting); otherwise it waits out of reach.
- The ranges come from each enemy's reach, which the sim already has, so no search is needed.
- The flags: `BACK_OFF_MIN_RANGE` = `BACK_OFF_MAX_RANGE` = that standoff distance from P's maneuver target.
  **(verify)** in the movement scenarios that vanilla holds it.

As built (2026-11-14):
- **Branch** `STANDOFF` (`OwnPlan.Standoff`) replaces `HOLD~`; plain `HOLD` (keep still) stays for the vent. P flies
  the pursuit mover with a hold range re-set every step: on the line from its target through P, the target's
  standoff (its longest non-missile gun + both radii + 100) or P's own engagement range if farther, pushed out past
  every other hostile non-fighter's standoff (committed ones where they are in the branch, the rest on base paths).
  When no point on the line is clear within 1000 more (a hostile behind P) it keeps the target's range and the sim
  shows the cost. Missiles don't count toward reach: their ammo runs out, and a standoff beyond them never engages.
- **Rule:** the `HOLD` state is judged by `STANDOFF` alone (its own safety and flux). **Kiting:** while the standoff
  deals damage and takes none, `ADVANCE` must also take more there and beat its trade (more net damage share, or
  more net flux share at no worse damage). Otherwise identical trades (nothing reaches either) went to the standoff
  and the unarmed-Lasher advance check fell to 61%.
- **Flying it:** CAT hands vanilla the strafe at standoff + 100 and sets the range flags to that, without
  `DO_NOT_BACK_OFF`. Translation of obfuscated code (`StrafeTargetManeuverV2`): the strafe closes beyond its range
  + 20 and backs up only inside its range - 100, and not at all under `DO_NOT_BACK_OFF`. The + 100 makes it start
  backing up at the standoff; it never sits closer than the sim has it.
- **Checked:** unit tests for the path (outranging, outranged, gun reach not missile reach, push-out past a second
  hostile, a faster enemy catches it) and the kiting rule. `movecases` gained a standoff phase: the Brawler
  (Arbalests, 700) against an Enforcer with five chainguns (450): HOLD 64–71% and ADVANCE the rest (the Brawler's
  own range also clears the chainguns), closest 739–766 against a reach of 555, no damage taken, 1080–1793 armor
  dealt. Against the Dominator in the threat phase it now holds at the standoff about half the time, no hull lost.

### 13.5 Branches and cost

Per CAT ship:
- keep still (vent);
- standoff `HOLD`;
- `ADVANCE` and `ADVANCE~`;
- `BACK_OFF`;
- the system variants;
- `BACK_OFF@ally` and the five 4b directions while backing off.

That is about 5–12, roughly today's count. Each branch gets heavier: P's autofire and the defences of the enemies
it hits. Estimate 25–40 ms per batch, against 12–20 ms now. Measured in `aibattle`.

### 13.5a Fire discipline (vanilla's `AutofireManagerV2`)

The first pass let P fire until its flux bar was full, which made every option overload from P's own fire. Vanilla's
ship AI doesn't do that. Translation of obfuscated code: `combat.ai.attack.AutofireManagerV2` re-decides each
autofire weapon group every 0.4–0.7 s:
- groups that cost no flux fire (while flux < 0.95);
- cheapest first, groups whose flux per second fits in half the net dissipation (dissipation − shield upkeep;
  more generous when all flux is hard) always fire;
- the rest: off with under 10 s of firing left in the free flux; else off above a flux level threshold and back on
  0.1 below it. The threshold is 0.2, + 0.07 × (own size − target size + 1) when the target is at least the ship's
  size, + 0.25 against a slower target, − 5 × recent damage.

The sim (`FireDiscipline`) runs this per weapon every 0.5 s for P and for its side's ships. It leaves out the
slower-target and recent-damage terms and the hard-flux case. Enemies keep firing everything until full (the worst
case).

### 13.6 Tests

- Unit:
  - autofire target choice (ship target wins, least turn otherwise, STRIKE skips frigates, flux-limited firing);
  - share and ratio math;
  - the standoff path.
- Scenarios:
  - P outranging a slow enemy → `HOLD` at standoff, deals damage, takes none;
  - P against a weaker ship → `ADVANCE`;
  - P against a stronger ship → no `ADVANCE`;
  - two equal ships → both engage on the flux trade.
- Battle: dealt and taken shares per CAT ship from the trace, predicted vs actual.

As built (2026-11-14), as unit tests (`DamageTests.tradeScenarios`) on the real prediction path:
- **The path:** the options `MoveController` asks for, each branch's damage with reactions through
  `DamageSim.branch` (moved out of `PredictorManager`, which now calls it), read through `DamageOutlook` (compiled
  into the tests too: it is pure), decided by `MoveRule` with the default config's limits.
- **The setup:** P has 2000 hull, 3000 flux, 150 dissipation and two 200 DPS guns; one enemy starts 1300 away; both
  have front shields and 700 range guns.
- **Results:**
  - a weaker enemy (half of everything) → ADVANCE: flux share 0.93 dealt against 0.19 taken;
  - a stronger one (double everything) → no ADVANCE: advancing overloads in 8.2 s, so it holds at the standoff;
  - a tankier one with the same guns (double flux pool and dissipation) → no ADVANCE although advancing is safe:
    the flux trade loses. This is the case only the trade gate decides;
  - an equal one → ADVANCE on the flux trade, from either side: no damage share yet, flux 0.44 dealt against 0.40
    taken. The margin comes from enemies firing under the generous discipline (§13.8), which costs them more flux.
- The "outranging a slow enemy" case is `standoffTrade` (unit) and the `movecases` standoff phase (§13.4).

Later: fleet coordination (who engages whom, covering each other), §11a's coupling.

### 13.7 As built (first pass, 2026-11-14)

- **Fire:** `FireControl`'s weapon cycle moved into `Gunnery`, which can switch targets per weapon and draws flux
  from a `FluxGate`: the shooter's own budget (`ShooterFlux`, enemies) or the ship's defence state (P).
- **Autofire:** `Volley` re-picks every 0.4 s: the ship target, else the current one, else the least turn. STRIKE
  weapons skip frigates.
- **Own fire:** P's own fire runs inside its `DefenseResolver` each step after the hits, on its real flux. It is in
  every branch, so all flux limits include it.
- **Targets:** each enemy P hits is resolved with its own captured defences:
  - P's hits and missiles, plus its side's hits (`SideFire`: once per batch, on base paths);
  - its own firing flux from shooting at P.
  - Captured defences now include enemies within 4000 of a protected ship.
- **Trade:** `DamageOutlook` turns it into a `Trade` (shares) for `HOLD` and `ADVANCE` (`OptionRequest.trade`).
  `MoveRule.favourable` gates `ADVANCE`: taking nothing is free, or more damage share dealt than taken, or at least
  as much flux share dealt as taken. The reason line shows `trade d dealt/taken f dealt/taken`.
- **Not built yet:** §13.4 standoff `HOLD` and the trade scenarios of §13.6. (Since built: the standoff, its unit
  tests and all four §13.6 scenarios.)

Results (`aibattle`, laptop, another game running alongside):

| | before §13 | trade only | + discipline and target-based commit |
|---|---|---|---|
| DISENGAGE share of samples | 51% | 48% | 28% |
| ADVANCE share of samples | 26% | 37% | 48% |
| committed enemies per sample | 3.90 | 1.68 | 1.42 |
| ms per batch | 21 | 70 | 38 |
| pursuit bound violations (> 100 closer) | 2.7% | 4.6% | 6.1% |

The trade-only column already had the target-based commit; the discipline cut most of the overloads.

- The rest of the DISENGAGE is still the worst case: every option overloads in 5–9 s from committed enemies firing
  everything until full.
- One vent went over its limit: 37 armor against 30. Staying was predicted to overload in 3.7 s, so it vented as
  the lesser risk.
- Damage shares often read 1.00 or 2.00 (whole ships killed within 10 s): enemies don't react to the fire on them
  yet (§11a coupling).
- In `movecases` the threat phase failed once and passed on a rerun: the Dominator used its burn drive and caught the
  Brawler. The other groups pass: `ventcases` 12/12, `worstcase` 6/6, `movecases` 11/12 with the obsolete Warden
  "moved away" check, now a note.

Noticed: with all back-offs overloading, the full `ADVANCE` sometimes shows no flux and nothing dealt. Most likely
the approach mover flies nose-first toward a target that lies away from the threat. That beats every back-off,
which thrusts while facing the nearest enemy. Unverified, and it doesn't drive any decision (`ADVANCE~` overloads),
but a "turn and run" escape may be worth a branch.

### 13.8 Enemies reacting to P's fire (backing off, going quiet)

Asked for 2026-11-14: committed enemies should back off at high flux, as vanilla's do. Translation of obfuscated
code (`StrafeTargetManeuverV2`, `combat.ai.N`). A ship backs off:
- while its hard flux level is over a threshold, or its flux level over 0.9. The threshold:
  - by personality: reckless and aggressive 0.8, steady 0.5, cautious and timid 0.3;
  - lowered per hull size the target is larger (0.15 a size, reckless 0.05), down to 0.5 / 0.3 / 0.2 / 0.1;
  - phase ships + 0.1 (max 0.8);
  - + a CR offset once peak CR time runs out.

  Full assault, intercept orders and strike orders (non-carriers) make it reckless. Not while its target is
  overloaded for 3 s more or venting for 4 s more. Automated ships (`backingOffWhileNotVentingAllowed = false`)
  don't back off on flux.
- always while overloaded or venting, unless `DO_NOT_BACK_OFF`.

How it backs off: it holds its strafe range + radii instead of pressing in by up to 200, and backs up only when
closer than that − 100. Only "in danger" (overloaded or venting for over 2 s more; also very low hull, or shield off
under threat) do frigates and destroyers accelerate backwards facing the target at any range; bigger ships still
only inside their range. While overloaded nothing fires.

A first version had frigates and destroyers retreat on flux too. The battle trace showed it was wrong: a steady
Shrike at hard flux 0.54–0.86 held about 550–600 from the CAT ship instead of retreating, which matches the code
once read past the outer branch.

The other half of vanilla's choices at high flux is the fire discipline of §13.5a: its ships run it too. The first
pass of §13.8 left enemies firing everything until full, and in `aibattle` that kept every option overloading
in 6–9 s while reactions almost never happened (P rarely fired in those options). So enemies now fire under the
discipline as well, with + 0.25 always (the slower-target term): they fire as long as vanilla ever would.

**In the sim** (`sim/Reaction.kt`). A branch runs twice when it needs to:
1. as before, with every enemy P fires at resolved against its own defences: P's and its side's hits, plus its own
   disciplined firing flux;
2. if any enemy reacts, or P's side put hard flux on one (the pressure), again with that `Coupling`:
   `World.branch(..., reactions)` and `DamageSim.run(..., coupling)`. The pressure goes on each enemy's flux bar
   for its discipline, so a pressed enemy fires less. The vent-now outcome stays the first run's: P doesn't fire
   while venting, so nothing reacts to it.

Each enemy's first event in run 1 counts; nothing reacts in run 1, so what happens after an enemy starts backing
off would differ. Conservative choices:
- **Discipline, generous:** the + 0.25 term always (vanilla adds it against a target at least its size that isn't
  faster), the recent-damage term never; the shield upkeep of enemies without captured defences is left out.
- **Not while P vents:** the vent-now outcome has enemies fire everything until full. With discipline there, 2 of 5
  `aibattle` vents went over their limits (a Shrike's energy guns did 3.6× the predicted damage during one); a
  venting ship can't defend itself, so the vent keeps the old worst case.
- **Pressure:** the first run's hard flux on the enemy. On an enemy that backs off it drains at full dissipation
  from then on, though it couldn't with the shield up.
- **Back off on flux:** holds its back-off range until P overloads for 3 s more, or for the rest of the window (its
  hard flux can't drain while its shield is up).
- **Overload:** silent and backing off for its duration (retreating if over 2 s, frigates and destroyers), then
  back to the pursuit.
- **Already overloaded or venting at the snapshot:** not modelled. Backing them off was tried; in `aibattle` 5 of 8
  came more than 100 closer than predicted (likely vanilla's safe-vent spot). Their fire is silent either way.
- **Only enemies whose live target is P move:** vanilla backs off from its own target, so one fighting another ship
  backs away from that one, maybe toward P. The others still go quiet and feel the pressure.
- **Left out:** a second back-off, an overload after a back-off on flux, the flux trigger from the enemy's own
  fire, venting, and vanilla's back-off on relative flux (over 0.5 flux and 0.1 more than its target; vanilla
  checks it only under a condition not decoded yet).

Results (`aibattle`, laptop, one battle per row; the fleets differ per run, so the numbers are noisy):

| version | DISENGAGE | ADVANCE | committed per sample | ms per batch | bound violations, all / P held |
|---|---|---|---|---|---|
| §13.7 (no reactions) | 28% | 48% | 1.42 | 38 | 6.1% / – |
| reactions only, enemies fire everything | 40% | 30% | 2.14 | 17 | 8.5% / 4.9% |
| + enemy discipline and pressure | 23% | 48% | 1.89 | 76 (other game running) | 10.9% / 5.5% |
| + only enemies targeting P move | 35% | 54% | 1.13 | 21 | 10.0% / 4.2% |
| + back-off on flux holds instead of retreating | 23% | 58% | 1.15 | 36 | 8.7% / 3.3% |

Checked against the trace (`tools/trace_report.py` splits the bound violations by predicted reaction):
- The personalities read right (steady, 0.5). Steady ships past 0.5 hard flux kept their range instead of
  retreating, which led to the hold model above.
- Enemies predicted backing off by +2 s: 41% more than 100 closer with retreats and no target check, 0 of 3 after
  the fixes (few samples: only enemies fighting P back off now).
- One loss in the last run: a Drover disengaged for 17 s with its shield up, so its hard flux (0.53) never drained,
  and every option overloaded in 4–8 s so no vent was safe. A burst took it to 0.8, it overloaded and died
  overloaded. Not a reaction error: an open case for shield use while disengaging.

### 13.9 Shield flicker

Asked for 2026-11-14 (the Drover case above: shield up for 17 s, hard flux stuck). Hard flux only drains with the
shield down, so drop it whenever that is safe: if dropped now and raised again next frame, it is fully up before
the next hit it would block.

- **Prediction:** the resolver records the time of every hit the shield covers (`DefenseOutcome.shieldHitTimes`,
  held conservative shots included: they would fire at a dropped shield). `DamageOutlook.nextShieldHit` reads the
  branch the ship is flying (else `HOLD`).
- **Rule** (`decide/rules/ShieldRule`): drop when the next shield hit is further away than the window: unfold time
  (engine seconds) + 0.4 s, + 0.15 s for omni shields (they may have to turn).
- **Only when it gains enough** (`ShieldRule.gain`): over the time it could stay down (until the next predicted
  shield hit less the window, at most 2 s), more flux drains with the shield down (all flux at full dissipation)
  than up (soft flux only, at dissipation − upkeep; flux rises when upkeep is over dissipation). At least 25 flux
  or 1% of max. So a little soft flux with good net dissipation keeps the shield up; hard flux, or a full bar
  slowed by upkeep, drops it.
- **Live check** (`decide/ShieldController`) keeps the shield up for:
  - a hit on the shield within the last 0.5 s (its hard flux rose);
  - an enemy beam whose line passes within its radius + 50, charging ones included;
  - an enemy shot passing within its radius + 30 within the window;
  - an enemy missile within its speed × window + 100.
- **Driving it:** every frame. While safe, `DO_NOT_USE_SHIELDS` (0.1 s): vanilla's shield AIs turn the shield off
  while it is set, and vanilla's own danger check clears it (low hull under threat). When it stops being safe, CAT
  clears the flag and raises the shield itself.
- **Audit:** `trace_report.py` lists the drops per ship, the hard flux drained, the raw damage that landed off the
  shield while down (+1 s), and why the shield stayed up while hard flux was above 0.1.

Tuned on `aibattle` (raw damage on the shielded side, within 90° of the facing, while held down and the second
after, until a vent or overload begins):

| version | drops | time down | hard flux drained | raw damage, shielded side |
|---|---|---|---|---|
| margin 0.2 s (2 battles) | 175 | 436 s | 4697 | 729 |
| margin 0.4 s (2 battles) | 85 | 155 s | 2303 | 688 |
| + recent shield hit, beam lines (2 battles) | 101 | 192 s | 5273 | 513 |
| + only with hard flux (2 battles) | 21 | 22 s | 2013 | 0 |
| hard-flux gate replaced by the gain (upkeep counts) (2 battles) | 33 | 66 s | 3150 | 42 |

The hard-flux-only gate was wrong: the shield costs upkeep while on, so it slows the soft flux drain too (the user's
correction). "Hard flux drained" counts hard flux only; the upkeep saved isn't measured.

What the misses were: the shield came back up as planned, but the next shot landed 0.2–0.5 s later, before it was
fully unfolded (the predicted shot timing was a little late); one ship dropped it 0.02 s after taking two hits on
it and a charging beam from the same ship then ramped up on the hull; most bad drops had no hard flux to drain at
all (the gain gate now keeps those up). With enemies in range the shield mostly stays up for live threats (missiles, beams, shots inbound) or a
predicted hit within the window.

## 12. Open questions

- **`H` (10 s)** is a starting value (it is also the commit window now, §5.1); tune it in milestone 5.
- **Omni shield facing.** If vanilla's omni shield AI behaves differently from §6.3, copy what it does.
- **SCY systems.** Their hints exist but are averages. P's own system uses explicit profiles from the real stats
  scripts: SCY thrusters ×3 accel and −75% turning; the armor switch ×2.5 accel and −50% turning, with the armor
  module and weapon arcs captured live. The vanilla jets, plasma jets and burn drive get profiles too.
- **DEM stats API.** The audit found no DEM-specific stat getter in this build; confirm which addition was
  meant.
- **Settings.** LunaLib for `H` and the debug overlay, vs the per-variant dialog.

## 14. Later investigations

Noted 2026-11-14 at the user's request; not planned yet.

- **Armor tanking.** Sometimes it is worth dropping the shield in a fight, not only between hits (§13.9): against
  mostly kinetic weapons (half damage on armor), with good armor, or when holding the shield up leaves no flux to fire
  back, or a mix of these. How to judge it isn't worked out. A likely shape: a shield-down branch (or a shield plan
  per branch) resolved like the others, then compared on the trade (§13.3): armor and hull lost against the flux
  freed for the ship's own fire.
- **Point defense** (§11a). The sim leaves out P's PD shooting missiles, fighters and DEMs down, so missile-heavy
  enemies are over-predicted (DEM payloads about 2×). Modelling it properly means modelling PD accuracy, which is
  hard. Start with a simple correction heuristic (an expected interception share per missile from P's PD DPS in
  range and the missile's HP), and judge from the trace whether more is worth it.

  **Built 2026-11-14** (`sim/PointDefense.kt`), with vanilla's aim, because the user pointed out its lead is poor.
  - **Vanilla's lead** (translation of obfuscated code: `combat.ai.N`, the lead point the autofire AI `combat.ai.P`
    aims at):
    - an intercept on the missile's current velocity relative to the ship, from the ship's centre, with the
      distance less 0.75 × both radii;
    - every speed is multiplied by k = 2 − the ship's autofire aim accuracy (0 without skills, so k = 2: it leads
      by about half the true flight time), minus the weapon's `autofireAccBonus` (blank on every vanilla weapon);
    - k drops by 1/15 per second the weapon has fired at the same target, at least 1. It is 1 for PD weapons on
      ships with `pd_best_target_leading` (Integrated Point Defense AI; Gunnery Implants gives autofire accuracy
      +100% instead);
    - acceleration and turning are never led.
  - **Per shot:**
    - the miss is the closest approach of the shot (ship velocity plus muzzle speed toward that aim point, from
      the mount) to the missile carrying on with this step's acceleration;
    - the hit chance is that miss against the spread cone at that distance, over the missile's width (its radius
      + 5);
    - it is multiplied by how well the turret tracks the missile's angular rate. Beams count tracking only.
  - **Per missile run:**
    - P's PD and PD_ONLY weapons (not STRIKE, not PD_ALSO) each engage one missile at a time, after 0.3 s in
      range and arc, and stay on it while it lives;
    - the expected damage lands after the shot's flight time;
    - a missile whose hitpoints (live, or the spec's) run out never arrives.
  - **Both ways:** P's PD against incoming missiles, enemies' PD against P's missiles (the trade), and against the
    side's missiles.
  - **Never while venting:** the vent-now run has no PD.
  - **A/B switch:** `CAT_PD=false` in the checks (`-Dcat.pd=false`).
  - **Tests:** unit tests for a missile flying straight in (hit even at k = 2), a crossing missile (missed at k = 2,
    hit at k = 1), acceleration not led, spread and tracking, interception in a run (light missile down, heavy one
    through, a weapon facing away doesn't help, one weapon one missile at a time), and no PD while venting.
  - **Measured** (`trace_report.py` "Point defense", DEMs left out of both): the share of missiles aimed at CAT
    ships that were shot down, actual against predicted:
    - `aibattle` runs: 0.40 against 0.31 (21 missiles), and 0.11 against 0.08 (35);
    - `worstcase`: the missile-in-flight over-prediction (4000 against 0) went away.
    - So the prediction stays a little conservative, as intended.
  - **Cost:** the first version doubled batch time (62 ms); the arc check ran on every step of every missile's
    flight. Range first, a reach cull and lazy tracking brought it back to 33 ms.
  - **Still open:**
    - Flak's proximity fuse hits far wider than the 5 unit shot radius;
    - PD_ALSO weapons and fighters as targets;
    - PD weapons firing at ships in the same branch are counted for both;
    - DEM payloads: the Gorgon's drones fire from beyond most PD (Vulcans reach 250), and its payload beams were
      about 3.8× over-predicted in `worstcase` (19.8k against 5.2k). That is the DEM model, not PD.
  - **DEM double count, fixed 2026-11-14.** Vanilla's `DEMScript` spawns a `dem_drone` with the payload at the
    trigger and keeps the missile alive while the drone fires. The capture counted both: the drone's beam
    (`captureDemDroneBeam`) and the missile in its FIRE state. A DEM missile firing or done is now left out; its
    drone carries the rest of the payload. `worstcase`: 15.2k predicted against 8.8k actual, then 16.6k against
    6.9k, so about 2× over instead of 4×. What is left is for the per-DEM audit (geometry, launch timing, PD).
  - **Per-DEM audit, 2026-11-15** (`worstcase`, Gorgons from a `hammerhead_DEM` at a Dominator; the trace's drone
    beam ticks per DEM):
    - **The payload model.** All four vanilla DEM payloads are burst beams. Gorgon: 800 DPS, 0.05 s charge-up,
      0.9 s burst, 0.25 s charge-down. Damage follows brightness squared (the beam ramps above), so a Gorgon payload
      is 800 × (0.9 + 0.3 / 3) = 800, and the trace shows exactly that: a ramp of 12.8 and 33, then 80 per 0.1 s
      tick. The sim dealt the DPS for the whole firing time (1000; a Dragonfire 8000 instead of 4000). Now
      `DemInfo.payloadStart` / `payloadDuration` carry the burst with a third of the ramps, the captured drone beam
      adds its charge-down third (or what its fade still holds), and a tick out of the payload's range deals nothing.
    - **PD cuts a firing DEM.** Vanilla's `DEMScript` removes the drone when the missile dies, also while it fires
      (unless `bombPumped`: the missile is gone at once and the drone fires on). In `worstcase` 3 of 16 Gorgons
      delivered nothing and several only part, at 25–150 of their 250 hp. The sim dealt the whole payload the moment
      firing started. Now a firing DEM holds station as a PD target and deals each step's ticks only while alive.
      A triggered DEM also no longer expires on its flight time (vanilla sets it to 10000).
    - **Launch timing.** From 12 s to 55 s every prediction had the Hammerhead launching Gorgons; it launched none
      until 55 s, about 760 away. Vanilla's manual missile fire (translation of obfuscated code:
      `combat.ai.attack.AttackAIModule`) holds limited, non-kinetic missiles while the target's shield could take
      1.25 × the group's volley (its free flux plus half the dissipation over the flight time) and either its shield
      is up and covers the shooter (active arc + 130°), the shooter is in its front 120°, or it is beyond 500 +
      radius (400 for omni shields). It fires them as finishers: on high flux, overload or vent, or from behind
      close in. The sim's `conservative` flag already holds these hits while P's shield covers them and releases
      them when it drops, so the after-defence prediction follows this. The `worstcase` raw totals counted them,
      though: the report now shows the conservative part per delivery.
    - **Barrels:** the Hydra payload has two barrels ([0, 0]). Whether a multi-barrel beam's DPS is per barrel isn't
      verified: the Paladin PD's 1000 DPS over five barrels suggests it is the total. Left as one beam.
    - **Result** (`worstcase`, one run each; the fights differ):

      | | DEM beam predicted (conservative) | actual | over |
      |---|---|---|---|
      | before (DEM double count fixed) | 20.6k (–) | 7.2k | 2.9× |
      | payload model | 17.2k (1.6k) | 7.2k | 2.4× (2.2× without the held part) |
      | + PD cuts the firing DEM | 16.2k (1.8k) | 9.9k | 1.6× (1.45×) |

      Actual per firing DEM in the last run: 764 of 800. What is left: the sim predicts somewhat more DEMs reaching
      their payload than vanilla delivers (its PD on station-keeping DEMs is a little weak), which is conservative.
      Unit tests 290 (payload 800, ticks, range, PD cut, bomb-pumped); `aibattle` 14/14.
  - **Missile flight cost:** `MissileSim.guided` computed an atan2 on every step of every missile to test "flying
    more than 90° off its facing"; a dot product says the same. `fastAtan2` fell from 28% to 15% of CAT's CPU in a
    missile-heavy `aibattle`.
- **Beam ramps** (found 2026-11-14, `aibattle` vent audit, 2 of 3 safe vents over their limits):
  - a Lasher vented "safe" (predicted 0 armor) and lost 199 armor to an Anubis burst beam fading out: ticks of
    112, 87, 66, 47, 33, 22, 9, 6 (382 raw). The sim deals no beam damage outside a burst's full-power part, so a
    beam charging down at the snapshot predicts nothing;
  - a Lasher vented at 28 of its 30 armor limit and lost 61 to a Shrike's beams.
  - Next: model burst beams' charge-up and charge-down damage (vanilla scales beam damage by its brightness;
    **(verify)** the curve in the beam code), and capture beams still fading at the snapshot.
  - **Built 2026-11-15.** How vanilla does it (translation of obfuscated code: `BeamWeaponRay.advance`, the beam
    weapon tracker `trackers.String`):
    - each frame adds brightness² × frame time (ship time) to an accumulator;
    - every 0.1 s or more the ray deals one tick: the damage multiplier is the accumulator over the elapsed time,
      dpsDuration is the elapsed time, and the ship's damage bonuses are applied as modifiers;
    - a burst beam's last fade tick also gets b² / 4 × charge-down;
    - brightness ramps linearly over the charge-up, holds for the burst, and ramps down over the charge-down;
    - a burst beam's `getCooldown()` is charge-down + burst cooldown;
    - `getChargeLevel()` is not the brightness while fading; `BeamAPI.getBrightness()` is.

    So each ramp is worth a third of its length at full power. The capture adds (charge-up + charge-down) / 3 to
    the burst, taken out of the charge-up wait and the cooldown: the same cycle, the same sustained DPS. A burst
    beam fading at the snapshot fires on for b³ × charge-down / 3. Continuous beams are unchanged: the sim fires
    them whenever they can engage, ramps ignored.
  - **Vent margin:** a safe vent must predict at most 80% of the limits (`VentRule.SAFE_MARGIN`). The audit still
    judges against the full limits.
  - **Checked:** unit tests 282 (the margin); `ventcases` 12/12; two `aibattle` runs 14/14 with 5 vents, none over.
    The fading-beam case didn't recur in those battles.
- **Vent pursuit** (found 2026-11-14 in `aibattle`): a Hound vented "safe" (predicted 2 armor, 27 hull) and took
  69 armor and 350 hull, two 500-damage energy hits from a Shrike that caught it while it backed off venting. The vent
  prediction has no PD by design, so it's the back-off-while-venting path or the pursuit model.

  **Found and fixed 2026-11-15: the capture, not the pursuit.**
  - **What happened:** the Shrike's Plasma Burn (2 charges, one per 10 s) started on its last charge between two
    trace samples, just before the 122.82 s batch. A system on its last charge is out of ammo while it runs, and the
    motion capture read that as never usable (`readyIn` = MAX). A system already on at the snapshot wasn't modelled at
    all. So the sim's Shrike fell behind the fleeing Hound, and the vent read as safe.
  - **How it was found:** the trace showed the Shrike burning (speed 120 → 580, 639 → 388 in 1 s) the moment the
    Hound vented, and BACK_OFF jumping to 1244 predicted hull once the burn was captured. A unit test at the trace's
    snapshot gives 29 hull within the 3.9 s window with the system unusable (the battle predicted 27) and 680 with it
    ready.
  - **Vanilla's side** (translation of obfuscated code: `combat.ai.system.a`, the burn drive AI, which Plasma Burn
    uses): a short-burst system (burst > 0, no speed hint, active ≤ 1 s) treats a target venting or overloaded for
    more than 1 s as fair game even when it would otherwise hold back. It burns in when farther than the burst + 0.75
    × radii, its own flux is under 0.65 and 0.2 below the target's, and the target is smaller or the same size. The
    sim's pursuers already burn whenever the gap is worth it, so nothing changes there.
  - **Fix:**
    - out of charges: ready when the next one regenerates, (1 − reload progress) / ammo per second, as the damage
      capture's system threats already did; never only when it doesn't regenerate;
    - `SystemInfo.activeLeft`: a system on at the snapshot (IN or ACTIVE) keeps its effect for the charge-up and
      active time still to come, in the pursuit and in the commit and clear-range checks.
  - **Tests:** unit tests at the snapshot (unusable: falls behind; ready: catches; already burning: catches).
    `ventcases` has a pursuit case: a CAT Hound with the default limits at 90% hard flux, a `shrike_Attack` 640 away on
    1 of 2 charges, both free. It checks that a safe vent stays within its limits, and that a burn on the last charge
    is captured running and ready when the charge comes back.
  - **Checked:** unit tests 285; `ventcases` 15/15 (CAT waited out the Shrike and vented at 1226 mid-burn, predicting
    nothing; the Shrike burned twice and got no closer than 700); `aibattle` 14/14, 2 vents, none over.
  - **Trace:** ship records now carry the system state (`sst`, charges `sam`, cooldown `scd`), and `predo` records
    the BACK_OFF branch next to `pred` (the vent's other option).
