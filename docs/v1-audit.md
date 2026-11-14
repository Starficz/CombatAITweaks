# Combat AI Tweaks v1: audit against the game code

Status: done, 2026-11-13. Game build 0.98.5a-RC4; ScyNation as installed (gameVersion 0.98a-RC8).

Each v1 assumption is checked against the decompiled game (`obf/`), the API sources (`api/`) or SCY's own source.
Vanilla logic is described in translation. Obfuscated class names (single letters like `A`, `oO0O`) are given
only so the code can be found again; they change between game builds and must never be compiled against.

Verdicts: **right**, **partly right**, **wrong**, or **unverified** (still to check, with where).

## Summary

| # | v1 assumption | Verdict | What v2 does instead |
|---|---|---|---|
| 1 | CAT's listener can steer the AI through flags | right | keep, with the correct flag per intent (rows 2–6) |
| 2 | `HARASS_MOVE_IN` makes a ship advance | **wrong** for most ships | `BACK_OFF_MAX_RANGE` sets how close it holds |
| 3 | `DO_NOT_BACK_OFF` keeps a ship in the fight | right | keep |
| 4 | `BACK_OFF` makes a ship back off | partly right | ignored by automated ships unless venting: use direct movement for them |
| 5 | `BACK_OFF_MIN_RANGE` pushes a ship out | right | keep |
| 6 | Vents happen only when CAT says so | **wrong** | set `DO_NOT_VENT` whenever CAT decides not to vent |
| 7 | Commands CAT gives override the AI | right, with a rule | block the opposing command types, then give ours |
| 8 | Weapon cooldown from `getCooldown()` | **wrong** for projectile weapons | `getRefireDelay()` (includes rate-of-fire bonuses) |
| 9 | Beam DPS, EMP and DEM payload damage from `derivedStats` | **wrong** (base spec values) | the weapon's live `damage` |
| 10 | EMP per shot = `damage.fluxComponent` | right | keep |
| 11 | Shield flux uses the base `shieldDamageTakenMult` | **wrong** | the modified value, and the `DamageType` enum's mults |
| 12 | Armor: one pooled value and v1's hull overflow formula | **wrong** | vanilla's cell-by-cell grid formula |
| 13 | Hardpoints: 20° arc, half the ship's turn rate | approximation | the real slot arc, with the ship's simulated facing |
| 14 | Missiles steer straight at the target at full thrust | partly right | lead pursuit like vanilla's `MissileAI` |
| 15 | MIRV splits at the end of flight time, and within `splitRange` | **wrong** | vanilla's split conditions; no end-of-life split |
| 16 | MIRV submunitions copy the parent's stats | **wrong**, now fixable | `SettingsAPI.getMissileSpec()` / `getProjectileSpec()` are now in the API |
| 17 | Mobility system speeds: jets +50/×3, plasma +125/×4.75 | right | the system's AI hints for enemies; exact stats for own active systems |
| 18 | SCY thrusters ×2.5 accel, armor switch ×2 | **wrong** | thrusters ×3 and −75% turning; armor switch ×2.5 and −50% turning |
| 19 | DEM parameters as range midpoints | approximation | each in-flight DEM's own rolled values (reflection on `DEMScript`) |
| 20 | Enemies fire forever | **wrong** | flux budget (plan §5.6) |

## 1. Flags and how vanilla reads them

### Frame order

Each frame, `CombatEngine.advance` runs **every ship's AI first** ("Advancing entity AI"), then advances each ship.
CAT's `AdvanceableListener` runs inside `Ship.advance`. So CAT always acts **after** the AI has read flags and
queued commands for that frame:

- **Flags** CAT sets are read by the AI on the next frame. A 0.1 s duration is enough at any frame rate above 10
  fps.
- **`unsetFlag`** takes effect on the next frame too, unless vanilla sets the flag again during that frame's AI
  pass. Vanilla's circumstance evaluator (`oO0O`) does set `BACK_OFF` itself, for 1–6 s, when the ship is out of
  ammo or weapons.

### Commands

Commands are queued, then filtered later in `Ship.advance`, after the listeners have run:

- `blockCommandForOneFrame(X)` removes every queued X, **including the AI's**. Only engine-internal commands
  can't be blocked.
- Duplicates are dropped. Forward and backward thrust exclude each other, and the first one queued wins, which
  is the AI's.
- **Rule for direct control:** block the command types we don't want (which removes the AI's), then give the
  ones we do. This makes plan milestone 4b feasible.

### `HARASS_MOVE_IN`: wrong for most ships

`BasicShipAI` only reads it in "harass mode", which applies to:
- ships under a HARASS order or assignment;
- **cautious or timid** officers facing a target of equal or bigger hull size;
- non-combat ships.

