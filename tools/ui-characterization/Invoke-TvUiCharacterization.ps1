[CmdletBinding()]
param(
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$CandidateCommit = '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9',

    [string]$EvidenceRoot = '.work/evidence/ui-characterization',

    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $repositoryRoot 'tools\android\Initialize-AndroidSdkEnvironment.ps1')
. (Join-Path $repositoryRoot 'tools\android\AndroidSdk.ps1')

$baselineA = '2302c11441c85b8b5752d7f03cc5bc13be8c6d92'
$baselineB = '515072022d11b218fcb20f43079f94098b3ea973'
$baselineC = $CandidateCommit
$avdName = 'MuxTV_TV_CURRENT_API36'
$probeSource = Join-Path $PSScriptRoot 'probe\UiCharacterizationProbeTest.kt'
$targetProbeRelativePath = 'app\tv\src\androidTest\kotlin\app\muxtv\UiCharacterizationProbeTest.kt'
$worktreeRoot = Join-Path $repositoryRoot '.work\ui-characterization\worktrees'
$resolvedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$runRoot = Join-Path $resolvedEvidenceRoot $timestamp
$appPackage = 'app.muxtv.tv.debug'

$comparisonRefs = @(
    [pscustomobject]@{ Id = 'A'; Commit = $baselineA },
    [pscustomobject]@{ Id = 'B'; Commit = $baselineB },
    [pscustomobject]@{ Id = 'C'; Commit = $baselineC }
)

$displayProfiles = @(
    [pscustomobject]@{ Id = '1080p-tv'; Label = 'representative-1080p'; Size = '1920x1080'; Width = 1920; Height = 1080; Density = 320; Representative = $true },
    [pscustomobject]@{ Id = '720p-tv'; Label = 'representative-720p-tv'; Size = '1280x720'; Width = 1280; Height = 720; Density = 213; Representative = $true },
    [pscustomobject]@{ Id = 'compact-stress'; Label = 'compact-stress'; Size = '1280x720'; Width = 1280; Height = 720; Density = 320; Representative = $false }
)

function Invoke-CheckedGit {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = @(& git @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $exitCode.`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Assert-CommitAvailable {
    param([Parameter(Mandatory)][string]$Commit)
    & git cat-file -e "$Commit^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Required immutable UI comparison commit is not available locally: $Commit. Checkout the workflow with full history."
    }
}

function Test-PortPairAvailable {
    param([Parameter(Mandatory)][int]$ConsolePort)
    foreach ($port in @($ConsolePort, $ConsolePort + 1)) {
        $listener = $null
        try {
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
            $listener.Start()
        } catch {
            return $false
        } finally {
            if ($null -ne $listener) { $listener.Stop() }
        }
    }
    return $true
}

function Get-FreeConsolePort {
    for ($port = 5554; $port -le 5680; $port += 2) {
        if (Test-PortPairAvailable -ConsolePort $port) { return $port }
    }
    throw 'No free Android Emulator port pair is available.'
}

function Test-AdbReady {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial
    )
    $state = @(& $Tools.Adb -s $Serial get-state 2>$null)
    return $LASTEXITCODE -eq 0 -and $state.Count -gt 0 -and ([string]$state[0]).Trim() -eq 'device'
}

function Wait-UiCharacterizationDevice {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 180
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($Process.HasExited) {
            throw "UI characterization emulator exited before ADB registration. ExitCode=$($Process.ExitCode)"
        }
        if (Test-AdbReady -Tools $Tools -Serial $Serial) { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "UI characterization emulator did not register as $Serial within $TimeoutSeconds seconds."
}

function Set-DisplayProfile {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)]$Profile
    )
    & $Tools.Adb -s $Serial shell wm size reset | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to reset display size before characterization.' }
    & $Tools.Adb -s $Serial shell wm density reset | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to reset display density before characterization.' }
    & $Tools.Adb -s $Serial shell wm size $Profile.Size | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to set display size $($Profile.Size)." }
    & $Tools.Adb -s $Serial shell wm density ([string]$Profile.Density) | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to set display density $($Profile.Density)." }
    Start-Sleep -Seconds 2
}

function Reset-DisplayProfile {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial
    )
    # Literal reset commands are part of the static safety contract.
    & $Tools.Adb -s $Serial shell wm size reset | Out-Null
    & $Tools.Adb -s $Serial shell wm density reset | Out-Null
}

function Clear-AppStateIfInstalled {
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$PackageName
    )

    # A clean canonical AVD may not contain the target package before the first
    # connectedDebugAndroidTest install. Probe package presence first so the initial
    # characterization case is not rejected simply because there is nothing to clear yet.
    $packagePaths = @(& $Tools.Adb -s $Serial shell pm path $PackageName 2>$null)
    $pathExitCode = $LASTEXITCODE
    $installed = $pathExitCode -eq 0 -and @(
        $packagePaths | Where-Object { ([string]$_).Trim().StartsWith('package:', [StringComparison]::Ordinal) }
    ).Count -gt 0
    if (-not $installed) {
        Write-Host "Package $PackageName is not installed yet; skipping pre-test pm clear."
        return
    }

    $clearOutput = @(& $Tools.Adb -s $Serial shell pm clear $PackageName 2>&1)
    $clearExitCode = $LASTEXITCODE
    if ($clearExitCode -ne 0 -or -not ($clearOutput -join "`n").Contains('Success', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unable to clear MuxTV debug app state for installed package $PackageName.`n$($clearOutput -join [Environment]::NewLine)"
    }
}

