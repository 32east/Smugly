<#
.SYNOPSIS
    Swap the running Smugly for the TUN build, prove the tunnel actually works, and put the old
    one back -- automatically, including when the test breaks the network badly enough that nobody
    can intervene.

.DESCRIPTION
    A TUN takes the machine's default route. If it goes wrong there is no browser, no Discord and
    no way to ask for help, so this script is built around one rule: **the previous, working setup
    comes back on its own.** Three things enforce that:

      * the test build lives in its own directory and the known-good one is never touched, so a
        rollback is "start the other exe", not "restore from a copy";
      * every exit path -- pass, fail, throw, Ctrl+C -- runs the same recovery in a finally block;
      * an independent watchdog process rolls back if the network dies or this script stops
        reporting in, which covers the case where this script is what got stuck.

    What it proves that proxy mode never could: `curl.exe` bypasses the Windows proxy setting
    entirely, so if *curl* comes back with the tunnel's exit IP, the traffic was carried by the
    adapter and not by anything reading a proxy setting.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\tun-live-test.ps1
#>
[CmdletBinding()]
param(
    [string]$OldExe = '',
    [string]$NewExe = '',
    [string]$SettingsPath = (Join-Path $env:USERPROFILE '.smugly\settings.json'),
    # Leave the TUN build running at the end instead of rolling back. For when a human is watching.
    [switch]$Keep,
    # Skip the split-tunnelling half (halves the runtime).
    [switch]$SkipSplitTest,
    [int]$ConnectTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Resolved here, not in the param block: $PSScriptRoot is not reliably expanded in a default
# value, and a silently wrong path here would mean "roll back to a build that does not exist".
if (-not $OldExe) { $OldExe = Join-Path $PSScriptRoot '..\dist\Smugly5\Smugly-cli.exe' }
if (-not $NewExe) { $NewExe = Join-Path $PSScriptRoot '..\dist\SmuglyTun\Smugly-cli.exe' }
$OldExe = [System.IO.Path]::GetFullPath($OldExe)
$NewExe = [System.IO.Path]::GetFullPath($NewExe)
$recover = Join-Path $PSScriptRoot 'tun-recover.ps1'
$watchdogScript = Join-Path $PSScriptRoot 'tun-watchdog.ps1'

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$workDir = Join-Path $env:TEMP "smugly-tun-test-$stamp"
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
$logFile = Join-Path $workDir 'test.log'
$heartbeat = Join-Path $workDir 'heartbeat'
$settingsBackup = Join-Path $workDir 'settings.backup.json'

$script:results = @()
$script:failed = $false
# Initialised up front because the finally block reads it: under StrictMode an unassigned
# variable there would throw *inside* the recovery path, which is the last place that may fail.
$tunnelIpBefore = ''


function Log([string]$Message) {
    $line = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $Message
    Write-Host $line
    Add-Content -Path $logFile -Value $line -ErrorAction SilentlyContinue
}

function Beat { Set-Content -Path $heartbeat -Value (Get-Date -Format o) -ErrorAction SilentlyContinue }

function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    $script:results += [pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail }
    if (-not $Ok) { $script:failed = $true }
    Log ("{0} {1}{2}" -f $(if ($Ok) { 'PASS' } else { 'FAIL' }), $Name, $(if ($Detail) { " -- $Detail" } else { '' }))
}

# Raw TCP to a literal address: no DNS, no proxy, no HTTP stack. Used as the liveness signal
# because DNS is among the first things a broken TUN takes down.
function Test-Internet {
    foreach ($t in @(@{ h = '1.1.1.1'; p = 443 }, @{ h = '8.8.8.8'; p = 53 })) {
        $c = New-Object System.Net.Sockets.TcpClient
        try {
            $a = $c.BeginConnect($t.h, $t.p, $null, $null)
            if ($a.AsyncWaitHandle.WaitOne(3000, $false) -and $c.Connected) { return $true }
        } catch { } finally { $c.Close() }
    }
    return $false
}

<#
    External IP as seen by a client that ignores the Windows proxy setting.

    --noproxy '*' is the whole point of using curl.exe here: it is exactly the class of program
    the old proxy mode could never cover, so its answer is the only one that distinguishes "the
    TUN is carrying traffic" from "some apps read a proxy setting".
#>
function Get-ExternalIp([int]$TimeoutSeconds = 15) {
    $exe = Join-Path $env:SystemRoot 'System32\curl.exe'
    if (-not (Test-Path $exe)) { return '' }
    try {
        $ip = & $exe --noproxy '*' -s --max-time $TimeoutSeconds 'https://api.ipify.org' 2>$null
        return ($ip | Out-String).Trim()
    } catch { return '' }
}

