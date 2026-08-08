[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$preflightScript = Join-Path $PSScriptRoot "Assert-SelfHostedRunnerPreflight.ps1"
if (-not (Test-Path -LiteralPath $preflightScript -PathType Leaf)) {
    throw "Self-hosted runner preflight script was not found."
}

$tokens = $null
$parseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile(
    $preflightScript,
    [ref]$tokens,
    [ref]$parseErrors
)
if (@($parseErrors).Count -gt 0) {
    throw "Self-hosted runner preflight script is not valid PowerShell."
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-runner-preflight-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

    $successEvidence = Join-Path $tempRoot "success.json"
    & $preflightScript `
        -RepositoryRoot $repositoryRoot `
        -EvidencePath $successEvidence `
        -MinimumFreeDiskGb 0 `
        -MinimumPhysicalMemoryGb 0 `
        -SkipAndroidToolchain `
        -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
        -HttpsProbe { param([uri]$Uri) 403 }

    if (-not (Test-Path -LiteralPath $successEvidence -PathType Leaf)) {
        throw "Runner preflight did not publish success evidence."
    }
    $success = Get-Content -LiteralPath $successEvidence -Raw -Encoding utf8 | ConvertFrom-Json
    if ($success.status -cne "passed") {
        throw "Runner preflight success evidence has an unexpected status."
    }
    if (@($success.endpoints).Count -ne 2) {
        throw "Runner preflight must verify both GitHub results and Azure Blob artifact endpoints."
    }

    $dnsFailureEvidence = Join-Path $tempRoot "dns-failure.json"
    $dnsFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $dnsFailureEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -SkipAndroidToolchain `
            -DnsResolver { param([string]$HostName) throw "fixture DNS failure" } `
            -HttpsProbe { param([uri]$Uri) 200 }
    } catch {
        $dnsFailed = $_.Exception.Message -match "artifact endpoint DNS"
    }
    if (-not $dnsFailed) {
        throw "Runner preflight did not fail closed with a stable DNS diagnostic."
    }
    $dnsFailure = Get-Content -LiteralPath $dnsFailureEvidence -Raw -Encoding utf8 | ConvertFrom-Json
    if ($dnsFailure.status -cne "failed") {
        throw "Runner preflight did not preserve failed DNS evidence."
    }

    $httpsFailureEvidence = Join-Path $tempRoot "https-failure.json"
    $httpsFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $httpsFailureEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -SkipAndroidToolchain `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) throw "fixture HTTPS failure" }
    } catch {
        $httpsFailed = $_.Exception.Message -match "artifact endpoint HTTPS"
    }
    if (-not $httpsFailed) {
        throw "Runner preflight did not fail closed with a stable HTTPS diagnostic."
    }
    $httpsFailure = Get-Content -LiteralPath $httpsFailureEvidence -Raw -Encoding utf8 | ConvertFrom-Json
    if ($httpsFailure.status -cne "failed") {
        throw "Runner preflight did not preserve failed HTTPS evidence."
    }

    $serviceFailureEvidence = Join-Path $tempRoot "service-failure.json"
    $serviceFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $serviceFailureEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -SkipAndroidToolchain `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 503 }
    } catch {
        $serviceFailed = $_.Exception.Message -match "artifact endpoint HTTPS"
    }
    if (-not $serviceFailed) {
        throw "Runner preflight accepted an unavailable artifact service."
    }

    $resourceFailureEvidence = Join-Path $tempRoot "resource-failure.json"
    $resourceFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $resourceFailureEvidence `
            -MinimumFreeDiskGb 2 `
            -MinimumPhysicalMemoryGb 2 `
            -SkipAndroidToolchain `
            -ResourceProbe { [pscustomobject]@{ FreeDiskGb = 1; PhysicalMemoryGb = 1 } } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 }
    } catch {
        $resourceFailed = $_.Exception.Message -match "free disk"
    }
    if (-not $resourceFailed) {
        throw "Runner preflight accepted insufficient runner resources."
    }

    $memoryFailureEvidence = Join-Path $tempRoot "memory-failure.json"
    $memoryFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $memoryFailureEvidence `
            -MinimumFreeDiskGb 2 `
            -MinimumPhysicalMemoryGb 2 `
            -SkipAndroidToolchain `
            -ResourceProbe { [pscustomobject]@{ FreeDiskGb = 10; PhysicalMemoryGb = 1 } } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 }
    } catch {
        $memoryFailed = $_.Exception.Message -match "physical memory"
    }
    if (-not $memoryFailed) {
        throw "Runner preflight accepted insufficient physical memory."
    }

    $adbIsolationScript = Join-Path $PSScriptRoot "Assert-NoConnectedAndroidDevice.ps1"
    if (-not (Test-Path -LiteralPath $adbIsolationScript -PathType Leaf)) {
        throw "ADB isolation assertion was not found."
    }
    & $adbIsolationScript -DeviceLines @("List of devices attached", "", "")
    foreach ($stateLine in @(
        "emulator-5554`tdevice",
        "emulator-5554`toffline",
        "emulator-5554`tunauthorized"
    )) {
        $adbFailed = $false
        try {
            & $adbIsolationScript -DeviceLines @("List of devices attached", $stateLine, "")
        } catch {
            $adbFailed = $_.Exception.Message -match "unexpected Android device"
        }
        if (-not $adbFailed) {
            throw "ADB isolation accepted device state: $stateLine"
        }
    }

    $fakeAdb = Join-Path $tempRoot "adb.exe"
    $fakeEmulator = Join-Path $tempRoot "emulator.exe"
    Set-Content -LiteralPath $fakeAdb -Value "fixture" -Encoding ascii
    Set-Content -LiteralPath $fakeEmulator -Value "fixture" -Encoding ascii
    $adbIntegrationEvidence = Join-Path $tempRoot "adb-integration-failure.json"
    $adbIntegrationFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $adbIntegrationEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -RequireNoConnectedDevice `
            -ResourceProbe { [pscustomobject]@{ FreeDiskGb = 10; PhysicalMemoryGb = 10 } } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 } `
            -AndroidToolchainProbe { param([string]$Root) [pscustomobject]@{ Adb = $fakeAdb; Emulator = $fakeEmulator } } `
            -AdbDeviceProbe { param([string]$AdbPath) [pscustomobject]@{ ExitCode = 0; Output = @("List of devices attached", "emulator-5554`toffline") } }
    } catch {
        $adbIntegrationFailed = $_.Exception.Message -match "unexpected Android device"
    }
    if (-not $adbIntegrationFailed) {
        throw "Runner preflight integration path accepted an offline Android device."
    }

    foreach ($workflowPath in @(
        ".github\workflows\self-hosted-validation.yml",
        ".github\workflows\android-tv-product-device-matrix.yml",
        ".github\workflows\database-migration-device-matrix.yml",
        ".github\workflows\measurement-variance-smoke.yml",
        ".github\workflows\focused-m3u-evidence.yml"
    )) {
        $workflow = Join-Path $repositoryRoot $workflowPath
        $content = Get-Content -LiteralPath $workflow -Raw -Encoding utf8
        if ($content.IndexOf("Assert-SelfHostedRunnerPreflight.ps1", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted workflow does not invoke the runner preflight: $workflowPath"
        }
        if ($content.IndexOf("if-no-files-found: error", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted workflow does not fail when required evidence is absent: $workflowPath"
        }
        if ($content.IndexOf("compression-level: 0", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted workflow does not disable redundant artifact compression: $workflowPath"
        }
        if ($content.IndexOf("persist-credentials: false", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted workflow leaves checkout credentials persisted: $workflowPath"
        }
        if ($content.IndexOf("pull_request_target", [System.StringComparison]::Ordinal) -ge 0) {
            throw "Self-hosted workflow must not execute through pull_request_target: $workflowPath"
        }
    }

    foreach ($workflowPath in @(
        ".github\workflows\self-hosted-validation.yml",
        ".github\workflows\android-tv-product-device-matrix.yml",
        ".github\workflows\database-migration-device-matrix.yml",
        ".github\workflows\measurement-variance-smoke.yml"
    )) {
        $workflow = Join-Path $repositoryRoot $workflowPath
        $content = Get-Content -LiteralPath $workflow -Raw -Encoding utf8
        if ($content.IndexOf("github.event.pull_request.head.repo.full_name == github.repository", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted PR workflow does not reject fork code: $workflowPath"
        }
    }

    $varianceWorkflow = Get-Content -LiteralPath (Join-Path $repositoryRoot ".github\workflows\measurement-variance-smoke.yml") -Raw -Encoding utf8
    if ($varianceWorkflow.IndexOf('group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}', [System.StringComparison]::Ordinal) -lt 0) {
        throw "Measurement variance workflow does not isolate concurrency by PR/ref."
    }
    if ($varianceWorkflow.IndexOf("cancel-in-progress: `${{ github.event_name == 'pull_request' }}", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Measurement variance workflow does not preserve manual/release-like runs."
    }

    Write-Host "Self-hosted runner preflight contract passed."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
