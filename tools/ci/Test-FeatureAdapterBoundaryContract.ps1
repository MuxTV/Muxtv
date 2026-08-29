[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$checker = Join-Path $PSScriptRoot "Assert-FeatureAdapterBoundaries.ps1"
if (-not (Test-Path -LiteralPath $checker -PathType Leaf)) {
    throw "Feature adapter boundary checker is missing: $checker"
}

$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) "muxtv-feature-boundary-$([guid]::NewGuid().ToString('N'))"
$featureBuild = Join-Path $fixtureRoot "feature\test\build.gradle.kts"
$appBuild = Join-Path $fixtureRoot "app\tv\build.gradle.kts"

function Write-TextFile {
    param(
        [string]$Path,
        [string]$Content
    )

    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    Set-Content -LiteralPath $Path -Value $Content -Encoding utf8
}

function Invoke-ExpectedPass {
    param([string]$Description)

    try {
        & $checker -RepositoryRoot $fixtureRoot
    }
    catch {
        throw "$Description should pass, but failed: $($_.Exception.Message)"
    }
}

function Invoke-ExpectedFailure {
    param(
        [string]$Target,
        [string]$Description
    )

    $expected = "Forbidden dependency: feature:test -> $Target"
    try {
        & $checker -RepositoryRoot $fixtureRoot
    }
    catch {
        $message = $_.Exception.Message
        if (-not $message.Contains($expected, [System.StringComparison]::Ordinal)) {
            throw "$Description failed with the wrong diagnostic. Expected '$expected', got '$message'."
        }
        return
    }

    throw "$Description should fail with '$expected', but the checker passed."
}

try {
    Write-TextFile -Path $featureBuild -Content @'
plugins {
    id("muxtv.android.library")
}

dependencies {
    implementation(project(":catalog:api"))
    implementation(project(":player:api"))
}
'@
    Write-TextFile -Path $appBuild -Content @'
dependencies {
    implementation(project(":player:media3"))
    implementation(project(":core:database"))
}
'@
    Invoke-ExpectedPass -Description "stable feature dependencies plus app composition-root adapter dependencies"

    foreach ($target in @(
        "core:database",
        "core:credentials",
        "catalog:sync",
        "player:media3"
    )) {
        Write-TextFile -Path $featureBuild -Content @"
dependencies {
    implementation(project(\":$target\"))
}
"@
        Invoke-ExpectedFailure -Target $target -Description "feature:test -> $target"
    }

    Write-Host "Feature adapter boundary contract synthetic RED/GREEN fixtures passed."
}
finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}
