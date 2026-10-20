param(
    [string]$Python = (Get-Command python -ErrorAction Stop).Source,
    [string]$Repository = 'E:\dev\zer0drv\blog',
    [string]$BackupRoot = 'E:\dev\zer0drv\backups',
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $Repository 'deploy\local-backup.py'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { throw 'Backup script is missing' }
foreach ($value in @($Python, $scriptPath, $BackupRoot)) {
    if ($value.Contains('"')) { throw 'Paths must not contain quotes' }
}
$configPath = Join-Path $Repository '.env'
$arguments = '"{0}" --root "{1}" --config "{2}" --execute' -f $scriptPath, $BackupRoot, $configPath
if (-not $Install) {
    Write-Output "Plan: Blog-Local-Backup at 02:00 and 14:00, current logged-in user, maintenance downtime."
    Write-Output "Plan: Blog-Backup-Status hourly; nonzero task result means failure or a backup older than 18 hours."
    Write-Output "Destination: $BackupRoot; no offsite copy or external notification; no automatic deletion."
    return
}
if (Get-ScheduledTask -TaskName 'Blog-Local-Backup','Blog-Backup-Status' -ErrorAction SilentlyContinue) {
    throw 'Existing backup tasks found; inspect them instead of silently replacing their settings'
}
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive
# Do not kill a process while its finally block is responsible for restarting services.
# Each Docker subprocess has its own bounded timeout; recovery duration is monitored separately.
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 5)
$action = New-ScheduledTaskAction -Execute $Python -Argument $arguments
$triggers = @((New-ScheduledTaskTrigger -Daily -At '02:00'), (New-ScheduledTaskTrigger -Daily -At '14:00'))
Register-ScheduledTask -TaskName 'Blog-Local-Backup' -Action $action -Trigger $triggers -Principal $principal -Settings $settings | Out-Null
$monitor = New-ScheduledTaskAction -Execute $Python -Argument ('"{0}" --root "{1}" --status' -f $scriptPath, $BackupRoot)
$hourly = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) -RepetitionInterval (New-TimeSpan -Hours 1)
Register-ScheduledTask -TaskName 'Blog-Backup-Status' -Action $monitor -Trigger $hourly -Principal $principal -Settings $settings | Out-Null
Write-Output 'Installed local tasks. Docker Desktop and the user session must remain available; validate the first run.'
