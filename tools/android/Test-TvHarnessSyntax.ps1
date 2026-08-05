[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\harness-syntax"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$diagnosticPath = Join-Path $evidenceDirectory "harness-syntax.log"

$files = @(
    (Join-Path $PSScriptRoot "Initialize-AndroidSdkEnvironment.ps1"),
    (Join-Path $PSScriptRoot "AndroidSdk.ps1"),
    (Join-Path $PSScriptRoot "Invoke-TvDeviceValidation.ps1"),
    (Join-Path $PSScriptRoot "Invoke-CatalogDatabaseMeasurement.ps1"),
    (Join-Path $PSScriptRoot "Invoke-CatalogDatabaseDeviceValidation.ps1"),
    (Join-Path $PSScriptRoot "Invoke-PlayerProxyMeasurement.ps1"),
    (Join-Path $PSScriptRoot "Invoke-PlayerProxyDeviceValidation.ps1"),
    (Join-Path $repositoryRoot "tools\verify-local.ps1")
)
$productWorkflowPath = Join-Path $repositoryRoot ".github\workflows\android-tv-product-device-matrix.yml"
$databaseWorkflowPath = Join-Path $repositoryRoot ".github\workflows\database-migration-device-matrix.yml"

$messages = @()
foreach ($file in $files) {
    if (-not (Test-Path $file -PathType Leaf)) {
        $messages += "Missing Android harness script: " + $file
        continue
    }

    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$parseErrors)

    foreach ($parseError in @($parseErrors)) {
        $location = "{0}:{1}:{2}" -f $file, $parseError.Extent.StartLineNumber, $parseError.Extent.StartColumnNumber
        $messages += $location + " " + $parseError.Message
    }
}

foreach ($workflowPath in @($productWorkflowPath, $databaseWorkflowPath)) {
    if (-not (Test-Path $workflowPath -PathType Leaf)) {
        $messages += "Missing Android TV workflow: " + $workflowPath
    }
}

$initializerContent = Get-Content -Path $files[0] -Raw
foreach ($requiredInitializerFragment in @(
    "Add-PathEntryIfMissing",
    "System32",
    "GITHUB_PATH"
)) {
    if ($initializerContent -notmatch [regex]::Escape($requiredInitializerFragment)) {
        $messages += "Android SDK initialization does not preserve required Windows runtime PATH behavior: " +
            $requiredInitializerFragment
    }
}

$androidSdkContent = Get-Content -Path $files[1] -Raw
$requiredFunctions = @(
    "Get-AndroidSdkTools",
    "ConvertFrom-TvSystemImagePackage",
    "ConvertFrom-SdkManagerTvSystemImageLines",
    "Get-InstalledTvSystemImages",
    "Get-AvailableTvSystemImages",
    "Resolve-TvSystemImage",
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
        $messages += "Missing function declaration: " + $functionName
    }
}
if ($androidSdkContent -notmatch '\$images\s*=\s*@\(Get-AvailableTvSystemImages') {
    $messages += "Resolve-TvSystemImage must preserve singleton image output as an array."
}
if ($androidSdkContent -notmatch '\$lines\s*=\s*@\(&\s*\$Tools\.SdkManager\s+--list') {
    $messages += "sdkmanager list output must be captured as an array."
}
if ($androidSdkContent -notmatch 'Get-InstalledTvSystemImages') {
    $messages += "TV system image resolution must inspect installed SDK directories."
}

$verifyLocalContent = Get-Content -Path $files[7] -Raw
if ($verifyLocalContent -notmatch 'DeviceOnly') {
    $messages += "verify-local must expose DeviceOnly connected-test mode."
}
if ($verifyLocalContent -notmatch '\[ValidateSet\("Product",\s*"Database"\)\]') {
    $messages += "verify-local must expose Product/Database ConnectedSuite values."
}
if ($verifyLocalContent -notmatch '\[string\]\$ConnectedSuite\s*=\s*"Product"') {
    $messages += "verify-local ConnectedSuite must default to Product for direct/manual callers."
}
if ($verifyLocalContent -notmatch 'connectedSuite\s*=\s*\$ConnectedSuite') {
    $messages += "verify-local evidence manifest must record ConnectedSuite."
}

