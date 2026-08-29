[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "Test-PlayerStablePortContractBase.ps1")
& (Join-Path $PSScriptRoot "Test-FeatureAdapterBoundaryContract.ps1")
& (Join-Path $PSScriptRoot "Test-ProcessAsyncOwnershipContract.ps1")
