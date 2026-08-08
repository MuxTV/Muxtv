[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$ExpectedCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$actualCommit = (& git rev-parse HEAD 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to resolve the checked-out Git commit."
}

if ($actualCommit -cne $ExpectedCommit) {
    throw "Evidence commit provenance mismatch: checked out $actualCommit but expected $ExpectedCommit."
}

Write-Host "Evidence commit provenance verified: $actualCommit"