In harass mode the ship normally kites just outside the target's threat range. `HARASS_MOVE_IN` makes it move in
to its own longest non-missile weapon range instead. For steady, aggressive and reckless ships it does nothing to
the range. (`StrafeTargetManeuverV2` also counts it as one way to qualify to stay in while venting.)

**v1's ADVANCE state therefore doesn't make ships come closer**; in practice it is just "don't back off".

### `BACK_OFF_MAX_RANGE` / `BACK_OFF_MIN_RANGE`: right, and MAX is the real lever

`StrafeTargetManeuverV2` clamps the range it holds (`MANEUVER_RANGE_FROM_TARGET`) to the range MIN sets as a
floor, then to the MAX ceiling. MAX is how to make a ship hold a chosen, closer range; v1 never uses it.

### `DO_NOT_BACK_OFF` and `DO_NOT_BACK_OFF_EVEN_WHILE_VENTING`: right

In `StrafeTargetManeuverV2`:
- `DO_NOT_BACK_OFF` cancels both of vanilla's back-off triggers (the flux and hard-flux threshold, and the
  "losing the trade" check).
- Overloaded or venting ships back off unless `DO_NOT_BACK_OFF` is set.
- Staying in place *while venting* also needs `DO_NOT_BACK_OFF_EVEN_WHILE_VENTING`, which is what the API
  javadoc says.

v1 sets both together, which is correct.

### `BACK_OFF`: right, but not for every ship

`BACK_OFF` forces back-off in the strafe maneuver and stops pursuit in `BasicShipAI`. Vanilla's mobility-system
AIs (maneuvering jets, burn drive) also use it as a signal to burn away. The jets' own spec validation says "the
MANEUVERING_JETS AI assumes the system is useful for backing off".

**Exception:** with `ShipAIConfig.backingOffWhileNotVentingAllowed = false`, the maneuver drops back-off unless
the ship is venting. `CoreLifecyclePluginImpl` sets it false for:
- all automated ships (`Misc.isAutomated`), **including the player's AI-core ships**;
- derelicts, and enemy Ziggurats;
- reckless Threat and Dweller hulls.

On those ships, v1's BACK_OFF and DISENGAGE do nothing.

### `DO_NOT_PURSUE`: right, minor

`BasicShipAI` treats it like `BACK_OFF` for not switching to the older pursuit/strafe maneuver. Vanilla sets it
itself whenever it backs off.

### `MANEUVER_RANGE_FROM_TARGET`: right. `MANEUVER_TARGET`: only a short-lived signal

`MANEUVER_RANGE_FROM_TARGET` is set every frame by `StrafeTargetManeuverV2` (0.5 s duration), and v1 reads it
correctly. v2 also reads it on **enemy** ships (plan §5.2).

Corrected during milestone 4: `MANEUVER_TARGET` is set for only 1 s by the maneuver sequence around a target
maneuver, so it flickers. The ship AI's real target is `BasicShipAI`'s private field `target`. v2 reads that field
by reflection (`runtime/VanillaTarget`) and falls back to the flag for other AIs. Vanilla's system scripts in
`api/impl` (`DroneStrikeStats` and others) read the flag too (not checked whether the flicker matters to them).

### Venting: v1 only adds vents, it can't stop vanilla's

Vanilla vents on its own from three places:
- the threat evaluator `A` vents when it judges itself safe (and sets `SAFE_VENT`), and when the target is venting
  too;
- a retreat maneuver;
- a "dump flux" maneuver.

**The threat evaluator's whole vent section is skipped while `DO_NOT_VENT` is set**, and the maneuvers check it
too. v1 never sets it, so vanilla still vents when CAT judged venting unsafe. **v2 sets `DO_NOT_VENT` every frame
unless it has decided to vent**, which also turns off vanilla's flux-based `BACK_OFF` in that section, so CAT
owns that decision fully.

## 2. Weapon model

