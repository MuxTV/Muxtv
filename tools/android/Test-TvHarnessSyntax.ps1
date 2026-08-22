[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\harness-syntax"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$diagnosticPath = Join-Path $evidenceDirectory "harness-syntax.log"

$harnessFiles = [ordered]@{
    Initializer = Join-Path $PSScriptRoot "Initialize-AndroidSdkEnvironment.ps1"
    AndroidSdk = Join-Path $PSScriptRoot "AndroidSdk.ps1"
    TvValidation = Join-Path $PSScriptRoot "Invoke-TvDeviceValidation.ps1"
    CatalogMeasurement = Join-Path $PSScriptRoot "Invoke-CatalogDatabaseMeasurement.ps1"
    CatalogDevice = Join-Path $PSScriptRoot "Invoke-CatalogDatabaseDeviceValidation.ps1"
    PlayerMeasurement = Join-Path $PSScriptRoot "Invoke-PlayerProxyMeasurement.ps1"
    PlayerDevice = Join-Path $PSScriptRoot "Invoke-PlayerProxyDeviceValidation.ps1"
    VerifyLocal = Join-Path $repositoryRoot "tools\verify-local.ps1"
    EvidenceAssert = Join-Path $repositoryRoot "tools\ci\Assert-EvidenceCommit.ps1"
    RunnerPreflight = Join-Path $repositoryRoot "tools\ci\Assert-SelfHostedRunnerPreflight.ps1"
    BenchmarkDryRun = Join-Path $PSScriptRoot "Invoke-BenchmarkDryRun.ps1"
}

$messages = [System.Collections.Generic.List[string]]::new()

function Add-ContractError {
    param([Parameter(Mandatory)][string]$Message)
    $script:messages.Add($Message)
}

foreach ($entry in $harnessFiles.GetEnumerator()) {
    $file = [string]$entry.Value
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        Add-ContractError "Missing Android harness script: $file"
        continue
    }

    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$parseErrors)
    foreach ($parseError in @($parseErrors)) {
        $location = "{0}:{1}:{2}" -f $file, $parseError.Extent.StartLineNumber, $parseError.Extent.StartColumnNumber
        Add-ContractError ($location + " " + $parseError.Message)
    }
}

$initializerContent = Get-Content -LiteralPath $harnessFiles.Initializer -Raw
foreach ($requiredInitializerFragment in @(
    "Add-PathEntryIfMissing",
    "System32",
    "GITHUB_PATH",
    '$env:ADB_MDNS_AUTO_CONNECT = "0"',
    '"ADB_MDNS_AUTO_CONNECT=0"'
)) {
    if ($initializerContent -notmatch [regex]::Escape($requiredInitializerFragment)) {
        Add-ContractError ("Android SDK initialization does not preserve required Windows runtime PATH behavior: " + $requiredInitializerFragment)
    }
}

$androidSdkContent = Get-Content -LiteralPath $harnessFiles.AndroidSdk -Raw
$requiredFunctions = @(
    "Get-AndroidSdkTools",
    "ConvertFrom-TvSystemImagePackage",
    "ConvertFrom-SdkManagerTvSystemImageLines",
    "Get-InstalledTvSystemImages",
    "Get-AvailableTvSystemImages",
    "Resolve-TvSystemImage",
    "Get-MuxTvCanonicalAvdNames",
    "Get-StaleMuxTvAvdNames",
    "Remove-StaleMuxTvAvds",
    "Test-AndroidSystemImageInstalled",
    "Install-AndroidPackage",
    "Test-AndroidAcceleration",
    "New-TvAvd",
    "Start-TvEmulator",
    "Wait-AndroidBoot",
    "Collect-AndroidEvidence",
    "Stop-TvEmulator"
)
foreach ($functionName in $requiredFunctions) {
    $pattern = "(?m)^function\s+" + [regex]::Escape($functionName) + "\s*\{"
    if ($androidSdkContent -notmatch $pattern) {
        Add-ContractError ("Missing function declaration: " + $functionName)
    }
}
if ($androidSdkContent -notmatch '\$images\s*=\s*@\(Get-AvailableTvSystemImages') {
    Add-ContractError "Resolve-TvSystemImage must preserve singleton image output as an array."
}
if ($androidSdkContent -notmatch '\$lines\s*=\s*@\(&\s*\$Tools\.SdkManager\s+--list') {
    Add-ContractError "sdkmanager list output must be captured as an array."
}
if ($androidSdkContent.IndexOf('AllowOldEdgeFallback', [System.StringComparison]::Ordinal) -ge 0) {
    Add-ContractError "Resolve-TvSystemImage must not retain a dormant old-edge fallback API."
}

