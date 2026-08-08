[CmdletBinding()]
param(
    [Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$DeviceLines
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$deviceRows = @(
    $DeviceLines |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object {
            $_ -and
            $_ -notmatch '^List of devices attached' -and
            $_ -notmatch '^\* daemon '
        }
)

if ($deviceRows.Count -ne 0) {
    throw "Self-hosted runner preflight failed: an unexpected Android device is connected."
}
