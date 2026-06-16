param(
    [Parameter(Mandatory=$true)][string]$FixtureDir,
    [string]$OutDir = "build\reports\archive-fixtures",
    [string]$Password = ""
)

if (-not (Test-Path $FixtureDir -PathType Container)) {
    Write-Error "Fixture directory does not exist: $FixtureDir"
    exit 2
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$argsList = @(
    "-Dtextview.externalArchiveFixtureDir=$FixtureDir",
    "-Dtextview.archiveFixtureReportOutDir=$OutDir"
)
if ($Password.Length -gt 0) {
    $argsList += "-Dtextview.archiveFixturePassword=$Password"
}

& .\gradlew.bat testDebugUnitTest --tests com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest --tests com.readwide.manager.archive.ArchiveFixtureMatrixReportTest @argsList

Write-Host "Archive fixture reports written under: $OutDir"
