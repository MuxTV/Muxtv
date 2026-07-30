[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$')]
    [string]$SourceBranch = "local",

    [ValidateSet("current-normal", "old-edge-normal", "current-low-ram")]
    [string]$ProfileId = "current-normal",

    [ValidateRange(2, 20)]
    [int]$Repetitions = 2,

    [string]$EvidenceRoot = ".work/evidence",

    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Wait-MeasurementStableAndroidBoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [int]$TimeoutSeconds = 300
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $consecutiveReadyChecks = 0
    do {
        $stateOutput = @(& $Tools.Adb -s $Serial get-state 2>$null)
        $stateExitCode = $LASTEXITCODE
        $state = if ($stateOutput.Count -gt 0) { ([string]$stateOutput[0]).Trim() } else { "" }

        $bootOutput = @(& $Tools.Adb -s $Serial shell getprop sys.boot_completed 2>$null)
        $bootExitCode = $LASTEXITCODE
        $bootCompleted = if ($bootOutput.Count -gt 0) { ([string]$bootOutput[0]).Trim() } else { "" }

        if ($stateExitCode -eq 0 -and $bootExitCode -eq 0 -and
            $state -eq "device" -and $bootCompleted -eq "1") {
            $consecutiveReadyChecks += 1
            if ($consecutiveReadyChecks -ge 2) {
                break
            }
        } else {
            $consecutiveReadyChecks = 0
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    if ($consecutiveReadyChecks -lt 2) {
        throw "Android TV emulator did not reach stable boot readiness within the configured timeout."
    }

    $packageDeadline = (Get-Date).AddSeconds(90)
    $androidPackage = ""
    do {
        $packageOutput = @(& $Tools.Adb -s $Serial shell pm path android 2>$null)
        $packageExitCode = $LASTEXITCODE
        $androidPackage = if ($packageOutput.Count -gt 0) { ([string]$packageOutput[0]).Trim() } else { "" }
        if ($packageExitCode -eq 0 -and $androidPackage.StartsWith("package:")) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $packageDeadline)

    if (-not $androidPackage.StartsWith("package:")) {
        throw "Android package manager did not become ready within the configured timeout."
    }

    & $Tools.Adb -s $Serial shell input keyevent 82 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global window_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global transition_animation_scale 0 | Out-Null
    & $Tools.Adb -s $Serial shell settings put global animator_duration_scale 0 | Out-Null
    Write-Host "Android TV emulator completed stable boot readiness."
}

Set-Alias `
    -Name Wait-AndroidBoot `
    -Value Wait-MeasurementStableAndroidBoot `
    -Scope Global `
    -Option AllScope

$coreScript = Join-Path $PSScriptRoot "Invoke-MeasurementSeriesCore.ps1"
if (-not (Test-Path $coreScript -PathType Leaf)) {
    throw "Measurement series core script was not found."
}

$arguments = @{
    SourceCommit = $SourceCommit
    SourceBranch = $SourceBranch
    ProfileId = $ProfileId
    Repetitions = $Repetitions
    EvidenceRoot = $EvidenceRoot
}
if ($NoDaemon) {
    $arguments.NoDaemon = $true
}

& $coreScript @arguments
