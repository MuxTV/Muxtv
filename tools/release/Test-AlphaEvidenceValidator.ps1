[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$validatorPath = Join-Path $PSScriptRoot "Validate-AlphaEvidenceManifest.ps1"
$schemaPath = Join-Path $repositoryRoot "docs\release\schemas\alpha-evidence-manifest.schema.json"
$gateCatalogPath = Join-Path $repositoryRoot "docs\release\alpha-gates-v1.json"

if (-not (Test-Path $validatorPath -PathType Leaf)) {
    throw "Alpha evidence validator entry point is missing."
}
if (-not (Test-Path $schemaPath -PathType Leaf)) {
    throw "Alpha evidence schema is missing."
}
if (-not (Test-Path $gateCatalogPath -PathType Leaf)) {
    throw "Alpha gate catalog is missing."
}

$gateCatalog = Get-Content -LiteralPath $gateCatalogPath -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-alpha-validator-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null

$passed = 0
$failures = [System.Collections.Generic.List[string]]::new()
$canonicalCommit = "1111111111111111111111111111111111111111"
$otherCommit = "2222222222222222222222222222222222222222"
$canonicalSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

function New-EligibleManifest {
    $gates = [ordered]@{}
    foreach ($gateDefinition in @($gateCatalog.gates)) {
        $id = [string]$gateDefinition.id
        if ([bool]$gateDefinition.requiredByDefault) {
            $gates[$id] = [ordered]@{
                required = $true
                status = "PASSED"
                evidenceCommit = $canonicalCommit
                evidence = @(
                    [ordered]@{
                        kind = "WORKFLOW_RUN_ID"
                        value = "123456"
                    }
                )
            }
        } else {
            $gates[$id] = [ordered]@{
                required = $false
                status = "PENDING"
                evidence = @()
            }
        }
    }

    return [ordered]@{
        schemaVersion = 1
        repository = "MuxTV/Muxtv"
        commit = $canonicalCommit
        sourceRef = "release/0.1.0-alpha"
        releaseVersion = "0.1.0-alpha"
        generatedAtUtc = "2026-09-02T00:00:00Z"
        claimEligible = $true
        gates = $gates
        artifacts = @(
            [ordered]@{
                kind = "APK"
                name = "muxtv-0.1.0-alpha.apk"
                sha256 = $canonicalSha256
                byteCount = 1024
                sourceCommit = $canonicalCommit
            }
        )
        knownLimitations = @("Validation fixture; no compatibility claim.")
    }
}

function Write-FixtureManifest {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Manifest
    )

    $path = Join-Path $tempRoot ($Name + ".json")
    $Manifest | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $path -Encoding utf8NoBOM
    return $path
}