<#
    External IP as seen by a process that is NOT on the bypass list.

    Proxy explicitly disabled, so the only route to the internet is the adapter. This is the other
    half of the split-tunnelling proof: showing that the bypassed app got the real IP means little
    on its own, because a *broken* tunnel would produce the same answer. Two different processes
    getting two different IPs at the same moment is the thing that cannot happen by accident.
#>
function Get-ExternalIpTunnelled([int]$TimeoutSeconds = 15) {
    try {
        $req = [System.Net.HttpWebRequest]::Create('https://api.ipify.org')
        $req.Proxy = $null
        $req.Timeout = $TimeoutSeconds * 1000
        $resp = $req.GetResponse()
        try {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            return $reader.ReadToEnd().Trim()
        } finally { $resp.Close() }
    } catch { return '' }
}

function Test-TunAdapter {
    try {
        return [bool](Get-NetIPAddress -IPAddress '172.19.0.1' -ErrorAction SilentlyContinue)
    } catch { return $false }
}

<#
    A real UDP round trip through the tunnel.

    Not DNS: the config hijacks DNS on purpose and answers it over TCP, so a name lookup proves
    nothing about UDP. NTP does -- a 48-byte request to a public server and a 48-byte reply, over
    UDP/123, carried end to end by the adapter. This is the flow that voice chat and games depend
    on, and the one a CONNECT-only proxy could never carry.
