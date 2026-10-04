param(
    [string]$ModelPath,
    [int]$Context = 32768,
    [int]$Port = 8080,
    [string]$Alias = 'Qwen3.5-4B',
    [ValidateSet('on', 'off', 'auto')]
    [string]$Reasoning = 'on',
    [ValidateRange(-1, 16384)]
    [int]$ReasoningBudget = 256
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ModelPath) { $ModelPath = Join-Path $projectRoot 'model\Qwen3.5-4B-Q4_K_M.gguf' }
$taskModel = Get-Item -LiteralPath $ModelPath -ErrorAction Stop
$taskServer = Join-Path $projectRoot 'llamacpp\gpu\llama-server.exe'
if (-not (Test-Path -LiteralPath $taskServer)) { throw 'Bundled llama-server.exe is missing.' }
& $taskServer -m $taskModel.FullName --alias $Alias --host 127.0.0.1 --port $Port -ngl 99 -c $Context -b 64 -ub 32 -np 1 --jinja --reasoning $Reasoning --reasoning-budget $ReasoningBudget --reasoning-format deepseek --flash-attn on
exit $LASTEXITCODE
