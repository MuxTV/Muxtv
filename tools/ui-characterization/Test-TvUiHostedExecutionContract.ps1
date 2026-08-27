[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$workflowPath = Join-Path $repositoryRoot '.github/workflows/tv-ui-characterization-device.yml'
$entrypointPath = Join-Path $repositoryRoot 'tools/ui-characterization/Run-HostedTvUiCharacterization.sh'

if (-not (Test-Path -LiteralPath $workflowPath -PathType Leaf)) { throw "Hosted U0 workflow is missing: $workflowPath" }
if (-not (Test-Path -LiteralPath $entrypointPath -PathType Leaf)) { throw "Hosted U0 entrypoint is missing: $entrypointPath" }

$workflow = Get-Content -LiteralPath $workflowPath -Raw -Encoding utf8
$entrypoint = Get-Content -LiteralPath $entrypointPath -Raw -Encoding utf8

function Require-Token {
    param([string]$Text, [string]$Token, [string]$Owner)
    if ($Text.IndexOf($Token, [System.StringComparison]::Ordinal) -lt 0) { throw "$Owner is missing required hosted U0 token: $Token" }
}
function Reject-Token {
    param([string]$Text, [string]$Token, [string]$Owner)
    if ($Text.IndexOf($Token, [System.StringComparison]::Ordinal) -ge 0) { throw "$Owner still contains removed self-hosted U0 ownership token: $Token" }
}

foreach ($token in @(
    'runs-on: ubuntu-latest',
    'uses: ./.github/actions/setup-muxtv-jdks',
    'run: bash ./tools/ci/Enable-HostedAndroidKvm.sh',
    'ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d',
    'api-level: 36',
    'target: android-tv',
    'arch: x86_64',
    'profile: tv_1080p',
    'avd-name: MuxTV_TV_CURRENT_API36',
    'script: bash ./tools/ui-characterization/Run-HostedTvUiCharacterization.sh',
    'fetch-depth: 0',
    'retention-days: 7'
)) { Require-Token -Text $workflow -Token $token -Owner 'TV UI characterization device workflow' }

foreach ($token in @(
    'self-hosted', 'Assert-SelfHostedRunnerPreflight.ps1', 'Reset-SelfHostedAndroidState.ps1',
    'New-TvAvd', 'Start-TvEmulator', '.github/ui-characterization/run.request'
)) { Reject-Token -Text $workflow -Token $token -Owner 'TV UI characterization device workflow' }

foreach ($token in @(
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    '2302c11441c85b8b5752d7f03cc5bc13be8c6d92',
    '515072022d11b218fcb20f43079f94098b3ea973',
    '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9',
    'MuxTV_TV_CURRENT_API36',
    '1080p-tv:representative-1080p:1920x1080:1920:1080:320:true',
    '720p-tv:representative-720p-tv:1280x720:1280:720:213:true',
    'compact-stress:compact-stress:1280x720:1280:720:320:false',
    'UiCharacterizationProbeTest.kt',
    'git worktree add --detach',
    'wm size reset',
    'wm density reset',
    'connectedDebugAndroidTest',
    'adb -s "$ANDROID_SERIAL" pull'
)) { Require-Token -Text $entrypoint -Token $token -Owner 'Hosted U0 entrypoint' }

foreach ($token in @('avdmanager create avd', 'emulator @', 'sdkmanager --install')) {
    Reject-Token -Text $entrypoint -Token $token -Owner 'Hosted U0 entrypoint'
}

Write-Host 'Hosted U0 execution contract passed: external API36 emulator ownership and immutable A/B/C characterization are explicit.'
