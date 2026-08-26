[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$probePath = Join-Path $PSScriptRoot 'probe\UiCharacterizationProbeTest.kt'

if (-not (Test-Path -LiteralPath $probePath -PathType Leaf)) {
    throw "Missing common UI characterization probe: $probePath"
}

$probe = Get-Content -LiteralPath $probePath -Raw
$comparisons = [ordered]@{
    A = '2302c11441c85b8b5752d7f03cc5bc13be8c6d92'
    B = '515072022d11b218fcb20f43079f94098b3ea973'
    C = '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9'
}

$contracts = @(
    [pscustomobject]@{
        Destination = 'channels'
        SourcePath = 'feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt'
        SourcePattern = 'ChannelsFilter\.ALL\s*->\s*"([^"]+)"'
        SourceDescription = 'ChannelsFilter.ALL screen title'
    },
    [pscustomobject]@{
        Destination = 'guide'
        SourcePath = 'feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideRoute.kt'
        SourcePattern = '(?s)MuxTvScreenScaffold\s*\(\s*title\s*=\s*"([^"]+)"'
        SourceDescription = 'Guide MuxTvScreenScaffold title'
    },
    [pscustomobject]@{
        Destination = 'search'
        SourcePath = 'feature/search/src/main/kotlin/app/muxtv/feature/search/SearchRoute.kt'
        SourcePattern = '(?s)MuxTvScreenScaffold\s*\(\s*title\s*=\s*"([^"]+)"'
        SourceDescription = 'Search MuxTvScreenScaffold title'
    }
)

function Get-ProbeTitleAnchor {
    param([Parameter(Mandatory)][string]$Destination)

    $escapedDestination = [regex]::Escape($Destination)
    $pattern = '(?s)destination\s*=\s*"' + $escapedDestination + '".*?anchor\s*=\s*Anchor\.Title\("([^"]+)"\)'
    $matches = [regex]::Matches($probe, $pattern)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one title anchor for destination '$Destination', found $($matches.Count)."
    }

    return $matches[0].Groups[1].Value
}

function Get-GitFileText {
    param(
        [Parameter(Mandatory)][string]$Commit,
        [Parameter(Mandatory)][string]$Path
    )

    $spec = "${Commit}:$Path"
    $lines = @(& git -C $repositoryRoot show $spec 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read immutable source '$spec': $($lines -join [Environment]::NewLine)"
    }

    return $lines -join [Environment]::NewLine
}

foreach ($contract in $contracts) {
    $probeTitle = Get-ProbeTitleAnchor -Destination $contract.Destination

    foreach ($entry in $comparisons.GetEnumerator()) {
        $source = Get-GitFileText -Commit $entry.Value -Path $contract.SourcePath
        $match = [regex]::Match($source, $contract.SourcePattern)
        if (-not $match.Success) {
            throw "Unable to resolve $($contract.SourceDescription) for comparison $($entry.Key) at $($entry.Value)."
        }

        $sourceTitle = $match.Groups[1].Value
        if ($probeTitle -cne $sourceTitle) {
            throw (
                "Probe title anchor mismatch for destination '{0}' on comparison {1}: probe='{2}', source='{3}' ({4})." -f
                    $contract.Destination,
                    $entry.Key,
                    $probeTitle,
                    $sourceTitle,
                    $contract.SourceDescription
            )
        }
    }
}

Write-Host 'TV UI probe title anchors match immutable A/B/C screen-title contracts.'
