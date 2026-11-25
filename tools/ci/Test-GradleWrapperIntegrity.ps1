[CmdletBinding()]
param([string]$RepositoryRoot = (Join-Path $PSScriptRoot "../.."))
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$guard = Join-Path $PSScriptRoot "Assert-GradleWrapperIntegrity.ps1"
& $guard -RepositoryRoot $RepositoryRoot
$fixture = Join-Path ([IO.Path]::GetTempPath()) ("muxtv-wrapper-" + [guid]::NewGuid().ToString("N"))
$directory = Join-Path $fixture "gradle/wrapper"
$properties = Join-Path $directory "gradle-wrapper.properties"
$jar = Join-Path $directory "gradle-wrapper.jar"
$sourceDir = Join-Path $RepositoryRoot "gradle/wrapper"
$original = [IO.File]::ReadAllText((Join-Path $sourceDir "gradle-wrapper.properties"))

function Assert-Rejected([string]$Name) {
    $rejected = $false
    try {
        & $guard -RepositoryRoot $fixture
    } catch {
        if ($_.Exception.Message -notlike 'Wrapper integrity:*') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw "Wrapper contract accepted invalid fixture: $Name" }
    Write-Host "Rejected wrapper fixture: $Name"
}

try {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceDir "gradle-wrapper.jar") -Destination $jar
    $utf8 = [Text.UTF8Encoding]::new($false)
    $lf = $original.Replace("`r`n", "`n")
    [IO.File]::WriteAllText($properties, $lf, $utf8)
    & $guard -RepositoryRoot $fixture
    [IO.File]::WriteAllText($properties, $lf.Replace("`n", "`r`n"), $utf8)
    & $guard -RepositoryRoot $fixture
    $mutations = [ordered]@{
        missingChecksum = ($original -replace '(?m)^distributionSha256Sum=.*\r?\n', '')
        wrongChecksum = ($original -replace '(?m)^distributionSha256Sum=[0-9a-f]{64}', ('distributionSha256Sum=' + ('0' * 64)))
        wrongUrl = $original.Replace('services.gradle.org', 'example.invalid')
        duplicate = ($original + "`ndistributionUrl=https\://example.invalid/other.zip`n")
        escapedKey = $original.Replace('distributionUrl=', 'distribution\u0055rl=')
        disabledValidation = $original.Replace('validateDistributionUrl=true', 'validateDistributionUrl=false')
        continuation = ($original + "`ndistributionUrl=\`nhttps\://example.invalid/other.zip`n")
    }
    foreach ($entry in $mutations.GetEnumerator()) {
        [IO.File]::WriteAllText($properties, [string]$entry.Value, $utf8)
        Assert-Rejected $entry.Key
    }
    [IO.File]::WriteAllText($properties, $original, $utf8)
    $jarBytes = [IO.File]::ReadAllBytes($jar)
    $jarBytes[0] = $jarBytes[0] -bxor 1
    [IO.File]::WriteAllBytes($jar, $jarBytes)
    Assert-Rejected 'tamperedJar'
    Remove-Item -LiteralPath $jar -Force
    Assert-Rejected 'missingJar'
} finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force
}
Write-Host "Gradle wrapper integrity contract PASS (valid LF/CRLF and 9 negative cases)."
