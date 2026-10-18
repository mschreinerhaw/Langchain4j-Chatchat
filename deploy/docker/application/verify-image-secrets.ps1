param([Parameter(Mandatory = $true)][string]$JarPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
function Assert-NoDeploymentSecrets($archive, [string]$location) {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -match '(?i)(?:\.key$|(?:^|/)license\.dat$|(?:^|/)[^/]*(?:private|signing)[^/]*\.pem$|(?:^|/)\.env[^/]*$)') {
            throw "Deployment secret file in application package: $location/$($entry.FullName)"
        }
        # First-party library JARs can be nested inside the API's Spring Boot archive.
        if ($entry.FullName -match '(?:^|/)chatchat-[^/]+\.jar$') {
            $memory = New-Object IO.MemoryStream
            $input = $entry.Open()
            try { $input.CopyTo($memory) } finally { $input.Dispose() }
            $memory.Position = 0
            $nested = New-Object IO.Compression.ZipArchive($memory, [IO.Compression.ZipArchiveMode]::Read)
            try { Assert-NoDeploymentSecrets $nested ($location + '/' + $entry.FullName) }
            finally { $nested.Dispose(); $memory.Dispose() }
        }
    }
}
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JarPath).Path)
try { Assert-NoDeploymentSecrets $archive ([IO.Path]::GetFileName($JarPath)) }
finally { $archive.Dispose() }
Write-Output 'Application and nested first-party libraries contain no deployment secret files.'
