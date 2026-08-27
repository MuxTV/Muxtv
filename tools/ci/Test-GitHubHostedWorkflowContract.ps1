[CmdletBinding()]
param(
    [string]$RepositoryRoot = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
} else {
    $RepositoryRoot = (Resolve-Path $RepositoryRoot).Path
}

$workflowRoot = Join-Path $RepositoryRoot ".github/workflows"

$supportedWorkflows = @(
    "android-tv-focused-device.yml",
    "android-tv-product-device-matrix.yml",
    "app-tv-lint.yml",
    "benchmark-foundation.yml",
    "database-migration-device-matrix.yml",
    "focused-m3u-evidence.yml",
    "integration-gate.yml",
    "measurement-variance-smoke.yml",
    "media3-lint.yml",
    "phase00-red.yml",
    "self-hosted-validation.yml"
)

$forbiddenPatterns = [ordered]@{
    "self-hosted runner label" = '(?im)^\s*runs-on\s*:\s*.*self-hosted.*$'
    "self-hosted preflight helper" = 'Assert-SelfHostedRunnerPreflight\.ps1'
    "persistent runner reset helper" = 'Reset-SelfHostedAndroidState\.ps1'
}

$violations = [System.Collections.Generic.List[string]]::new()

foreach ($workflow in $supportedWorkflows) {
    $path = Join-Path $workflowRoot $workflow
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $violations.Add("${workflow}: supported workflow file is missing")
        continue
    }

    $content = Get-Content -LiteralPath $path -Raw
    foreach ($entry in $forbiddenPatterns.GetEnumerator()) {
        if ($content -match $entry.Value) {
            $violations.Add("${workflow}: $($entry.Key)")
        }
    }
}

if ($violations.Count -gt 0) {
    $message = @(
        "GitHub-hosted workflow contract failed."
        "Public-repository CI must not depend on the removed private self-hosted runner."
        "Violations:"
        ($violations | ForEach-Object { "  - $_" })
    ) -join [Environment]::NewLine
    throw $message
}

Write-Host "GitHub-hosted workflow contract passed for $($supportedWorkflows.Count) supported workflows."
