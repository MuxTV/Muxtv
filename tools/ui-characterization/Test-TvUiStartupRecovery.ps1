[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$helperPath = Join-Path $PSScriptRoot 'UiCharacterizationStartupRecovery.ps1'
if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) {
    throw "UI characterization startup-recovery helper is missing: $helperPath"
}

. $helperPath

$root = Join-Path ([System.IO.Path]::GetTempPath()) ('muxtv-ui-startup-recovery-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $root | Out-Null

function New-AttemptEvidence {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Stderr,
        [switch]$PassedCase
    )

    $attempt = Join-Path $root $Name
    New-Item -ItemType Directory -Force -Path $attempt | Out-Null
    Set-Content -LiteralPath (Join-Path $attempt 'emulator-stderr.log') -Value $Stderr -Encoding utf8

    if ($PassedCase) {
        $case = Join-Path $attempt 'A\1080p-tv'
        New-Item -ItemType Directory -Force -Path $case | Out-Null
        '{"status":"passed"}' | Set-Content -LiteralPath (Join-Path $case 'case-manifest.json') -Encoding utf8
    }

    return $attempt
}

try {
    New-AttemptEvidence `
        -Name '20260823T201120Z' `
        -Stderr 'qemu-system-x86_64-headless.exe: Unable to connect character device modem: Failed to connect socket: Input/output error' | Out-Null

    $recoverable = Test-TvUiRecoverableEmulatorStartupFailure `
        -EvidenceRoot $root `
        -FailureMessage 'Unable to reset display size before characterization.'
    if (-not $recoverable) {
        throw 'Known pre-characterization Android Emulator modem transport failure must be eligible for one bounded retry.'
    }

    $genericRoot = Join-Path $root 'generic'
    New-Item -ItemType Directory -Force -Path $genericRoot | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $genericRoot '20260823T202000Z') | Out-Null
    'ordinary stderr without modem transport failure' |
        Set-Content -LiteralPath (Join-Path $genericRoot '20260823T202000Z\emulator-stderr.log') -Encoding utf8

    if (Test-TvUiRecoverableEmulatorStartupFailure -EvidenceRoot $genericRoot -FailureMessage 'Unable to reset display size before characterization.') {
        throw 'Generic display-profile failure must not be retried without the emulator modem transport signature.'
    }

    if (Test-TvUiRecoverableEmulatorStartupFailure -EvidenceRoot $root -FailureMessage 'UI characterization instrumentation failed for A/1080p-tv.') {
        throw 'Instrumentation or UI assertion failures must never be retried as startup transport failures.'
    }

    $progressRoot = Join-Path $root 'progress'
    New-Item -ItemType Directory -Force -Path $progressRoot | Out-Null
    $attempt = Join-Path $progressRoot '20260823T203000Z'
    New-Item -ItemType Directory -Force -Path $attempt | Out-Null
    'qemu-system-x86_64-headless.exe: Unable to connect character device modem: Failed to connect socket: Input/output error' |
        Set-Content -LiteralPath (Join-Path $attempt 'emulator-stderr.log') -Encoding utf8
    $passedCase = Join-Path $attempt 'A\1080p-tv'
    New-Item -ItemType Directory -Force -Path $passedCase | Out-Null
    '{"status":"passed"}' | Set-Content -LiteralPath (Join-Path $passedCase 'case-manifest.json') -Encoding utf8

    if (Test-TvUiRecoverableEmulatorStartupFailure -EvidenceRoot $progressRoot -FailureMessage 'Unable to reset display size before characterization.') {
        throw 'A run that already produced a passed characterization case must not be replayed as startup recovery.'
    }

    $staleRoot = Join-Path $root 'stale'
    New-Item -ItemType Directory -Force -Path $staleRoot | Out-Null
    $oldAttempt = Join-Path $staleRoot '20260823T204000Z'
    $newAttempt = Join-Path $staleRoot '20260823T205000Z'
    New-Item -ItemType Directory -Force -Path $oldAttempt, $newAttempt | Out-Null
    'Unable to connect character device modem: Failed to connect socket: Input/output error' |
        Set-Content -LiteralPath (Join-Path $oldAttempt 'emulator-stderr.log') -Encoding utf8
    'newest attempt has no modem failure' |
        Set-Content -LiteralPath (Join-Path $newAttempt 'emulator-stderr.log') -Encoding utf8
    (Get-Item -LiteralPath $oldAttempt).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(-2)
    (Get-Item -LiteralPath $newAttempt).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(-1)

    if (Test-TvUiRecoverableEmulatorStartupFailure -EvidenceRoot $staleRoot -FailureMessage 'Unable to reset display size before characterization.') {
        throw 'Recovery classification must inspect only the latest attempt and must not reuse stale failure signatures.'
    }
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'TV UI startup recovery classifier contract passed.'
