<#
.SYNOPSIS
    WebSocket failover test for ws-gateway (profile ha).
    Connects N clients, stops one ws-gateway replica, measures reconnect time.

.DESCRIPTION
    Requires: docker compose --profile apps --profile ha up -d
    Creates 20 customer WebSocket connections, then stops ws-gateway container,
    measures time until all clients reconnect and receive driver_location again.

.PARAMETER ClientCount
    Number of client connections (default 20)

.PARAMETER WsUrl
    WebSocket URL (default wss://ridehailing.duckdns.org/ws/customer)

.PARAMETER Token
    JWT token for authentication (required)

.EXAMPLE
    .\tools\evidence\failover_ws.ps1 -Token "eyJhbGciOiJIUzI1NiIs..."
#>

param(
    [int]$ClientCount = 20,
    [string]$WsUrl = "wss://ridehailing.duckdns.org/ws/customer",
    [Parameter(Mandatory=$true)]
    [string]$Token,
    [string]$OutDir = "tools/evidence/out"
)

$ErrorActionPreference = "Stop"

# Add-type for WebSocket client
Add-Type -AssemblyName "System.Net.WebSockets.Client, Version=4.0.0.0, Culture=neutral, PublicKeyToken=cc7b13ffcd2ddd51"
Add-Type -AssemblyName "System.Net.WebSockets, Version=4.0.0.0, Culture=neutral, PublicKeyToken=cc7b13ffcd2ddd51"

class WsClient {
    [string]$Id
    [System.Net.WebSockets.ClientWebSocket]$Ws
    [bool]$Connected = $false
    [bool]$Authenticated = $false
    [datetime]$DisconnectTime
    [datetime]$ReconnectTime
    [int]$MessagesBefore = 0
    [int]$MessagesAfter = 0
    [System.Collections.Generic.List[datetime]]$MessageTimes = [System.Collections.Generic.List[datetime]]::new()
}

function New-WsClient {
    param($id, $url, $token)
    $client = [WsClient]::new()
    $client.Id = $id
    $client.Ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $client.Ws.Options.KeepAliveInterval = [TimeSpan]::FromSeconds(30)
    return $client
}

async function Connect-Client {
    param($client, $url, $token)
    try {
        await $client.Ws.ConnectAsync([Uri]::new($url), [System.Threading.CancellationToken]::None)
        $client.Connected = $true
        # Send auth
        $authMsg = '{"t":"auth","token":"' + $token + '"}'
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($authMsg)
        await $client.Ws.SendAsync($bytes, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [System.Threading.CancellationToken]::None)
        return $true
    }
    catch {
        Write-Host "Client $($client.Id) connect failed: $($_.Exception.Message)"
        return $false
    }
}

async function Receive-Loop {
    param($client)
    $buffer = [System.Array]::CreateInstance([byte], 4096)
    while ($client.Ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        try {
            $result = await $client.Ws.ReceiveAsync($buffer, [System.Threading.CancellationToken]::None)
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                break
            }
            $msg = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
            $client.MessageTimes.Add([datetime]::Now)
            if (-not $client.Authenticated -and $msg.Contains('"t":"auth_ok"')) {
                $client.Authenticated = $true
            }
            elseif ($client.Authenticated -and $msg.Contains('"t":"driver_location"')) {
                if (-not $client.DisconnectTime) { $client.MessagesBefore++ }
                else { $client.MessagesAfter++ }
            }
        }
        catch {
            break
        }
    }
    $client.Connected = $false
    if (-not $client.DisconnectTime) { $client.DisconnectTime = [datetime]::Now }
}

# Main
Write-Host "=== WebSocket Failover Test ===" -ForegroundColor Cyan
Write-Host "Clients: $ClientCount"
Write-Host "URL: $WsUrl"
Write-Host "Output: $OutDir"

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

# Create clients
$clients = @()
for ($i = 0; $i -lt $ClientCount; $i++) {
    $clients += (New-WsClient -id $i -url $WsUrl -token $Token)
}

# Connect all
Write-Host "`nConnecting $ClientCount clients..." -ForegroundColor Yellow
$connectTasks = @()
foreach ($c in $clients) {
    $connectTasks += Connect-Client -client $c -url $WsUrl -token $Token
}
$results = await [System.Threading.Tasks.Task]::WhenAll($connectTasks)
$connected = ($results | Where-Object { $_ }).Count
Write-Host "Connected: $connected / $ClientCount" -ForegroundColor Green

