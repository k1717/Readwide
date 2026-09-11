[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Output
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not ('Readwide.SourceZip.Crc32' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;

namespace Readwide.SourceZip {
    public static class Crc32 {
        private static readonly uint[] Table = BuildTable();

        private static uint[] BuildTable() {
            uint[] table = new uint[256];
            for (uint i = 0; i < table.Length; i++) {
                uint value = i;
                for (int bit = 0; bit < 8; bit++) {
                    value = (value & 1) != 0
                        ? 0xEDB88320U ^ (value >> 1)
                        : value >> 1;
                }
                table[i] = value;
            }
            return table;
        }

        public static uint Compute(Stream stream) {
            byte[] buffer = new byte[1024 * 1024];
            uint crc = 0xFFFFFFFFU;
            int read;
            while ((read = stream.Read(buffer, 0, buffer.Length)) > 0) {
                for (int i = 0; i < read; i++) {
                    crc = Table[(crc ^ buffer[i]) & 0xFF] ^ (crc >> 8);
                }
            }
            return crc ^ 0xFFFFFFFFU;
        }
    }
}
'@
}

$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
$outputPath = [System.IO.Path]::GetFullPath($outputPath)
if (Test-Path -LiteralPath $outputPath) {
    throw "Refusing to overwrite existing ZIP: $outputPath"
}
$temporaryPath = $outputPath + '.' + [guid]::NewGuid().ToString('N') + '.new'
$outputDirectory = Split-Path -Parent $outputPath
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

$libarchiveCmake = 'third_party/libarchive-android/library/src/main/jni/external/libarchive/build/cmake'
$excludedDirectories = @(
    '.git', '.gradle', '.idea', '.cxx', '.externalNativeBuild',
    '.kotlin', 'build', 'captures'
)
$excludedNames = @(
    '.DS_Store', 'Thumbs.db', 'desktop.ini', 'GoogleService-Info.plist',
    'google-services.json', 'keystore.properties', 'local.properties',
    'search_func.txt', 'search_funcs.txt', 'search_ui.txt', 'secrets.properties'
)
$excludedSuffixes = @(
    '.aab', '.apk', '.apks', '.ap_', '.bak', '.class', '.der', '.dex',
    '.hprof', '.jks', '.keystore', '.log', '.orig', '.p12', '.pem',
    '.pfx', '.swp', '.tmp'
)

function Get-RelativeSourcePath([string]$FullName) {
    $full = [System.IO.Path]::GetFullPath($FullName)
    if (-not $full.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Source path escaped project root: $full"
    }
    return $full.Substring($root.Length + 1).Replace('\', '/')
}

function Get-OutputRelativePath([string]$Path) {
    $full = [System.IO.Path]::GetFullPath($Path)
    if ($full.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($root.Length + 1).Replace('\', '/')
    }
    return $null
}

$outputRelative = Get-OutputRelativePath $outputPath
$temporaryRelative = Get-OutputRelativePath $temporaryPath

function Test-SourceIncluded([string]$Relative) {
    if ($Relative -eq $outputRelative -or $Relative -eq $temporaryRelative) {
        return $false
    }
    $parts = $Relative -split '/'
    $name = $parts[$parts.Length - 1]
    if ($excludedNames -contains $name) {
        return $false
    }
    $suffix = [System.IO.Path]::GetExtension($name).ToLowerInvariant()
    if ($excludedSuffixes -contains $suffix) {
        return $false
    }
    if ($name -eq '.env' -or $name.StartsWith('.env.')) {
        return $false
    }
    $lowerName = $name.ToLowerInvariant()
    if ($lowerName.EndsWith('.json') -and
            ($lowerName.StartsWith('readwide_backup_') -or
             $lowerName.StartsWith('textview_backup_'))) {
        return $false
    }
    for ($index = 0; $index -lt $parts.Length - 1; $index++) {
        $part = $parts[$index]
        if (-not ($excludedDirectories -contains $part)) {
            continue
        }
        if ($part -eq 'build' -and
                ($Relative -eq $libarchiveCmake -or
                 $Relative.StartsWith($libarchiveCmake + '/'))) {
            continue
        }
        return $false
    }
    return $true
}

$files = @(
    Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
        $relative = Get-RelativeSourcePath $_.FullName
        if (Test-SourceIncluded $relative) {
            [pscustomobject]@{ Relative = $relative; Source = $_.FullName }
        }
    } | Sort-Object Relative
)

$ownsTemporary = $false
try {
    $fileStream = [System.IO.File]::Open(
        $temporaryPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None)
    $ownsTemporary = $true
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $fileStream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $true)
        try {
            foreach ($file in $files) {
                $entry = $archive.CreateEntry(
                    $file.Relative,
                    [System.IO.Compression.CompressionLevel]::Optimal)
                $modified = (Get-Item -LiteralPath $file.Source).LastWriteTime
                if ($modified.Year -lt 1980) {
                    $modified = [datetime]::new(1980, 1, 1)
                } elseif ($modified.Year -gt 2107) {
                    $modified = [datetime]::new(2107, 12, 31, 23, 59, 58)
                }
                $entry.LastWriteTime = [datetimeoffset]$modified
                $input = [System.IO.File]::OpenRead($file.Source)
                try {
                    $outputStream = $entry.Open()
                    try {
                        $input.CopyTo($outputStream, 1024 * 1024)
                    } finally {
                        $outputStream.Dispose()
                    }
                } finally {
                    $input.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $fileStream.Dispose()
    }

    # ZipArchive uses the host platform in "version made by". Patch each central
    # header to UNIX and set a regular-file POSIX mode so gradlew/*.sh remain
    # executable when the GitHub source package is unpacked on Linux.
    $bytes = [System.IO.File]::ReadAllBytes($temporaryPath)
    $minimumEocd = [Math]::Max(0, $bytes.Length - 65557)
    $eocd = -1
    for ($index = $bytes.Length - 22; $index -ge $minimumEocd; $index--) {
        if ($bytes[$index] -eq 0x50 -and $bytes[$index + 1] -eq 0x4B -and
                $bytes[$index + 2] -eq 0x05 -and $bytes[$index + 3] -eq 0x06) {
            $eocd = $index
            break
        }
    }
    if ($eocd -lt 0) {
        throw 'ZIP end-of-central-directory record is missing'
    }
    $entryCount = [System.BitConverter]::ToUInt16($bytes, $eocd + 10)
    $centralOffset = [int][System.BitConverter]::ToUInt32($bytes, $eocd + 16)
    if ($entryCount -ne $files.Count) {
        throw "ZIP central entry count mismatch: $entryCount != $($files.Count)"
    }

    $expectedCrc = @{}
    $centralModes = @{}
    $cursor = $centralOffset
    for ($entryIndex = 0; $entryIndex -lt $entryCount; $entryIndex++) {
        if ($bytes[$cursor] -ne 0x50 -or $bytes[$cursor + 1] -ne 0x4B -or
                $bytes[$cursor + 2] -ne 0x01 -or $bytes[$cursor + 3] -ne 0x02) {
            throw "Invalid ZIP central header at byte $cursor"
        }
        $flags = [System.BitConverter]::ToUInt16($bytes, $cursor + 8)
        $nameLength = [System.BitConverter]::ToUInt16($bytes, $cursor + 28)
        $extraLength = [System.BitConverter]::ToUInt16($bytes, $cursor + 30)
        $commentLength = [System.BitConverter]::ToUInt16($bytes, $cursor + 32)
        $encoding = if (($flags -band 0x0800) -ne 0) {
            [System.Text.Encoding]::UTF8
        } else {
            [System.Text.Encoding]::ASCII
        }
        $name = $encoding.GetString($bytes, $cursor + 46, $nameLength)
        $executable = $name -eq 'gradlew' -or $name.EndsWith('.sh')
        $attributes = if ($executable) { [uint32]2179792896 } else { [uint32]2175008768 }
        $bytes[$cursor + 5] = 3
        [System.BitConverter]::GetBytes($attributes).CopyTo($bytes, $cursor + 38)
        $expectedCrc[$name] = [System.BitConverter]::ToUInt32($bytes, $cursor + 16)
        $centralModes[$name] = if ($executable) { 493 } else { 420 }
        $cursor += 46 + $nameLength + $extraLength + $commentLength
    }
    [System.IO.File]::WriteAllBytes($temporaryPath, $bytes)

    $expectedNames = @($files.Relative)
    $actualNames = [System.Collections.Generic.List[string]]::new()
    $readArchive = [System.IO.Compression.ZipFile]::OpenRead($temporaryPath)
    try {
        foreach ($entry in $readArchive.Entries) {
            $name = $entry.FullName
            if ($name.Contains('\') -or $name.StartsWith('/') -or
                    $name -match '^[A-Za-z]:' -or ($name -split '/') -contains '..') {
                throw "Unsafe ZIP entry path: $name"
            }
            $actualNames.Add($name)
            if (-not $expectedCrc.ContainsKey($name)) {
                throw "Unexpected ZIP entry: $name"
            }
            $stream = $entry.Open()
            try {
                $actualCrc = [Readwide.SourceZip.Crc32]::Compute($stream)
            } finally {
                $stream.Dispose()
            }
            if ($actualCrc -ne [uint32]$expectedCrc[$name]) {
                throw "ZIP CRC validation failed at $name"
            }
            $actualMode = ($entry.ExternalAttributes -shr 16) -band 0x1FF
            if ($actualMode -ne $centralModes[$name]) {
                throw "Non-portable ZIP mode for ${name}: $actualMode"
            }
        }
    } finally {
        $readArchive.Dispose()
    }

    $actualSorted = @($actualNames | Sort-Object)
    if (($expectedNames -join "`n") -ne ($actualSorted -join "`n")) {
        throw 'ZIP entry list does not match the filtered source tree'
    }
    if (-not ($actualNames | Where-Object {
            $_.StartsWith($libarchiveCmake + '/') })) {
        throw 'Vendored libarchive CMake source directory is missing'
    }

    # The two-argument File.Move refuses an existing destination even if it
    # appeared after the initial check. Never replace a previous source ZIP.
    [System.IO.File]::Move($temporaryPath, $outputPath)
} finally {
    if ($ownsTemporary -and (Test-Path -LiteralPath $temporaryPath)) {
        Remove-Item -LiteralPath $temporaryPath -Force
    }
}

$outputFile = Get-Item -LiteralPath $outputPath
$digest = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash
Write-Output "ZIP=$outputPath"
Write-Output "FILES=$($files.Count)"
Write-Output "BYTES=$($outputFile.Length)"
Write-Output "SHA256=$digest"