function Write-CaseManifest {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$SourceCommit,
        [Parameter(Mandatory)]$Profile,
        [Parameter(Mandatory)][string]$ProbeSha256,
        [Parameter(Mandatory)][string]$AvdName,
        [Parameter(Mandatory)][string]$Status,
        [string]$Failure = ''
    )
    [ordered]@{
        schemaVersion = 1
        sourceCommit = $SourceCommit
        displayProfile = $Profile.Id
        displayLabel = $Profile.Label
        displayWidthPx = $Profile.Width
        displayHeightPx = $Profile.Height
        displayDensityDpi = $Profile.Density
        representativeTvMode = [bool]$Profile.Representative
        probeSha256 = $ProbeSha256
        avdName = $AvdName
        status = $Status
        failure = if ([string]::IsNullOrWhiteSpace($Failure)) { $null } else { $Failure }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Path -Encoding utf8
}

if (-not (Test-Path -LiteralPath $probeSource -PathType Leaf)) {
    throw "Common UI characterization probe is missing: $probeSource"
}

Set-Location $repositoryRoot
foreach ($comparison in $comparisonRefs) { Assert-CommitAvailable -Commit $comparison.Commit }

$probeSha256 = (Get-FileHash -LiteralPath $probeSource -Algorithm SHA256).Hash.ToLowerInvariant()
New-Item -ItemType Directory -Force -Path $worktreeRoot, $runRoot | Out-Null

$tools = Get-AndroidSdkTools
Collect-AndroidToolchainEvidence -Tools $tools -EvidenceDirectory $runRoot
Test-AndroidAcceleration -Tools $tools -EvidenceDirectory $runRoot
$image = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
if ($image.Package -cne 'system-images;android-36;android-tv;x86_64') {
    throw "U0 resolved an unexpected API36 image: $($image.Package)"
}
Install-AndroidPackage -Tools $tools -Package $image.Package -EvidenceDirectory $runRoot

$consolePort = Get-FreeConsolePort
$serial = "emulator-$consolePort"
$emulatorProcess = $null
$createdWorktrees = [System.Collections.Generic.List[string]]::new()
$previousAndroidSerial = $env:ANDROID_SERIAL

