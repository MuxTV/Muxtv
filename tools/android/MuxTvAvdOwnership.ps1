Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LegacyMuxTvAvdNames {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string[]]$Names
    )

    $canonicalNames = @(
        "MuxTV_TV_OLD_API26",
        "MuxTV_TV_CURRENT_API36"
    )
    $legacyPatterns = @(
        '^MuxTV_VARIANCE_.+$',
        '^MuxTV_BENCHMARK_API\d+$',
        '^MuxTV_CATALOG_MEASUREMENT_API\d+$',
        '^MuxTV_PLAYER_MEASUREMENT_API\d+$',
        '^MuxTV_TV_OLD_API\d+$',
        '^MuxTV_TV_CURRENT_API\d+$'
    )

    $legacy = foreach ($rawName in @($Names)) {
        $name = ([string]$rawName).Trim()
        if ([string]::IsNullOrWhiteSpace($name)) {
            continue
        }
        if ($canonicalNames -ccontains $name) {
            continue
        }

        foreach ($pattern in $legacyPatterns) {
            if ($name -cmatch $pattern) {
                $name
                break
            }
        }
    }

    return @($legacy | Sort-Object -Unique)
}