#>
function Test-UdpThroughTunnel {
    $client = New-Object System.Net.Sockets.UdpClient
    try {
        $client.Client.ReceiveTimeout = 6000
        $request = New-Object byte[] 48
        $request[0] = 0x1B   # LI=0, VN=3, Mode=3 (client)
        # Cloudflare's NTP service, by address so no name has to resolve first.
        [void]$client.Send($request, $request.Length, '162.159.200.1', 123)
        $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        $reply = $client.Receive([ref]$remote)
        return ($reply.Length -ge 48)
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Set-SettingsValue([hashtable]$Values) {
    $json = Get-Content $SettingsPath -Raw | ConvertFrom-Json
    foreach ($k in $Values.Keys) {
        if ($json.PSObject.Properties.Name -contains $k) { $json.$k = $Values[$k] }
        else { $json | Add-Member -NotePropertyName $k -NotePropertyValue $Values[$k] }
    }
    # No BOM. Set-Content -Encoding utf8 writes one on PowerShell 5.1, and a BOM at the start of
    # settings.json used to make the app's JSON parser fail and silently fall back to defaults --
    # i.e. the test would run against mode=PROXY while the harness believed it had asked for TUN.
    # The app tolerates a BOM now; writing one anyway would still be writing a file no other
    # producer of this file writes.
    $text = $json | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($SettingsPath, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Start-TestBuild {
    Log "starting test build: $NewExe --connect"
    $out = Join-Path $workDir ("smugly-{0}.out.log" -f (Get-Random))
    $p = Start-Process -FilePath $NewExe -ArgumentList '--connect' -WindowStyle Hidden `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err" -PassThru
    return @{ Process = $p; Out = $out }
}

<#
    Why the head and not just the tail: a start failure says why in its first few lines, and the
    tail is then a thousand lines of per-second traffic counters that say nothing. Reading only
    the tail once cost a whole run.
#>
function Show-BuildOutput($Run) {
    Log '--- test build output (first lines) ---'
    Get-Content $Run.Out -ErrorAction SilentlyContinue -TotalCount 25 | ForEach-Object { Log "  $_" }
    Get-Content "$($Run.Out).err" -ErrorAction SilentlyContinue -TotalCount 15 | ForEach-Object { Log "  err: $_" }
}

function Wait-ForTun([int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Beat
        if (Test-TunAdapter) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

<#
    Run one of the CLI helpers and log whatever it says.

    The redirection is deliberate and so is the ErrorActionPreference dance: in Windows PowerShell
    5.1, piping a native executable's stderr wraps every line in an ErrorRecord, which under
    'Stop' turns a harmless warning into a terminating error. The CLI prints warnings on a normal
    run, so without this the harness aborts on output it was only meant to log.
#>
function Invoke-SmuglyCli([string]$Exe, [string]$Flag) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe $Flag 2>&1 | ForEach-Object { Log "  $_" }
    } catch {
        Log "  ($Flag failed: $($_.Exception.Message))"
    } finally {
        $ErrorActionPreference = $previous
    }
}

<#
    Stop the known-good build and everything it started.

    Deliberately not tun-recover.ps1: that script's job is the opposite direction -- it stops the
    *test* build and brings this one back. Reusing it here left the old build running, holding
    port 1080, and the test build then failed to start for a reason that had nothing to do with
    the TUN.
#>
function Stop-KnownGoodBuild {
    $dir = Split-Path -Parent $OldExe
    Get-Process -Name ([System.IO.Path]::GetFileNameWithoutExtension($OldExe)) -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $OldExe } |
        ForEach-Object {
            Log "  stopping known-good build pid=$($_.Id)"
            Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
        }
    foreach ($name in @('xray', 's3fu', 'cdnfu', 'slipstream', 'slipstream-client')) {
        Get-Process -Name $name -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -and $_.Path.StartsWith($dir, [System.StringComparison]::OrdinalIgnoreCase) } |
            ForEach-Object {
                Log "  stopping its engine $name pid=$($_.Id)"
                Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
            }
    }
    Start-Sleep -Seconds 2
    # A force-killed app never runs its shutdown hook, so the machine can be left pointing at a
    # local port that has just gone away.
    Invoke-SmuglyCli -Exe $OldExe -Flag '--restore-system-proxy' 
}

# The engine port is handed back slightly after the process dies, and starting the test build one
# instant too early fails with "port already in use" -- which reads like a bug in the thing under
# test rather than a race in the harness.
function Wait-PortFree([int]$Port, [int]$Seconds = 25) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $busy = $false
        $c = New-Object System.Net.Sockets.TcpClient
        try {
            $a = $c.BeginConnect('127.0.0.1', $Port, $null, $null)
            if ($a.AsyncWaitHandle.WaitOne(400, $false) -and $c.Connected) { $busy = $true }
        } catch { } finally { $c.Close() }
        if (-not $busy) { return $true }
        Beat
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Stop-TestBuild {
    $dir = Split-Path -Parent $NewExe
    Get-Process -Name ([System.IO.Path]::GetFileNameWithoutExtension($NewExe)) -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $NewExe } |
        ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    Get-Process -Name 'sing-box' -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    # The tunnel engine too: a force-killed launcher never runs its shutdown hook, so xray keeps
    # the engine port and the next run cannot bind it.
    foreach ($name in @('xray', 's3fu', 'cdnfu', 'slipstream', 'slipstream-client')) {
        Get-Process -Name $name -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -and $_.Path.StartsWith($dir, [System.StringComparison]::OrdinalIgnoreCase) } |
            ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    }
    Start-Sleep -Seconds 2
}

# ---------------------------------------------------------------- preflight

Log "work dir: $workDir"

if (-not (Test-Path $OldExe)) { throw "known-good build not found: $OldExe" }
if (-not (Test-Path $NewExe)) { throw "test build not found: $NewExe -- run tools\tun-build-test-image.ps1 first" }
if (-not (Test-Path $SettingsPath)) { throw "settings not found: $SettingsPath" }

$elevated = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $elevated) { throw 'TUN mode needs an elevated shell: creating the adapter and writing routes are privileged.' }

$engineDir = Join-Path (Split-Path -Parent $NewExe) 'app\engines'
foreach ($needed in @('sing-box.exe', 'wintun.dll')) {
    if (-not (Test-Path (Join-Path $engineDir $needed))) { throw "$needed missing from $engineDir" }
}

Copy-Item $SettingsPath $settingsBackup -Force
Log "settings backed up to $settingsBackup"
Beat

# ---------------------------------------------------------------- run

try {
    Log 'starting watchdog (independent process)'
    Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$watchdogScript`"",
        '-OldExe', "`"$OldExe`"", '-NewExe', "`"$NewExe`"",
        '-SettingsBackup', "`"$settingsBackup`"", '-Heartbeat', "`"$heartbeat`"",
        '-LogFile', "`"$logFile`""
    ) | Out-Null

    # Labelled carefully: curl ignores the system proxy, so in proxy mode this is the machine's
    # real IP even while a tunnel is up. That is the leak TUN mode exists to close, and it is why
    # the same measurement later is the proof that the adapter carried the traffic.
    $tunnelIpBefore = Get-ExternalIp
    Log "external IP as curl sees it right now (proxy mode: expected to be the real one): '$tunnelIpBefore'"

    Log 'stopping the running Smugly'
    Stop-KnownGoodBuild
    Beat
    foreach ($port in @(1080, 1081)) {
        if (-not (Wait-PortFree $port)) { throw "port $port is still in use after stopping the old build" }
    }
    Beat

    $directIp = Get-ExternalIp
    Log "external IP with no tunnel at all: '$directIp'"
    Check 'baseline: internet works without a tunnel' ([bool]$directIp) "ip=$directIp"
    if (-not $directIp) { throw 'no internet after stopping the old tunnel -- refusing to go further' }

    # ---- pass 1: TUN carries everything -------------------------------------
    Log 'configuring: mode=VPN (TUN), split tunnelling OFF'
    Set-SettingsValue @{ mode = 'VPN'; perAppMode = 'OFF'; perAppPackages = '' }
    $run = Start-TestBuild
    Beat

    $up = Wait-ForTun $ConnectTimeoutSeconds
    Check 'TUN adapter came up (172.19.0.1)' $up
    if (-not $up) {
        Show-BuildOutput $run
        throw 'the adapter never appeared'
    }

    Start-Sleep -Seconds 3
    Beat
    Check 'internet still reachable through the TUN' (Test-Internet)

    $tunIp = Get-ExternalIp
    Beat
    # This is the assertion the whole mode exists for: curl ignores the proxy setting, so a
    # tunnel exit IP here means the adapter carried it.
    Check 'curl.exe (ignores the system proxy) leaves via the tunnel' `
        ([bool]$tunIp -and $tunIp -ne $directIp) "tun=$tunIp direct=$directIp"

    Check 'UDP survives the tunnel (NTP round trip)' (Test-UdpThroughTunnel)

    # ---- pass 2: split tunnelling -------------------------------------------
    if (-not $SkipSplitTest) {
        Log 'reconfiguring: split tunnelling = all but selected, curl.exe bypasses'
        Stop-TestBuild
        Beat
        # The engine hands its listener back slightly after the process dies; starting the next
        # run too early fails to bind and looks exactly like "the TUN is broken".
        foreach ($port in @(1080, 1081)) {
            if (-not (Wait-PortFree $port)) { Log "WARNING: port $port still busy before restart" }
        }
        Set-SettingsValue @{ mode = 'VPN'; perAppMode = 'DISALLOWED'; perAppPackages = 'curl.exe' }
        $run = Start-TestBuild
        $up2 = Wait-ForTun $ConnectTimeoutSeconds
        Check 'TUN adapter came up again with rules' $up2
        if (-not $up2) { Show-BuildOutput $run }
        if ($up2) {
            Start-Sleep -Seconds 3
            Beat
            $bypassIp = Get-ExternalIp
            $tunnelledIp = Get-ExternalIpTunnelled
            Check 'a bypassed app leaves with the real IP' `
                ($bypassIp -eq $directIp) "bypassed=$bypassIp direct=$directIp"
            # The half that makes the one above mean something: at the same moment, a process that
            # is not on the bypass list must still be coming out of the tunnel.
            Check 'at the same time a non-bypassed app is still tunnelled' `
                ([bool]$tunnelledIp -and $tunnelledIp -ne $directIp) "tunnelled=$tunnelledIp bypassed=$bypassIp"
            Check 'the tunnel is still up for everyone else' (Test-TunAdapter)
        }
    }
} catch {
    $script:failed = $true
    Log "ERROR: $($_.Exception.Message)"
} finally {
    Beat
    if ($Keep -and -not $script:failed) {
        Log 'PASSED and -Keep was given: leaving the TUN build running'
        Log 'roll back by hand with: tools\tun-recover.ps1 -OldExe ... -NewExe ... -SettingsBackup ...'
    } else {
        Log 'rolling back to the previous build'
        & $recover -OldExe $OldExe -NewExe $NewExe -SettingsBackup $settingsBackup -LogFile $logFile
        Start-Sleep -Seconds 8
        $backIp = Get-ExternalIp
        Log "external IP after rollback: '$backIp' (was '$tunnelIpBefore' before the test)"
        if (-not $backIp) {
            Log 'WARNING: still no internet after rollback -- check the previous build by hand'
        }
    }
    # Standing the watchdog down is the last thing, so it is armed for the rollback too.
    Remove-Item $heartbeat -Force -ErrorAction SilentlyContinue

    Log ''
    Log '================ RESULT ================'
    foreach ($r in $script:results) {
        Log ("{0}  {1}{2}" -f $(if ($r.Ok) { '[ok]  ' } else { '[FAIL]' }), $r.Name, $(if ($r.Detail) { " ($($r.Detail))" } else { '' }))
    }
    Log ("verdict: {0}" -f $(if ($script:failed) { 'FAILED' } else { 'PASSED' }))
    Log "log: $logFile"
}

if ($script:failed) { exit 1 }
