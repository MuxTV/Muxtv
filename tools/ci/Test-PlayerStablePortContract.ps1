[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$featureRoot = Join-Path $repositoryRoot "feature\player"
$buildFile = Join-Path $featureRoot "build.gradle.kts"

if (-not (Test-Path -LiteralPath $buildFile -PathType Leaf)) {
    throw "Player stable-port contract input was not found: feature/player/build.gradle.kts"
}

$build = Get-Content -LiteralPath $buildFile -Raw -Encoding utf8
$requiredDependency = 'implementation(project(":player:api"))'
if ($build.IndexOf($requiredDependency, [System.StringComparison]::Ordinal) -lt 0) {
    throw "feature:player must consume the stable player:api boundary."
}

$forbiddenDependencies = @(
    ':player:media3',
    'libs.media3.ui.compose'
)
foreach ($dependency in $forbiddenDependencies) {
    if ($build.IndexOf($dependency, [System.StringComparison]::Ordinal) -ge 0) {
        throw "feature:player still depends on Media3 implementation surface $dependency."
    }
}

$forbiddenImports = @(
    'androidx.media3.',
    'app.muxtv.player.media3.'
)
$sourceFiles = Get-ChildItem -LiteralPath (Join-Path $featureRoot "src\main") -Recurse -File -Filter "*.kt"
foreach ($sourceFile in $sourceFiles) {
    $content = Get-Content -LiteralPath $sourceFile.FullName -Raw -Encoding utf8
    foreach ($forbiddenImport in $forbiddenImports) {
        if ($content.IndexOf($forbiddenImport, [System.StringComparison]::Ordinal) -ge 0) {
            $relative = [System.IO.Path]::GetRelativePath($repositoryRoot, $sourceFile.FullName)
            throw "Player feature implementation leakage remains in ${relative}: $forbiddenImport"
        }
    }
}

Write-Host "Player feature consumes only the stable player API boundary."
