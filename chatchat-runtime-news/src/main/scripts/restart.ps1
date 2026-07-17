param([Parameter(ValueFromRemainingArguments = $true)][string[]]$StartArgs)
& (Join-Path $PSScriptRoot "stop.ps1")
& (Join-Path $PSScriptRoot "start.ps1") @StartArgs
exit $LASTEXITCODE
