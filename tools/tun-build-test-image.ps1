<#
.SYNOPSIS
    Build a side-by-side copy of the installed app carrying the current source, for testing TUN
    mode without touching the build the user is relying on.

.DESCRIPTION
    Deliberately *not* a jpackage run. The known-good image already contains the JRE and every
    dependency; the only things that change between it and a source build are two jars. Copying
    the image and swapping those is seconds instead of minutes, and -- the part that matters -- it
    leaves the working install completely untouched, so rolling back is "run the other exe"
    rather than "restore from a backup and hope".

    The jars have to be built first (in WSL, like every other Gradle task in this repo):

        ./gradlew :shared:desktopJar :desktop:jar
#>
[CmdletBinding()]
param(
    [string]$SourceImage = '',
    [string]$TargetImage = '',
    [string]$RepoRoot = '',
    # sing-box + wintun.dll to stage into the test image's engines dir.
    [string]$EnginesSource = ''
)

$ErrorActionPreference = 'Stop'

# Resolved here rather than in the param block: $PSScriptRoot is not reliably expanded in a
# default value depending on how the script was invoked, and silently getting C:\dist\... is
# exactly the kind of wrong path that then "cannot be found".
if (-not $RepoRoot) { $RepoRoot = Join-Path $PSScriptRoot '..' }
if (-not $SourceImage) { $SourceImage = Join-Path $RepoRoot 'dist\Smugly5' }
if (-not $TargetImage) { $TargetImage = Join-Path $RepoRoot 'dist\SmuglyTun' }
if (-not $EnginesSource) { $EnginesSource = Join-Path $RepoRoot 'engines' }

$SourceImage = [System.IO.Path]::GetFullPath($SourceImage)
$TargetImage = [System.IO.Path]::GetFullPath($TargetImage)
$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot)
$EnginesSource = [System.IO.Path]::GetFullPath($EnginesSource)

if (-not (Test-Path $SourceImage)) { throw "source image not found: $SourceImage" }

$jars = @(
    @{ From = Join-Path $RepoRoot 'shared\build\libs\shared-desktop.jar'; To = 'shared-desktop.jar' },
    @{ From = Join-Path $RepoRoot 'desktop\build\libs\desktop.jar';      To = 'desktop.jar' }
)
foreach ($j in $jars) {
    if (-not (Test-Path $j.From)) {
        throw "$($j.From) not built. Run: ./gradlew :shared:desktopJar :desktop:jar"
    }
}

# A test build older than the source it is supposed to be testing is the kind of thing that
# wastes an hour, so say so rather than quietly proceeding. Only *main* sources count: test
# sources never reach a jar, and comparing against them reports staleness that is not real.
foreach ($j in $jars) {
    $moduleSrc = Join-Path $RepoRoot ($j.Module + '\src')
    if (-not (Test-Path $moduleSrc)) { continue }
    $newest = Get-ChildItem -Path $moduleSrc -Recurse -File -Filter *.kt -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\src\[^\]*[Tt]est' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $newest) { continue }
    $jarTime = (Get-Item $j.From).LastWriteTime
    if ($jarTime -lt $newest.LastWriteTime) {
        throw ("{0} is older than {1} ({2:HH:mm:ss} < {3:HH:mm:ss}). " -f
            $j.To, $newest.Name, $jarTime, $newest.LastWriteTime) +
            'Run: ./gradlew :shared:desktopJar :desktop:jar'
    }
}

if (Test-Path $TargetImage) {
    Write-Host "removing previous test image: $TargetImage"
    # Anything still running out of it would keep the files locked and leave a half-updated image.
    Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($TargetImage, [System.StringComparison]::OrdinalIgnoreCase) } |
        ForEach-Object {
            Write-Host "  stopping $($_.ProcessName) pid=$($_.Id) from the old test image"
            Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
        }
    Start-Sleep -Milliseconds 500
    Remove-Item -Recurse -Force $TargetImage
}

Write-Host "copying $SourceImage -> $TargetImage"
Copy-Item -Recurse -Force $SourceImage $TargetImage

foreach ($j in $jars) {
    $dest = Join-Path $TargetImage "app\$($j.To)"
    Copy-Item -Force $j.From $dest
    Write-Host ("  {0}  {1:N0} KB" -f $j.To, ((Get-Item $dest).Length / 1KB))
}

$engineDir = Join-Path $TargetImage 'app\engines'
New-Item -ItemType Directory -Path $engineDir -Force | Out-Null
foreach ($name in @('sing-box.exe', 'wintun.dll')) {
    $src = Join-Path $EnginesSource $name
    if (-not (Test-Path $src)) {
        throw "$name not found in $EnginesSource -- see docs/tun-mode.md for where to get it"
    }
    Copy-Item -Force $src (Join-Path $engineDir $name)
    Write-Host ("  {0}  {1:N0} KB" -f $name, ((Get-Item $src).Length / 1KB))
}

Write-Host ''
Write-Host "test image ready: $TargetImage"
Write-Host "the working build at $SourceImage was not modified"