| v1 does | Game does | Verdict |
|---|---|---|
| cooldown = `weapon.cooldown` | projectile weapons: `getCooldown()` returns the **spec's** refire delay; `getRefireDelay()` returns the live tracker value with rate-of-fire stats | **wrong**: use `getRefireDelay()` |
| beam DPS, burst-beam damage, beam EMP from `derivedStats` | `getDerivedStats()` returns the weapon spec's derived stats (no ship bonuses) | **wrong**: use `damage.damage` (DPS for beams) and `getEmpPerSecond`-style values scaled by the live damage |
| per-shot damage `weapon.damage.damage` | `DamageAPI.getDamage()` = base × modifier × multiplier, but a projectile weapon's `getDamage()` is its projectile **spec's** damage: the ship's weapon-type bonuses are added only to each fired shot (`computeExtraDamageComponents`). A beam's is the live ray's, × brightness | **wrong** on ships with damage bonuses (checked in M2): v2 adds the bonuses itself |
| EMP = `damage.fluxComponent` | vanilla sets `fluxComponent` to EMP (MIRV submunitions, EMP arcs) | right |
| range = `weapon.range` + 0.8 × fade range | `getRange()` includes every range modifier | right |
| projectile speed = `weapon.projectileSpeed` | includes stats | right |
| beam hit strength = DPS / 2 | `dpsToHitStrengthMult` = 0.5 | right |
| hardpoints: arc 20°, turn at half the ship's turn rate | slot arc as defined; the ship turns to aim | approximation; v2 simulates enemy facing (plan §5.5), so it can use the real arc |
| fires whenever in range and arc | `AttackAIModule` holds fire on flux-inefficient weapons vs shields (no `FIRE_WHEN_INEFFICIENT`, target's flux within 0.2 of own) and conserves ammo | fine for a worst case |
| unlimited fire | weapons can't fire past max flux | **wrong**: plan §5.6 |

## 3. Damage and flux formulas

### Shield flux

Vanilla's own estimators (`AttackAIModule`, danger evaluator `C`) use:

```
flux = damage × type.getShieldMult() × shield.getFluxPerPointOfDamage() × stats.getShieldDamageTakenMult().getModifiedValue()
```

`getFluxPerPointOfDamage()` already includes `getShieldAbsorptionMult()`.

v1 defaults to `shieldDamageTakenMult.base`, which overestimates flux on ships with shield bonuses. It also
hardcodes the type multipliers; use the `DamageType` enum's `getShieldMult()`, `getArmorMult()` and
`getHullMult()` instead.

### Armor and hull

From the armor grid class (implements `ArmorGridAPI`); this is the formula in plan §6.5.

- Damage and hit strength are both multiplied by `type.getArmorMult() × armorDamageTakenMult` (× the kinetic
  armor mult for kinetic). Beams use DPS × 0.5 as hit strength.
- Armor at the hit cell is the weighted 21-cell sum: weight 1 for the centre and inner ring, 0.5 for the outer 12.
  Then `effectiveArmorBonus` is applied, floored at `armorRating × minArmorFraction`.
- The damage factor is `1 − min(armor / (armor + hitStrength), maxArmorDamageReduction)`, from vanilla's
  `computeDamageMultiplier`.
- Damage is spread 1/15 to each of the inner 9 cells and 1/30 to each of the outer 12. Each cell's overflow goes
  to hull × `type.getHullMult() / armorMult × hullDamageTakenMult`.

v1 pools armor into one number and computes hull damage as a proportion of the hit. It gets the order of magnitude
right, but not where the damage lands or the overflow.

Also, vanilla ships turn their least damaged armor toward the threat (`turnToFaceWithUndamagedArmor`, the armor
evaluator in `StrafeTargetManeuverV2`). v2's hit placement should assume P does the same.

## 4. Missile model

### Guidance (`MissileAI`)

Vanilla's guided missiles:
- aim at a **lead point**: an intercept prediction using the missile's max speed and a lead multiplier from
  `1.5 − 0.5 × guidanceBonus`;
- curve in on a pursuit offset at long range;
- only accelerate while their velocity is within about 30° of their facing, and brake when it's more than 90° off;
- `MISSILE_SPREAD` weapons spread their aim points across the target.

v1 steers straight at the target's current position at full thrust. It is **partly right**: hit or miss mostly
agrees for slow targets, but arrival times are late against moving ones. v2 uses lead pursuit.

### MIRV (`MirvAI`)

| | v1 | Vanilla |
|---|---|---|
| when it splits | distance ≤ `splitRange` and time ≥ `minTimeToSplit`, **or at the end of flight time** | distance < `splitRange` + target collision radius, **and** the missile's facing within a small tolerance of the bearing to the target (0.1 × the target's angular size + max(0, arc − 1.5)), and time ≥ `minTimeToSplit` (or an early split once the arrival estimate allows it). **No split at end of life.** `splitRange` gets a random `splitRangeRange` added per missile |
| submunition velocity | parent velocity + spread direction × `spreadSpeed` | same, plus random `spreadSpeedRange`. Spread angles are random within `arc` unless `evenSpread` |
| submunition facing | (after my fix) the spread angle | missiles and plasma shots face **the bearing to the target**; ballistic submunitions travel on their spread angle |
| submunition stats | the parent's engine stats (`TODO 0.98.5`) | `SettingsAPI.getMissileSpec(id)` and `getProjectileSpec(id)` now exist, so the real submunition spec is available |
| submunition damage | `damage` param, else the parent's | `damage`, `emp` and `damageType` from the behavior JSON |

