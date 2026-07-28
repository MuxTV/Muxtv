[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\harness-syntax"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$diagnosticPath = Join-Path $evidenceDirectory "harness-syntax.log"

$files = @(
    (Join-Path $PSScriptRoot "AndroidSdk.ps1"),
    (Join-Path $PSScriptRoot "Invoke-TvDeviceValidation.ps1"),
    (Join-Path $PSScriptRoot "Invoke-CatalogDatabaseMeasurement.ps1"),
    (Join-Path $PSScriptRoot "Invoke-CatalogDatabaseDeviceValidation.ps1")
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

$androidSdkContent = Get-Content -Path $files[0] -Raw
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

if ($messages.Count -gt 0) {
    $joined = [string]::Join([Environment]::NewLine, $messages)
    $message = "Android TV harness validation failed." + [Environment]::NewLine + $joined
    Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
    Write-Host $message
    throw $message
}

$message = "Android TV harness PowerShell syntax and function surface are valid."
Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
Write-Host $message
