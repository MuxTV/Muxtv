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
$assertWorktreeContent = Get-Content -LiteralPath $assertWorktreeScript -Raw -Encoding utf8
foreach ($diagnostic in @(
    "Tracked worktree provenance mismatch: unstaged tracked changes detected.",
    "Tracked worktree provenance mismatch: staged tracked changes detected.",
    "Unable to inspect tracked worktree provenance."
)) {
    if ($assertWorktreeContent.IndexOf($diagnostic, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Evidence worktree provenance helper is missing stable diagnostic: $diagnostic"
    }
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

function Assert-GuardFails {
    param(
        [Parameter(Mandatory)][string]$Case,
        [Parameter(Mandatory)][string]$Repository
    )

    $failed = $false
    try {
        & $assertWorktreeScript -RepositoryRoot $Repository
    } catch {
        $failed = $true
    }

    if (-not $failed) {
        throw "Evidence worktree provenance guard unexpectedly accepted repository state: $Case"
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
    Assert-GuardFails -Repository $fixtureRepository -Case "unstaged tracked drift"
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("reset", "--hard", "--quiet", "HEAD")

    # Staged tracked drift must also fail closed. Prove the index contains the intended fixture drift first.
    Set-Content -LiteralPath (Join-Path $fixtureRepository "tracked.txt") -Value "staged drift" -Encoding utf8
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("add", "tracked.txt")
    Assert-GitFixtureDrift -Repository $fixtureRepository -Kind "staged"
    Assert-GuardFails -Repository $fixtureRepository -Case "staged tracked drift"
    Invoke-GitFixture -Repository $fixtureRepository -Arguments @("reset", "--hard", "--quiet", "HEAD")

    # A non-Git root is a provenance failure, not a clean checkout.
    Assert-GuardFails -Repository $invalidRepository -Case "non-Git root"

    Write-Host "Evidence tracked-worktree provenance contract passed."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
