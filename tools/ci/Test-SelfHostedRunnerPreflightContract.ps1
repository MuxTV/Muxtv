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

$actionlintConfig = Join-Path $repositoryRoot ".github\actionlint.yaml"
if (-not (Test-Path -LiteralPath $actionlintConfig -PathType Leaf)) {
    throw "Repository actionlint configuration was not found."
}
$actionlintContent = Get-Content -LiteralPath $actionlintConfig -Raw -Encoding utf8
foreach ($runnerLabel in @("muxtv-android", "muxtv-device")) {
    if ($actionlintContent.IndexOf("- $runnerLabel", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Repository actionlint configuration does not declare custom runner label: $runnerLabel"
    }
}

function Get-WorkflowContent {
    param([Parameter(Mandatory)][string]$RelativePath)

    $workflow = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $workflow -PathType Leaf)) {
        throw "Expected self-hosted workflow was not found: $RelativePath"
    }
    return Get-Content -LiteralPath $workflow -Raw -Encoding utf8
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
        -ExpectedRunnerLabels @("muxtv-android") `
        -RunnerListenerProbe { 1 } `
        -RunnerMetadataProbe { [pscustomobject]@{ Name = "fixture"; Os = "Windows"; Architecture = "X64"; Version = "2.999.0" } } `
        -TempPathProbe { param([string]$Path) $true } `
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
    if ($success.runner_version -cne "2.999.0" -or $success.runner_temp_writable -ne $true) {
        throw "Runner preflight did not preserve runner version and writable temp evidence."
    }
    if ($success.runner_listener_count -ne 1) {
        throw "Runner preflight did not preserve the singleton listener evidence."
    }
    if ([string]::Join(",", @($success.expected_runner_labels)) -cne "muxtv-android") {
        throw "Runner preflight did not preserve the scheduling label contract."
    }

    $duplicateListenerEvidence = Join-Path $tempRoot "duplicate-listener-failure.json"
    $duplicateListenerFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $duplicateListenerEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -SkipAndroidToolchain `
            -RunnerListenerProbe { 2 } `
            -RunnerMetadataProbe { [pscustomobject]@{ Name = "fixture"; Os = "Windows"; Architecture = "X64"; Version = "2.999.0" } } `
            -TempPathProbe { param([string]$Path) $true } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 }
    } catch {
        $duplicateListenerFailed = $_.Exception.Message -match "exactly one Runner.Listener"
    }
    if (-not $duplicateListenerFailed) {
        throw "Runner preflight accepted duplicate Runner.Listener processes."
    }
    $duplicateListener = Get-Content -LiteralPath $duplicateListenerEvidence -Raw -Encoding utf8 | ConvertFrom-Json
    if ($duplicateListener.status -cne "failed" -or $duplicateListener.runner_listener_count -ne 2) {
        throw "Runner preflight did not preserve duplicate listener failure evidence."
    }

    $runnerFailureEvidence = Join-Path $tempRoot "runner-failure.json"
    $runnerFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $runnerFailureEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -SkipAndroidToolchain `
            -RunnerMetadataProbe { [pscustomobject]@{ Name = "fixture"; Os = "Windows"; Architecture = "X64"; Version = "" } } `
            -TempPathProbe { param([string]$Path) $true } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 }
    } catch {
        $runnerFailed = $_.Exception.Message -match "runner version"
    }
    if (-not $runnerFailed) {
        throw "Runner preflight accepted missing runner version evidence."
    }

    $tempFailureEvidence = Join-Path $tempRoot "temp-failure.json"
    $tempFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $tempFailureEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -SkipAndroidToolchain `
            -RunnerMetadataProbe { [pscustomobject]@{ Name = "fixture"; Os = "Windows"; Architecture = "X64"; Version = "2.999.0" } } `
            -TempPathProbe { param([string]$Path) $false } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 }
    } catch {
        $tempFailed = $_.Exception.Message -match "temporary path"
    }
    if (-not $tempFailed) {
        throw "Runner preflight accepted an unwritable temporary path."
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
    $fakeSdkRoot = Join-Path $tempRoot "android-sdk"
    $fakeSystemImage = Join-Path $fakeSdkRoot "system-images\android-36\android-tv\x86_64"
    New-Item -ItemType Directory -Force -Path $fakeSystemImage | Out-Null
    Set-Content -LiteralPath (Join-Path $fakeSystemImage "source.properties") -Value "Pkg.Revision=1" -Encoding ascii
    Set-Content -LiteralPath $fakeAdb -Value "fixture" -Encoding ascii
    Set-Content -LiteralPath $fakeEmulator -Value "fixture" -Encoding ascii

    $adbPreflightOperations = [System.Collections.Generic.List[string]]::new()
    $adbIntegrationEvidence = Join-Path $tempRoot "adb-integration-failure.json"
    $adbIntegrationFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $adbIntegrationEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -RequireNoConnectedDevice `
            -ExpectedSystemImageApis 36 `
            -ResourceProbe { [pscustomobject]@{ FreeDiskGb = 10; PhysicalMemoryGb = 10 } } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 } `
            -AndroidToolchainProbe { param([string]$Root) [pscustomobject]@{ Root = $fakeSdkRoot; Adb = $fakeAdb; Emulator = $fakeEmulator } } `
            -AdbDisconnectProbe { param([string]$AdbPath) $adbPreflightOperations.Add("disconnect"); [pscustomobject]@{ ExitCode = 0; Output = @() } } `
            -AdbDeviceProbe { param([string]$AdbPath) $adbPreflightOperations.Add("devices"); [pscustomobject]@{ ExitCode = 0; Output = @("List of devices attached", "emulator-5554`toffline") } }
    } catch {
        $adbIntegrationFailed = $_.Exception.Message -match "unexpected Android device"
    }
    if (-not $adbIntegrationFailed) {
        throw "Runner preflight integration path accepted an offline Android device."
    }
    if ([string]::Join(",", $adbPreflightOperations) -cne "disconnect,devices") {
        throw "Runner preflight did not clear stale network transports before enforcing ADB isolation."
    }

    $systemImageFailureEvidence = Join-Path $tempRoot "system-image-failure.json"
    $systemImageFailed = $false
    try {
        & $preflightScript `
            -RepositoryRoot $repositoryRoot `
            -EvidencePath $systemImageFailureEvidence `
            -MinimumFreeDiskGb 0 `
            -MinimumPhysicalMemoryGb 0 `
            -ExpectedSystemImageApis 26 `
            -ResourceProbe { [pscustomobject]@{ FreeDiskGb = 10; PhysicalMemoryGb = 10 } } `
            -DnsResolver { param([string]$HostName) @("203.0.113.10") } `
            -HttpsProbe { param([uri]$Uri) 200 } `
            -AndroidToolchainProbe { param([string]$Root) [pscustomobject]@{ Root = $fakeSdkRoot; Adb = $fakeAdb; Emulator = $fakeEmulator } } `
            -AdbDeviceProbe { param([string]$AdbPath) [pscustomobject]@{ ExitCode = 0; Output = @("List of devices attached") } }
    } catch {
        $systemImageFailed = $_.Exception.Message -match "expected emulator system image"
    }
    if (-not $systemImageFailed) {
        throw "Runner preflight accepted a missing emulator system image."
    }

    $allSelfHostedWorkflows = @(
        ".github\workflows\self-hosted-validation.yml",
        ".github\workflows\android-tv-focused-device.yml",
        ".github\workflows\android-tv-product-device-matrix.yml",
        ".github\workflows\database-migration-device-matrix.yml",
        ".github\workflows\measurement-variance-smoke.yml",
        ".github\workflows\benchmark-foundation.yml",
        ".github\workflows\integration-gate.yml",
        ".github\workflows\focused-m3u-evidence.yml"
    )
    foreach ($workflowPath in $allSelfHostedWorkflows) {
        $content = Get-WorkflowContent $workflowPath
        foreach ($requiredFragment in @(
            "Assert-SelfHostedRunnerPreflight.ps1",
            "persist-credentials: false",
            "muxtv-android",
            "ExpectedRunnerLabels",
            "actions: read",
            "uses: ./.github/actions/upload-evidence-with-retry",
            "Reset-SelfHostedAndroidState.ps1"
        )) {
            if ($content.IndexOf($requiredFragment, [System.StringComparison]::Ordinal) -lt 0) {
                throw "Self-hosted workflow is missing required safety/evidence contract '$requiredFragment': $workflowPath"
            }
        }
        if ($content.IndexOf("pull_request_target", [System.StringComparison]::Ordinal) -ge 0) {
            throw "Self-hosted workflow must not execute through pull_request_target: $workflowPath"
        }
        foreach ($unsafeBranchInterpolation in @(
            '-SourceBranch "${{ github.head_ref',
            '$branch = "${{ github.head_ref'
        )) {
            if ($content.IndexOf($unsafeBranchInterpolation, [System.StringComparison]::Ordinal) -ge 0) {
                throw "Self-hosted workflow interpolates an untrusted branch name into PowerShell: $workflowPath"
            }
        }
        $uploadIndex = $content.IndexOf("uses: ./.github/actions/upload-evidence-with-retry", [System.StringComparison]::Ordinal)
        $cleanupIndex = $content.LastIndexOf("Reset-SelfHostedAndroidState.ps1", [System.StringComparison]::Ordinal)
        if ($uploadIndex -lt 0 -or $cleanupIndex -lt 0 -or $cleanupIndex -lt $uploadIndex) {
            throw "Self-hosted workflow does not run Android cleanup after shared artifact publication: $workflowPath"
        }
    }

    $prSelfHostedWorkflows = @(
        ".github\workflows\self-hosted-validation.yml",
        ".github\workflows\android-tv-focused-device.yml",
        ".github\workflows\android-tv-product-device-matrix.yml",
        ".github\workflows\database-migration-device-matrix.yml",
        ".github\workflows\measurement-variance-smoke.yml"
    )
    foreach ($workflowPath in $prSelfHostedWorkflows) {
        $content = Get-WorkflowContent $workflowPath
        if ($content.IndexOf("pull_request:", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Expected PR-triggered self-hosted workflow has no pull_request trigger: $workflowPath"
        }
        if ($content.IndexOf("github.event.pull_request.head.repo.full_name == github.repository", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted PR workflow does not reject fork code: $workflowPath"
        }
    }

    $manualOnlyWorkflows = @(
        ".github\workflows\benchmark-foundation.yml",
        ".github\workflows\integration-gate.yml",
        ".github\workflows\focused-m3u-evidence.yml"
    )
    foreach ($workflowPath in $manualOnlyWorkflows) {
        $content = Get-WorkflowContent $workflowPath
        if ($content.IndexOf("workflow_dispatch:", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Manual-only workflow has no workflow_dispatch trigger: $workflowPath"
        }
        if ($content.IndexOf("pull_request:", [System.StringComparison]::Ordinal) -ge 0) {
            throw "Manual-only workflow unexpectedly auto-runs on pull requests: $workflowPath"
        }
        if ($content.IndexOf('ref: ${{ github.sha }}', [System.StringComparison]::Ordinal) -lt 0) {
            throw "Manual-only workflow does not check out the selected exact commit: $workflowPath"
        }
        if ($content.IndexOf("cancel-in-progress: false", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Manual-only workflow may cancel an already-selected evidence run: $workflowPath"
        }
    }

    $productMatrixWorkflow = Get-WorkflowContent ".github\workflows\android-tv-product-device-matrix.yml"
    if ($productMatrixWorkflow.IndexOf("workflow_dispatch:", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Risk-routed product matrix must preserve manual workflow_dispatch support."
    }
    if ($productMatrixWorkflow.IndexOf('ref: ${{ github.event_name == ''pull_request'' && github.event.pull_request.head.sha || github.sha }}', [System.StringComparison]::Ordinal) -lt 0) {
        throw "Risk-routed product matrix must check out the exact PR head when pull-request triggered."
    }
    if ($productMatrixWorkflow.IndexOf("cancel-in-progress: `${{ github.event_name == 'pull_request' }}", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Risk-routed product matrix must cancel superseded PR evidence without cancelling manual evidence."
    }

    $varianceWorkflow = Get-WorkflowContent ".github\workflows\measurement-variance-smoke.yml"
    if ($varianceWorkflow.IndexOf('group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}', [System.StringComparison]::Ordinal) -lt 0) {
        throw "Measurement variance workflow does not isolate concurrency by PR/ref."
    }
    if ($varianceWorkflow.IndexOf("cancel-in-progress: `${{ github.event_name == 'pull_request' }}", [System.StringComparison]::Ordinal) -lt 0) {
        throw "Measurement variance workflow does not preserve manual/release-like runs."
    }

    $deviceWorkflows = @(
        ".github\workflows\android-tv-focused-device.yml",
        ".github\workflows\android-tv-product-device-matrix.yml",
        ".github\workflows\database-migration-device-matrix.yml",
        ".github\workflows\measurement-variance-smoke.yml",
        ".github\workflows\benchmark-foundation.yml",
        ".github\workflows\integration-gate.yml"
    )
    foreach ($workflowPath in $deviceWorkflows) {
        $content = Get-WorkflowContent $workflowPath
        if ($content.IndexOf("runs-on: [self-hosted, Windows, X64, muxtv-android, muxtv-device]", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Device workflow does not require the dedicated device runner label: $workflowPath"
        }
        if ($content.IndexOf("group: muxtv-device-global", [System.StringComparison]::Ordinal) -ge 0) {
            throw "Device workflow uses a shared native concurrency group that cancels an already-pending workflow instead of queueing it: $workflowPath"
        }
        if ($content.IndexOf("ExpectedSystemImageApis", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Device workflow does not preflight its expected emulator system image: $workflowPath"
        }
    }

    $selfHostedWorkflow = Get-WorkflowContent ".github\workflows\self-hosted-validation.yml"
    foreach ($requiredFragment in @(
        '''["self-hosted","Windows","X64","muxtv-android"]''',
        '''["self-hosted","Windows","X64","muxtv-android","muxtv-device"]''',
        'runs-on: ${{ fromJSON('
    )) {
        if ($selfHostedWorkflow.IndexOf($requiredFragment, [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted validation does not route host and device modes to dedicated labels: $requiredFragment"
        }
    }
    if ($selfHostedWorkflow.IndexOf("muxtv-device-global", [System.StringComparison]::Ordinal) -ge 0) {
        throw "Self-hosted validation uses the cancelling shared device concurrency group instead of the singleton device runner label."
    }
    if ($selfHostedWorkflow.IndexOf("matrix.lane", [System.StringComparison]::Ordinal) -ge 0) {
        throw "Self-hosted validation must not use matrix context in a job condition."
    }

    Write-Host "Self-hosted runner preflight contract passed."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}