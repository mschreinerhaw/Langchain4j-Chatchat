param([Parameter(Mandatory = $true)][string]$JarPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath).Path)
try {
    $prefix = 'BOOT-INF/classes/static/'
    $index = $archive.GetEntry($prefix + 'index.html')
    if ($null -eq $index) { throw 'Application JAR has no web index.' }
    $reader = New-Object IO.StreamReader($index.Open())
    try { $html = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $references = [regex]::Matches($html, '(?i)(?:src|href)\s*=\s*["'']([^"'']+)["'']')
    $checked = 0
    foreach ($reference in $references) {
        $url = $reference.Groups[1].Value
        if ($url -match '^(?:[a-z][a-z0-9+.-]*:|//|#)') { continue }
        $asset = ($url -split '[?#]', 2)[0] -replace '^\./', '' -replace '^/', ''
        if (-not $asset -or $asset -match '(^|/)\.\.(/|$)') { throw 'Invalid web asset path.' }
        if ($null -eq $archive.GetEntry($prefix + $asset)) { throw "Application JAR is missing a referenced web asset: $asset" }
        $checked++
    }
    if ($checked -eq 0) { throw 'Web index contains no packaged local assets.' }
    Write-Output "Packaged web references verified: $checked"
} finally { $archive.Dispose() }
