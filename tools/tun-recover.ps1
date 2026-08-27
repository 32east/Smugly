<#
.SYNOPSIS
    Put the machine back the way it was: stop the test build, restore settings and the system
    proxy, and bring the previous Smugly back up.

.DESCRIPTION
    This is the one script that must never need the network and must be safe to run twice. It is
    called from three places -- the end of a successful test, the failure path, and the watchdog
    that fires when nobody is left to call it -- so every step is idempotent and every step is
    attempted even if an earlier one threw.

    Order matters. sing-box owns the default route while it lives, so it dies first; only then is
    it safe to touch the proxy setting or start anything that expects normal routing.
#>
[CmdletBinding()]
param(
    # Previous, known-good launcher to bring back, e.g. dist\Smugly5\Smugly-cli.exe
    [Parameter(Mandatory = $true)][string]$OldExe,
    # Test launcher to make sure is dead, e.g. dist\SmuglyTun\Smugly-cli.exe
    [string]$NewExe = '',
    # settings.json copy taken before the test
    [string]$SettingsBackup = '',
    [string]$SettingsPath = (Join-Path $env:USERPROFILE '.smugly\settings.json'),
    # Skip restarting the old build (used when the caller will start it itself)
    [switch]$NoRestart,
    [string]$LogFile = ''
)

$ErrorActionPreference = 'Continue'

function Write-Step([string]$Message) {
    $line = "[{0:HH:mm:ss}] recover: {1}" -f (Get-Date), $Message
    Write-Host $line
    if ($LogFile) { Add-Content -Path $LogFile -Value $line -ErrorAction SilentlyContinue }
}

function Stop-ByPath([string]$Path, [string]$Label) {
    if (-not $Path) { return }
    $leaf = [System.IO.Path]::GetFileNameWithoutExtension($Path)
    $procs = Get-Process -Name $leaf -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $Path }
    foreach ($p in $procs) {
        Write-Step "stopping $Label pid=$($p.Id)"
        # A hard kill on purpose: these are jpackage launchers with no console, so there is no
        # graceful signal to send. Whatever the shutdown hook would have restored is restored
        # explicitly below instead of being hoped for.
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
}

# 1. The TUN first. While sing-box lives it owns the default route, so nothing else can be
#    checked or started meaningfully. Its adapter and routes vanish with the process.
$singbox = Get-Process -Name 'sing-box' -ErrorAction SilentlyContinue
foreach ($p in $singbox) {
    Write-Step "stopping sing-box pid=$($p.Id)"
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
}

# 2. The test build, and any engine it left behind.
Stop-ByPath -Path $NewExe -Label 'test build'
if ($NewExe) {
    $newDir = Split-Path -Parent $NewExe
    foreach ($name in @('xray', 's3fu', 'cdnfu', 'slipstream', 'slipstream-client')) {
        Get-Process -Name $name -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -and $_.Path.StartsWith($newDir, [System.StringComparison]::OrdinalIgnoreCase) } |
            ForEach-Object {
                Write-Step "stopping engine $name pid=$($_.Id)"
                Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
            }
    }
}

Start-Sleep -Milliseconds 800

# 3. Settings, before the old build reads them. It shares ~/.smugly with the test build, so a
#    left-over mode=VPN would have the previous version trying to do something it cannot.
if ($SettingsBackup -and (Test-Path $SettingsBackup)) {
    Write-Step "restoring settings.json"
    Copy-Item -Path $SettingsBackup -Destination $SettingsPath -Force -ErrorAction SilentlyContinue
}

# 4. The system proxy. A force-killed app cannot run its own shutdown hook, so the machine can be
#    left pointing at a local port that no longer listens -- which looks exactly like "the internet
#    is broken". The CLI knows how to undo it from the backup file it wrote.
if (Test-Path $OldExe) {
    Write-Step "restoring system proxy"
    # $ErrorActionPreference is Continue for the whole script precisely so this cannot abort:
    # in PowerShell 5.1 a native command's stderr becomes an ErrorRecord, and the CLI prints
    # warnings on a perfectly normal run. Recovery must never stop on a log line.
    try { & $OldExe --restore-system-proxy 2>&1 | ForEach-Object { Write-Step "  $_" } }
    catch { Write-Step "  (restore-system-proxy reported: $($_.Exception.Message))" }
}

# 5. Bring the previous build back.
if (-not $NoRestart -and (Test-Path $OldExe)) {
    $already = Get-Process -Name ([System.IO.Path]::GetFileNameWithoutExtension($OldExe)) -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $OldExe }
    if ($already) {
        Write-Step "previous build already running (pid=$($already[0].Id))"
    } else {
        Write-Step "starting previous build: $OldExe --connect"
        Start-Process -FilePath $OldExe -ArgumentList '--connect' -WindowStyle Hidden | Out-Null
    }
}

Write-Step 'done'
