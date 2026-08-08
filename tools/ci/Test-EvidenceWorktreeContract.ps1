[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$assertWorktreeScript = Join-Path $PSScriptRoot "Assert-EvidenceWorktree.ps1"
if (-not (Test-Path -LiteralPath $assertWorktreeScript -PathType Leaf)) {
    throw "Evidence worktree provenance assertion was not found."
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-evidence-worktree-" + [Guid]::NewGuid().ToString("N"))
$fixtureRepository = Join-Path $tempRoot "repository with spaces"
$outsideDirectory = Join-Path $tempRoot "outside caller"
$invalidRepository = Join-Path $tempRoot "not a repository"

function Invoke-GitFixture {
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = @(& git -C $Repository @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Git fixture command failed with exit code ${exitCode}: git -C $Repository $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
}

function Assert-GitFixtureDrift {
    param(
        [Parameter(Mandatory)][string]$Repository,
        [Parameter(Mandatory)][ValidateSet("unstaged", "staged")][string]$Kind
    )

    $arguments = if ($Kind -ceq "staged") {
        @("diff", "--cached", "--name-only", "--no-ext-diff", "--", "tracked.txt")
    } else {
        @("diff", "--name-only", "--no-ext-diff", "--", "tracked.txt")
    }
    $output = @(& git -C $Repository @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Git fixture drift inspection failed with exit code ${exitCode}."
    }
    $paths = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    if ($paths -notcontains "tracked.txt") {
        throw "Git fixture did not materialize expected $Kind tracked drift."
    }
}

function Assert-GuardFailsLike {
    param(
        [Parameter(Mandatory)][string]$ExpectedPrefix,
        [Parameter(Mandatory)][string]$Repository
    )

    $failure = $null
    try {
        & $assertWorktreeScript -RepositoryRoot $Repository
    } catch {
        $failure = [string]$_.Exception.Message
    }

    if ($null -eq $failure) {
        throw "Evidence worktree provenance guard unexpectedly accepted repository state: $ExpectedPrefix"
    }
    if (-not $failure.Contains($ExpectedPrefix, [System.StringComparison]::Ordinal)) {
        throw "Evidence worktree provenance guard failed for an unexpected reason: $failure"
    }
}

try {
    New-Item -ItemType Directory -Force -Path $fixtureRepository, $outsideDirectory, $invalidRepository | Out-Null

    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("init", "--quiet")
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("config", "user.email", "muxtv-ci@example.invalid")
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("config", "user.name", "MuxTV CI")
    Set-Content -LiteralPath (Join-Path $fixtureRepository "tracked.txt") -Value "baseline" -Encoding utf8
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("add", "tracked.txt")
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("commit", "--quiet", "-m", "fixture")

    # Clean tracked state must pass even when both repo and caller paths contain spaces.
    Push-Location $outsideDirectory
    try {
        & $assertWorktreeScript -RepositoryRoot $fixtureRepository
    } finally {
        Pop-Location
    }

    # Untracked evidence is explicitly allowed.
    $untrackedEvidence = Join-Path $fixtureRepository ".work\evidence\fixture\result.json"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $untrackedEvidence) | Out-Null
    Set-Content -LiteralPath $untrackedEvidence -Value "{}" -Encoding utf8
    & $assertWorktreeScript -RepositoryRoot $fixtureRepository

    # Unstaged tracked drift must fail closed. Prove Git sees the fixture drift before invoking the guard.
    Set-Content -LiteralPath (Join-Path $fixtureRepository "tracked.txt") -Value "unstaged drift" -Encoding utf8
    Assert-GitFixtureDrift -Repository $fixtureRepository -Kind "unstaged"
    Assert-GuardFailsLike `
        -Repository $fixtureRepository `
        -ExpectedPrefix "Tracked worktree provenance mismatch: unstaged tracked changes detected."
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("reset", "--hard", "--quiet", "HEAD")

    # Staged tracked drift must also fail closed. Prove the index contains the intended fixture drift first.
    Set-Content -LiteralPath (Join-Path $fixtureRepository "tracked.txt") -Value "staged drift" -Encoding utf8
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("add", "tracked.txt")
    Assert-GitFixtureDrift -Repository $fixtureRepository -Kind "staged"
    Assert-GuardFailsLike `
        -Repository $fixtureRepository `
        -ExpectedPrefix "Tracked worktree provenance mismatch: staged tracked changes detected."
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("reset", "--hard", "--quiet", "HEAD")

    # A non-Git root is a provenance failure, not a clean checkout.
    Assert-GuardFailsLike `
        -Repository $invalidRepository `
        -ExpectedPrefix "Unable to inspect tracked worktree provenance:"

    Write-Host "Evidence tracked-worktree provenance contract passed."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
