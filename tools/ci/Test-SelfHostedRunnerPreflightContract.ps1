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
    if ([string]::Join(",", @($success.expected_runner_labels)) -cne "muxtv-android") {
        throw "Runner preflight did not preserve the scheduling label contract."
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
            -AdbDeviceProbe { param([string]$AdbPath) [pscustomobject]@{ ExitCode = 0; Output = @("List of devices attached", "emulator-5554`toffline") } }
    } catch {
        $adbIntegrationFailed = $_.Exception.Message -match "unexpected Android device"
    }
    if (-not $adbIntegrationFailed) {
        throw "Runner preflight integration path accepted an offline Android device."
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
        foreach ($unsafeBranchInterpolation in @(
            '-SourceBranch "${{ github.head_ref',
            '$branch = "${{ github.head_ref'
        )) {
            if ($content.IndexOf($unsafeBranchInterpolation, [System.StringComparison]::Ordinal) -ge 0) {
                throw "Self-hosted workflow interpolates an untrusted branch name into PowerShell: $workflowPath"
            }
        }
        if ($content.IndexOf("muxtv-android", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted workflow does not target the repository Android runner label: $workflowPath"
        }
        if ($content.IndexOf("ExpectedRunnerLabels", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted workflow does not record its scheduling label contract: $workflowPath"
        }

        $uploadIndex = $content.IndexOf("actions/upload-artifact@", [System.StringComparison]::Ordinal)
        $cleanupIndex = $content.IndexOf("Reset-SelfHostedAndroidState.ps1", [System.StringComparison]::Ordinal)
        if ($cleanupIndex -lt 0 -or $cleanupIndex -lt $uploadIndex) {
            throw "Self-hosted workflow does not run Android cleanup after artifact publication: $workflowPath"
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

    foreach ($workflowPath in @(
        ".github\workflows\android-tv-product-device-matrix.yml",
        ".github\workflows\database-migration-device-matrix.yml",
        ".github\workflows\measurement-variance-smoke.yml"
    )) {
        $workflow = Join-Path $repositoryRoot $workflowPath
        $content = Get-Content -LiteralPath $workflow -Raw -Encoding utf8
        if ($content.IndexOf("runs-on: [self-hosted, Windows, X64, muxtv-android, muxtv-device]", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Device workflow does not require the dedicated device runner label: $workflowPath"
        }
        if ($content.IndexOf("group: muxtv-device-global", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Device workflow is not serialized through the repository-wide device concurrency group: $workflowPath"
        }
        if ($content.IndexOf("ExpectedSystemImageApis", [System.StringComparison]::Ordinal) -lt 0) {
            throw "Device workflow does not preflight its expected emulator system image: $workflowPath"
        }
    }

    $selfHostedWorkflow = Get-Content -LiteralPath (Join-Path $repositoryRoot ".github\workflows\self-hosted-validation.yml") -Raw -Encoding utf8
    foreach ($requiredFragment in @(
        '''["self-hosted","Windows","X64","muxtv-android"]''',
        '''["self-hosted","Windows","X64","muxtv-android","muxtv-device"]''',
        'runs-on: ${{ fromJSON(',
        "'muxtv-device-global'"
    )) {
        if ($selfHostedWorkflow.IndexOf($requiredFragment, [System.StringComparison]::Ordinal) -lt 0) {
            throw "Self-hosted validation does not route host and device modes to dedicated labels: $requiredFragment"
        }
    }
    if ($selfHostedWorkflow.IndexOf("matrix.lane", [System.StringComparison]::Ordinal) -ge 0) {
        throw "Self-hosted validation must not use matrix context in a job condition."
    }

    $productWorkflow = Get-Content -LiteralPath (Join-Path $repositoryRoot ".github\workflows\android-tv-product-device-matrix.yml") -Raw -Encoding utf8
    foreach ($requiredPath in @(
        "feature/home/**",
        "feature/guide/**",
        "feature/search/**",
        "feature/sources/**",
        "feature/doctor/**",
        "feature/settings/**",
        "core/database/**",
        "catalog/**",
        "gradle/**",
        "settings.gradle.kts"
    )) {
        if ($productWorkflow.IndexOf($requiredPath, [System.StringComparison]::Ordinal) -lt 0) {
            throw "Product matrix path filters do not cover MVP UI/data changes: $requiredPath"
        }
    }

    Write-Host "Self-hosted runner preflight contract passed."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
