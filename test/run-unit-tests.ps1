# Unit tests for the pure `sim` package, outside the game.
# Compiles src/.../sim with ONLY the Kotlin stdlib on the classpath (so any Global/Misc/API use in sim fails to
# compile), plus the tests in test/, then runs them. Exit code 0 = all passed.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File test\run-unit-tests.ps1 [-OutDir <dir>]
param(
    [string]$OutDir = "$env:TEMP\cat-unit-tests",
    [string]$JdkBin = 'C:\Program Files\Java\jdk-17\bin',
    [string]$KotlinHome = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate\plugins\Kotlin\kotlinc"
)
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot
$Mods = Split-Path -Parent $Project
$Stdlib = Join-Path $Mods 'LazyLib\jars\internal\Kotlin-Runtime.jar'   # the Kotlin runtime the game uses
$Java = Join-Path $JdkBin 'java.exe'
$KLib = Join-Path $KotlinHome 'lib'
foreach ($f in $Java, $Stdlib, (Join-Path $KLib 'kotlin-compiler.jar')) {
    if (-not (Test-Path -LiteralPath $f)) { throw "Missing prerequisite: $f" }
}

if (Test-Path -LiteralPath $OutDir) { Remove-Item -LiteralPath $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$OutDir\classes" | Out-Null

# pure code: the sim package and the decision rules (decide/rules)
$sources = @((Join-Path $Project 'src\org\starficz\combataitweaks\sim'),
    (Join-Path $Project 'src\org\starficz\combataitweaks\decide\rules'),
    (Join-Path $Project 'test')) | ForEach-Object {
    Get-ChildItem -LiteralPath $_ -Recurse -Filter *.kt -File } | ForEach-Object { $_.FullName }
$argFile = Join-Path $OutDir 'kotlinc.args'
$ktArgs = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-api-version', '2.1', '-language-version', '2.2',
    '-cp', $Stdlib, '-d', "$OutDir\classes") + $sources
($ktArgs | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }) | Set-Content -Encoding ASCII -LiteralPath $argFile
Write-Host "kotlinc sim + tests ($($sources.Count) files)"
& $Java -Xmx1g "-Dkotlin.home=$KotlinHome" -cp (Join-Path $KLib 'kotlin-preloader.jar') org.jetbrains.kotlin.preloading.Preloader `
    -cp (Join-Path $KLib 'kotlin-compiler.jar') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$argFile"
if ($LASTEXITCODE) { throw 'kotlinc failed' }

& $Java -cp "$OutDir\classes;$Stdlib" org.starficz.combataitweaks.sim.SimTestsKt
exit $LASTEXITCODE
