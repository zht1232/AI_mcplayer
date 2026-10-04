param(
    [string]$ModelPath,
    [int]$Context = 32768,
    [int]$Port = 8080,
    [ValidateRange(32, 2048)][int]$BatchSize = 256,
    [ValidateRange(16, 2048)][int]$MicroBatchSize = 128,
    [string]$Alias = 'Qwen3.5-4B',
    [ValidateSet('on', 'off', 'auto')]
    [string]$Reasoning = 'off',
    [ValidateRange(-1, 16384)]
    [int]$ReasoningBudget = 256
)
$ErrorActionPreference = 'Stop'
if ($MicroBatchSize -gt $BatchSize) { throw 'MicroBatchSize must not exceed BatchSize.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ModelPath) { $ModelPath = Join-Path $projectRoot 'model\Qwen3.5-4B-Q4_K_M.gguf' }
$taskModel = Get-Item -LiteralPath $ModelPath -ErrorAction Stop
$taskServer = Join-Path $projectRoot 'llamacpp\gpu\llama-server.exe'
if (-not (Test-Path -LiteralPath $taskServer)) { throw 'Bundled llama-server.exe is missing.' }
$arguments = @('-m', $taskModel.FullName, '--alias', $Alias, '--host', '127.0.0.1', '--port', $Port,
    '-ngl', 99, '-c', $Context, '-b', $BatchSize, '-ub', $MicroBatchSize, '-np', 1, '--jinja', '--reasoning', $Reasoning,
    '--reasoning-budget', $ReasoningBudget, '--reasoning-format', 'deepseek', '--flash-attn', 'on')
# The 256-token ceiling remains available for requests that explicitly enable thinking.
& $taskServer @arguments
exit $LASTEXITCODE
