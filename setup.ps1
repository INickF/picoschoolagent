# Installs the agent on every headset connected over USB (or the ones given with -Devices) and grants
# the permissions that cannot be granted from inside the headset. Run once per headset; it survives reboots.
#
#   .\setup.ps1                          # all connected headsets
#   .\setup.ps1 -Devices PA7L10MGF1234   # specific serial(s) from "adb devices"
param(
    [string[]]$Devices,
    [string]$Apk = "$PSScriptRoot\build\PicoSchoolAgent.apk"
)
$ErrorActionPreference = 'Stop'
$pkg = 'com.picoschool.agent'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $adb = Get-ChildItem 'C:\Program Files\Unity\Hub\Editor\*\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe' -ErrorAction SilentlyContinue |
        Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $adb) { throw 'adb.exe not found: install Android platform-tools or add adb to PATH' }
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (run build.ps1 first)" }

if (-not $Devices) {
    $Devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] }
}
if (-not $Devices) { throw 'No headsets found. Connect over USB, enable developer mode and accept the USB debugging prompt.' }

foreach ($d in $Devices) {
    Write-Host "== $d" -ForegroundColor Cyan
    & $adb -s $d install -r $Apk
    if ($LASTEXITCODE -ne 0) { Write-Warning "install failed on $d"; continue }

    # Launching / closing apps from a background service needs "display over other apps".
    & $adb -s $d shell appops set $pkg SYSTEM_ALERT_WINDOW allow
    # Detecting the current app needs usage access.
    & $adb -s $d shell appops set $pkg GET_USAGE_STATS allow
    # Keep the service out of battery optimisation so it is not stopped in idle.
    & $adb -s $d shell dumpsys deviceidle whitelist +$pkg | Out-Null
    # PICO blocks BOOT_COMPLETED for third-party apps; an enabled accessibility service is bound by the
    # system at boot instead, which restarts the agent. Keep any services that are already enabled.
    $svc = "$pkg/$pkg.AgentAccessibilityService"
    $enabled = ((& $adb -s $d shell settings get secure enabled_accessibility_services) -join '').Trim()
    if ($enabled -eq 'null' -or -not $enabled) { $enabled = $svc }
    elseif (($enabled -split ':') -notcontains $svc) { $enabled = "${enabled}:$svc" }
    & $adb -s $d shell settings put secure enabled_accessibility_services $enabled
    & $adb -s $d shell settings put secure accessibility_enabled 1

    # PICO forbids starting third-party processes by broadcast, so start the agent through its activity.
    $started = (& $adb -s $d shell am start -n "$pkg/.MainActivity") -join ' '
    if ($started -match 'not started') {
        Write-Host '   The headset refused to open the agent (boundary/setup screen showing?).' -ForegroundColor Yellow
        Write-Host '   Put the headset on, finish the boundary setup, then run setup.ps1 again.' -ForegroundColor Yellow
    }

    $ip = (& $adb -s $d shell ip -f inet addr show wlan0) -join ' ' -replace '.*inet (\d+\.\d+\.\d+\.\d+).*', '$1'
    Start-Sleep -Seconds 1
    try {
        $status = Invoke-RestMethod "http://${ip}:3350/api/status" -TimeoutSec 3
        Write-Host "   OK  $ip  $($status.name)  overlay=$($status.permissions.overlay) usage=$($status.permissions.usage)" -ForegroundColor Green
    } catch {
        Write-Host "   Installed. Agent address: ${ip}:3350 (not reachable from this PC right now: $($_.Exception.Message))" -ForegroundColor Yellow
    }
}