function Invoke-Validator {
    param([Parameter(Mandatory)][string]$ManifestPath)

    & $validatorPath `
        -ManifestPath $ManifestPath `
        -SchemaPath $schemaPath `
        -GateCatalogPath $gateCatalogPath
}

function Assert-ValidatorSuccess {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Manifest
    )

    try {
        $path = Write-FixtureManifest -Name $Name -Manifest $Manifest
        $null = Invoke-Validator -ManifestPath $path
        $script:passed += 1
    } catch {
        $script:failures.Add("$Name expected success but failed: $($_.Exception.Message)")
    }
}

function Assert-ValidatorFailure {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Manifest,
        [string]$ForbiddenEcho
    )

    try {
        $path = Write-FixtureManifest -Name $Name -Manifest $Manifest
    } catch {
        $script:failures.Add("$Name fixture setup failed: $($_.Exception.Message)")
        return
    }

    try {
        $null = Invoke-Validator -ManifestPath $path
        $script:failures.Add("$Name expected failure but validator accepted the manifest.")
    } catch {
        if ($ForbiddenEcho -and $_.Exception.Message.Contains($ForbiddenEcho, [StringComparison]::Ordinal)) {
            $script:failures.Add("$Name leaked a forbidden value in the diagnostic.")
        } else {
            $script:passed += 1
        }
    }
}

try {
    Assert-ValidatorSuccess -Name "eligible" -Manifest (New-EligibleManifest)

    $safeReferences = New-EligibleManifest
    $safeReferences.gates["security.redaction"].evidence = @(
        [ordered]@{
            kind = "OTHER"
            value = "https://github.com/MuxTV/Muxtv/actions/runs/123456"
        },
        [ordered]@{
            kind = "REPOSITORY_PATH"
            value = ".work/evidence/release/alpha-redaction.json"
        }
    )
    Assert-ValidatorSuccess -Name "safe-references" -Manifest $safeReferences

    $notYetEligible = New-EligibleManifest
    $notYetEligible.claimEligible = $false
    $pendingGate = $notYetEligible.gates["scope.alpha_dependencies"]
    $pendingGate.status = "PENDING"
    $pendingGate.evidence = @()
    $pendingGate.Remove("evidenceCommit")
    Assert-ValidatorSuccess -Name "noneligible-with-pending-required" -Manifest $notYetEligible

    $missingRequired = New-EligibleManifest
    $missingRequired.gates.Remove("scope.alpha_dependencies")
    Assert-ValidatorFailure -Name "missing-required-gate" -Manifest $missingRequired

    $weakenedRequired = New-EligibleManifest
    $weakenedRequired.gates["scope.alpha_dependencies"].required = $false
    Assert-ValidatorFailure -Name "weakened-required-gate" -Manifest $weakenedRequired

    $unknownGate = New-EligibleManifest
    $unknownGate.gates["future.typo_gate"] = [ordered]@{
        required = $false
        status = "PENDING"
        evidence = @()
    }
    Assert-ValidatorFailure -Name "unknown-gate" -Manifest $unknownGate

    $obsoleteMainstreamGate = New-EligibleManifest
    $obsoleteMainstreamGate.gates["virtual.mainstream"] = [ordered]@{
        required = $false
        status = "PENDING"
        evidence = @()
    }
    Assert-ValidatorFailure -Name "obsolete-mainstream-avd-gate" -Manifest $obsoleteMainstreamGate

    $passedCommitMismatch = New-EligibleManifest
    $passedCommitMismatch.claimEligible = $false
    $passedCommitMismatch.gates["security.redaction"].evidenceCommit = $otherCommit
    Assert-ValidatorFailure -Name "passed-commit-mismatch" -Manifest $passedCommitMismatch

    $artifactCommitMismatch = New-EligibleManifest
    $artifactCommitMismatch.claimEligible = $false
    $artifactCommitMismatch.artifacts[0].sourceCommit = $otherCommit
    Assert-ValidatorFailure -Name "artifact-commit-mismatch" -Manifest $artifactCommitMismatch

    $pendingRequired = New-EligibleManifest
    $eligiblePendingGate = $pendingRequired.gates["scope.alpha_dependencies"]
    $eligiblePendingGate.status = "PENDING"
    $eligiblePendingGate.evidence = @()
    $eligiblePendingGate.Remove("evidenceCommit")
    Assert-ValidatorFailure -Name "eligible-with-pending-required" -Manifest $pendingRequired

    $missingReleaseArtifact = New-EligibleManifest
    $missingReleaseArtifact.artifacts = @(
        [ordered]@{
            kind = "SBOM"
            name = "sbom.cdx.json"
            sha256 = $canonicalSha256
            byteCount = 256
            sourceCommit = $canonicalCommit
        }
    )
    Assert-ValidatorFailure -Name "eligible-without-apk-or-aab" -Manifest $missingReleaseArtifact

    $releaseArtifactWithoutDigest = New-EligibleManifest
    $releaseArtifactWithoutDigest.artifacts[0].sha256 = $null
    Assert-ValidatorFailure -Name "eligible-release-artifact-without-digest" -Manifest $releaseArtifactWithoutDigest

    $tokenSecret = "super-secret-token"
    $tokenReference = New-EligibleManifest
    $tokenReference.gates["security.redaction"].evidence[0].value =
        "https://example.invalid/evidence?token=$tokenSecret"
    Assert-ValidatorFailure `
        -Name "token-query-secret" `
        -Manifest $tokenReference `
        -ForbiddenEcho $tokenSecret

    $authorizationSecret = "Bearer abc.def.ghi"
    $authorizationReference = New-EligibleManifest
    $authorizationReference.gates["security.redaction"].evidence[0].value =
        "Authorization: $authorizationSecret"
    Assert-ValidatorFailure `
        -Name "authorization-secret" `
        -Manifest $authorizationReference `
        -ForbiddenEcho $authorizationSecret

    $cookieSecret = "session=private-cookie-value"
    $cookieReference = New-EligibleManifest
    $cookieReference.gates["security.redaction"].evidence[0].value = "Cookie: $cookieSecret"
    Assert-ValidatorFailure `
        -Name "cookie-secret" `
        -Manifest $cookieReference `
        -ForbiddenEcho $cookieSecret

    $userInfoSecret = "private-password"
    $userInfoReference = New-EligibleManifest
    $userInfoReference.gates["security.redaction"].evidence[0].value =
        "https://release-user:$userInfoSecret@example.invalid/artifact"
    Assert-ValidatorFailure `
        -Name "uri-userinfo-secret" `
        -Manifest $userInfoReference `
        -ForbiddenEcho $userInfoSecret

    $privateMachinePath = New-EligibleManifest
    $privateMachinePath.artifacts[0].name = "C:\\Users\\private-user\\Desktop\\muxtv.apk"
    Assert-ValidatorFailure -Name "private-windows-absolute-path" -Manifest $privateMachinePath

    $privateUnixPath = New-EligibleManifest
    $privateUnixPath.artifacts[0].name = "/home/private-user/muxtv.apk"
    Assert-ValidatorFailure -Name "private-unix-absolute-path" -Manifest $privateUnixPath

    $signedQuerySecret = "signed-value"
    $signedQueryReference = New-EligibleManifest
    $signedQueryReference.gates["security.redaction"].evidence[0].value =
        "https://example.invalid/evidence?X-Amz-Signature=$signedQuerySecret"
    Assert-ValidatorFailure `
        -Name "signed-query-secret" `
        -Manifest $signedQueryReference `
        -ForbiddenEcho $signedQuerySecret

    $sourceRefSecret = "source-ref-secret"
    $sourceRefReference = New-EligibleManifest
    $sourceRefReference.sourceRef = "https://example.invalid/source?token=$sourceRefSecret"
    Assert-ValidatorFailure `
        -Name "source-ref-secret" `
        -Manifest $sourceRefReference `
        -ForbiddenEcho $sourceRefSecret

    $deferRationaleSecret = "defer-rationale-secret"
    $deferRationaleReference = New-EligibleManifest
    $deferRationaleReference.claimEligible = $false
    $deferRationaleGate = $deferRationaleReference.gates["physical.fire_tv"]
    $deferRationaleGate.status = "DEFERRED"
    $deferRationaleGate.defer = [ordered]@{
        issueNumber = 31
        rationale = "https://example.invalid/defer?token=$deferRationaleSecret"
        scopeEffect = "Fire TV remains outside current alpha claims."
    }
    Assert-ValidatorFailure `
        -Name "defer-rationale-secret" `
        -Manifest $deferRationaleReference `
        -ForbiddenEcho $deferRationaleSecret

    $deferScopeEffectSecret = "defer-scope-secret"
    $deferScopeEffectReference = New-EligibleManifest
    $deferScopeEffectReference.claimEligible = $false
    $deferScopeEffectGate = $deferScopeEffectReference.gates["physical.fire_tv"]
    $deferScopeEffectGate.status = "DEFERRED"
    $deferScopeEffectGate.defer = [ordered]@{
        issueNumber = 31
        rationale = "Fire TV evidence is unavailable."
        scopeEffect = "Authorization: Bearer $deferScopeEffectSecret"
    }
    Assert-ValidatorFailure `
        -Name "defer-scope-effect-secret" `
        -Manifest $deferScopeEffectReference `
        -ForbiddenEcho $deferScopeEffectSecret
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Host $failure
    }
    throw "Alpha evidence validator contract failed."
}

Write-Host "Alpha evidence validator contract passed."
Write-Host "cases=$passed"
