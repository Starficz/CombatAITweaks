# Builds Combat AI Tweaks with IntelliJ's bundled kotlinc into $OutJar (does not touch the mod's jars/ folder).
param(
    [Parameter(Mandatory)][string]$OutJar,
    [string]$StarUIJar = 'C:\Program Files (x86)\Fractal Softworks\Starsector\mods\StarUIFramework\jars\StarUIFramework.jar',
    [string]$JdkBin = 'C:\Program Files\Java\jdk-17\bin',
    [string]$KotlinHome = "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate\plugins\Kotlin\kotlinc"
)
$ErrorActionPreference = 'Stop'
$Mods = 'C:\Program Files (x86)\Fractal Softworks\Starsector\mods'
$Project = Join-Path $Mods 'Combat AI Tweaks'
$Core = 'C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core'
$Java = Join-Path $JdkBin 'java.exe'
$JarExe = Join-Path $JdkBin 'jar.exe'
$KLib = Join-Path $KotlinHome 'lib'

$GameJars = @('starfarer.api.jar', 'starfarer_obf.jar', 'fs.common_obf.jar', 'fs.sound_obf.jar', 'lwjgl.jar', 'lwjgl_util.jar',
    'log4j-1.2.9.jar', 'json.jar', 'xstream-1.4.10.jar') | ForEach-Object { Join-Path $Core $_ }
$LibJars = @("$Mods\LazyLib\jars\LazyLib.jar", "$Mods\LazyLib\jars\LazyLib-Kotlin.jar",
    "$Mods\LazyLib\jars\internal\Kotlin-Runtime.jar", $StarUIJar, "$Mods\PatchLib\jars\PatchLibAPI.jar")
foreach ($f in $GameJars + $LibJars) { if (-not (Test-Path -LiteralPath $f)) { throw "Missing jar: $f" } }

$OutJar = [IO.Path]::GetFullPath($OutJar)
$Work = Join-Path (Split-Path -Parent $OutJar) 'cat-build'
if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$Work\classes" | Out-Null

$sources = Get-ChildItem -LiteralPath (Join-Path $Project 'src') -Recurse -Filter *.kt -File | ForEach-Object { $_.FullName }
$argFile = Join-Path $Work 'kotlinc.args'
$ktArgs = @('-no-stdlib', '-no-reflect', '-jvm-target', '17', '-api-version', '2.1', '-language-version', '2.2',
    '-module-name', 'Combat AI Tweaks', '-cp', (($GameJars + $LibJars) -join ';'), '-d', "$Work\classes") + $sources
($ktArgs | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }) | Set-Content -Encoding ASCII -LiteralPath $argFile
Write-Host "kotlinc Combat AI Tweaks ($($sources.Count) files)"
& $Java -Xmx2g "-Dkotlin.home=$KotlinHome" -cp (Join-Path $KLib 'kotlin-preloader.jar') org.jetbrains.kotlin.preloading.Preloader `
    -cp (Join-Path $KLib 'kotlin-compiler.jar') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$argFile"
if ($LASTEXITCODE) { throw 'kotlinc failed' }
if (Test-Path -LiteralPath $OutJar) { Remove-Item -LiteralPath $OutJar -Force }
& $JarExe --create --file $OutJar -C "$Work\classes" .
if ($LASTEXITCODE) { throw 'jar failed' }
Write-Host "built $OutJar"
