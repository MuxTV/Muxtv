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

function Assert-DoesNotContainLiteral {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Literal,
        [Parameter(Mandatory)][string]$Failure
    )

    if ($Text.IndexOf($Literal, [System.StringComparison]::Ordinal) -ge 0) {
        throw $Failure
    }
}

# Route setup is not measurement evidence. Immutable A/B/C already prove the TV rail with Compose
# performKeyInput, so setup must use one synchronized Compose input seam end-to-end rather than
# mixing framework Left/Down/Enter with a late Compose fallback.
foreach ($literal in @(
    'private fun openRailDestination(',
    'private fun pressSetupKey(tag: String, key: Key)',
    '.assertIsFocused()',
    '.press(key)',
    'pressSetupKey(focus, Key.DirectionLeft)',
    'pressSetupKey(focus, Key.DirectionDown)',
    'pressSetupKey(navTag, Key.Enter)',
    'compose-enter'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe is missing unified Compose route-setup primitive: $literal"
}

# Content editors such as Search can consume DirectionLeft without transferring focus to the rail.
# A non-null content focus owner is therefore not proof that setup reached navigation. After the
# bounded Compose Left attempt, setup must explicitly recover the already-selected rail item before
# walking Down to the next destination.
$editorSafeRailRecoveryPattern = '(?s)pressSetupKey\(focus, Key\.DirectionLeft\).*?focus\s*=\s*focusedNodeDescription\(\).*?if\s*\(!navigationTags\.contains\(focus\)\).*?recoverSelectedRailFocus\(navTag\)'
if (-not [regex]::IsMatch($probe, $editorSafeRailRecoveryPattern)) {
    throw 'Common U0 route setup must recover selected rail focus when Compose Left remains inside a content editor.'
}

# The geometry/focus characterization itself intentionally stays on Android framework input.
# This is the measured seam and must not be conflated with setup-only Compose input.
foreach ($literal in @(
    'private fun pressKey(keyCode: Int)',
    'instrumentation.sendKeyDownUpSync(keyCode)',
    'pressKey(KeyEvent.KEYCODE_DPAD_LEFT)',
    'pressKey(KeyEvent.KEYCODE_BACK)',
    'pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe lost required framework measurement input: $literal"
}

# Route readiness and geometry are separate evidence. A route may share its visible title with a
# rail label (Channels: "Эфир"), so a plain text-exists check can produce a false positive before
# destination content is mounted. Readiness must use route-owned semantics while geometry keeps its
# original measurement anchor.
foreach ($literal in @(
    'private sealed interface ReadinessAnchor',
    'data class Tag(val tag: String) : ReadinessAnchor',
    'data class ContentTitle(val text: String) : ReadinessAnchor',
    'readinessAnchor: ReadinessAnchor',
    'readinessAnchor.isPresent(navTag)',
    'ReadinessAnchor.ContentTitle("Эфир")',
    'ReadinessAnchor.Tag("guide-status")',
    'ReadinessAnchor.Tag("search-input")',
    'ReadinessAnchor.Tag("settings-section-sources")',
    'anchor = Anchor.Title("Эфир")'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe is missing route-owned readiness evidence: $literal"
}

# A's focus-expanded rail and B/C's permanently expanded rail all render the selected Channels
# label while route setup owns rail focus. ChannelsRoute renders the same exact title in every
# loading/error/empty/content state. Therefore the collision is proven structurally by a second
# exact-text semantics node; full rail bounds are not a valid separator because A overlays its
# expanded rail over destination content.
foreach ($literal in @(
    'private fun exactTextNodeCount(text: String): Int',
    'exactTextNodeCount(text) >= 2'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe is missing collision-safe content-title readiness evidence: $literal"
}
Assert-DoesNotContainLiteral $probe 'private fun navigationRailRight()' 'Content-title readiness must not derive route ownership from the expanded rail right edge.'
Assert-DoesNotContainLiteral $probe 'node.boundsInRoot.left >= railRight' 'Content-title readiness must not reject content geometrically overlapped by immutable A rail.'

# A transient Selected=true is not sufficient: activation may succeed only when the selected rail
# item and destination-owned readiness anchor are visible in the same synchronized observation.
foreach ($literal in @(
    'awaitRouteReady',
    'U0 route activation',
    'selected=',
    'readinessPresent=',
    'assertIsSelected()',
    'navIsSelected(navTag) && readinessAnchor.isPresent(navTag)'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe is missing durable route-readiness evidence: $literal"
}

# Framework Enter/Center are setup-only in the current probe. Keeping them would preserve the
# mixed input path that the immutable-A smoke test and current U0 failure have isolated.
foreach ($literal in @(
    'KEYCODE_ENTER',
    'KEYCODE_DPAD_CENTER',
    'framework-enter',
    'framework-dpad-center'
)) {
    Assert-DoesNotContainLiteral $probe $literal "Common U0 probe still mixes framework activation into route setup: $literal"
}

Assert-DoesNotContainLiteral $probe 'Thread.sleep' 'UI characterization probe must not use arbitrary Thread.sleep for route setup.'

Write-Host 'TV UI route setup characterization contract passed: setup uses Compose input, editor-safe rail recovery, route-owned readiness, and framework-driven measured Left/Back/Right.'
