# CAT v2 tuning: handoff

Written 2026-11-15 on the laptop, for continuing milestone 6 (tuning) on the desktop. Read `docs/v2-plan.md` first
(the whole design; §11b ship systems, §11b2 tuning); this file is the state of the tuning work and how to run it.

## Where things are

- Branch `starui-inject`. Everything up to and including the tuning harness is committed.
- Done before tuning, all committed and passing: ship systems (enemy offensive systems with profiles; P's defensive
  systems decided by CAT with `+DEFENSE` options, used as late as still covers the hits with a 0.35 s buffer). See
  plan §11b.
- Tuning: the harness is built and ran once (a trial with one seed and a 150 s cap). No baseline yet: the laptop
  could only run one game at a time and a 6-seed baseline takes about an hour.

## The harness

- **Group `tunebattle`** (`native-smoke/live/check-mod/src/livecheck/TuneBattle.java`, `TuneBattleDriver.java`).
  One battle holds rounds. Each round spawns two seeded fleets (`FleetFactoryV3`, `params.random` from the seed;
  player and enemy faction picked from the seed), the player's with CAT (`cat`) or without (`vanilla`), and fights
  until one side has no ships left or the cap. It writes one `TUNE seed=... mode=...` line per round, then clears the
  field. No boot and no post-battle dialog between rounds.
- **Settings**, environment variables read by `native-smoke/live/project.sh` (`java_args`):

  | variable | default | what |
  |---|---|---|
  | `CAT_TUNE_SEEDS` | `1,2,3` | comma-separated seeds |
  | `CAT_TUNE_MODES` | `cat,vanilla` | `cat` only for a CAT setting once the vanilla baseline exists |
  | `CAT_TUNE_FP` | `60` | fleet points per side |
  | `CAT_TUNE_SECONDS` | `240` | cap per round (150 left both trial rounds undecided at 60 FP) |
  | `CAT_TUNE_SLICES` | `1` | how many games share the seeds (with `--shards`, below) |
  | `CAT_TUNE_SET` | empty | CAT settings, space separated `-Dcat.tune.<name>=<value>` |
  | `CAT_TRACE` | `true` | set `false` for tuning runs (the trace costs CPU) |

- **Parallel games**: `--shards N` deals groups to games, so each game runs its own group name. `tunebattle` is slice 1,
  `tunebattle2` ... `tunebattle6` slices 2-6; with `CAT_TUNE_SLICES=N` the game running `tunebattleK` plays every Nth
  seed starting at the K-th. Compiled, not run yet.
- **CAT's tunables**: `src/org/starficz/combataitweaks/sim/Tuning.kt`. Each one is read once at startup from
  `-Dcat.tune.<name>`, default the shipped value. The run logs every value (`TUNE settings ...`; compiled, not run
  yet):

  | name | default | what |
  |---|---|---|
  | `horizon` | 10 | H, seconds simulated ahead (also the commit window) |
  | `reactionTime` | 0.7 | τ, how long an option is kept before turning around (`ADVANCE~` and the other escape options) |
  | `hullWeight` | 3 | an option's cost: hull x this + armor |
  | `systemGain`, `systemMinSaving` | 0.8, 50 | a `+SYSTEM` or `+DEFENSE` variant must cost at most gain x plain and save at least this |
  | `defenseBuffer` | 0.35 | the margin before predicted trouble when the defensive system is used |
  | `minVentLevel` | 0.2 | the config defaults (`CombatAIConfig`); a ship's saved config wins |
  | `maxArmorDamageLevelToVent`, `maxHullDamageLevelToVent` | 0.1, 0.05 | vent limits |
  | `stopVentingBelowHullLevel` | 0.5 | |
  | `maxFluxLevelRaiseToHarass` | 0.3 | |
  | `reengageIfFluxDropsBelowLevel` | 0.4 | |
  | `forceAdvanceIfFluxBelowLevel` | 0.7 | |
  | `backoffIfFluxAboveLevel`, `backoffIfHardfluxAboveLevel` | 0.85, 0.75 | |

- **Report**: `python tools/tune_report.py <run folder> [...]`. Vanilla rounds of a seed are its baseline, since they
  don't depend on CAT's settings. CAT rounds are grouped by their run's settings line. It prints CAT - vanilla per
  setting:
  - own loss (FP-weighted hull share lost, a destroyed ship counting fully; lower is better);
  - enemy loss (higher is better);
  - the exchange (enemy loss - own loss).

  It also shows the spread of repeats.

## Running it

From Git Bash, in the mods folder:

```
# vanilla baseline and CAT at the defaults, 12 seeds over 3 games
CAT_TRACE=false CAT_TUNE_SEEDS=1,2,3,4,5,6,7,8,9,10,11,12 CAT_TUNE_SLICES=3 \
  bash ~/.claude/skills/starsector-native-smoke/scripts/smoke.sh checks "Combat AI Tweaks" \
  tunebattle,tunebattle2,tunebattle3 --shards 3 --limit 5400

# one CAT setting against the existing baseline (no vanilla rounds)
CAT_TRACE=false CAT_TUNE_MODES=cat CAT_TUNE_SEEDS=1,...,12 CAT_TUNE_SLICES=3 CAT_TUNE_SET="-Dcat.tune.horizon=8" \
  bash .../smoke.sh checks "Combat AI Tweaks" tunebattle,tunebattle2,tunebattle3 --shards 3 --limit 5400

python "Combat AI Tweaks/tools/tune_report.py" <baseline run folders> <setting run folders>
```

