# Runs the external archive fixture smoke test against a local fixture folder.

param(
    [string] $FixtureDir = "$env:USERPROFILE\Downloads"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
$env:TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR = $FixtureDir

Write-Host "Project root: $ProjectRoot"
Write-Host "Fixture dir: $env:TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR"

.\gradlew.bat :app:testDebugUnitTest --tests com.readwide.manager.archive.ExternalArchiveFixtureSmokeTest --offline
