[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ManifestPath,
    [Parameter(Mandatory)][string]$SchemaPath,
    [Parameter(Mandatory)][string]$GateCatalogPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Fail-Validation {
    param([Parameter(Mandatory)][string]$Message)
    throw $Message
}

function Read-JsonDocument {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail-Validation $FailureMessage
    }

    try {
        return Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json -Depth 100
    } catch {
        Fail-Validation $FailureMessage
    }
}

function Assert-PublicSafeValue {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][bool]$RejectAbsolutePath
    )

    $sensitivePatterns = @(
        '(?i)\b(?:authorization|cookie|set-cookie)\s*:',
        '(?i)^[a-z][a-z0-9+.-]*://[^/?#\s]*@',
        '(?i)[?&#](?:access[_-]?token|token|api[_-]?key|password|passwd|signature|sig|x-amz-signature|x-goog-signature|credential|auth)\s*=',
        '(?i)^file://'
    )

    foreach ($pattern in $sensitivePatterns) {
        if ($Value -match $pattern) {
            Fail-Validation "Alpha evidence metadata failed redaction policy."
        }
    }

    if ($RejectAbsolutePath) {
        if ($Value -match '^[A-Za-z]:[\\/]' -or $Value -match '^[/\\]{1,2}(?![/\\])') {
            Fail-Validation "Alpha evidence metadata contains a private absolute path."
        }
    }
}

foreach ($requiredFile in @($ManifestPath, $SchemaPath, $GateCatalogPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        Fail-Validation "Alpha evidence validator input is missing."
    }
}

try {
    $testJsonCommand = Get-Command Test-Json -ErrorAction Stop
} catch {
    Fail-Validation "PowerShell Test-Json support is required."
}
if (-not $testJsonCommand.Parameters.ContainsKey("SchemaFile")) {
    Fail-Validation "PowerShell Test-Json -SchemaFile support is required."
}

try {
    $manifestJson = Get-Content -LiteralPath $ManifestPath -Raw -Encoding utf8
    $schemaValid = $manifestJson | Test-Json -SchemaFile $SchemaPath -ErrorAction Stop
} catch {
    Fail-Validation "Alpha evidence manifest schema validation failed."
}
if (-not $schemaValid) {
    Fail-Validation "Alpha evidence manifest schema validation failed."
}

$manifest = Read-JsonDocument -Path $ManifestPath -FailureMessage "Alpha evidence manifest JSON is invalid."
$catalog = Read-JsonDocument -Path $GateCatalogPath -FailureMessage "Alpha gate catalog JSON is invalid."

if ($catalog.schemaVersion -ne 1 -or [string]$catalog.releaseTrack -cne "0.1.0-alpha") {
    Fail-Validation "Alpha gate catalog identity is invalid."
}

$catalogById = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
foreach ($definition in @($catalog.gates)) {
    $id = [string]$definition.id
    if ([string]::IsNullOrWhiteSpace($id) -or $catalogById.ContainsKey($id)) {
        Fail-Validation "Alpha gate catalog contains an invalid or duplicate gate id."
    }
    $catalogById.Add($id, $definition)
}
if ($catalogById.Count -eq 0) {
    Fail-Validation "Alpha gate catalog is empty."
}

$manifestById = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
foreach ($property in @($manifest.gates.PSObject.Properties)) {
    if ($manifestById.ContainsKey($property.Name)) {
        Fail-Validation "Alpha evidence manifest contains duplicate gates."
    }
    $manifestById.Add($property.Name, $property.Value)
}

if ($manifestById.Count -ne $catalogById.Count) {
    Fail-Validation "Alpha evidence manifest does not contain the canonical gate set."
}

foreach ($entry in $catalogById.GetEnumerator()) {
    $id = $entry.Key
    $definition = $entry.Value
    if (-not $manifestById.ContainsKey($id)) {
        Fail-Validation "Alpha evidence manifest is missing a canonical gate."
    }

    $gate = $manifestById[$id]
    if ([bool]$definition.requiredByDefault -and -not [bool]$gate.required) {
        Fail-Validation "Alpha evidence manifest weakens a required gate."
    }
}

foreach ($id in $manifestById.Keys) {
    if (-not $catalogById.ContainsKey($id)) {
        Fail-Validation "Alpha evidence manifest contains an unknown gate."
    }
}

if ($null -ne $manifest.PSObject.Properties["sourceRef"] -and $null -ne $manifest.sourceRef) {
    Assert-PublicSafeValue -Value ([string]$manifest.sourceRef) -RejectAbsolutePath $true
}

$manifestCommit = [string]$manifest.commit
foreach ($gate in $manifestById.Values) {
    if ([string]$gate.status -ceq "PASSED") {
        if ([string]$gate.evidenceCommit -cne $manifestCommit) {
            Fail-Validation "Passed alpha evidence does not belong to the manifest commit."
        }
    }

    foreach ($reference in @($gate.evidence)) {
        Assert-PublicSafeValue -Value ([string]$reference.value) -RejectAbsolutePath $true
    }

    if ($null -ne $gate.PSObject.Properties["note"] -and $null -ne $gate.note) {
        Assert-PublicSafeValue -Value ([string]$gate.note) -RejectAbsolutePath $false
    }

    if ($null -ne $gate.PSObject.Properties["facts"] -and $null -ne $gate.facts) {
        foreach ($factProperty in @($gate.facts.PSObject.Properties)) {
            if ($factProperty.Value -is [string]) {
                Assert-PublicSafeValue -Value ([string]$factProperty.Value) -RejectAbsolutePath $false
            }
        }
    }

    if ($null -ne $gate.PSObject.Properties["defer"] -and $null -ne $gate.defer) {
        Assert-PublicSafeValue -Value ([string]$gate.defer.rationale) -RejectAbsolutePath $false
        Assert-PublicSafeValue -Value ([string]$gate.defer.scopeEffect) -RejectAbsolutePath $false
    }
}

foreach ($artifact in @($manifest.artifacts)) {
    if ([string]$artifact.sourceCommit -cne $manifestCommit) {
        Fail-Validation "Alpha release artifact does not belong to the manifest commit."
    }
    Assert-PublicSafeValue -Value ([string]$artifact.name) -RejectAbsolutePath $true
}

foreach ($limitation in @($manifest.knownLimitations)) {
    Assert-PublicSafeValue -Value ([string]$limitation) -RejectAbsolutePath $false
}

if ([bool]$manifest.claimEligible) {
    foreach ($gate in $manifestById.Values) {
        if ([bool]$gate.required -and [string]$gate.status -cne "PASSED") {
            Fail-Validation "Claim-eligible alpha evidence has a required gate that is not passed."
        }
    }

    $qualifiedReleaseArtifacts = @(
        @($manifest.artifacts) | Where-Object {
            ([string]$_.kind -in @("APK", "AAB")) -and
            ($null -ne $_.sha256) -and
            ([string]$_.sha256 -cmatch '^[0-9a-f]{64}$')
        }
    )
    if ($qualifiedReleaseArtifacts.Count -lt 1) {
        Fail-Validation "Claim-eligible alpha evidence requires an APK or AAB with SHA-256 provenance."
    }
}

Write-Host "Alpha evidence manifest validation passed."
