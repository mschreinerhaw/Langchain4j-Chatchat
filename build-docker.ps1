param(
    [string]$Tag,
    [string]$OutputDir,
    [string]$DockerCliPath,
    [switch]$RunTests,
    [switch]$Plan,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$BuildArguments
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$python = Get-Command python -ErrorAction Stop
$arguments = @((Join-Path $PSScriptRoot 'scripts/build-docker-release.py'))
if ($Tag) { $arguments += @('--tag', $Tag) }
if ($OutputDir) { $arguments += @('--output-dir', $OutputDir) }
if ($DockerCliPath) { $arguments += @('--docker-cli', $DockerCliPath) }
if ($RunTests) { $arguments += '--run-tests' }
if ($Plan) { $arguments += '--plan' }
if ($BuildArguments) { $arguments += $BuildArguments }
& $python.Source @arguments
if ($LASTEXITCODE -ne 0) { throw "Docker release build failed (exit $LASTEXITCODE). See the build log in the output directory." }
