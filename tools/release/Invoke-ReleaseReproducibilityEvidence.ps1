[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-SingleArtifact {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)][string]$Filter,
        [Parameter(Mandatory)][string]$DisplayName
    )

    $files = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File -Recurse -ErrorAction SilentlyContinue)
    if ($files.Count -ne 1) {
        throw "Expected exactly one $DisplayName artifact; found $($files.Count)."
    }
    return $files[0]
}

function Get-LowerSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ArchiveContentManifest {
    param([Parameter(Mandatory)][string]$ArchivePath)

    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $manifest = [System.Collections.Generic.List[object]]::new()
        $entries = @(
            $archive.Entries |
                Where-Object { -not $_.FullName.EndsWith("/", [System.StringComparison]::Ordinal) } |
                Sort-Object FullName
        )

        foreach ($entry in $entries) {
            $stream = $entry.Open()
            try {
                $hasher = [System.Security.Cryptography.SHA256]::Create()
                try {
                    $entrySha256 = [System.Convert]::ToHexString($hasher.ComputeHash($stream)).ToLowerInvariant()
                }
                finally {
                    $hasher.Dispose()
                }
            }
            finally {
                $stream.Dispose()
            }

            $manifest.Add([pscustomobject][ordered]@{
                path = $entry.FullName
                sizeBytes = [long]$entry.Length
                entrySha256 = $entrySha256
            })
        }

        $graphLines = @($manifest | ForEach-Object { "$($_.path)`t$($_.sizeBytes)`t$($_.entrySha256)" })
        $graphBytes = [System.Text.Encoding]::UTF8.GetBytes(($graphLines -join "`n"))
        $graphHasher = [System.Security.Cryptography.SHA256]::Create()
        try {
            $contentGraphSha256 = [System.Convert]::ToHexString($graphHasher.ComputeHash($graphBytes)).ToLowerInvariant()
        }
        finally {
            $graphHasher.Dispose()
        }

        return [pscustomobject][ordered]@{
            entryCount = $manifest.Count
            contentGraphSha256 = $contentGraphSha256
            entries = @($manifest)
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Get-ArchiveEntryText {
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$EntryPath
    )

    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $entry = $archive.GetEntry($EntryPath)
        if ($null -eq $entry) {
            throw "Archive is missing required provenance entry: $EntryPath"
        }
        $stream = $entry.Open()
        try {
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true)
            try {
                return $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }
        }
        finally {
            $stream.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory "..\..")).Path
$apkDirectory = Join-Path $repositoryRoot "app\tv\build\outputs\apk\release"
$aabDirectory = Join-Path $repositoryRoot "app\tv\build\outputs\bundle\release"
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\release-reproducibility"
$metadataPath = Join-Path $evidenceDirectory "release-reproducibility-evidence.json"

function Invoke-CleanReleaseBuild {
    param(
        [Parameter(Mandatory)][ValidateSet("build1", "build2")][string]$BuildLabel
    )

    Write-Host "Starting reproducibility $BuildLabel for exact source $SourceCommit."
    & .\gradlew.bat :app:tv:clean :app:tv:assembleRelease :app:tv:bundleRelease `
        --no-build-cache `
        --rerun-tasks `
        --no-daemon `
        --stacktrace `
        --console=plain `
        --no-problems-report
    if ($LASTEXITCODE -ne 0) {
        throw "Exact-head release $BuildLabel failed."
    }

    $apk = Get-SingleArtifact -Directory $apkDirectory -Filter "*.apk" -DisplayName "$BuildLabel release APK"
    $aab = Get-SingleArtifact -Directory $aabDirectory -Filter "*.aab" -DisplayName "$BuildLabel release AAB"

    $buildDirectory = Join-Path $evidenceDirectory $BuildLabel
    New-Item -ItemType Directory -Force -Path $buildDirectory | Out-Null
    $savedApk = Join-Path $buildDirectory "release.apk"
    $savedAab = Join-Path $buildDirectory "release.aab"
    Copy-Item -LiteralPath $apk.FullName -Destination $savedApk -Force
    Copy-Item -LiteralPath $aab.FullName -Destination $savedAab -Force

    $trackedChanges = @(& git status --porcelain=v1 --untracked-files=no 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to verify tracked workspace state after $BuildLabel."
    }
    if ($trackedChanges.Count -gt 0) {
        throw "Release $BuildLabel modified tracked repository files; reproducibility evidence is invalid."
    }

    return [pscustomobject][ordered]@{
        label = $BuildLabel
        apkPath = $savedApk
        aabPath = $savedAab
    }
}