$verifyLocalContent = Get-Content -LiteralPath $harnessFiles.VerifyLocal -Raw
if ($verifyLocalContent -notmatch 'DeviceOnly') {
    Add-ContractError "verify-local must expose DeviceOnly connected-test mode."
}
if ($verifyLocalContent -notmatch [regex]::Escape('"--no-problems-report"')) {
    Add-ContractError "verify-local must disable the non-evidence Gradle HTML problems report to avoid Windows report publication races."
}

$provenanceAssertContent = Get-Content -LiteralPath $harnessFiles.EvidenceAssert -Raw
foreach ($requiredProvenanceFragment in @('git rev-parse HEAD', 'Evidence commit provenance mismatch', 'ExpectedCommit')) {
    if ($provenanceAssertContent -notmatch [regex]::Escape($requiredProvenanceFragment)) {
        Add-ContractError ("Evidence commit assertion is missing required behavior: " + $requiredProvenanceFragment)
    }
}

$runnerPreflightContract = Join-Path $repositoryRoot "tools\ci\Test-SelfHostedRunnerPreflightContract.ps1"
if (-not (Test-Path -LiteralPath $runnerPreflightContract -PathType Leaf)) {
    Add-ContractError "Self-hosted runner preflight contract test was not found."
}
$manualEvidenceQueueContract = Join-Path $repositoryRoot "tools\ci\Test-ManualEvidenceQueueContract.ps1"
if (-not (Test-Path -LiteralPath $manualEvidenceQueueContract -PathType Leaf)) {
    Add-ContractError "Manual evidence queue contract test was not found."
}

function Assert-WorkflowEvidenceContract {
    param(
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string]$CheckoutToken,
        [Parameter(Mandatory)][string]$SourceCommitToken
    )

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Add-ContractError ("Missing evidence workflow: " + $path)
        return
    }

    $content = Get-Content -LiteralPath $path -Raw
    if ($content.IndexOf($CheckoutToken, [System.StringComparison]::Ordinal) -lt 0) {
        Add-ContractError ("Evidence workflow must explicitly check out its claimed source commit: " + $path)
    }
    if ($content.IndexOf($SourceCommitToken, [System.StringComparison]::Ordinal) -lt 0) {
        Add-ContractError ("Evidence workflow does not preserve the expected source-commit expression: " + $path)
    }
    if ($content.IndexOf('Assert-EvidenceCommit.ps1', [System.StringComparison]::Ordinal) -lt 0) {
        Add-ContractError ("Evidence workflow must verify git HEAD before producing evidence: " + $path)
    }
}

$prOrDispatchCheckout = 'ref: ${{ github.event_name == ''pull_request'' && github.event.pull_request.head.sha || github.sha }}'
$prOrDispatchSource = 'github.event_name == ''pull_request'' && github.event.pull_request.head.sha || github.sha'

Assert-WorkflowEvidenceContract `
    -RelativePath ".github\workflows\self-hosted-validation.yml" `
    -CheckoutToken $prOrDispatchCheckout `
    -SourceCommitToken $prOrDispatchSource

Assert-WorkflowEvidenceContract `
    -RelativePath ".github\workflows\android-tv-focused-device.yml" `
    -CheckoutToken $prOrDispatchCheckout `
    -SourceCommitToken $prOrDispatchSource

