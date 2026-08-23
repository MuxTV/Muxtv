[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [string[]]$AdditionalCleanupPath = @(),
    [scriptblock]$EmulatorProcessProbe = {
        @(
            Get-Process -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.ProcessName -ceq "emulator" -or
                    $_.ProcessName -like "qemu-system-*"
                }
        )
    },
    [scriptblock]$StopEmulatorProcess = {
        param([object]$Process)
        Stop-Process -Id ([int]$Process.Id) -Force -ErrorAction Stop
    },
    [scriptblock]$AdbAction = {
        param([string]$Command)
        . (Join-Path $RepositoryRoot "tools\android\Initialize-AndroidSdkEnvironment.ps1")
        . (Join-Path $RepositoryRoot "tools\android\AndroidSdk.ps1")
        $tools = Get-AndroidSdkTools
        $output = if ($Command -ceq "disconnect") {
            @(& $tools.Adb disconnect 2>&1)
        } elseif ($Command -ceq "kill-server") {
            @(& $tools.Adb kill-server 2>&1)
        } else {
            throw "Unsupported ADB cleanup command: $Command"
        }
        $exitCode = $LASTEXITCODE
        $output | ForEach-Object { Write-Host $_ }
        if ($exitCode -ne 0) {
            throw "ADB cleanup command failed: $Command"
        }
    },
    [scriptblock]$BuildDirectoryProbe = {
        param([string]$ResolvedRepositoryRoot)
        $buildFiles = @(& git -C $ResolvedRepositoryRoot ls-files -- "*build.gradle" "*build.gradle.kts" 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to enumerate repository Gradle projects for cleanup."
        }
        @(
            $buildFiles |
                ForEach-Object {
                    $relativeDirectory = Split-Path -Parent ([string]$_)
                    if ($relativeDirectory) {
                        Join-Path $ResolvedRepositoryRoot $relativeDirectory "build"
                    } else {
                        Join-Path $ResolvedRepositoryRoot "build"
                    }
                } |
                Sort-Object -Unique
        )
    },
    [scriptblock]$PathRemoval = {
        param([string]$Path)
        Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
    }
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-CleanupTarget {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Candidate
    )

    $rootFullPath = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $candidateFullPath = [System.IO.Path]::GetFullPath($Candidate).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $rootPrefix = $rootFullPath + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidateFullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Runner cleanup target is outside the repository root: $candidateFullPath"
    }
    $candidateFullPath
}

$resolvedRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
Write-Host "Resetting Android state for self-hosted runner workspace: $resolvedRoot"

$initialProcesses = @(& $EmulatorProcessProbe)
foreach ($process in $initialProcesses) {
    Write-Host "Stopping emulator process: $($process.ProcessName) ($($process.Id))"
    try {
        & $StopEmulatorProcess $process
    } catch {
        $sameProcess = @(
            & $EmulatorProcessProbe |
                Where-Object {
                    [int]$_.Id -eq [int]$process.Id -and
                    [string]$_.ProcessName -ceq [string]$process.ProcessName
                }
        )
        if ($sameProcess.Count -gt 0) {
            throw
        }
        Write-Host "Emulator process exited before cleanup stop completed: $($process.ProcessName) ($($process.Id))"
    }
}

& $AdbAction "disconnect"
& $AdbAction "kill-server"

$remainingProcesses = @(& $EmulatorProcessProbe)
for ($attempt = 0; $remainingProcesses.Count -gt 0 -and $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 200
    $remainingProcesses = @(& $EmulatorProcessProbe)
}
if ($remainingProcesses.Count -gt 0) {
    $identities = $remainingProcesses | ForEach-Object { "$($_.ProcessName):$($_.Id)" }
    throw "Emulator processes remain after runner cleanup: $([string]::Join(', ', $identities))"
}

$targets = [System.Collections.Generic.List[string]]::new()
$targets.Add((Join-Path $resolvedRoot ".work"))
foreach ($buildDirectory in @(& $BuildDirectoryProbe $resolvedRoot)) {
    foreach ($relativeOutput in @(
        "outputs\apk",
        "outputs\androidTest-results",
        "outputs\screenshots",
        "reports\androidTests"
    )) {
        $targets.Add((Join-Path ([string]$buildDirectory) $relativeOutput))
    }
}
foreach ($path in $AdditionalCleanupPath) {
    if ($path) {
        $targets.Add($path)
    }
}

$validatedTargets = @(
    $targets |
        ForEach-Object { Resolve-CleanupTarget -Root $resolvedRoot -Candidate $_ } |
        Sort-Object -Unique
)
foreach ($target in $validatedTargets) {
    if (Test-Path -LiteralPath $target) {
        Write-Host "Removing repository-owned runner output: $target"
        & $PathRemoval $target
    }
}
foreach ($target in $validatedTargets) {
    if (Test-Path -LiteralPath $target) {
        throw "Repository-owned temporary output remains after cleanup: $target"
    }
}

Write-Host "Self-hosted Android runner state reset completed."