try {
    New-TvAvd -Tools $tools -Name $avdName -SystemImagePackage $image.Package -RamMb 2048 -CpuCores 2
    $emulatorProcess = Start-TvEmulator -Tools $tools -AvdName $avdName -Port $consolePort -EvidenceDirectory $runRoot
    Wait-UiCharacterizationDevice -Tools $tools -Serial $serial -Process $emulatorProcess
    Wait-AndroidBoot -Tools $tools -Serial $serial -TimeoutSeconds 360
    $env:ANDROID_SERIAL = $serial

    foreach ($comparison in $comparisonRefs) {
        $worktreePath = Join-Path $worktreeRoot $comparison.Id
        if (Test-Path -LiteralPath $worktreePath) {
            Invoke-CheckedGit -Arguments @('worktree', 'remove', '--force', $worktreePath) | Out-Null
        }
        Invoke-CheckedGit -Arguments @('worktree', 'add', '--detach', $worktreePath, $comparison.Commit) | Out-Null
        $createdWorktrees.Add($worktreePath)

        $actualCommit = (& git -C $worktreePath rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or $actualCommit -cne $comparison.Commit) {
            throw "Worktree provenance mismatch for $($comparison.Id): expected $($comparison.Commit), got $actualCommit"
        }

        $targetProbe = Join-Path $worktreePath $targetProbeRelativePath
        Copy-Item -LiteralPath $probeSource -Destination $targetProbe -Force
        $targetProbeSha256 = (Get-FileHash -LiteralPath $targetProbe -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($targetProbeSha256 -cne $probeSha256) {
            throw "Probe identity mismatch for comparison $($comparison.Id)."
        }

        $comparisonEvidence = Join-Path $runRoot $comparison.Id
        New-Item -ItemType Directory -Force -Path $comparisonEvidence | Out-Null

        foreach ($profile in $displayProfiles) {
            $caseDirectory = Join-Path $comparisonEvidence $profile.Id
            New-Item -ItemType Directory -Force -Path $caseDirectory | Out-Null
            $manifestPath = Join-Path $caseDirectory 'case-manifest.json'
            Write-CaseManifest -Path $manifestPath -SourceCommit $comparison.Commit -Profile $profile -ProbeSha256 $probeSha256 -AvdName $avdName -Status 'running'

            try {
                Set-DisplayProfile -Tools $tools -Serial $serial -Profile $profile
                Clear-AppStateIfInstalled -Tools $tools -Serial $serial -PackageName $appPackage

                $arguments = @(
                    ':app:tv:connectedDebugAndroidTest',
                    '--stacktrace',
                    '--console=plain',
                    '-Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.UiCharacterizationProbeTest',
                    "-Pandroid.testInstrumentationRunnerArguments.sourceCommit=$($comparison.Commit)",
                    "-Pandroid.testInstrumentationRunnerArguments.displayProfile=$($profile.Id)",
                    "-Pandroid.testInstrumentationRunnerArguments.displayWidthPx=$($profile.Width)",
                    "-Pandroid.testInstrumentationRunnerArguments.displayHeightPx=$($profile.Height)",
                    "-Pandroid.testInstrumentationRunnerArguments.displayDensityDpi=$($profile.Density)"
                )
                if ($NoDaemon) { $arguments += '--no-daemon' }

                $gradleLog = Join-Path $caseDirectory 'gradle.log'
                $gradleOutput = @(& (Join-Path $worktreePath 'gradlew.bat') @arguments 2>&1)
                $gradleExitCode = $LASTEXITCODE
                $gradleOutput | Tee-Object -FilePath $gradleLog | ForEach-Object { Write-Host $_ }
                if ($gradleExitCode -ne 0) {
                    throw "UI characterization instrumentation failed for $($comparison.Id)/$($profile.Id)."
                }

                $remoteEvidence = "/sdcard/Android/data/$appPackage/files/ui-characterization/."
                & $tools.Adb -s $serial pull $remoteEvidence $caseDirectory 2>&1 |
                    Set-Content -LiteralPath (Join-Path $caseDirectory 'adb-pull.log') -Encoding utf8
                if ($LASTEXITCODE -ne 0) {
                    throw "Unable to pull UI characterization evidence for $($comparison.Id)/$($profile.Id)."
                }

                Write-CaseManifest -Path $manifestPath -SourceCommit $comparison.Commit -Profile $profile -ProbeSha256 $probeSha256 -AvdName $avdName -Status 'passed'
            } catch {
                Write-CaseManifest -Path $manifestPath -SourceCommit $comparison.Commit -Profile $profile -ProbeSha256 $probeSha256 -AvdName $avdName -Status 'failed' -Failure $_.Exception.Message
                throw
            } finally {
                Reset-DisplayProfile -Tools $tools -Serial $serial
            }
        }
    }
} finally {
    if ($null -ne $tools -and -not [string]::IsNullOrWhiteSpace($serial)) {
        try { Reset-DisplayProfile -Tools $tools -Serial $serial } catch { Write-Warning $_.Exception.Message }
    }
    if ($null -ne $emulatorProcess) {
        try {
            Stop-TvEmulator -Tools $tools -Serial $serial -Process $emulatorProcess
        } catch {
            Stop-Process -Id $emulatorProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }

    if ([string]::IsNullOrWhiteSpace($previousAndroidSerial)) {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }

    foreach ($worktreePath in @($createdWorktrees)) {
        if (Test-Path -LiteralPath $worktreePath) {
            try { Invoke-CheckedGit -Arguments @('worktree', 'remove', '--force', $worktreePath) | Out-Null } catch { Write-Warning $_.Exception.Message }
        }
    }
}

Write-Host "TV UI characterization evidence: $runRoot"