- Each round runs up to 240 s of game time plus 2 s of clearing. A game boot is about 2-3 minutes.
- Run folders: `%LOCALAPPDATA%/starsector-smoke/work/combataitweaks/slotN/runs/<time>-tunebattle/results.txt`.
- `--limit` is the run's time limit in seconds.
- A game needs about 5 GB of free memory; the runner waits otherwise.
- Stop runs with `smoke.sh stop combataitweaks`, never by killing java.

## Setting up the desktop

- **The `starsector-native-smoke` skill**: `smoke.sh setup` once per machine. The base save is made on the first run.
- **`native-smoke/live/build-jar.ps1` hardcodes paths**: the game at `C:\Program Files (x86)\Fractal Softworks\Starsector`,
  JDK 17 at `C:\Program Files\Java\jdk-17`, and IntelliJ Ultimate's bundled kotlinc. Edit the params if the desktop
  differs.
- **StarUIFramework**: CAT compiles against `mods/StarUIFramework/jars/StarUIFramework.jar`. On the laptop that
  committed jar lagged the StarUI source, so runs passed `CAT_STARUI_JAR=<a jar built from StarUI source>`. On the
  desktop, build StarUIFramework from its repo first, and set `CAT_STARUI_JAR` if the committed jar is older.
- **Mods needed** in `mods/`: LazyLib, PatchLib, StarUIFramework. The runner adds dependencies from `mod_info.json`.

## Plan for the tuning

1. **Baseline**: 12+ seeds, `cat,vanilla`, defaults.
2. **Noise**: the same seeds again in a second run, for the spread of repeats. Battles aren't deterministic even with
   the same fleets (AI timing). A setting's difference counts only when it is well outside that spread.
3. **One knob at a time**, CAT rounds only, against the baseline. Suggested order:
   - `horizon` 6 / 8 / 12;
   - `backoffIfFluxAboveLevel` and `backoffIfHardfluxAboveLevel`;
   - `reactionTime` 0.5 / 1.0;
   - `hullWeight`;
   - the vent limits.
4. **Survival first**: the main measure is own loss. A setting that raises the exchange but also raises own loss is
   a no unless the user decides otherwise (player ships would rather run than trade).
5. **Write the results into plan §11b2** as a table per knob, then set the defaults in `Tuning.kt`.

Before reading the results, check:
- that the round lines show the same `fleets=` for a seed across runs (the report notes it when they differ);
- that the `TUNE settings` line is in each run's log;
- how many rounds end `winner=none` at the cap (if most do, raise `CAT_TUNE_SECONDS` or lower `CAT_TUNE_FP`).

## Known open items (not tuning)

- **`ventcases` pursuit case**: the check "a burn on the last charge is captured" fails when the enemy Shrike never
  burns on its last charge (nothing to judge). That's vanilla's choice. It could pass as "nothing to judge" like the
  vent check next to it.
- **Fortress shield**: CAT's control of it hasn't been seen in game (no check ships one yet).
- **Enemy defensive systems in P's trade**: only an enemy damper that is already on counts.
- **The §11a pessimism lever** (expected PD interception on missiles): the first thing to try if tuning shows CAT too
  cautious against missile-heavy fleets.

## Working conventions (from the laptop's memory files, which don't sync)

- **Code**: Kotlin; package `org.starficz.combataitweaks`; LazyLib at runtime; descriptive names. Never put an
  obfuscated game name in mod code; label translated decompiled code as a translation.
- **Tests**:
  - unit tests: `powershell -File test\run-unit-tests.ps1`, from `Combat AI Tweaks`. They compile `sim`,
    `decide/rules` and `DamageOutlook` with only the Kotlin stdlib.
  - game checks: `smoke.sh checks "Combat AI Tweaks" <group>`, with groups `ventcases`, `movecases`, `aibattle`,
    `worstcase`. Run them one group per command; `aibattle` stays small on a laptop.
- **CAT decisions**: survival first. Player ships run (toward friends) rather than trade. More state flips and some
  extra CPU are fine.
- **Measuring performance**: measure allocation, not G1 pauses (the user's game runs Shenandoah with 2 GB). Use
  `CAT_TRACE=false aibattle` with the JFR split. Perf checks are noisy while other sessions or games run, so don't
  chase a perf warning then.
- **Shell**: never `python - <<EOF` heredocs (they hang); write a script file and run it.
- **Commits**: only when the user asks. Don't commit another session's work in progress (on the laptop that was
  `src/.../ui/ConfigDialog.kt`, `jars/` and `tools/__pycache__/`).
