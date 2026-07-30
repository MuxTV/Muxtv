Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-MuxTvMeasurementProfile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidatePattern('^[a-z0-9][a-z0-9-]{0,31}$')]
        [string]$Id
    )

    switch ($Id) {
        "current-normal" {
            return [pscustomobject]@{
                Id = "current-normal"
                RequestedApi = 36
                RamMb = 2048
                CpuCores = 2
                AllowOldEdgeFallback = $false
            }
        }
        "old-edge-normal" {
            return [pscustomobject]@{
                Id = "old-edge-normal"
                RequestedApi = 26
                RamMb = 1536
                CpuCores = 2
                AllowOldEdgeFallback = $true
            }
        }
        "current-low-ram" {
            return [pscustomobject]@{
                Id = "current-low-ram"
                RequestedApi = 36
                RamMb = 1024
                CpuCores = 2
                AllowOldEdgeFallback = $false
            }
        }
        default {
            throw "Unknown repository measurement profile."
        }
    }
}

function Get-MuxTvMeasurementProfileIds {
    [CmdletBinding()]
    param()

    return @(
        "current-normal"
        "old-edge-normal"
        "current-low-ram"
    )
}
