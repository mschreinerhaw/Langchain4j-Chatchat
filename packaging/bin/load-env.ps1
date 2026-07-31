function Import-JvmOptions {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }

    foreach ($RawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $Line = $RawLine.Trim()
        if (-not $Line -or $Line.StartsWith("#")) { continue }
        if ($Line.StartsWith("JAVA_OPTS=")) {
            [Environment]::SetEnvironmentVariable("JAVA_OPTS", $Line.Substring("JAVA_OPTS=".Length), "Process")
        }
    }
}

Import-JvmOptions (Join-Path $AppHome "config/env.properties")
