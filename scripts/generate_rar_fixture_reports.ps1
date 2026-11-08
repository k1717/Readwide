param(
    [Parameter(Mandatory=$true)]
    [string]$FixtureDir,
    [string]$OutputDir = "build/reports/rar-fixtures"
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $FixtureDir -PathType Container)) {
    throw "Fixture directory does not exist: $FixtureDir"
}
$FixtureDir = (Resolve-Path -LiteralPath $FixtureDir).ProviderPath
$OutputDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDir)
[System.IO.Directory]::CreateDirectory($OutputDir) | Out-Null
$projectRoot = Split-Path -Parent $PSScriptRoot

& (Join-Path $projectRoot 'gradlew.bat') --project-dir $projectRoot testDebugUnitTest `
  --tests "com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest.rarFixtureMatrixReport_generatesExtractionSmokeMarkdown" `
  -Dtextview.externalArchiveFixtureDir="$FixtureDir" `
  -Dtextview.rarFixtureReportOutDir="$OutputDir"
if ($LASTEXITCODE -ne 0) {
    throw "RAR fixture tests failed with exit code $LASTEXITCODE"
}

Write-Host "Fixture test task completed. Report directory: $OutputDir"
Write-Host "Check the test results for skipped fixtures; a skipped fixture does not establish coverage."