$tvValidationContent = Get-Content -Path $files[2] -Raw
$hostValidationIndex = $tvValidationContent.IndexOf('"-Mode", "Full"')
$profileLoopIndex = $tvValidationContent.IndexOf('foreach ($profile in $profiles)')
$deviceOnlyIndex = $tvValidationContent.IndexOf('"-Mode", "DeviceOnly"')
if (
    $hostValidationIndex -lt 0 -or
    $profileLoopIndex -lt 0 -or
    $hostValidationIndex -gt $profileLoopIndex
) {
    $messages += "TV device validation must complete Full host validation before the profile loop."
}
if ($deviceOnlyIndex -lt 0 -or $deviceOnlyIndex -lt $profileLoopIndex) {
    $messages += "TV profile validation must use DeviceOnly inside the profile loop."
}
if ($tvValidationContent -notmatch '\[ValidateSet\("Product",\s*"Database"\)\]') {
    $messages += "TV device validation must expose Product/Database ConnectedSuite values."
}
if ($tvValidationContent -notmatch '\[string\]\$ConnectedSuite\s*=\s*"Product"') {
    $messages += "TV device validation ConnectedSuite must default to Product."
}
if ($tvValidationContent -notmatch '"-ConnectedSuite",\s*\$ConnectedSuite') {
    $messages += "TV profile validation must forward ConnectedSuite to verify-local DeviceOnly."
}
if ($tvValidationContent -notmatch 'connectedSuite\s*=\s*\$ConnectedSuite') {
    $messages += "TV device manifest must record ConnectedSuite."
}

if (Test-Path $productWorkflowPath -PathType Leaf) {
    $productWorkflowContent = Get-Content -Path $productWorkflowPath -Raw
    if ($productWorkflowContent -notmatch '-ConnectedSuite\s+Product') {
        $messages += "Android TV product workflow must explicitly select ConnectedSuite Product."
    }
}
if (Test-Path $databaseWorkflowPath -PathType Leaf) {
    $databaseWorkflowContent = Get-Content -Path $databaseWorkflowPath -Raw
    if ($databaseWorkflowContent -notmatch '-ConnectedSuite\s+Database') {
        $messages += "Database migration workflow must explicitly select ConnectedSuite Database."
    }
}

if ($messages.Count -eq 0) {
    . $files[1]

    $parsedImages = @(ConvertFrom-SdkManagerTvSystemImageLines -Lines @(
        "system-images;android-36;android-tv;x86 | 1 | Android TV",
        "prefix system-images;android-30;google-tv;x86_64 suffix"
    ))
    if ($parsedImages.Count -ne 2) {
        $messages += "sdkmanager TV image parser did not preserve all package lines."
    }

    $tempSdkRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-sdk-catalog-" + [Guid]::NewGuid().ToString("N"))
    try {
        $installedImageDirectory = Join-Path $tempSdkRoot "system-images\android-36\android-tv\x86"
        New-Item -ItemType Directory -Force -Path $installedImageDirectory | Out-Null
        Set-Content -Path (Join-Path $installedImageDirectory "source.properties") -Value "Pkg.Revision=1" -Encoding ascii
        $installedImages = @(Get-InstalledTvSystemImages -Tools ([pscustomobject]@{ Root = $tempSdkRoot }))
        if ($installedImages.Count -ne 1 -or $installedImages[0].Package -ne "system-images;android-36;android-tv;x86") {
            $messages += "Installed Android TV image discovery did not resolve the SDK filesystem package."
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
        $messages += "Resolve-TvSystemImage did not handle singleton image output."
    }
}

if ($messages.Count -gt 0) {
    $joined = [string]::Join([Environment]::NewLine, $messages)
    $message = "Android TV harness validation failed." + [Environment]::NewLine + $joined
    Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
    Write-Host $message
    throw $message
}

$measurementHarnessCheck = Join-Path $repositoryRoot "tools\measurements\Test-MeasurementHarnessSyntax.ps1"
if (-not (Test-Path $measurementHarnessCheck -PathType Leaf)) {
    throw "Measurement harness syntax checker was not found."
}
& $measurementHarnessCheck

$message = "Android TV and measurement harness PowerShell syntax and function surfaces are valid."
Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
Write-Host $message
