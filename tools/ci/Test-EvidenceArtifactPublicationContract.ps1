[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$errors = [System.Collections.Generic.List[string]]::new()

function Add-ContractError {
    param([Parameter(Mandatory)][string]$Message)
    $script:errors.Add($Message)
}

$uploadActionRelativePath = ".github\actions\upload-evidence-with-retry\action.yml"
$uploadActionPath = Join-Path $repositoryRoot $uploadActionRelativePath
$diagnosticsRelativePath = "tools\ci\Write-ArtifactTransportDiagnostics.ps1"
$diagnosticsPath = Join-Path $repositoryRoot $diagnosticsRelativePath
$resolverRelativePath = "tools\ci\Resolve-ArtifactUploadAttempt.ps1"
$resolverPath = Join-Path $repositoryRoot $resolverRelativePath

$workflowContracts = [ordered]@{
    ".github\workflows\self-hosted-validation.yml" = "self-hosted-validation-"
    ".github\workflows\android-tv-focused-device.yml" = "android-tv-focused-device-"
    ".github\workflows\android-tv-product-device-matrix.yml" = "android-tv-product-device-matrix-"
    ".github\workflows\database-migration-device-matrix.yml" = "database-migration-device-matrix-"
    ".github\workflows\measurement-variance-smoke.yml" = "measurement-variance-smoke-"
    ".github\workflows\integration-gate.yml" = "integration-acceptance-"
    ".github\workflows\benchmark-foundation.yml" = "benchmark-foundation-"
    ".github\workflows\focused-m3u-evidence.yml" = "focused-m3u-evidence-"
}

if (-not (Test-Path -LiteralPath $uploadActionPath -PathType Leaf)) {
    Add-ContractError "Missing shared bounded evidence upload action: $uploadActionRelativePath"
} else {
    $actionContent = Get-Content -LiteralPath $uploadActionPath -Raw
    $pinnedUpload = "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"
    $uploadCount = ([regex]::Matches($actionContent, [regex]::Escape($pinnedUpload))).Count
    if ($uploadCount -ne 2) {
        Add-ContractError "Shared evidence upload action must contain exactly two pinned upload-artifact attempts; found $uploadCount."
    }
    if (([regex]::Matches($actionContent, "(?m)^\s*continue-on-error:\s*true\s*$")).Count -ne 2) {
        Add-ContractError "Each of the two upload attempts must be non-terminal so repository reconciliation can decide whether retry is safe."
    }
    foreach ($requiredFragment in @(
        '${{ inputs.name-prefix }}-attempt-1',
        '${{ inputs.name-prefix }}-attempt-2',
        $diagnosticsRelativePath.Replace("\", "/"),
        $resolverRelativePath.Replace("\", "/"),
        'accepted-artifact-name',
        'accepted-artifact-id',
        'accepted-artifact-digest',
        'accepted-attempt',
        'github-token',
        'github.run_id',
        'github.repository',
        "if: always() && steps.reconcile_1.outputs.accepted != 'true'"
    )) {
        if ($actionContent.IndexOf($requiredFragment, [System.StringComparison]::Ordinal) -lt 0) {
            Add-ContractError "Shared evidence upload action is missing required contract fragment: $requiredFragment"
        }
    }
    if ($actionContent -match "(?mi)^\s*overwrite:\s*true\s*$") {
        Add-ContractError "Evidence retry must not overwrite immutable artifacts; every attempt requires a unique name."
    }
    if ($actionContent.IndexOf('No evidence artifact was accepted after 2 bounded attempts.', [System.StringComparison]::Ordinal) -lt 0) {
        Add-ContractError "Shared evidence upload action must fail fatally when both bounded attempts are unaccepted."
    }
}

foreach ($scriptContract in @(
    @{ Path = $diagnosticsPath; Relative = $diagnosticsRelativePath; Missing = "Missing secret-free artifact transport diagnostics script" },
    @{ Path = $resolverPath; Relative = $resolverRelativePath; Missing = "Missing artifact attempt reconciliation script" }
)) {
    if (-not (Test-Path -LiteralPath $scriptContract.Path -PathType Leaf)) {
        Add-ContractError "$($scriptContract.Missing): $($scriptContract.Relative)"
        continue
    }

    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile(
        $scriptContract.Path,
        [ref]$tokens,
        [ref]$parseErrors
    )
    foreach ($parseError in @($parseErrors)) {
        Add-ContractError ("{0}:{1}:{2} {3}" -f `
            $scriptContract.Relative, `
            $parseError.Extent.StartLineNumber, `
            $parseError.Extent.StartColumnNumber, `
            $parseError.Message)
    }
}

foreach ($entry in $workflowContracts.GetEnumerator()) {
    $relativePath = [string]$entry.Key
    $expectedNamePrefix = [string]$entry.Value
    $workflowPath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $workflowPath -PathType Leaf)) {
        Add-ContractError "Missing evidence workflow: $relativePath"
        continue
    }

    $content = Get-Content -LiteralPath $workflowPath -Raw
    if ($content.IndexOf("actions: read", [System.StringComparison]::Ordinal) -lt 0) {
        Add-ContractError "$relativePath must grant only the additional actions: read permission required to reconcile ambiguous artifact upload failures."
    }
    if ($content.IndexOf("uses: ./.github/actions/upload-evidence-with-retry", [System.StringComparison]::Ordinal) -lt 0) {
        Add-ContractError "$relativePath must use the shared bounded evidence publication action."
    }
    if ($content -match "(?mi)^\s*uses:\s*actions/upload-artifact@") {
        Add-ContractError "$relativePath must not call upload-artifact directly once the shared publication contract is installed."
    }
    foreach ($requiredFragment in @(
        "name-prefix: $expectedNamePrefix",
        '${{ github.run_id }}',
        '${{ github.run_attempt }}',
        'github-token: ${{ github.token }}'
    )) {
        if ($content.IndexOf($requiredFragment, [System.StringComparison]::Ordinal) -lt 0) {
            Add-ContractError "$relativePath is missing bounded publication fragment: $requiredFragment"
        }
    }
}

if ($errors.Count -gt 0) {
    $message = "Evidence artifact publication contract failed." + [Environment]::NewLine + [string]::Join([Environment]::NewLine, $errors)
    Write-Host $message
    throw $message
}

Write-Host "Evidence artifact publication contract passed."
