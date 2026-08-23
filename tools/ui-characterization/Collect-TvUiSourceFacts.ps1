[CmdletBinding()]
param(
    [string]$OutputPath = '.work/evidence/ui-characterization/source-facts.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$resolvedOutputPath = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath
} else {
    Join-Path $repositoryRoot $OutputPath
}

$refs = @(
    [pscustomobject]@{ Id = 'A'; Commit = '2302c11441c85b8b5752d7f03cc5bc13be8c6d92' },
    [pscustomobject]@{ Id = 'B'; Commit = '515072022d11b218fcb20f43079f94098b3ea973' },
    [pscustomobject]@{ Id = 'C'; Commit = '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9' }
)

$tokenPath = 'core/designsystem/src/main/kotlin/app/muxtv/designsystem/TvTokens.kt'
$navigationPath = 'app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt'
$railPath = 'core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt'

function Get-GitFileText {
    param(
        [Parameter(Mandatory)][string]$Commit,
        [Parameter(Mandatory)][string]$Path
    )

    $spec = '{0}:{1}' -f $Commit, $Path
    $output = @(& git show $spec 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read immutable source $spec.`n$($output -join [Environment]::NewLine)"
    }
    return $output -join "`n"
}

function Get-DpValue {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Name
    )
    $pattern = 'val\s+' + [regex]::Escape($Name) + '(?:\s*:\s*[A-Za-z0-9_.]+)?\s*=\s*([0-9]+(?:\.[0-9]+)?)\.dp'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) { throw "Unable to extract dp token '$Name'." }
    return [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Get-SpValue {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Name
    )
    $pattern = 'val\s+' + [regex]::Escape($Name) + '(?:\s*:\s*[A-Za-z0-9_.]+)?\s*=\s*([0-9]+(?:\.[0-9]+)?)\.sp'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) { throw "Unable to extract sp token '$Name'." }
    return [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
}

Set-Location $repositoryRoot
$facts = [System.Collections.Generic.List[object]]::new()
foreach ($ref in $refs) {
    & git cat-file -e "$($ref.Commit)^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Immutable UI comparison commit is unavailable locally: $($ref.Commit)"
    }

    $tokens = Get-GitFileText -Commit $ref.Commit -Path $tokenPath
    $navigation = Get-GitFileText -Commit $ref.Commit -Path $navigationPath
    $rail = Get-GitFileText -Commit $ref.Commit -Path $railPath

    $reservationMatch = [regex]::Match(
        $navigation,
        'Modifier\.padding\(start\s*=\s*TvTokens\.Size\.(railCollapsed|railExpanded)\)'
    )
    if (-not $reservationMatch.Success) {
        throw "Unable to extract navigation content reservation token for comparison $($ref.Id)."
    }

    $railMode = if (
        $rail.Contains('targetValue = if (railFocused) TvTokens.Size.railExpanded else TvTokens.Size.railCollapsed', [StringComparison]::Ordinal) -and
        $rail.Contains('.width(width)', [StringComparison]::Ordinal)
    ) {
        'transient-focus-expanded'
    } elseif (
        $rail.Contains('.width(TvTokens.Size.railExpanded)', [StringComparison]::Ordinal) -and
        $rail.Contains('expanded = true', [StringComparison]::Ordinal)
    ) {
        'permanent-expanded'
    } else {
        'unknown'
    }

    $labelsMode = if ($rail.Contains('expanded = railFocused', [StringComparison]::Ordinal)) {
        'focus-dependent'
    } elseif ($rail.Contains('expanded = true', [StringComparison]::Ordinal)) {
        'always-visible'
    } else {
        'unknown'
    }

    $facts.Add([pscustomobject]@{
        comparisonId = $ref.Id
        sourceCommit = $ref.Commit
        contentReservationToken = $reservationMatch.Groups[1].Value
        railMode = $railMode
        railLabels = $labelsMode
        railCollapsedDp = Get-DpValue -Text $tokens -Name 'railCollapsed'
        railExpandedDp = Get-DpValue -Text $tokens -Name 'railExpanded'
        focusOutlineDp = Get-DpValue -Text $tokens -Name 'outlineWidth'
        screenInsetDp = Get-DpValue -Text $tokens -Name 'screenInset'
        sectionGapDp = Get-DpValue -Text $tokens -Name 'sectionGap'
        homeCardWidthDp = Get-DpValue -Text $tokens -Name 'homeCardWidth'
        homeCardHeightDp = Get-DpValue -Text $tokens -Name 'homeCardHeight'
        heroTitleSp = Get-SpValue -Text $tokens -Name 'heroTitle'
        sectionTitleSp = Get-SpValue -Text $tokens -Name 'sectionTitle'
        cardTitleSp = Get-SpValue -Text $tokens -Name 'cardTitle'
        metadataSp = Get-SpValue -Text $tokens -Name 'metadata'
    })
}

$byId = @{}
foreach ($fact in $facts) { $byId[$fact.comparisonId] = $fact }
$a = $byId['A']
$b = $byId['B']
$c = $byId['C']

$document = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    sourcePaths = [ordered]@{
        tokens = $tokenPath
        navigation = $navigationPath
        rail = $railPath
    }
    comparisons = @($facts)
    derived = [ordered]@{
        expectedContentOriginShiftDp = [double]$b.railExpandedDp - [double]$a.railCollapsedDp
        aUsesCollapsedReservation = $a.contentReservationToken -ceq 'railCollapsed'
        bUsesExpandedReservation = $b.contentReservationToken -ceq 'railExpanded'
        cUsesExpandedReservation = $c.contentReservationToken -ceq 'railExpanded'
        aUsesTransientRail = $a.railMode -ceq 'transient-focus-expanded'
        bUsesPermanentRail = $b.railMode -ceq 'permanent-expanded'
        cUsesPermanentRail = $c.railMode -ceq 'permanent-expanded'
        aLabelsFocusDependent = $a.railLabels -ceq 'focus-dependent'
        bLabelsAlwaysVisible = $b.railLabels -ceq 'always-visible'
        cLabelsAlwaysVisible = $c.railLabels -ceq 'always-visible'
    }
}

$parent = Split-Path -Parent $resolvedOutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$document | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedOutputPath -Encoding utf8
Write-Host "TV UI immutable source facts: $resolvedOutputPath"
