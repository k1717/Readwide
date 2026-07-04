# Readwide release build helper.
#
# This script intentionally does not store signing passwords. It reads the
# keystore password at runtime and passes signing values to Gradle through
# environment variables.

param(
    [string] $KeystorePath = $(if ($env:READWIDE_KEYSTORE_PATH) { $env:READWIDE_KEYSTORE_PATH } else { $env:TEXTVIEW_KEYSTORE_PATH }),
    [string] $KeyAlias = "readwide",
    [switch] $SeparateKeyPassword,
    [switch] $Clean
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

# Save the session's toolchain env and any keystore path/alias the caller set, so we
# can restore them in the finally below -- even if keystore validation throws -- instead
# of leaving JAVA_HOME/Path mutated or clobbering the caller's configuration.
$oldJavaHome = $env:JAVA_HOME
$oldAndroidHome = $env:ANDROID_HOME
$oldAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$oldPath = $env:Path
$oldReadwideKeystorePath = $env:READWIDE_KEYSTORE_PATH
$oldReadwideKeyAlias = $env:READWIDE_KEY_ALIAS

try {
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

    $env:READWIDE_KEYSTORE_PATH = $KeystorePath
    $env:READWIDE_KEYSTORE_PASSWORD = $storePassPlain
    $env:READWIDE_KEY_ALIAS = $KeyAlias
    $env:READWIDE_KEY_PASSWORD = $keyPassPlain

    Write-Host ""
    Write-Host "Project root: $ProjectRoot"
    Write-Host "Keystore: [provided]"
    Write-Host "Key alias: $env:READWIDE_KEY_ALIAS"
    Write-Host "JAVA_HOME=$env:JAVA_HOME"
    java -version

    Write-Host ""
    Write-Host "Building release APK..."
    if ($Clean) {
        .\gradlew.bat clean assembleRelease --offline
    } else {
        .\gradlew.bat assembleRelease --offline
    }
    # gradlew.bat is a native command; PowerShell does not throw on its non-zero
    # exit, so check explicitly and fail loudly instead of letting the APK listing
    # below report a stale APK from a previous build as if it were this one.
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    # Always clear the decrypted passwords from this session, restore the caller's
    # original keystore path/alias (or remove ours if they had none), and restore the
    # toolchain env captured above -- regardless of where in the try we exited.
    Remove-Item Env:\READWIDE_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\READWIDE_KEY_PASSWORD -ErrorAction SilentlyContinue
    if ($null -ne $oldReadwideKeystorePath) { $env:READWIDE_KEYSTORE_PATH = $oldReadwideKeystorePath } else { Remove-Item Env:\READWIDE_KEYSTORE_PATH -ErrorAction SilentlyContinue }
    if ($null -ne $oldReadwideKeyAlias) { $env:READWIDE_KEY_ALIAS = $oldReadwideKeyAlias } else { Remove-Item Env:\READWIDE_KEY_ALIAS -ErrorAction SilentlyContinue }
    $env:Path = $oldPath
    if ($null -ne $oldJavaHome) { $env:JAVA_HOME = $oldJavaHome } else { Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue }
    if ($null -ne $oldAndroidHome) { $env:ANDROID_HOME = $oldAndroidHome } else { Remove-Item Env:\ANDROID_HOME -ErrorAction SilentlyContinue }
    if ($null -ne $oldAndroidSdkRoot) { $env:ANDROID_SDK_ROOT = $oldAndroidSdkRoot } else { Remove-Item Env:\ANDROID_SDK_ROOT -ErrorAction SilentlyContinue }
}

Write-Host ""
Write-Host "Release APK:"
Get-ChildItem ".\app\build\outputs\apk\release\*.apk" -ErrorAction SilentlyContinue |
    Select-Object FullName, Length, LastWriteTime
