# Readwide / TextView Reader debug build helper.

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

Write-Host "Project root: $ProjectRoot"
Write-Host "JAVA_HOME=$env:JAVA_HOME"
java -version

.\gradlew.bat assembleDebug --offline

Get-ChildItem ".\app\build\outputs\apk\debug\*.apk" -ErrorAction SilentlyContinue |
    Select-Object FullName, Length, LastWriteTime