Assert-WorkflowEvidenceContract `
    -RelativePath ".github\workflows\android-tv-product-device-matrix.yml" `
    -CheckoutToken 'ref: ${{ github.sha }}' `
    -SourceCommitToken 'github.sha'

Assert-WorkflowEvidenceContract `
    -RelativePath ".github\workflows\database-migration-device-matrix.yml" `
    -CheckoutToken 'ref: ${{ github.event.pull_request.head.sha || github.sha }}' `
    -SourceCommitToken 'github.event.pull_request.head.sha || github.sha'

Assert-WorkflowEvidenceContract `
    -RelativePath ".github\workflows\measurement-variance-smoke.yml" `
    -CheckoutToken $prOrDispatchCheckout `
    -SourceCommitToken $prOrDispatchSource

Assert-WorkflowEvidenceContract `
    -RelativePath ".github\workflows\integration-gate.yml" `
    -CheckoutToken 'ref: ${{ github.sha }}' `
    -SourceCommitToken 'github.sha'

$tvValidationContent = Get-Content -LiteralPath $harnessFiles.TvValidation -Raw
$hostValidationIndex = $tvValidationContent.IndexOf('"-Mode", "Full"', [System.StringComparison]::Ordinal)
$profileLoopIndex = $tvValidationContent.IndexOf('foreach ($profile in $profiles)', [System.StringComparison]::Ordinal)
$deviceOnlyIndex = $tvValidationContent.IndexOf('"-Mode", "DeviceOnly"', [System.StringComparison]::Ordinal)
$avdCleanupIndex = $tvValidationContent.IndexOf('Remove-StaleMuxTvAvds -Tools $tools', [System.StringComparison]::Ordinal)
if ($hostValidationIndex -lt 0 -or $profileLoopIndex -lt 0 -or $hostValidationIndex -gt $profileLoopIndex) {
    Add-ContractError "TV device validation must complete Full host validation before the profile loop."
}
if ($deviceOnlyIndex -lt 0 -or $deviceOnlyIndex -lt $profileLoopIndex) {
    Add-ContractError "TV profile validation must use DeviceOnly inside the profile loop."
}
if ($avdCleanupIndex -lt 0 -or $avdCleanupIndex -gt $profileLoopIndex) {
    Add-ContractError "TV device validation must remove only stale MuxTV-owned AVD identities before creating profiles."
}
foreach ($requiredProfileFragment in @(
    'Resolve-TvSystemImage -Tools $tools -PreferredApi 26',
    'Resolve-TvSystemImage -Tools $tools -PreferredApi 36',
    'AvdName = "MuxTV_TV_OLD_API26"',
    'AvdName = "MuxTV_TV_CURRENT_API36"'
)) {
    if ($tvValidationContent.IndexOf($requiredProfileFragment, [System.StringComparison]::Ordinal) -lt 0) {
        Add-ContractError ("TV device validation is missing the exact two-profile contract: " + $requiredProfileFragment)
    }
}
foreach ($forbiddenProfileFragment in @(
    '-AllowOldEdgeFallback',
    'MuxTV_TV_OLD_API$($oldImage.Api)',
    'MuxTV_TV_CURRENT_API$($currentImage.Api)'
)) {
    if ($tvValidationContent.IndexOf($forbiddenProfileFragment, [System.StringComparison]::Ordinal) -ge 0) {
        Add-ContractError ("TV device validation still permits profile drift or fallback: " + $forbiddenProfileFragment)
    }
}

$catalogDeviceValidationContent = Get-Content -LiteralPath $harnessFiles.CatalogDevice -Raw
if ($catalogDeviceValidationContent.IndexOf('"-EntryCount", "50000"', [System.StringComparison]::Ordinal) -lt 0) {
    Add-ContractError "Catalog device validation must preserve the manual canonical 50k measurement profile."
}
if ($catalogDeviceValidationContent.IndexOf('"-EntryCount", "10000"', [System.StringComparison]::Ordinal) -ge 0) {
    Add-ContractError "Catalog device validation still references the obsolete 10k measurement profile."
}

