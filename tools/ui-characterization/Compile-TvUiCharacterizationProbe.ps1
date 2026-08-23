[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('A', 'B', 'C')]
    [string]$ComparisonId,

    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$Commit,

    [string]$WorktreeRoot = $env:RUNNER_TEMP
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$probe = (Resolve-Path (Join-Path $PSScriptRoot 'probe\UiCharacterizationProbeTest.kt')).Path
$probeHash = (Get-FileHash -LiteralPath $probe -Algorithm SHA256).Hash.ToLowerInvariant()
$targetRelativePath = 'app\tv\src\androidTest\kotlin\app\muxtv\UiCharacterizationProbeTest.kt'
$resolvedWorktreeRoot = if ([string]::IsNullOrWhiteSpace($WorktreeRoot)) {
    Join-Path $repositoryRoot '.work\ui-characterization\compile'
} else {
    $WorktreeRoot
}
$worktreePath = Join-Path $resolvedWorktreeRoot "muxtv-ui-compile-$ComparisonId"

function Invoke-CheckedGit {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = @(& git @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $exitCode.`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

Set-Location $repositoryRoot
Invoke-CheckedGit -Arguments @('config', '--local', 'core.longpaths', 'true') | Out-Null

& git cat-file -e "$Commit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Comparison commit is unavailable locally: $Commit. Checkout with fetch-depth: 0."
}

try {
    if (Test-Path -LiteralPath $worktreePath) {
        Invoke-CheckedGit -Arguments @('worktree', 'remove', '--force', $worktreePath) | Out-Null
    }

    Invoke-CheckedGit -Arguments @('worktree', 'add', '--detach', $worktreePath, $Commit) | Out-Null

    $actual = (& git -C $worktreePath rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -cne $Commit) {
        throw "Comparison $ComparisonId provenance mismatch: expected $Commit, got $actual"
    }

    $target = Join-Path $worktreePath $targetRelativePath
    Copy-Item -LiteralPath $probe -Destination $target -Force
    $targetHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($targetHash -cne $probeHash) {
        throw "Comparison $ComparisonId did not receive the byte-identical characterization probe."
    }

    Push-Location $worktreePath
    try {
        & .\gradlew.bat `
            :app:tv:compileDebugAndroidTestKotlin `
            --no-daemon `
            --console=plain `
            --stacktrace `
            --warning-mode all `
            --no-problems-report
        if ($LASTEXITCODE -ne 0) {
            throw "Common characterization probe failed to compile on comparison $ComparisonId ($Commit)."
        }
    } finally {
        Pop-Location
    }

    [ordered]@{
        schemaVersion = 1
        comparisonId = $ComparisonId
        sourceCommit = $Commit
        probeSha256 = $probeHash
        status = 'passed'
    } | ConvertTo-Json -Depth 3 | Write-Host
} finally {
    if (Test-Path -LiteralPath $worktreePath) {
        try {
            Invoke-CheckedGit -Arguments @('worktree', 'remove', '--force', $worktreePath) | Out-Null
        } catch {
            Write-Warning $_.Exception.Message
        }
    }
    & git worktree prune 2>$null
}
