Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-TvUiDeviceAdmissionState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$TriggerHead,
        [Parameter(Mandatory)][string]$ParentHead,
        [AllowEmptyString()][string]$MarkerContent = '',
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$ChangedPaths,
        [Parameter(Mandatory)][string]$MarkerPath
    )

    $normalizedMarkerPath = $MarkerPath.Trim().Replace('\', '/')
    $normalizedChangedPaths = @(
        $ChangedPaths |
            ForEach-Object { ([string]$_).Trim().Replace('\', '/') } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    $validatedParent = $MarkerContent.Trim()

    $reason = 'admitted'
    $allowed = $true

    if ($TriggerHead -notmatch '^[0-9a-f]{40}$') {
        $allowed = $false
        $reason = 'invalid-trigger-sha'
    } elseif ($ParentHead -notmatch '^[0-9a-f]{40}$') {
        $allowed = $false
        $reason = 'invalid-parent-sha'
    } elseif ($validatedParent -notmatch '^[0-9a-f]{40}$') {
        $allowed = $false
        $reason = 'invalid-marker-sha'
    } elseif ($ParentHead -cne $validatedParent) {
        $allowed = $false
        $reason = 'parent-marker-mismatch'
    } elseif ($normalizedChangedPaths.Count -ne 1 -or $normalizedChangedPaths[0] -cne $normalizedMarkerPath) {
        $allowed = $false
        $reason = 'not-marker-only'
    }

    return [pscustomobject]@{
        Allowed = $allowed
        Reason = $reason
        TriggerHead = $TriggerHead
        ParentHead = $ParentHead
        ValidatedCompiledParent = $validatedParent
        MarkerPath = $normalizedMarkerPath
        TriggerOnly = $allowed
        ChangedPaths = @($normalizedChangedPaths)
    }
}
