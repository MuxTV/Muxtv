[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\artifact-transport"
$diagnosticPath = Join-Path $evidenceDirectory "artifact-transport.log"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

$lines = [System.Collections.Generic.List[string]]::new()
function Add-Line {
    param([Parameter(Mandatory)][string]$Name, [AllowEmptyString()][string]$Value)
    $script:lines.Add("$Name=$Value")
}

Add-Line "timestamp_utc" ([DateTimeOffset]::UtcNow.ToString("o"))
Add-Line "runner_name" ([string]$env:RUNNER_NAME)
Add-Line "runner_os" ([string]$env:RUNNER_OS)
Add-Line "runner_arch" ([string]$env:RUNNER_ARCH)
Add-Line "http_proxy_configured" ([bool](-not [string]::IsNullOrWhiteSpace($env:HTTP_PROXY))).ToString().ToLowerInvariant()
Add-Line "https_proxy_configured" ([bool](-not [string]::IsNullOrWhiteSpace($env:HTTPS_PROXY))).ToString().ToLowerInvariant()
Add-Line "no_proxy_configured" ([bool](-not [string]::IsNullOrWhiteSpace($env:NO_PROXY))).ToString().ToLowerInvariant()

$resultsHost = "results-receiver.actions.githubusercontent.com"
Add-Line "results_receiver_host" $resultsHost
Add-Line "artifact_blob_requirement" "*.blob.core.windows.net"
Add-Line "signed_blob_url_logged" "false"

$actionsResultsHost = ""
if (-not [string]::IsNullOrWhiteSpace($env:ACTIONS_RESULTS_URL)) {
    try {
        $uri = [Uri]$env:ACTIONS_RESULTS_URL
        $actionsResultsHost = $uri.Host
    } catch {
        $actionsResultsHost = "unparseable"
    }
}
Add-Line "actions_results_host" $actionsResultsHost

try {
    $addresses = [System.Net.Dns]::GetHostAddresses($resultsHost) |
        ForEach-Object { $_.IPAddressToString } |
        Sort-Object -Unique
    Add-Line "results_receiver_dns_ok" "true"
    Add-Line "results_receiver_addresses" ([string]::Join(",", $addresses))
} catch {
    Add-Line "results_receiver_dns_ok" "false"
    Add-Line "results_receiver_dns_error" $_.Exception.GetType().Name
}

$tcpClient = $null
try {
    $tcpClient = [System.Net.Sockets.TcpClient]::new()
    $connectTask = $tcpClient.ConnectAsync($resultsHost, 443)
    if (-not $connectTask.Wait([TimeSpan]::FromSeconds(5))) {
        throw [TimeoutException]::new("TCP connect timeout")
    }
    Add-Line "results_receiver_tcp_443_ok" "true"

    $ssl = $null
    try {
        $ssl = [System.Net.Security.SslStream]::new($tcpClient.GetStream(), $false)
        $options = [System.Net.Security.SslClientAuthenticationOptions]::new()
        $options.TargetHost = $resultsHost
        $ssl.AuthenticateAsClient($options)
        Add-Line "results_receiver_tls_ok" "true"
        Add-Line "results_receiver_tls_protocol" ([string]$ssl.SslProtocol)
        if ($ssl.RemoteCertificate) {
            Add-Line "results_receiver_cert_subject" ([string]$ssl.RemoteCertificate.Subject)
            Add-Line "results_receiver_cert_issuer" ([string]$ssl.RemoteCertificate.Issuer)
        }
    } catch {
        Add-Line "results_receiver_tls_ok" "false"
        Add-Line "results_receiver_tls_error" $_.Exception.GetType().Name
    } finally {
        if ($ssl) { $ssl.Dispose() }
    }
} catch {
    Add-Line "results_receiver_tcp_443_ok" "false"
    Add-Line "results_receiver_tcp_443_error" $_.Exception.GetType().Name
} finally {
    if ($tcpClient) { $tcpClient.Dispose() }
}

try {
    $lines | Set-Content -LiteralPath $diagnosticPath -Encoding utf8
    Write-Host "Secret-free artifact transport diagnostics recorded: $diagnosticPath"
} catch {
    # Diagnostic collection must not replace publication itself as the acceptance authority.
    Write-Warning "Unable to persist artifact transport diagnostics: $($_.Exception.GetType().Name)"
}
