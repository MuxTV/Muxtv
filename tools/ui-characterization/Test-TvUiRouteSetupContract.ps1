[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$probePath = Join-Path $PSScriptRoot 'probe\UiCharacterizationProbeTest.kt'
if (-not (Test-Path -LiteralPath $probePath -PathType Leaf)) {
    throw "Missing common UI characterization probe: $probePath"
}

$probe = Get-Content -LiteralPath $probePath -Raw -Encoding utf8

function Assert-ContainsLiteral {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Literal,
        [Parameter(Mandatory)][string]$Failure
    )

    if ($Text.IndexOf($Literal, [System.StringComparison]::Ordinal) -lt 0) {
        throw $Failure
    }
}

# Route setup is not geometry evidence. It must prove that the requested destination became
# selected before the probe resolves a content anchor. The three activation paths deliberately
# distinguish framework key dispatch from the immutable-A Compose key path that is already known
# to open a rail destination successfully.
foreach ($literal in @(
    'assertIsSelected()',
    'KEYCODE_DPAD_CENTER',
    'performKeyInput',
    'framework-enter',
    'framework-dpad-center',
    'compose-enter',
    'routeActivation'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe is missing route-setup characterization primitive: $literal"
}

# A transient Selected=true is not sufficient: the previous GREEN candidate observed it while the
# content tree was still Home. Activation may succeed only after both the selected rail item and
# the destination-owned anchor are visible in the same synchronized observation.
foreach ($literal in @(
    'awaitRouteReady',
    'routeAnchor.isPresent()',
    'U0 route activation',
    'selected=',
    'anchorPresent='
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe is missing durable route-readiness evidence: $literal"
}

# A title can remain a geometry anchor for immutable historical refs, but it must never again be
# the sole evidence that navigation happened.
Assert-ContainsLiteral $probe 'navIsSelected(navTag) && routeAnchor.isPresent()' 'Route readiness must couple selected-navigation state with destination content presence.'

Write-Host 'TV UI route setup characterization contract passed.'
