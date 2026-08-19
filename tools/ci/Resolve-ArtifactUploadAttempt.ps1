[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$UploadOutcome,
    [AllowEmptyString()][string]$UploadArtifactId = "",
    [AllowEmptyString()][string]$UploadArtifactDigest = "",
    [Parameter(Mandatory)][string]$ArtifactName,
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][ValidateRange(1, 2)][int]$AttemptNumber,
    [ValidateRange(1, 5)][int]$LookupAttempts = 3,
    [ValidateRange(0, 10)][int]$LookupDelaySeconds = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Result {
    param(
        [Parameter(Mandatory)][bool]$Accepted,
        [AllowEmptyString()][string]$ArtifactId = "",
        [AllowEmptyString()][string]$ArtifactDigest = "",
        [AllowEmptyString()][string]$Reason = ""
    )

    $outputPath = $env:GITHUB_OUTPUT
    if ([string]::IsNullOrWhiteSpace($outputPath)) {
        throw "GITHUB_OUTPUT is not available."
    }

    "accepted=$($Accepted.ToString().ToLowerInvariant())" | Out-File -FilePath $outputPath -Encoding utf8 -Append
    "artifact_name=$ArtifactName" | Out-File -FilePath $outputPath -Encoding utf8 -Append
    "artifact_id=$ArtifactId" | Out-File -FilePath $outputPath -Encoding utf8 -Append
    "artifact_digest=$ArtifactDigest" | Out-File -FilePath $outputPath -Encoding utf8 -Append
    "attempt=$AttemptNumber" | Out-File -FilePath $outputPath -Encoding utf8 -Append
    "reason=$Reason" | Out-File -FilePath $outputPath -Encoding utf8 -Append
}

if ($UploadOutcome -eq "success" -and -not [string]::IsNullOrWhiteSpace($UploadArtifactId)) {
    Write-Result -Accepted $true -ArtifactId $UploadArtifactId -ArtifactDigest $UploadArtifactDigest -Reason "upload-action-success"
    Write-Host "Artifact upload attempt $AttemptNumber accepted from upload-artifact output: name=$ArtifactName id=$UploadArtifactId"
    exit 0
}

$token = $env:GITHUB_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Warning "Artifact reconciliation cannot query Actions REST because GITHUB_TOKEN is unavailable."
    Write-Result -Accepted $false -Reason "github-token-unavailable"
    exit 0
}

$escapedName = [Uri]::EscapeDataString($ArtifactName)
$uri = "https://api.github.com/repos/$Repository/actions/runs/$RunId/artifacts?name=$escapedName&per_page=100"
$headers = @{
    Accept = "application/vnd.github+json"
    Authorization = "Bearer $token"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent" = "MuxTV-evidence-reconciler"
}

for ($lookup = 1; $lookup -le $LookupAttempts; $lookup++) {
    try {
        $response = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers -TimeoutSec 15
        $artifacts = @($response.artifacts)
        $exact = @($artifacts | Where-Object {
            $_.name -ceq $ArtifactName -and
            (-not $_.PSObject.Properties["expired"] -or -not [bool]$_.expired)
        })

        if ($exact.Count -eq 1) {
            $candidate = $exact[0]
            $digest = ""
            if ($candidate.PSObject.Properties["digest"] -and $candidate.digest) {
                $digest = [string]$candidate.digest
                if ($digest.StartsWith("sha256:", [System.StringComparison]::OrdinalIgnoreCase)) {
                    $digest = $digest.Substring(7)
                }
            }
            Write-Result -Accepted $true -ArtifactId ([string]$candidate.id) -ArtifactDigest $digest -Reason "rest-reconciled-finalized-artifact"
            Write-Host "Artifact upload attempt $AttemptNumber reconciled to one finalized artifact: name=$ArtifactName id=$($candidate.id)"
            exit 0
        }

        if ($exact.Count -gt 1) {
            Write-Warning "Artifact reconciliation found multiple exact-name artifacts; attempt is not accepted."
            Write-Result -Accepted $false -Reason "ambiguous-exact-name-artifacts"
            exit 0
        }

        Write-Host ("Artifact not visible after reconciliation lookup {0}/{1}: name={2}" -f $lookup, $LookupAttempts, $ArtifactName)
    } catch {
        # A reconciliation transport failure does not prove that publication failed. A later unique-name
        # attempt is safe; acceptance still requires one explicit successful/reconciled artifact.
        Write-Warning ("Artifact reconciliation lookup {0}/{1} failed: {2}" -f $lookup, $LookupAttempts, $_.Exception.GetType().Name)
    }

    if ($lookup -lt $LookupAttempts -and $LookupDelaySeconds -gt 0) {
        Start-Sleep -Seconds $LookupDelaySeconds
    }
}

Write-Result -Accepted $false -Reason "artifact-not-finalized-or-not-visible"
Write-Host "Artifact upload attempt $AttemptNumber was not accepted after bounded reconciliation: name=$ArtifactName"
