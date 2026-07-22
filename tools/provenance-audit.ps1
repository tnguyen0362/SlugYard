[CmdletBinding()]
param(
    [string]$ProjectRoot = "",
    [string]$ApkPath = "",
    [string]$AarRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Definition)
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$legacyUpstreamPattern = '(?i)nuvio|com[\\/]nuvio|com\.nuvio'
$sourceExtensions = @('.kt', '.java', '.xml', '.gradle', '.kts', '.properties', '.pro', '.c', '.cc', '.cpp', '.h')
$compatibilityFiles = @(
    'PlayerSettingsDataStore.kt',
    'PlaybackIssueReportDto.kt'
)
$requiredAttributionFiles = @(
    'FfmpegAudioRenderer.java',
    'FfmpegAudioDecoder.java',
    'ffmpeg_jni.cc'
)
$allowedBinaryStrings = @(
    'nuvioPerformanceModeEnabled',
    'nuvio_performance_mode_enabled'
)
$issues = [System.Collections.Generic.List[string]]::new()

function Add-Issue([string]$message) {
    [void]$issues.Add($message)
    Write-Host "BLOCKER: $message"
}

function Get-RelativePath([string]$path) {
    $root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\') + '\'
    $full = if (Test-Path -LiteralPath $path) {
        (Resolve-Path -LiteralPath $path).Path
    } else {
        [System.IO.Path]::GetFullPath($path)
    }
    if ($full.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($root.Length)
    }
    return $full
}

function Scan-SourceRoot([string]$root) {
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        Write-Host "SKIP: source root not present: $(Get-RelativePath $root)"
        return
    }

    $files = Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
        $sourceExtensions -contains $_.Extension.ToLowerInvariant()
    }
    foreach ($file in $files) {
        $matches = Select-String -LiteralPath $file.FullName -Pattern $legacyUpstreamPattern
        foreach ($match in $matches) {
            if ($compatibilityFiles -contains $file.Name) {
                Write-Host "ALLOW: persisted/protocol compatibility $(Get-RelativePath $file.FullName):$($match.LineNumber)"
            } elseif ($requiredAttributionFiles -contains $file.Name) {
                Write-Host "ALLOW: required legal attribution $(Get-RelativePath $file.FullName):$($match.LineNumber)"
            } else {
                Add-Issue "source match $(Get-RelativePath $file.FullName):$($match.LineNumber)"
            }
        }
    }
}

function Scan-Zip([string]$zipPath, [string]$kind) {
    if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
        Write-Host "SKIP: $kind not present: $(Get-RelativePath $zipPath)"
        return
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match $legacyUpstreamPattern) {
                Add-Issue "$kind entry $(Get-RelativePath $zipPath):$($entry.FullName)"
            }
            if ($entry.FullName -match '(?i)\.so$|classes\.jar$|\.dex$|\.class$') {
                $stream = $entry.Open()
                $memory = [System.IO.MemoryStream]::new()
                try {
                    $stream.CopyTo($memory)
                    $text = [System.Text.Encoding]::ASCII.GetString($memory.ToArray())
                    if ($text -match $legacyUpstreamPattern) {
                        $binaryMatches = [regex]::Matches($text, '(?i)nuvio[A-Za-z0-9_./-]*') |
                            ForEach-Object { $_.Value } |
                            Where-Object { $allowedBinaryStrings -notcontains $_ }
                        if ($binaryMatches) {
                            Add-Issue "$kind binary string $(Get-RelativePath $zipPath):$($entry.FullName)"
                        } else {
                            Write-Host "ALLOW: persisted/protocol compatibility $kind binary $(Get-RelativePath $zipPath):$($entry.FullName)"
                        }
                    }
                } finally {
                    $memory.Dispose()
                    $stream.Dispose()
                }
            }
        }
    } finally {
        $archive.Dispose()
    }
}

Write-Host "SlugYard provenance audit"
Write-Host "Root: $ProjectRoot"

Scan-SourceRoot (Join-Path $ProjectRoot 'app/src/main')
Scan-SourceRoot (Join-Path $ProjectRoot 'baselineprofile/src/main')
Scan-SourceRoot (Join-Path $ProjectRoot 'ffmpeg-decoder-downmix/src/main')

if ([string]::IsNullOrWhiteSpace($AarRoot)) {
    $AarRoot = Join-Path $ProjectRoot 'app/libs'
}
if (Test-Path -LiteralPath $AarRoot -PathType Container) {
    Get-ChildItem -LiteralPath $AarRoot -Filter '*.aar' -File | ForEach-Object {
        Scan-Zip $_.FullName 'AAR'
    }
} else {
    Write-Host "SKIP: AAR root not present: $(Get-RelativePath $AarRoot)"
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $ProjectRoot 'app/build/outputs/apk/full/debug/app-full-armeabi-v7a-debug.apk'
}
Scan-Zip $ApkPath 'APK'

if ($issues.Count -gt 0) {
    Write-Host "Audit failed with $($issues.Count) blocker(s)."
    exit 1
}

Write-Host "Audit passed: no forbidden legacy upstream names or binary strings found in scanned artifacts."
exit 0
