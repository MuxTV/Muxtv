[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$featureRoot = Join-Path $repositoryRoot "feature\sources"
$buildFile = Join-Path $featureRoot "build.gradle.kts"

if (-not (Test-Path -LiteralPath $buildFile -PathType Leaf)) {
    throw "Sources stable-port contract input was not found: feature/sources/build.gradle.kts"
}

$build = Get-Content -LiteralPath $buildFile -Raw -Encoding utf8
$requiredDependency = 'implementation(project(":catalog:api"))'
if ($build.IndexOf($requiredDependency, [System.StringComparison]::Ordinal) -lt 0) {
    throw "feature:sources must consume the stable catalog:api boundary."
}

$forbiddenDependencies = @(':catalog:refresh', ':catalog:sync', ':core:credentials', ':core:database')
foreach ($dependency in $forbiddenDependencies) {
    if ($build.IndexOf($dependency, [System.StringComparison]::Ordinal) -ge 0) {
        throw "feature:sources still depends on implementation module $dependency."
    }
}

$forbiddenImports = @('app.muxtv.catalog.refresh.', 'app.muxtv.catalog.sync.', 'app.muxtv.credentials.', 'app.muxtv.database.')
$sourceFiles = Get-ChildItem -LiteralPath (Join-Path $featureRoot "src\main") -Recurse -File -Filter "*.kt"
foreach ($sourceFile in $sourceFiles) {
    $content = Get-Content -LiteralPath $sourceFile.FullName -Raw -Encoding utf8
    foreach ($forbiddenImport in $forbiddenImports) {
        if ($content.IndexOf($forbiddenImport, [System.StringComparison]::Ordinal) -ge 0) {
            $relative = [System.IO.Path]::GetRelativePath($repositoryRoot, $sourceFile.FullName)
            throw "Sources feature implementation leakage remains in ${relative}: $forbiddenImport"
        }
    }
}

foreach ($relativeApiPath in @(
    'catalog\api\src\main\kotlin\app\muxtv\catalog\SourceManagement.kt',
    'catalog\api\src\main\kotlin\app\muxtv\catalog\SourceOnboarding.kt'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot $relativeApiPath) -PathType Leaf)) {
        throw "Sources stable catalog port is missing: $relativeApiPath"
    }
}

Write-Host "Sources feature consumes only stable catalog source-management/onboarding ports."
