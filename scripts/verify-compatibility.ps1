param(
    [Parameter(Mandatory = $true)][string] $Java8Home,
    [Parameter(Mandatory = $true)][string] $ModernJavaHome,
    [string] $Maven = 'mvn'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
Push-Location $projectDirectory
try {
    & $Maven dependency:copy '-Dartifact=com.github.bbottema:clustered-object-pool:4.0.4' '-DoutputDirectory=target/compatibility-baseline'
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the released compatibility baseline' }
    & $Maven dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/compatibility-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve runtime dependencies' }
    [xml] $projectPom = Get-Content pom.xml -Raw
    $candidate = Join-Path $projectDirectory "target/clustered-object-pool-$($projectPom.project.version).jar"
    $dependencies = (Get-Content target/compatibility-classpath.txt -Raw).Trim()
    $baseline = Join-Path $projectDirectory 'target/compatibility-baseline/clustered-object-pool-4.0.4.jar'
    $legacyClasses = New-Item -ItemType Directory -Force target/compatibility-legacy
    $apiClasses = New-Item -ItemType Directory -Force target/compatibility-api
    $moduleClasses = New-Item -ItemType Directory -Force target/compatibility-module
    & "$Java8Home/bin/javac.exe" -cp "$baseline;$dependencies" -d $legacyClasses.FullName src/test/compatibility/LegacyClusterClient.java
    if ($LASTEXITCODE -ne 0) { throw 'Legacy fixture did not compile against the previous release' }
    & "$Java8Home/bin/javac.exe" -cp "$candidate;$dependencies" -d $apiClasses.FullName src/test/compatibility/consumer/ClusterApiConsumer.java
    if ($LASTEXITCODE -ne 0) { throw 'Public API fixture did not compile on Java 8' }
    foreach ($runtime in @($Java8Home, $ModernJavaHome)) {
        & "$runtime/bin/java.exe" -cp "$($legacyClasses.FullName);$candidate;$dependencies" LegacyClusterClient
        if ($LASTEXITCODE -ne 0) { throw 'Previously compiled client failed' }
        & "$runtime/bin/java.exe" -cp "$($apiClasses.FullName);$candidate;$dependencies" consumer.ClusterApiConsumer
        if ($LASTEXITCODE -ne 0) { throw 'Public API classpath client failed' }
    }
    & "$ModernJavaHome/bin/javac.exe" --module-path "$candidate;$dependencies" -d $moduleClasses.FullName src/test/compatibility/module-info.java src/test/compatibility/consumer/ClusterApiConsumer.java
    if ($LASTEXITCODE -ne 0) { throw 'Public API module-path compilation failed' }
    & "$ModernJavaHome/bin/java.exe" --module-path "$($moduleClasses.FullName);$candidate;$dependencies" --add-modules ALL-MODULE-PATH --module cluster.compatibility.consumer/consumer.ClusterApiConsumer
    if ($LASTEXITCODE -ne 0) { throw 'Public API module-path client failed' }
} finally {
    Pop-Location
}
