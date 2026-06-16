param(
    [Parameter(Mandatory=$true)]
    [string]$FixtureDir,
    [string]$OutputDir = "build/reports/rar-fixtures"
)

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

.\gradlew.bat testDebugUnitTest `
  --tests "com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest.rarFixtureMatrixReport_generatesExtractionSmokeMarkdown" `
  -Dtextview.externalArchiveFixtureDir="$FixtureDir" `
  -Dtextview.rarFixtureReportOutDir="$OutputDir"

Write-Host "RAR fixture reports written to: $OutputDir"
