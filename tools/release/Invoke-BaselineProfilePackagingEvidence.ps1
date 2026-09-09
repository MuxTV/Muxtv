[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ZipEntrySha256 {
    param(
        [Parameter(Mandatory)]
        $Entry
    )

    $stream = $Entry.Open()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha.ComputeHash($stream)
        return ([System.Convert]::ToHexString($digest)).ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
        $stream.Dispose()
    }
}

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory "..\..")).Path
$sourceProfileRelative = "app\tv\src\release\generated\baselineProfiles\baseline-prof.txt"
$sourceProfilePath = Join-Path $repositoryRoot $sourceProfileRelative
$apkDirectory = Join-Path $repositoryRoot "app\tv\build\outputs\apk\release"
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\release-baseline-profile"
$metadataPath = Join-Path $evidenceDirectory "baseline-profile-packaging.json"
$compiledProfileLimitBytes = 1572864

Push-Location $repositoryRoot
try {
    & .\tools\ci\Assert-EvidenceCommit.ps1 -ExpectedCommit $SourceCommit

    if (-not (Test-Path -LiteralPath $sourceProfilePath -PathType Leaf)) {
        throw "Committed release Baseline Profile is missing."
    }

    $sourceProfile = Get-Item -LiteralPath $sourceProfilePath
    if ($sourceProfile.Length -le 0) {
        throw "Committed release Baseline Profile is empty."
    }

    & git ls-files --error-unmatch -- $sourceProfileRelative 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Release Baseline Profile must be tracked by Git before packaging evidence is collected."
    }

    $sourceProfileSha256 = (Get-FileHash -LiteralPath $sourceProfilePath -Algorithm SHA256).Hash.ToLowerInvariant()

    Remove-Item -LiteralPath $apkDirectory -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $evidenceDirectory -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

    & .\gradlew.bat :app:tv:assembleRelease `
        --no-daemon `
        --stacktrace `
        --console=plain `
        --no-problems-report
    if ($LASTEXITCODE -ne 0) {
        throw "Release APK assembly failed while collecting Baseline Profile packaging evidence."
    }

    $sourceProfileSha256AfterBuild = (Get-FileHash -LiteralPath $sourceProfilePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceProfileSha256AfterBuild -cne $sourceProfileSha256) {
        throw "Release assembly mutated the committed Baseline Profile input."
    }

    $profileDirty = (& git status --porcelain=v1 -- $sourceProfileRelative 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to verify Baseline Profile source cleanliness after release assembly."
    }
    if ($profileDirty) {
        throw "Release assembly left the committed Baseline Profile input dirty."
    }

    $apks = @(Get-ChildItem -LiteralPath $apkDirectory -Filter "*.apk" -File -Recurse)
    if ($apks.Count -ne 1) {
        throw "Expected exactly one release APK for Baseline Profile packaging evidence; found $($apks.Count)."
    }

    $apk = $apks[0]
    $apkSha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $apkRelativePath = [System.IO.Path]::GetRelativePath($repositoryRoot, $apk.FullName).Replace('\', '/')

    $archive = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try {
        $profileEntry = $archive.GetEntry("assets/dexopt/baseline.prof")
        if ($null -eq $profileEntry -or $profileEntry.Length -le 0) {
            throw "Release APK is missing a non-empty assets/dexopt/baseline.prof."
        }

        $profileMetadataEntry = $archive.GetEntry("assets/dexopt/baseline.profm")
        if ($null -eq $profileMetadataEntry -or $profileMetadataEntry.Length -le 0) {
            throw "Release APK is missing a non-empty assets/dexopt/baseline.profm."
        }

        if ($profileEntry.Length -ge $compiledProfileLimitBytes) {
            throw "Compiled Baseline Profile exceeds the supported release size limit."
        }

        $packagedProfileSizeBytes = $profileEntry.Length
        $packagedProfileMetadataSizeBytes = $profileMetadataEntry.Length
        $packagedProfileSha256 = Get-ZipEntrySha256 -Entry $profileEntry
        $packagedProfileMetadataSha256 = Get-ZipEntrySha256 -Entry $profileMetadataEntry
    }
    finally {
        $archive.Dispose()
    }

    $metadata = [ordered]@{
        schemaVersion = 1
        repository = "MuxTV/Muxtv"
        sourceCommit = $SourceCommit
        sourceProfilePath = $sourceProfileRelative.Replace('\', '/')
        sourceProfileSizeBytes = $sourceProfile.Length
        sourceProfileSha256 = $sourceProfileSha256
        apkPath = $apkRelativePath
        apkSizeBytes = $apk.Length
        apkSha256 = $apkSha256
        packagedProfilePath = "assets/dexopt/baseline.prof"
        packagedProfileSizeBytes = $packagedProfileSizeBytes
        packagedProfileSha256 = $packagedProfileSha256
        packagedProfileMetadataPath = "assets/dexopt/baseline.profm"
        packagedProfileMetadataSizeBytes = $packagedProfileMetadataSizeBytes
        packagedProfileMetadataSha256 = $packagedProfileMetadataSha256
        compiledProfileSizeLimitBytes = $compiledProfileLimitBytes
    }

    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding utf8

    Write-Host "Baseline Profile packaging evidence generated."
    Write-Host "sourceCommit=$SourceCommit"
    Write-Host "sourceProfileSha256=$sourceProfileSha256"
    Write-Host "apkSha256=$apkSha256"
    Write-Host "packagedProfileSha256=$packagedProfileSha256"
    Write-Host "packagedProfileMetadataSha256=$packagedProfileMetadataSha256"
    Write-Host "packagedProfileSizeBytes=$packagedProfileSizeBytes"
    Write-Host "metadata=.work/evidence/release-baseline-profile/baseline-profile-packaging.json"
}
finally {
    Pop-Location
}
