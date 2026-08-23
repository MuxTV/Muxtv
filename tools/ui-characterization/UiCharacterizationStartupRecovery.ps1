Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-LatestTvUiCharacterizationAttemptDirectory {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$EvidenceRoot)

    if (-not (Test-Path -LiteralPath $EvidenceRoot -PathType Container)) {
        return $null
    }

    return @(
        Get-ChildItem -LiteralPath $EvidenceRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending
    ) | Select-Object -First 1
}

function Test-TvUiRecoverableEmulatorStartupFailure {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$EvidenceRoot,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    $startupFailure =
        $FailureMessage -ceq 'Unable to reset display size before characterization.' -or
        $FailureMessage -ceq 'Unable to reset display density before characterization.' -or
        $FailureMessage.StartsWith('UI characterization emulator exited before ADB registration.', [StringComparison]::Ordinal) -or
        $FailureMessage.StartsWith('UI characterization emulator did not register as ', [StringComparison]::Ordinal) -or
        ($FailureMessage.StartsWith('Android TV emulator ', [StringComparison]::Ordinal) -and
            $FailureMessage.Contains(' did not complete boot within ', [StringComparison]::Ordinal))

    if (-not $startupFailure) {
        return $false
    }

    $attempt = Get-LatestTvUiCharacterizationAttemptDirectory -EvidenceRoot $EvidenceRoot
    if ($null -eq $attempt) {
        return $false
    }

    $passedCases = @(
        Get-ChildItem -LiteralPath $attempt.FullName -Filter 'case-manifest.json' -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object {
                try {
                    $manifest = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
                    ([string]$manifest.status) -ceq 'passed'
                } catch {
                    $false
                }
            }
    )
    if ($passedCases.Count -gt 0) {
        return $false
    }

    $stderrPath = Join-Path $attempt.FullName 'emulator-stderr.log'
    if (-not (Test-Path -LiteralPath $stderrPath -PathType Leaf)) {
        return $false
    }

    $stderr = Get-Content -LiteralPath $stderrPath -Raw
    $hasModemFailure = $stderr.Contains('Unable to connect character device modem', [StringComparison]::Ordinal)
    $hasTransportFailure =
        $stderr.Contains('Failed to connect socket: Input/output error', [StringComparison]::Ordinal) -or
        $stderr.Contains('address resolution failed', [StringComparison]::Ordinal)

    return $hasModemFailure -and $hasTransportFailure
}
