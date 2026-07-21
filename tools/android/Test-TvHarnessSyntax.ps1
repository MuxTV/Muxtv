[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$diagnosticPath = Join-Path $evidenceDirectory "harness-syntax.log"

$files = @(
    (Join-Path $PSScriptRoot "AndroidSdk.ps1")
    (Join-Path $PSScriptRoot "Invoke-TvDeviceValidation.ps1")
)

$allErrors = [System.Collections.Generic.List[string]]::new()
foreach ($file in $files) {
    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $file,
        [ref]$tokens,
        [ref]$parseErrors
    )

    foreach ($parseError in @($parseErrors)) {
        $allErrors.Add(
            "${file}:$($parseError.Extent.StartLineNumber):$($parseError.Extent.StartColumnNumber) $($parseError.Message)"
        )
    }
}

if ($allErrors.Count -gt 0) {
    $message = "Android TV harness PowerShell syntax validation failed:`n$($allErrors -join "`n")"
    $message | Set-Content -Path $diagnosticPath -Encoding utf8
    Write-Host $message
    throw $message
}

. (Join-Path $PSScriptRoot "AndroidSdk.ps1")
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

$missingFunctions = @(
    $requiredFunctions | Where-Object {
        $null -eq (Get-Command $_ -CommandType Function -ErrorAction SilentlyContinue)
    }
)
if ($missingFunctions.Count -gt 0) {
    $message = "Android TV harness functions are missing: $($missingFunctions -join ', ')"
    $message | Set-Content -Path $diagnosticPath -Encoding utf8
    Write-Host $message
    throw $message
}

"Android TV harness PowerShell syntax and function surface are valid." |
    Set-Content -Path $diagnosticPath -Encoding utf8
Write-Host "Android TV harness PowerShell syntax and function surface are valid."
