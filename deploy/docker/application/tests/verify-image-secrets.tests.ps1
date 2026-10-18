Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$output = Join-Path $repo ('target/image-package-tests/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$verifier = Join-Path $PSScriptRoot '../verify-image-secrets.ps1'
function New-Package([string]$name, [hashtable]$files) {
    $file = Join-Path $output $name
    $zip = [IO.Compression.ZipFile]::Open($file, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($key in $files.Keys) {
            $entry = $zip.CreateEntry($key)
            $stream = $entry.Open()
            try {
                $bytes = if ($files[$key] -is [byte[]]) { $files[$key] } else { [Text.Encoding]::UTF8.GetBytes([string]$files[$key]) }
                $stream.Write($bytes, 0, $bytes.Length)
            } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    return $file
}
$clean = New-Package 'clean.jar' @{'BOOT-INF/classes/license-public.pem'='SYNTHETIC PUBLIC KEY'}
& $verifier -JarPath $clean
$key = New-Package 'local-key.jar' @{'BOOT-INF/classes/internal-credential.key'='SYNTHETIC KEY'}
$library = New-Package 'library.jar' @{'internal-credential.key'='SYNTHETIC KEY'}
$nested = New-Package 'nested-local-key.jar' @{'BOOT-INF/lib/chatchat-example.jar'=[IO.File]::ReadAllBytes($library)}
foreach ($file in @($key,$nested)) {
    $blocked = $false
    try { & $verifier -JarPath $file }
    catch {
        if ($_.Exception.Message -notlike '*Deployment secret file*internal-credential.key*') { throw }
        $blocked = $true
    }
    if (-not $blocked) { throw 'Deployment key was not rejected.' }
}
@{tests=3;passed=3;failed=0;publicKeyAllowed=$true;localKeyRejected=$true;nestedFirstPartyKeyRejected=$true} |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $output 'results.json')
Write-Output "Image secret-file regression passed. Evidence: $output"
