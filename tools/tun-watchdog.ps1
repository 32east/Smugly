<#
.SYNOPSIS
    Independent guard for a live TUN test: if the machine loses the internet, or the test driver
    stops reporting in, put everything back without waiting to be asked.

.DESCRIPTION
    Runs as its own detached process, deliberately not as a job of the driver. The failure this
    exists for is precisely the one where the driver cannot help: a TUN that hijacks the default
    route and then breaks, or a driver that is killed (or blocked) while the adapter is up. A job
    would die with its parent; this does not.

    Two independent triggers, either one is enough:

      * connectivity   -- a raw TCP connect to a fixed IP, no DNS involved, because DNS is one of
                         the things a broken TUN takes down first.
      * heartbeat      -- the driver touches a file every few seconds. If it stops, whatever is
                         holding the driver is also holding the user's network.

    Rollback itself is [tun-recover.ps1], which needs no network at all.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OldExe,
    [Parameter(Mandatory = $true)][string]$NewExe,
    [Parameter(Mandatory = $true)][string]$SettingsBackup,
    [Parameter(Mandatory = $true)][string]$Heartbeat,
    [string]$LogFile = '',
    # Consecutive failed probes before rolling back. 6 x 5s = 30s of no internet, which is long
    # enough to ride out a tunnel reconnect and short enough not to strand anyone.
    [int]$FailuresBeforeRollback = 6,
    [int]$IntervalSeconds = 5,
    # How stale the driver's heartbeat may get before it counts as gone.
    [int]$HeartbeatTimeoutSeconds = 90,
    # Hard stop, so a forgotten watchdog cannot outlive the session.
    [int]$MaxMinutes = 30
)

$ErrorActionPreference = 'Continue'
$recover = Join-Path $PSScriptRoot 'tun-recover.ps1'

function Write-Step([string]$Message) {
    $line = "[{0:HH:mm:ss}] watchdog: {1}" -f (Get-Date), $Message
    Write-Host $line
    if ($LogFile) { Add-Content -Path $LogFile -Value $line -ErrorAction SilentlyContinue }
}

# Raw TCP to a literal address: no DNS, no proxy, no HTTP stack that might be configured to go
# somewhere clever. Either a packet gets out and back, or it does not.
function Test-Internet {
    foreach ($target in @(@{ h = '1.1.1.1'; p = 443 }, @{ h = '8.8.8.8'; p = 53 })) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $client.BeginConnect($target.h, $target.p, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(3000, $false) -and $client.Connected) { return $true }
        } catch {
        } finally {
            $client.Close()
        }
    }
    return $false
}

Write-Step "armed (interval=${IntervalSeconds}s, rollback after $FailuresBeforeRollback failures)"

$deadline = (Get-Date).AddMinutes($MaxMinutes)
$failures = 0

while ($true) {
    Start-Sleep -Seconds $IntervalSeconds

    # The driver removes the heartbeat when the test ends cleanly; that is the normal way out.
    if (-not (Test-Path $Heartbeat)) {
        Write-Step 'heartbeat gone -- test finished, standing down'
        break
    }

    $reason = $null
    $age = ((Get-Date) - (Get-Item $Heartbeat).LastWriteTime).TotalSeconds
    if ($age -gt $HeartbeatTimeoutSeconds) {
        $reason = "driver stopped reporting $([int]$age)s ago"
    } elseif (-not (Test-Internet)) {
        $failures++
        Write-Step "no internet ($failures/$FailuresBeforeRollback)"
        if ($failures -ge $FailuresBeforeRollback) {
            $reason = "no internet for $($failures * $IntervalSeconds)s"
        }
    } elseif ($failures -gt 0) {
        Write-Step 'internet is back'
        $failures = 0
    }

    if ((Get-Date) -gt $deadline) { $reason = "watchdog time limit (${MaxMinutes}m) reached" }

    if ($reason) {
        Write-Step "ROLLING BACK: $reason"
        & $recover -OldExe $OldExe -NewExe $NewExe -SettingsBackup $SettingsBackup -LogFile $LogFile
        # Give the restored tunnel a moment, then say whether the machine is actually usable
        # again -- a rollback that did not fix it is something the user has to know about.
        Start-Sleep -Seconds 8
        Write-Step "after rollback: internet=$(Test-Internet)"
        Remove-Item $Heartbeat -Force -ErrorAction SilentlyContinue
        break
    }
}

Write-Step 'exit'
