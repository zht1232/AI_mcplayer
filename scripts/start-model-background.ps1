param([string]$ModelPath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$listening = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
if ($listening) { Write-Output 'A service is already listening on port 8080. No duplicate model process was started.'; exit 0 }
$launcher = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$script = Join-Path $PSScriptRoot 'start-model.ps1'
$arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $script + '"'
if ($ModelPath) {
    $model = Get-Item -LiteralPath $ModelPath -ErrorAction Stop
    $arguments += ' -ModelPath "' + $model.FullName + '"'
}
$logRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$process = Start-Process -FilePath $launcher -ArgumentList $arguments -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'model-service.stdout.log') -RedirectStandardError (Join-Path $logRoot 'model-service.stderr.log') -PassThru
Write-Output ('Wildling local model launcher started, PID ' + $process.Id + '. Closing this window does not stop the model.')
