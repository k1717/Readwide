param(
    [Parameter(Mandatory=$true)][string]$FixtureDir,
    [string]$OutDir = "build\reports\archive-fixtures",
    [string]$Password = ""
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $FixtureDir -PathType Container)) {
    throw "Fixture directory does not exist: $FixtureDir"
}

$FixtureDir = (Resolve-Path -LiteralPath $FixtureDir).ProviderPath
$OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
[System.IO.Directory]::CreateDirectory($OutDir) | Out-Null
$projectRoot = Split-Path -Parent $PSScriptRoot
$argsList = @(
    "-Dtextview.externalArchiveFixtureDir=$FixtureDir",
    "-Dtextview.archiveFixtureReportOutDir=$OutDir"
)
if ($Password.Length -gt 0) {
    $argsList += "-Dtextview.archiveFixturePassword=$Password"
}

& (Join-Path $projectRoot 'gradlew.bat') --project-dir $projectRoot testDebugUnitTest --tests com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest --tests com.readwide.manager.archive.ArchiveFixtureMatrixReportTest @argsList
if ($LASTEXITCODE -ne 0) {
    throw "Archive fixture tests failed with exit code $LASTEXITCODE"
}

Write-Host "Fixture test task completed. Report directory: $OutDir"
Write-Host "Check the test results for skipped fixtures; a skipped fixture does not establish coverage."
