# Readwide diagnostic build helper.
#
# This keeps the release application ID/signing key, but builds the diagnostic
# variant with minify/shrink disabled so READWIDE_DIAG logs remain visible.

param(
    [string] $KeystorePath = $env:TEXTVIEW_KEYSTORE_PATH,
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

if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Read-Host "Release keystore path"
}

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
Write-Host "Building diagnostic APK..."
if ($Clean) {
    .\gradlew.bat clean assembleDiagnostic --offline
} else {
    .\gradlew.bat assembleDiagnostic --offline
}

Write-Host ""
Write-Host "Diagnostic APK:"
Get-ChildItem ".\app\build\outputs\apk\diagnostic\*.apk" -ErrorAction SilentlyContinue |
    Select-Object FullName, Length, LastWriteTime
