[CmdletBinding()]
param(
    [string]$SearchRoot = "benchmark/macrobenchmark/build/outputs/connected_android_test_additional_output",
    [string]$EvidenceRoot = ".work/evidence/benchmark-in-process-trace-api36"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resolvedSearchRoot = if ([System.IO.Path]::IsPathRooted($SearchRoot)) {
    $SearchRoot
} else {
    Join-Path $repositoryRoot $SearchRoot
}
$resolvedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}

if (-not (Test-Path -LiteralPath $resolvedSearchRoot -PathType Container)) {
    throw "Macrobenchmark additional output directory was not found: $resolvedSearchRoot"
}

$reports = @(Get-ChildItem -LiteralPath $resolvedSearchRoot -Recurse -File -Filter "*-benchmarkData.json")
if ($reports.Count -lt 1) {
    throw "Macrobenchmark produced no *-benchmarkData.json report under $resolvedSearchRoot"
}

$matchingBenchmarks = [System.Collections.Generic.List[object]]::new()
foreach ($report in $reports) {
    $data = Get-Content -LiteralPath $report.FullName -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($benchmark in @($data.benchmarks)) {
        if ([string]$benchmark.name -like "searchInProcessTraceEvidence*") {
            $matchingBenchmarks.Add([pscustomobject]@{
                Report = $report
                Benchmark = $benchmark
            })
        }
    }
}

if ($matchingBenchmarks.Count -ne 1) {
    throw "Expected exactly one searchInProcessTraceEvidence benchmark result, found $($matchingBenchmarks.Count)."
}

$match = $matchingBenchmarks[0]
$metricProperty = $match.Benchmark.metrics.PSObject.Properties["MuxTv.SearchCount"]
if ($null -eq $metricProperty) {
    throw "Macrobenchmark report does not contain the MuxTv.SearchCount metric."
}

$metric = $metricProperty.Value
$runs = @($metric.runs | ForEach-Object { [double]$_ })
if ($runs.Count -lt 1) {
    throw "MuxTv.SearchCount contains no measured runs."
}
$nonPositive = @($runs | Where-Object { $_ -lt 1.0 })
if ($nonPositive.Count -gt 0) {
    throw "Merged Perfetto trace did not contain MuxTv.Search in every evidence iteration: runs=$($runs -join ',')."
}

$traces = @(Get-ChildItem -LiteralPath $resolvedSearchRoot -Recurse -File -Filter "*.perfetto-trace")
if ($traces.Count -lt 1) {
    throw "Macrobenchmark produced no Perfetto trace file under $resolvedSearchRoot"
}

New-Item -ItemType Directory -Force -Path $resolvedEvidenceRoot | Out-Null
$summary = [ordered]@{
    schemaVersion = 1
    benchmark = [string]$match.Benchmark.name
    report = [System.IO.Path]::GetRelativePath($repositoryRoot, $match.Report.FullName)
    metric = "MuxTv.SearchCount"
    runs = $runs
    minimumCount = ($runs | Measure-Object -Minimum).Minimum
    perfettoTraceCount = $traces.Count
    perfettoTraces = @($traces | ForEach-Object {
        [System.IO.Path]::GetRelativePath($repositoryRoot, $_.FullName)
    })
    result = "PASS"
    claim = "trace-capture-correctness-only"
}
$summaryPath = Join-Path $resolvedEvidenceRoot "summary.json"
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding utf8

Write-Host "O2.4 in-process trace evidence passed: MuxTv.SearchCount=$($runs -join ',') traces=$($traces.Count)."
