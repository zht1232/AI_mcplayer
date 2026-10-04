$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkCandidates = Get-ChildItem -LiteralPath "$env:ProgramFiles\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue | Where-Object Name -Like 'jdk-25*' | Sort-Object Name -Descending
$taskJdk = $jdkCandidates | Select-Object -First 1 -ExpandProperty FullName
if (-not $taskJdk) { throw 'Java 25 JDK is required.' }
$oldJavaHome = $env:JAVA_HOME
$oldJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $taskJdk
    $taskTmp = Join-Path $projectRoot '.tools\jdk-tmp'
    New-Item -ItemType Directory -Force -Path $taskTmp | Out-Null
    $env:JAVA_TOOL_OPTIONS = "$oldJavaOptions -Djdk.net.unixdomain.tmpdir=$taskTmp -Djava.io.tmpdir=$taskTmp".Trim()
    $taskGradle = Join-Path $projectRoot '.tools\gradle-9.7.1\bin\gradle.bat'
    if (-not (Test-Path -LiteralPath $taskGradle)) { $taskGradle = Join-Path $projectRoot 'gradlew.bat' }
    Push-Location -LiteralPath $projectRoot
    try {
        & $taskGradle build --no-daemon '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m' --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:JAVA_TOOL_OPTIONS = $oldJavaOptions
}
