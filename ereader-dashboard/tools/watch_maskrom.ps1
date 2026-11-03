# Logs every time the Gen 2 maskrom/loader device (2207:310C) appears or disappears, with
# timestamps, and runs $Script the moment it is present with the WinUSB driver bound.
# Default $Script is the READ-ONLY backup; pass tools/flash_boot.sh only after approval.
param([int]$Minutes = 20, [string]$Script = "tools/read_backup.sh")
$root = Split-Path -Parent $PSScriptRoot
$log = Join-Path $root 'backup\maskrom-watch.log'
New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null

# UTF-8 log (Tee-Object in Windows PowerShell 5.1 writes UTF-16, which other tools misread).
function Write-Log([string]$text) {
    Write-Output $text
    Add-Content -Path $log -Value $text -Encoding UTF8
}

$end = (Get-Date).AddMinutes($Minutes)
$was = $false
Write-Log "watch start $(Get-Date -Format HH:mm:ss.fff)"
while ((Get-Date) -lt $end) {
    $dev = @(Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -like 'USB\VID_2207&PID_310C*' -or $_.InstanceId -like 'USB\VID_0000&PID_0002*' })
    $now = $dev.Count -gt 0
    if ($now -ne $was) {
        $desc = if ($now) { ($dev | ForEach-Object { "$($_.InstanceId) [$($_.Status)]" }) -join ', ' } else { 'gone' }
        Write-Log "$(Get-Date -Format HH:mm:ss.fff)  $desc"
        $was = $now
    }
    $rk = @($dev | Where-Object { $_.InstanceId -like 'USB\VID_2207&PID_310C*' })
    if ($rk.Count -gt 0) {
        $svc = (Get-PnpDeviceProperty -InstanceId $rk[0].InstanceId -KeyName 'DEVPKEY_Device_Service' -ErrorAction SilentlyContinue).Data
        if ($svc -eq 'WinUSB') {
            Write-Log "$(Get-Date -Format HH:mm:ss.fff)  WinUSB bound -> starting $Script"
            $output = & 'C:\Git\bin\bash.exe' (Join-Path $root $Script) 2>&1
            $code = $LASTEXITCODE
            $output | ForEach-Object { Write-Log "$_" }
            exit $code
        }
    }
    Start-Sleep -Milliseconds 300
}
Write-Log "watch end (timeout) $(Get-Date -Format HH:mm:ss.fff)"
exit 2
