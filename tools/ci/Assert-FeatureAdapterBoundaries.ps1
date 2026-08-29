[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$featureRoot = Join-Path $RepositoryRoot "feature"
if (-not (Test-Path -LiteralPath $featureRoot -PathType Container)) {
    throw "Feature module root is missing: $featureRoot"
}

$forbiddenTargets = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
@(
    "core:database",
    "core:credentials",
    "catalog:sync",
    "player:media3"
) | ForEach-Object { $null = $forbiddenTargets.Add($_) }

$buildFiles = @(
    Get-ChildItem -LiteralPath $featureRoot -Recurse -File -Filter "build.gradle.kts" -ErrorAction Stop |
        Sort-Object FullName
)
if ($buildFiles.Count -eq 0) {
    throw "No feature build.gradle.kts files were found under $featureRoot"
}

foreach ($buildFile in $buildFiles) {
    try {
        $content = Get-Content -LiteralPath $buildFile.FullName -Raw -Encoding utf8 -ErrorAction Stop
    }
    catch {
        throw "Unable to read feature build file '$($buildFile.FullName)': $($_.Exception.Message)"
    }

    $withoutBlockComments = [regex]::Replace(
        $content,
        '/\*.*?\*/',
        '',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )
    $activeContent = [regex]::Replace(
        $withoutBlockComments,
        '(?m)//.*$',
        ''
    )

    $relativeDirectory = [System.IO.Path]::GetRelativePath($featureRoot, $buildFile.DirectoryName)
    $moduleSuffix = $relativeDirectory.Replace('\', ':').Replace('/', ':')
    $source = "feature:$moduleSuffix"

    $dependencies = [regex]::Matches(
        $activeContent,
        'project\s*\(\s*(?:path\s*=\s*)?["''](:[^"'']+)["'']\s*\)'
    )
    foreach ($dependency in $dependencies) {
        $target = $dependency.Groups[1].Value.TrimStart(':')
        if ($forbiddenTargets.Contains($target)) {
            throw "Forbidden dependency: $source -> $target"
        }
    }
}

Write-Host "Feature adapter dependency boundaries passed for $($buildFiles.Count) feature module(s)."
