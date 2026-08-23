[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$manualEvidenceWorkflows = @(
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

# The product matrix is intentionally hybrid: risky harness PRs auto-run it, while
# operators can still dispatch explicit evidence. PR runs should cancel superseded
# heads; every manual run gets its own concurrency identity and cannot collide with
# a cancellable PR group or serialize behind another manual evidence run.
$productMatrixPath = Join-Path $repositoryRoot ".github\workflows\android-tv-product-device-matrix.yml"
if (-not (Test-Path -LiteralPath $productMatrixPath -PathType Leaf)) {
    throw "Product matrix workflow was not found."
}
$productMatrix = Get-Content -LiteralPath $productMatrixPath -Raw -Encoding utf8
foreach ($requiredFragment in @(
    "pull_request:",
    "workflow_dispatch:"
)) {
    if ($productMatrix.IndexOf($requiredFragment, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Hybrid product matrix trigger contract is missing: $requiredFragment"
    }
}

$concurrencyMatch = [regex]::Match(
    $productMatrix,
    '(?ms)^concurrency:\s*\r?\n.*?(?=^jobs:\s*$)'
)
if (-not $concurrencyMatch.Success) {
    throw "Hybrid product matrix concurrency block was not found."
}
$concurrencyBlock = $concurrencyMatch.Value
$expectedGroup = "group: android-tv-product-device-matrix-`${{ github.event_name == 'pull_request' && github.event.pull_request.number || github.run_id }}"
$expectedCancellation = "cancel-in-progress: `${{ github.event_name == 'pull_request' }}"
foreach ($requiredFragment in @($expectedGroup, $expectedCancellation)) {
    if ($concurrencyBlock.IndexOf($requiredFragment, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Hybrid product matrix concurrency block is missing: $requiredFragment"
    }
}
if ($concurrencyBlock.IndexOf("github.ref", [System.StringComparison]::Ordinal) -ge 0) {
    throw "Hybrid product matrix manual concurrency must use github.run_id, not github.ref."
}
if ($concurrencyBlock.IndexOf("queue: max", [System.StringComparison]::Ordinal) -ge 0) {
    throw "Hybrid product matrix must not combine queue: max with cancellable PR concurrency."
}
if ($concurrencyBlock.IndexOf("cancel-in-progress: true", [System.StringComparison]::Ordinal) -ge 0) {
    throw "Hybrid product matrix must not unconditionally cancel manual evidence."
}

Write-Host "Manual and hybrid evidence concurrency contracts passed."

$artifactPublicationContract = Join-Path $PSScriptRoot "Test-EvidenceArtifactPublicationContract.ps1"
if (-not (Test-Path -LiteralPath $artifactPublicationContract -PathType Leaf)) {
    throw "Evidence artifact publication contract test was not found."
}
& $artifactPublicationContract