Push-Location $repositoryRoot
try {
    & .\tools\ci\Assert-EvidenceCommit.ps1 -ExpectedCommit $SourceCommit

    Remove-Item -LiteralPath $evidenceDirectory -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

    $build1 = Invoke-CleanReleaseBuild -BuildLabel "build1"
    $build2 = Invoke-CleanReleaseBuild -BuildLabel "build2"

    $apk1Sha256 = Get-LowerSha256 -Path $build1.apkPath
    $apk2Sha256 = Get-LowerSha256 -Path $build2.apkPath
    $aab1Sha256 = Get-LowerSha256 -Path $build1.aabPath
    $aab2Sha256 = Get-LowerSha256 -Path $build2.aabPath

    $apk1Manifest = Get-ArchiveContentManifest -ArchivePath $build1.apkPath
    $apk2Manifest = Get-ArchiveContentManifest -ArchivePath $build2.apkPath
    $aab1Manifest = Get-ArchiveContentManifest -ArchivePath $build1.aabPath
    $aab2Manifest = Get-ArchiveContentManifest -ArchivePath $build2.aabPath

    $apkContentGraphIdentical = (
        $apk1Manifest.entryCount -eq $apk2Manifest.entryCount -and
        $apk1Manifest.contentGraphSha256 -ceq $apk2Manifest.contentGraphSha256
    )
    $aabContentGraphIdentical = (
        $aab1Manifest.entryCount -eq $aab2Manifest.entryCount -and
        $aab1Manifest.contentGraphSha256 -ceq $aab2Manifest.contentGraphSha256
    )
    $contentGraphIdentical = $apkContentGraphIdentical -and $aabContentGraphIdentical
    if (-not $contentGraphIdentical) {
        throw "Two clean exact-head release builds produced different APK/AAB archive content graphs."
    }

    $apkRawByteIdentical = $apk1Sha256 -ceq $apk2Sha256
    $aabRawByteIdentical = $aab1Sha256 -ceq $aab2Sha256
    $rawByteIdentical = $apkRawByteIdentical -and $aabRawByteIdentical

    $apkVcsPath = "META-INF/version-control-info.textproto"
    $aabVcsPath = "base/root/META-INF/version-control-info.textproto"
    foreach ($artifact in @(
        @{ Path = $build1.apkPath; Entry = $apkVcsPath; Label = "build1 APK" },
        @{ Path = $build2.apkPath; Entry = $apkVcsPath; Label = "build2 APK" },
        @{ Path = $build1.aabPath; Entry = $aabVcsPath; Label = "build1 AAB" },
        @{ Path = $build2.aabPath; Entry = $aabVcsPath; Label = "build2 AAB" }
    )) {
        $vcsText = Get-ArchiveEntryText -ArchivePath $artifact.Path -EntryPath $artifact.Entry
        if (-not $vcsText.Contains($SourceCommit, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "$($artifact.Label) does not embed the exact source commit in version-control-info.textproto."
        }
    }

    $apk1Manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceDirectory "apk-build1-content-manifest.json") -Encoding utf8
    $apk2Manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceDirectory "apk-build2-content-manifest.json") -Encoding utf8
    $aab1Manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceDirectory "aab-build1-content-manifest.json") -Encoding utf8
    $aab2Manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceDirectory "aab-build2-content-manifest.json") -Encoding utf8

    $metadata = [ordered]@{
        schemaVersion = 1
        repository = "MuxTV/Muxtv"
        sourceCommit = $SourceCommit
        buildPolicy = [ordered]@{
            buildCacheEnabled = $false
            rerunTasks = $true
            appCleanBeforeEachBuild = $true
        }
        apk = [ordered]@{
            build1Sha256 = $apk1Sha256
            build2Sha256 = $apk2Sha256
            build1ContentGraphSha256 = $apk1Manifest.contentGraphSha256
            build2ContentGraphSha256 = $apk2Manifest.contentGraphSha256
            entryCount = $apk1Manifest.entryCount
            contentGraphIdentical = $apkContentGraphIdentical
            rawByteIdentical = $apkRawByteIdentical
            containerOnlyDifference = ($apkContentGraphIdentical -and -not $apkRawByteIdentical)
        }
        aab = [ordered]@{
            build1Sha256 = $aab1Sha256
            build2Sha256 = $aab2Sha256
            build1ContentGraphSha256 = $aab1Manifest.contentGraphSha256
            build2ContentGraphSha256 = $aab2Manifest.contentGraphSha256
            entryCount = $aab1Manifest.entryCount
            contentGraphIdentical = $aabContentGraphIdentical
            rawByteIdentical = $aabRawByteIdentical
            containerOnlyDifference = ($aabContentGraphIdentical -and -not $aabRawByteIdentical)
        }
        contentGraphIdentical = $contentGraphIdentical
        rawByteIdentical = $rawByteIdentical
        embeddedRevisionVerified = $true
        provenanceEntry = "version-control-info.textproto"
    }
    $metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $metadataPath -Encoding utf8

    Write-Host "Release reproducibility evidence generated."
    Write-Host "sourceCommit=$SourceCommit"
    Write-Host "apkBuild1Sha256=$apk1Sha256"
    Write-Host "apkBuild2Sha256=$apk2Sha256"
    Write-Host "aabBuild1Sha256=$aab1Sha256"
    Write-Host "aabBuild2Sha256=$aab2Sha256"
    Write-Host "apkContentGraphSha256=$($apk1Manifest.contentGraphSha256)"
    Write-Host "aabContentGraphSha256=$($aab1Manifest.contentGraphSha256)"
    Write-Host "contentGraphIdentical=$contentGraphIdentical"
    Write-Host "rawByteIdentical=$rawByteIdentical"
    Write-Host "metadata=.work/evidence/release-reproducibility/release-reproducibility-evidence.json"
    exit 0
}
finally {
    Pop-Location
}