### DEM

- The parameters come from the missile's `behaviorJSON`, read by `DEMScript`'s constructor. Range-valued keys
  (`[min, max]`) are **rolled at random per missile** (`DEMScript.getValue`). v1 uses the midpoint, which is fine
  for weapons not yet fired. For DEMs in flight, read the actual rolled fields from the `DEMScript` instance:
  `triggerDistance`, `preferredMin/MaxFireDistance`, `targetingTime`, `minDelayBeforeTriggering` (v1 already reads
  `state`, `fireTarget` and the elapsed timers there).
- The payload damage should come from the live payload weapon, not `derivedStats` (row 9).
- `MissileAPI.getEtaModifier()` exists. `DEMScript` sets it to −`triggerDistance` / max speed, so the AI's arrival
  estimates already account for DEMs firing early.
- **Unverified: which API addition gives DEMs "actual stats"?** I found no DEM-specific stat getter in this
  build's API, and nothing in the engine reads `payloadWeaponId`. If you know the method, tell me and I'll check
  it; otherwise v2 uses the sources above.

## 5. Ship systems and AI hints

`ShipSystemSpecAPI.getAIHints2()` gives `getActiveSpeedIncrease`, `getBurstMovementPotential`,
`getAverageSpeedIncrease`, threat damage, speed, range and so on. Vanilla's threat evaluator `A` uses them for its
closing-speed estimate.

| System | Hints | Actual stats script | v1 profile |
|---|---|---|---|
| Maneuvering jets | +50 speed | +50 max speed, accel/decel +200% (×3), turn up | right |
| Plasma jets | +125 | +125, accel/decel +375% (×4.75), turn up | right |
| Burn drive | +100 (average) | +200 × effect level, accel +200 flat, no turning | n/a in v1 |
| Microburn | burst 400 | — | n/a |
| Phase teleporter | burst 1000 | — | n/a |
| SCY secondary thrusters | +100 | +100, accel/decel **+200% (×3)**, turn **−75%** | **wrong** (×2.5, no turn penalty) |
| SCY armor switch (Orthrus) | +40 | +40, accel/decel **+150% (×2.5)**, turn **−50%** | **wrong** (×2, no turn penalty) |
| SCY experimental teleporter | burst 1000 | — | n/a |

- **Hints are averages.** Burn drive's hint says +100 where the script gives up to +200. They're good for
  committing and pursuing enemies.
- **For P's own system**, v2 reads the actual stat modifiers. While a system is active, v1's "stats without the
  system's modifiers" trick works (the modifier id is the system id + `" effect"`, confirmed).
- **When P's system is idle**, use explicit profiles for systems that matter. That is SCY's two, plus the vanilla
  jets, plasma jets and burn drive. The hints cover the rest.

## 6. SCY

- **Every SCY system already declares `aiHints`**, including the new movement ones. Secondary thrusters say +100,
  armor switch +40, experimental teleporter a 1000 burst.
- **Capital ships:** Orthrus (armor switch), Keto (CIWS drones), Nemean Lion (targeting), Xanthus.
- **The Orthrus armor switch** moves the armor module into "shell mode", changes several weapon slots' arcs,
  and is forced on while venting. So its armor coverage and weapon arcs depend on the mode. For v2's damage model,
  its armor-module position and the weapon arcs must be captured live each batch, never cached per hull.
- **SCY's `ArmorSwitchAI` reads CAT's output** from the ship's custom data:
  - `USE_MOVEMENT_SYSTEM_BACKOFF` and `USE_MOVEMENT_SYSTEM_ADVANCE`;
  - `BEHAVIOR_STATE == "VANILLA_FALLBACK"` as "no enemies".

  It compiles against `CombatAIv2`'s constants. **v2 must keep these keys and values**, or SCY changes in
  the same step.
- **The SCY-only edge that motivated CAT**: the armor switch and thrusters need CAT to decide when they help.
  v2's per-option simulation covers that directly. The option "with system" uses the correct SCY profile, and the
  choice is published through the keys above.

## 7. Where v1's assumptions were fine

- Strafe factors by hull size: frigate 1, destroyer 0.75, cruiser 0.5, capital 0.25 (fighter 0.75, missing in v1).
- `MutableStat` recompute (`getModifiedValueWithout`).
- The system stat modifier id `"<systemId> effect"`.
- `isUIAutopilotOn()` being true while the player flies.
- The armor grid always having at least 2 padding cells per side.
- `DEMScript` field names and its `State` enum.
- The jets and plasma jets numbers.
