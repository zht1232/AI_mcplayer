param(
    [Parameter(Mandatory=$true)][string]$InstanceDir,
    [string]$JarPath
)
$ErrorActionPreference = 'Stop'
$instance = (Resolve-Path -LiteralPath $InstanceDir -ErrorAction Stop).Path
if (-not $JarPath) { $JarPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'mods\wildling-0.1.4.jar' }
$sourceJar = (Get-Item -LiteralPath $JarPath -ErrorAction Stop).FullName
$mods = [IO.Path]::GetFullPath((Join-Path $instance 'mods'))
if (-not $mods.StartsWith([IO.Path]::GetFullPath($instance).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Mods directory is outside the selected instance.' }
New-Item -ItemType Directory -Force -Path $mods | Out-Null
$targetJar = Join-Path $mods 'wildling-0.1.4.jar'
$old = @(Get-ChildItem -LiteralPath $mods -File | Where-Object Name -Match '^(mc-ai-partner|wildling)-[0-9.]+\.jar$')
if ($old.Count -eq 1 -and $old[0].FullName -eq $targetJar -and (Get-FileHash -LiteralPath $sourceJar).Hash -eq (Get-FileHash -LiteralPath $targetJar).Hash) { Write-Output 'Wildling v0.1.4 is already installed.'; exit 0 }
$backup = Join-Path $instance ('.wildling-backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
if (-not [IO.Path]::GetFullPath($backup).StartsWith([IO.Path]::GetFullPath($instance).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Backup directory is outside the selected instance.' }
New-Item -ItemType Directory -Force -Path $backup | Out-Null
try {
    foreach ($file in $old) { if ($file.FullName -ne $sourceJar) { Move-Item -LiteralPath $file.FullName -Destination (Join-Path $backup $file.Name) } }
    if ($sourceJar -ne $targetJar) { Copy-Item -LiteralPath $sourceJar -Destination $targetJar }
    if ((Get-FileHash -LiteralPath $sourceJar).Hash -ne (Get-FileHash -LiteralPath $targetJar).Hash) { throw 'Installed jar hash mismatch.' }
} catch {
    foreach ($file in Get-ChildItem -LiteralPath $backup -File) {
        $restore = Join-Path $mods $file.Name
        if (-not (Test-Path -LiteralPath $restore)) { Move-Item -LiteralPath $file.FullName -Destination $restore }
    }
    throw 'Installation could not finish. Close the Minecraft client, then run this script again. Existing files were preserved.'
}
$fabric = Join-Path (Split-Path -Parent $sourceJar) 'fabric-api-0.161.0+26.2.jar'
if ((Test-Path -LiteralPath $fabric) -and -not (Get-ChildItem -LiteralPath $mods -File -Filter 'fabric-api-*.jar')) { Copy-Item -LiteralPath $fabric -Destination (Join-Path $mods 'fabric-api-0.161.0+26.2.jar') }
Write-Output ('Installed Wildling v0.1.4: ' + $targetJar)
Write-Output ('Previous versions backed up: ' + $backup)