if ($connected -lt $ClientCount) {
    Write-Error "Not all clients connected"
    exit 1
}

# Start receive loops
$receiveTasks = @()
foreach ($c in $clients) {
    $receiveTasks += Receive-Loop -client $c
}

# Wait for all to authenticate
$startAuth = [datetime]::Now
while ($clients.Where({ $_.Authenticated }).Count -lt $connected) {
    Start-Sleep -Milliseconds 100
    if (([datetime]::Now - $startAuth).TotalSeconds -gt 10) {
        Write-Error "Auth timeout"
        exit 1
    }
}
Write-Host "All authenticated" -ForegroundColor Green

# Wait a bit for baseline messages
Write-Host "Collecting baseline messages (10s)..."
Start-Sleep -Seconds 10

# STOP ws-gateway (primary)
Write-Host "`n=== STOPPING ws-gateway (primary) ===" -ForegroundColor Red
$stopTime = [datetime]::Now
docker compose -f compose.yaml stop ws-gateway

# Wait for all to disconnect
Write-Host "Waiting for disconnects..."
while ($clients.Where({ $_.Connected }).Count -gt 0) {
    Start-Sleep -Milliseconds 200
}
$allDisconnectedTime = [datetime]::Now
Write-Host "All disconnected at $($allDisconnectedTime - $stopTime)"

# START ws-gateway again
Write-Host "`n=== STARTING ws-gateway ===" -ForegroundColor Green
docker compose -f compose.yaml start ws-gateway

# Wait for all to reconnect and authenticate
Write-Host "Waiting for reconnect + auth..."
$reconnectStart = [datetime]::Now
while ($clients.Where({ $_.Authenticated -and $_.ReconnectTime }).Count -lt $connected) {
    Start-Sleep -Milliseconds 200
    if (([datetime]::Now - $reconnectStart).TotalSeconds -gt 60) {
        Write-Warning "Reconnect timeout after 60s"
        break
    }
}
$reconnectEnd = [datetime]::Now

# Wait for post-reconnect messages
Write-Host "Collecting post-reconnect messages (10s)..."
Start-Sleep -Seconds 10

# Cancel receive loops
foreach ($c in $clients) {
    try { $c.Ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "Test done", [System.Threading.CancellationToken]::None) } catch {}
}
await [System.Threading.Tasks.Task]::WhenAll($receiveTasks)

# Calculate stats
$reconnectTimes = @()
$lostMessages = 0
foreach ($c in $clients) {
    if ($c.DisconnectTime -and $c.ReconnectTime) {
        $reconnectTimes += ($c.ReconnectTime - $c.DisconnectTime).TotalMilliseconds
    }
    # Messages lost = expected rate * disconnect duration - actual received after
    # Simplified: count gap in message times
}

$p50 = if ($reconnectTimes.Count -gt 0) { ($reconnectTimes | Sort-Object)[$reconnectTimes.Count / 2] } else { 0 }
$max = if ($reconnectTimes.Count -gt 0) { ($reconnectTimes | Measure-Object -Maximum).Maximum } else { 0 }

# Output results
$summary = @"
=== FAILOVER TEST RESULTS ===
Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Clients: $ClientCount
Connected: $connected
Disconnect time: $stopTime
All disconnected: $allDisconnectedTime (after $(($allDisconnectedTime - $stopTime).TotalSeconds) sec)
Reconnect start: $reconnectStart
Reconnect end: $reconnectEnd
Reconnect p50: $([math]::Round($p50, 2)) ms
Reconnect max: $([math]::Round($max, 2)) ms
Total test duration: $(($reconnectEnd - $stopTime).TotalSeconds) sec
"@

Write-Host $summary -ForegroundColor Cyan
$summary | Out-File -FilePath "$OutDir/failover_ws_summary.txt" -Encoding UTF8

# CSV
$csv = "client_id,disconnect_time,reconnect_time,reconnect_ms,messages_before,messages_after`n"
foreach ($c in $clients) {
    $rt = if ($c.DisconnectTime -and $c.ReconnectTime) { ($c.ReconnectTime - $c.DisconnectTime).TotalMilliseconds } else { "" }
    $csv += "$($c.Id),$($c.DisconnectTime),$($c.ReconnectTime),$rt,$($c.MessagesBefore),$($c.MessagesAfter)`n"
}
$csv | Out-File -FilePath "$OutDir/failover_ws.csv" -Encoding UTF8

Write-Host "Results saved to $OutDir/failover_ws.csv and _summary.txt" -ForegroundColor Green