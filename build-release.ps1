# Readwide release build helper.
#
# This script intentionally does not store signing passwords. It reads the
# keystore password at runtime and passes signing values to Gradle through
# environment variables.

param(
    [string] $KeystorePath = "$env:USERPROFILE\AndroidKeys\textview-release.jks",
    [string] $KeyAlias = "textview",
    [switch] $SeparateKeyPassword,
    [switch] $Clean
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw "Release keystore not found: $KeystorePath"
}

$storePassSecure = Read-Host "Keystore password" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePassSecure)
try {
    $storePassPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}

if ($SeparateKeyPassword) {
    $keyPassSecure = Read-Host "Key password" -AsSecureString
    $keyPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keyPassSecure)
    try {
        $keyPassPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPtr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPtr)
    }
} else {
    $keyPassPlain = $storePassPlain
}

$env:TEXTVIEW_KEYSTORE_PATH = $KeystorePath
$env:TEXTVIEW_KEYSTORE_PASSWORD = $storePassPlain
$env:TEXTVIEW_KEY_ALIAS = $KeyAlias
$env:TEXTVIEW_KEY_PASSWORD = $keyPassPlain

Write-Host ""
Write-Host "Project root: $ProjectRoot"
Write-Host "Keystore: $env:TEXTVIEW_KEYSTORE_PATH"
Write-Host "Key alias: $env:TEXTVIEW_KEY_ALIAS"
Write-Host "JAVA_HOME=$env:JAVA_HOME"
java -version

Write-Host ""
Write-Host "Building release APK..."
if ($Clean) {
    .\gradlew.bat clean assembleRelease --offline
} else {
    .\gradlew.bat assembleRelease --offline
}

Write-Host ""
Write-Host "Release APK:"
Get-ChildItem ".\app\build\outputs\apk\release\*.apk" -ErrorAction SilentlyContinue |
    Select-Object FullName, Length, LastWriteTime
