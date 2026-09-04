[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [ValidatePattern('^$|^[0-9a-fA-F]{64}$')]
    [string]$ExpectedSignerCertificateSha256 = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

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

function Get-RelativeRepositoryPath {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$FullName
    )

    return [System.IO.Path]::GetRelativePath($RepositoryRoot, $FullName).Replace('\', '/')
}

function Resolve-ApkSigner {
    param([Parameter(Mandatory)][string]$SdkRoot)

    $buildToolsRoot = Join-Path $SdkRoot "build-tools"
    if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) {
        throw "Android build-tools are unavailable; apksigner cannot be resolved."
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
            ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
    )
    if ($candidates.Count -lt 1) {
        throw "Android build-tools do not contain apksigner."
    }
    return $candidates[0]
}

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory "..\..")).Path
$applicationBuildPath = Join-Path $repositoryRoot "app\tv\build.gradle.kts"
$apkDirectory = Join-Path $repositoryRoot "app\tv\build\outputs\apk\release"
$aabDirectory = Join-Path $repositoryRoot "app\tv\build\outputs\bundle\release"
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\release-artifact-provenance"
$dependencyReportPath = Join-Path $evidenceDirectory "release-runtime-classpath.txt"
$metadataPath = Join-Path $evidenceDirectory "release-artifact-provenance.json"

Push-Location $repositoryRoot
try {
    & .\tools\ci\Assert-EvidenceCommit.ps1 -ExpectedCommit $SourceCommit

    if (-not (Test-Path -LiteralPath $applicationBuildPath -PathType Leaf)) {
        throw "Application release build configuration is missing."
    }
    $applicationBuild = Get-Content -LiteralPath $applicationBuildPath -Raw
    foreach ($forbiddenDebugSigning in @(
        'signingConfigs.getByName("debug")',
        'signingConfig = signingConfigs.debug'
    )) {
        if ($applicationBuild.Contains($forbiddenDebugSigning)) {
            throw "Release build configuration attempts to substitute debug signing; refusing provenance collection."
        }
    }

    Remove-Item -LiteralPath $apkDirectory -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $aabDirectory -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $evidenceDirectory -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

    & .\gradlew.bat :app:tv:assembleRelease :app:tv:bundleRelease `
        --no-daemon `
        --stacktrace `
        --console=plain `
        --no-problems-report
    if ($LASTEXITCODE -ne 0) {
        throw "Release APK/AAB build failed while collecting provenance evidence."
    }

    $dependencyOutput = @(
        & .\gradlew.bat :app:tv:dependencies `
            --configuration releaseRuntimeClasspath `
            --no-daemon `
            --console=plain `
            --no-problems-report 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Resolved releaseRuntimeClasspath dependency report generation failed."
    }
    if ($dependencyOutput.Count -lt 1 -or -not (($dependencyOutput -join "`n").Contains("releaseRuntimeClasspath"))) {
        throw "Resolved releaseRuntimeClasspath dependency report is empty or malformed."
    }
    $dependencyOutput | Set-Content -LiteralPath $dependencyReportPath -Encoding utf8

    $apk = Get-SingleArtifact -Directory $apkDirectory -Filter "*.apk" -DisplayName "release APK"
    $aab = Get-SingleArtifact -Directory $aabDirectory -Filter "*.aab" -DisplayName "release AAB"

    $apkSha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $aabSha256 = (Get-FileHash -LiteralPath $aab.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $apkSizeBytes = $apk.Length
    $aabSizeBytes = $aab.Length
    $apkRelativePath = Get-RelativeRepositoryPath -RepositoryRoot $repositoryRoot -FullName $apk.FullName
    $aabRelativePath = Get-RelativeRepositoryPath -RepositoryRoot $repositoryRoot -FullName $aab.FullName
    $dependencyReportRelativePath = Get-RelativeRepositoryPath -RepositoryRoot $repositoryRoot -FullName $dependencyReportPath

    . .\tools\android\AndroidSdk.ps1
    $sdkRoot = Get-AndroidSdkRoot
    $apksigner = Resolve-ApkSigner -SdkRoot $sdkRoot

    $signerOutput = @(& $apksigner verify --print-certs $apk.FullName 2>&1)
    $signerExitCode = $LASTEXITCODE
    $signingStatus = "UNSIGNED"
    $signingGateStatus = "PENDING"
    $signerCertificateSha256 = $null
    $signerIdentity = $null

    if ($signerExitCode -eq 0) {
        $signingStatus = "SIGNED"
        $signerText = $signerOutput -join "`n"
        $digestMatch = [regex]::Match(
            $signerText,
            'certificate SHA-256 digest:\s*(?<digest>[0-9a-fA-F]{64})',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if (-not $digestMatch.Success) {
            throw "Release APK is signed, but apksigner did not expose a certificate SHA-256 digest."
        }
        $signerCertificateSha256 = $digestMatch.Groups['digest'].Value.ToLowerInvariant()

        $dnMatch = [regex]::Match(
            $signerText,
            'Signer #\d+ certificate DN:\s*(?<dn>[^\r\n]+)',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if ($dnMatch.Success) {
            $signerIdentity = $dnMatch.Groups['dn'].Value.Trim()
            if ($signerIdentity -match '(?i)CN\s*=\s*Android Debug') {
                throw "Release artifact is signed with an Android debug certificate; refusing release provenance."
            }
        }

        if (-not [string]::IsNullOrWhiteSpace($ExpectedSignerCertificateSha256)) {
            $expectedSigner = $ExpectedSignerCertificateSha256.ToLowerInvariant()
            if ($signerCertificateSha256 -cne $expectedSigner) {
                throw "Release signer certificate fingerprint does not match the expected release signer."
            }
            $signingGateStatus = "PASSED"
        }
    }

    $metadata = [ordered]@{
        schemaVersion = 1
        repository = "MuxTV/Muxtv"
        sourceCommit = $SourceCommit
        apkPath = $apkRelativePath
        apkSizeBytes = $apkSizeBytes
        apkSha256 = $apkSha256
        aabPath = $aabRelativePath
        aabSizeBytes = $aabSizeBytes
        aabSha256 = $aabSha256
        dependencyConfiguration = "releaseRuntimeClasspath"
        dependencyReportPath = $dependencyReportRelativePath
        signingStatus = $signingStatus
        signingGateStatus = $signingGateStatus
        signerEvidenceArtifact = $apkRelativePath
        signerCertificateSha256 = $signerCertificateSha256
        signerIdentity = $signerIdentity
    }
    $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding utf8

    Write-Host "Release artifact provenance evidence generated."
    Write-Host "sourceCommit=$SourceCommit"
    Write-Host "apkSha256=$apkSha256"
    Write-Host "aabSha256=$aabSha256"
    Write-Host "apkSizeBytes=$apkSizeBytes"
    Write-Host "aabSizeBytes=$aabSizeBytes"
    Write-Host "dependencyReportPath=$dependencyReportRelativePath"
    Write-Host "signingStatus=$signingStatus"
    Write-Host "signingGateStatus=$signingGateStatus"
    Write-Host "metadata=.work/evidence/release-artifact-provenance/release-artifact-provenance.json"
}
finally {
    Pop-Location
}
