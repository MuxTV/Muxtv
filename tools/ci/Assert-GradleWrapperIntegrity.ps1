[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot "../..")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Independently published Gradle 9.9.3 checksums:
# https://gradle.org/release-checksums/
# Update these reviewed pins together with the wrapper in a toolchain PR.
$distributionSha256 = '4f2135b741f272acc88c168a52202a0a63fceee0cd61ee753da79694cd2b1e4b'
$wrapperJarSha256 = '621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8'
$expected = @(
    'distributionBase=GRADLE_USER_HOME'
    'distributionPath=wrapper/dists'
    'distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.3-bin.zip'
    "distributionSha256Sum=$distributionSha256"
    'networkTimeout=10000'
    'retries=0'
    'retryBackOffMs=500'
    'validateDistributionUrl=true'
    'zipStoreBase=GRADLE_USER_HOME'
    'zipStorePath=wrapper/dists'
)
$directory = Join-Path $RepositoryRoot "gradle/wrapper"
$properties = Join-Path $directory "gradle-wrapper.properties"
$jar = Join-Path $directory "gradle-wrapper.jar"

if (-not (Test-Path -LiteralPath $properties -PathType Leaf) -or
    -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Wrapper integrity: missing properties or JAR"
}

# Deliberately accept only the reviewed ASCII property subset, not a second
# general Java Properties parser. Escaped keys, continuations, duplicate
# assignments and alternate separators must not bypass the pin.
$bytes = [IO.File]::ReadAllBytes($properties)
foreach ($byte in $bytes) {
    if ($byte -ne 10 -and $byte -ne 13 -and ($byte -lt 32 -or $byte -gt 126)) {
        throw "Wrapper integrity: unexpected property encoding"
    }
}
$raw = [Text.Encoding]::ASCII.GetString($bytes)
if ($raw -match '\r(?!\n)') {
    throw "Wrapper integrity: unsupported line separator"
}
$allowed = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($line in $expected) { [void]$allowed.Add($line) }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($line in ($raw -split '\r?\n')) {
    if ($line -ceq '') { continue }
    if (-not $allowed.Contains($line) -or -not $seen.Add($line)) {
        throw "Wrapper integrity: unexpected or duplicate property"
    }
}
if ($seen.Count -ne $allowed.Count) {
    throw "Wrapper integrity: missing required property"
}

$actualJar = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualJar -cne $wrapperJarSha256) {
    throw "Wrapper integrity: JAR SHA-256 mismatch"
}
Write-Host "Gradle wrapper integrity PASS: version=9.9.3; jarSha256=$actualJar; distributionSha256=$distributionSha256"
