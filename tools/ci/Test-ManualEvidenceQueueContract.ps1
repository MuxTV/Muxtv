[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$manualEvidenceWorkflows = @(
    ".github\workflows\android-tv-product-device-matrix.yml",
    ".github\workflows\benchmark-foundation.yml",
    ".github\workflows\integration-gate.yml",
    ".github\workflows\focused-m3u-evidence.yml"
)

foreach ($relativePath in $manualEvidenceWorkflows) {
    $path = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Manual evidence workflow was not found: $relativePath"
    }

    $content = Get-Content -LiteralPath $path -Raw -Encoding utf8
    if ($content.IndexOf("workflow_dispatch:", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Manual evidence workflow has no workflow_dispatch trigger: $relativePath"
    }
    if ($content.IndexOf("pull_request:", [System.StringComparison]::Ordinal) -ge 0) {
        throw "Manual evidence workflow must not auto-run on pull requests: $relativePath"
    }
    if ($content.IndexOf("queue: max", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Manual evidence workflow may replace an existing pending run; queue: max is required: $relativePath"
    }
    if ($content.IndexOf("cancel-in-progress: false", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Manual evidence workflow may cancel selected evidence work; cancel-in-progress: false is required: $relativePath"
    }
    if ($content.IndexOf("cancel-in-progress: true", [System.StringComparison]::Ordinal) -ge 0) {
        throw "Manual evidence workflow combines preserved queueing with cancel-in-progress: true: $relativePath"
    }
}

Write-Host "Manual evidence concurrency queue contract passed."
