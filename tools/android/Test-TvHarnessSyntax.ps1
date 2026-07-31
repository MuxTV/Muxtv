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
    (Join-Path $PSScriptRoot "Invoke-PlayerProxyDeviceValidation.ps1")
)

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
    "Resolve-TvSystemImage",
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

if ($messages.Count -eq 0) {
    . $files[1]
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
