# Settings of this mod project for the starsector-native-smoke skill (smoke.sh checks, run-live-checks.sh --project).
# The runner sources this file in bash: plain assignments and the two functions, nothing else.

# slot owner, shown in smoke.sh status: letters, digits, '.', '_' and '-'
OWNER=combataitweaks

# ids (mod_info.json "id") of mods to enable besides this one. Dependencies named in mod_info.json files are added
# by the runner, so list only extra mods the checks want, such as lw_console
DEPS=()

# the mod's own Java package, so the profile report covers and warns about its code
PROFILE_PKG=org.starficz.combataitweaks

# the check mod: a copy of the skill's templates/live-check-mod with this project's groups
CHECK_DIR="$PROJECT/native-smoke/live/check-mod"

# How the mod under test gets built and staged. Without stage_mod the runner links the project's folders and copies
# its built jars (it stops with STALE_JAR when a jar is older than the sources).
# With a compile gate, build with it and stage its jar, so the game runs what the gate checked. The function runs in
# the runner's shell: PROJECT is this project, STAGE the folder to fill, LIVE_SCRIPTS the skill's scripts/live.
# CAT is Kotlin: build it with IntelliJ's bundled kotlinc (build-jar.ps1) into the work folder, never into jars/.
# CAT_STARUI_JAR: compile against a StarUIFramework jar built from source when the committed one lags behind it.
stage_mod() {
    local out="$STAGE.build/CombatAITweaks.jar"
    local args=(-OutJar "$(cygpath -w "$out")")
    [ -n "${CAT_STARUI_JAR:-}" ] && args+=(-StarUIJar "$(cygpath -w "$CAT_STARUI_JAR")")
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$PROJECT/native-smoke/live/build-jar.ps1")" "${args[@]}" || return 1
    bash "$LIVE_SCRIPTS/stage-mod.sh" "$PROJECT" "$STAGE" "$out"
}

# extra JVM arguments for the game, one per line. LAUNCH is base while the base save is made, checks for the run
# itself; GROUP holds the groups (an older check mod that reads its own property: -Dmymod.check=$GROUP on checks only)
# java_args() {
#     printf '%s\n' "-Dmymod.switch=${SWITCHES:-on}"
# }

# aibattle size per side: small by default (laptop); CAT_BATTLE_FLEETS=2 CAT_BATTLE_FP=250 for a big stress battle
# CAT_TRACE=false turns off the battle trace (saves/common/cat_trace/ in the work folder; tools/trace_report.py)
java_args() {
    printf '%s\n' "-Dcat.battle.fleets=${CAT_BATTLE_FLEETS:-1}" "-Dcat.battle.fp=${CAT_BATTLE_FP:-60}" \
        "-Dcat.trace=${CAT_TRACE:-true}"
}