if ($messages.Count -eq 0) {
    . $harnessFiles.AndroidSdk

    $canonicalAvdNames = @(Get-MuxTvCanonicalAvdNames)
    if (($canonicalAvdNames -join '|') -cne 'MuxTV_TV_OLD_API26|MuxTV_TV_CURRENT_API36') {
        Add-ContractError "Canonical MuxTV AVD identities must be exactly API26 old and API36 current."
    }

    $cleanupCandidates = @(Get-StaleMuxTvAvdNames -AvdNames @(
        'MuxTV_TV_OLD_API26',
        'MuxTV_TV_CURRENT_API36',
        'MuxTV_TV_OLD_API28',
        'MuxTV_TV_CURRENT_API35',
        'MuxTV_TV_LOW_RAM',
        'Pixel_8_API_36',
        'OtherProject_TV'
    ))
    if (($cleanupCandidates -join '|') -cne 'MuxTV_TV_CURRENT_API35|MuxTV_TV_LOW_RAM|MuxTV_TV_OLD_API28') {
        Add-ContractError "Stale AVD filtering must target only non-canonical MuxTV_TV_* identities."
    }

    $parsedImages = @(ConvertFrom-SdkManagerTvSystemImageLines -Lines @(
        "system-images;android-36;android-tv;x86 | 1 | Android TV",
        "prefix system-images;android-30;google-tv;x86_64 suffix"
    ))
    if ($parsedImages.Count -ne 2) {
        Add-ContractError "sdkmanager TV image parser did not preserve all package lines."
    }

    $tempSdkRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-sdk-catalog-" + [Guid]::NewGuid().ToString("N"))
    try {
        $installedImageDirectory = Join-Path $tempSdkRoot "system-images\android-36\android-tv\x86"
        New-Item -ItemType Directory -Force -Path $installedImageDirectory | Out-Null
        Set-Content -Path (Join-Path $installedImageDirectory "source.properties") -Value "Pkg.Revision=1" -Encoding ascii
        $installedImages = @(Get-InstalledTvSystemImages -Tools ([pscustomobject]@{ Root = $tempSdkRoot }))
        if ($installedImages.Count -ne 1 -or $installedImages[0].Package -ne "system-images;android-36;android-tv;x86") {
            Add-ContractError "Installed Android TV image discovery did not resolve the SDK filesystem package."
        }
    } finally {
        Remove-Item -Path $tempSdkRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    function Get-AvailableTvSystemImages {
        param([Parameter(Mandatory)]$Tools)
        return [pscustomobject]@{
            Package = "system-images;android-36;android-tv;x86"
            Api = 36
            Flavor = "android-tv"
            Abi = "x86"
        }
    }
    $singletonResult = Resolve-TvSystemImage -Tools ([pscustomobject]@{}) -PreferredApi 36
    if ($singletonResult.Package -ne "system-images;android-36;android-tv;x86") {
        Add-ContractError "Resolve-TvSystemImage did not handle singleton image output."
    }
}

if ($messages.Count -gt 0) {
    $joined = [string]::Join([Environment]::NewLine, $messages)
    $message = "Android TV harness validation failed." + [Environment]::NewLine + $joined
    Set-Content -LiteralPath $diagnosticPath -Value $message -Encoding utf8
    Write-Host $message
    throw $message
}

$measurementHarnessCheck = Join-Path $repositoryRoot "tools\measurements\Test-MeasurementHarnessSyntax.ps1"
if (-not (Test-Path -LiteralPath $measurementHarnessCheck -PathType Leaf)) {
    throw "Measurement harness syntax checker was not found."
}
& $measurementHarnessCheck
& $runnerPreflightContract
& $manualEvidenceQueueContract

$benchmarkFoundationContract = Join-Path $repositoryRoot "tools\ci\Test-BenchmarkFoundationContract.ps1"
if (-not (Test-Path -LiteralPath $benchmarkFoundationContract -PathType Leaf)) {
    throw "Benchmark foundation contract test was not found."
}
& $benchmarkFoundationContract

$message = "Android TV, CI evidence, measurement, and benchmark harness contracts are valid."
Set-Content -LiteralPath $diagnosticPath -Value $message -Encoding utf8
Write-Host $message