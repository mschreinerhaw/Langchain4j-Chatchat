function Import-EnvFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        return
    }

    foreach ($RawLine in Get-Content $Path) {
        $Line = $RawLine.Trim()
        if (-not $Line -or $Line.StartsWith("#")) {
            continue
        }
        if ($Line.StartsWith("export ")) {
            $Line = $Line.Substring(7).Trim()
        }

        $Separator = $Line.IndexOf("=")
        if ($Separator -lt 1) {
            continue
        }

        $Key = $Line.Substring(0, $Separator).Trim()
        if ($Key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            Write-Warning "Skip invalid env key in ${Path}: $Key"
            continue
        }

        $Value = $Line.Substring($Separator + 1)
        if (($Value.StartsWith('"') -and $Value.EndsWith('"')) -or
            ($Value.StartsWith("'") -and $Value.EndsWith("'"))) {
            $Value = $Value.Substring(1, $Value.Length - 2)
        }

        [System.Environment]::SetEnvironmentVariable($Key, $Value, "Process")
    }
}

Import-EnvFile (Join-Path $AppHome "config/env.properties")
Import-EnvFile (Join-Path $AppHome "config/env.local")
