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
$jdkActionPath = Join-Path $RepositoryRoot ".github/actions/setup-muxtv-jdks/action.yml"

$supportedWorkflows = @(
    "android-tv-focused-device.yml",
    "android-tv-product-device-matrix.yml",
    "app-tv-lint.yml",
    "benchmark-foundation.yml",
    "database-migration-device-matrix.yml",
    "focused-m3u-evidence.yml",
    "integration-gate.yml",
    "m0-catalog-measurement-correctness.yml",
    "measurement-variance-smoke.yml",
    "media3-lint.yml",
    "phase00-red.yml",
    "self-hosted-validation.yml"
)

$dualJdkWorkflows = @(
    "android-tv-focused-device.yml",
    "android-tv-product-device-matrix.yml",
    "app-tv-lint.yml",
    "benchmark-foundation.yml",
    "database-migration-device-matrix.yml",
    "focused-m3u-evidence.yml",
    "integration-gate.yml",
    "m0-catalog-measurement-correctness.yml",
    "measurement-variance-smoke.yml",
    "media3-lint.yml",
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

    if ($workflow -in $dualJdkWorkflows) {
        if ($content.IndexOf('uses: ./.github/actions/setup-muxtv-jdks', [System.StringComparison]::Ordinal) -lt 0) {
            $violations.Add("${workflow}: missing repository dual-JDK setup action")
        }
        if ($content -match '(?m)^\s*uses:\s*actions/setup-java@') {
            $violations.Add("${workflow}: bypasses repository dual-JDK setup with direct setup-java")
        }
    }
}

if (-not (Test-Path -LiteralPath $jdkActionPath -PathType Leaf)) {
    $violations.Add("setup-muxtv-jdks: composite action is missing")
} else {
    $jdkAction = Get-Content -LiteralPath $jdkActionPath -Raw
    foreach ($requiredToken in @(
        'actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961',
        "java-version: '17'",
        'set-default: false',
        "java-version: '25'",
        'org.gradle.java.installations.fromEnv=JAVA_HOME_17_X64,JAVA_HOME_25_X64'
    )) {
        if ($jdkAction.IndexOf($requiredToken, [System.StringComparison]::Ordinal) -lt 0) {
            $violations.Add("setup-muxtv-jdks: missing toolchain contract token: $requiredToken")
        }
    }
}

if ($violations.Count -gt 0) {
    $message = @(
        "GitHub-hosted workflow contract failed."
        "Public-repository CI must not depend on the removed private self-hosted runner and must expose all required JDK toolchains."
        "Violations:"
        ($violations | ForEach-Object { "  - $_" })
    ) -join [Environment]::NewLine
    throw $message
}

Write-Host "GitHub-hosted workflow contract passed for $($supportedWorkflows.Count) supported workflows with dual-JDK discovery where required."
