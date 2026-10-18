Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$output = Join-Path $repo ('target/web-package-tests/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$verifier = Join-Path $PSScriptRoot '../verify-web-assets.ps1'
function New-Package([string]$name, [bool]$includeModule) {
    $file = Join-Path $output $name
    $zip = [IO.Compression.ZipFile]::Open($file, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $files = @{'index.html'='<script type="module" src="./assets/index-hash.js"></script><link rel="stylesheet" href="./assets/index-hash.css?v=1">';'assets/index-hash.css'='body {}'}
        if ($includeModule) { $files['assets/index-hash.js'] = 'export const ready = true;' }
        foreach ($key in $files.Keys) {
            $entry = $zip.CreateEntry('BOOT-INF/classes/static/' + $key)
            $writer = New-Object IO.StreamWriter($entry.Open())
            try { $writer.Write($files[$key]) } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose() }
    return $file
}
$valid = New-Package 'valid.jar' $true
& $verifier -JarPath $valid
$broken = New-Package 'missing-hashed-module.jar' $false
$rejected = $false
try { & $verifier -JarPath $broken }
catch {
    if ($_.Exception.Message -notlike '*missing a referenced web asset: assets/index-hash.js*') { throw }
    $rejected = $true
}
if (-not $rejected) { throw 'Missing-module regression was not rejected.' }
@{tests=2;passed=2;failed=0;validPackageAccepted=$true;missingHashedModuleRejected=$rejected} |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $output 'results.json')
Write-Output "Web package regression passed. Evidence: $output"
