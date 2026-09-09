[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AgentId = "financial_indicator",
    [string]$Question,
    [string]$TaskId,
    [string]$RequiredWorkerVersion,
    [string]$AgentToken = $env:AGENT_TOKEN,
    [string]$SessionId = ("jar-debug-" + [guid]::NewGuid().ToString("N")),
    [string]$LogPath,
    [int]$PollIntervalSeconds = 3,
    [int]$TimeoutSeconds = 1800
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path (Split-Path $PSScriptRoot -Parent) `
        ("logs/local-dev/published-agent-{0}.log" -f $SessionId)
}
$LogPath = [System.IO.Path]::GetFullPath($LogPath)
$LogDirectory = Split-Path $LogPath -Parent
if (-not (Test-Path -LiteralPath $LogDirectory)) {
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
}

function Write-RunLog {
    param([Parameter(Mandatory = $true)][string]$Message)
    $Line = "{0} {1}" -f [DateTimeOffset]::Now.ToString("o"), $Message
    Write-Host $Line
    Add-Content -LiteralPath $LogPath -Value $Line -Encoding UTF8
}

trap {
    Write-RunLog ("debug invocation failed error={0}" -f $_.Exception.Message)
    break
}

if ([string]::IsNullOrWhiteSpace($AgentToken)) {
    throw "AgentToken is required. Pass -AgentToken or set AGENT_TOKEN."
}
if ([string]::IsNullOrWhiteSpace($TaskId) -and [string]::IsNullOrWhiteSpace($Question)) {
    throw "Question is required when TaskId is not supplied."
}
if ($PollIntervalSeconds -lt 1) {
    throw "PollIntervalSeconds must be at least 1."
}
if ($TimeoutSeconds -lt 1) {
    throw "TimeoutSeconds must be at least 1."
}

$BaseUrl = $BaseUrl.TrimEnd("/")
Write-RunLog "debug invocation started baseUrl=$BaseUrl agentId=$AgentId sessionId=$SessionId"
Write-RunLog "logPath=$LogPath"
$Headers = @{
    Authorization = "Bearer $AgentToken"
    Accept = "application/json"
}
if ([string]::IsNullOrWhiteSpace($TaskId)) {
    $SubmitUri = "$BaseUrl/api/v1/published-agents/$AgentId/questions"
    $SubmitBody = @{
        sessionId = $SessionId
        question = $Question
        idempotencyKey = "jar-debug:$SessionId"
    }
    if (-not [string]::IsNullOrWhiteSpace($RequiredWorkerVersion)) {
        $SubmitBody.parameters = @{
            requiredWorkerVersion = $RequiredWorkerVersion.Trim()
        }
        Write-RunLog "submission is pinned to requiredWorkerVersion=$($RequiredWorkerVersion.Trim())"
    }
    $SubmitBody = $SubmitBody | ConvertTo-Json -Depth 5

    $Submit = Invoke-RestMethod `
        -Method Post `
        -Uri $SubmitUri `
        -Headers $Headers `
        -ContentType "application/json; charset=utf-8" `
        -Body $SubmitBody `
        -TimeoutSec 30

    if ($Submit.code -ne 200 -and $Submit.code -ne 202) {
        throw "Question submission failed: code=$($Submit.code), message=$($Submit.message)"
    }

    $TaskId = $Submit.data.taskId
    if ([string]::IsNullOrWhiteSpace($TaskId)) {
        throw "Question submission did not return data.taskId."
    }
    Write-RunLog "submitted taskId=$TaskId sessionId=$SessionId status=$($Submit.data.status)"
} else {
    Write-RunLog "resuming existing taskId=$TaskId without submitting a duplicate request"
}

$StatusUri = "$BaseUrl/api/v1/published-agents/$AgentId/questions/$TaskId/status"
$AnswerUri = "$BaseUrl/api/v1/published-agents/$AgentId/questions/$TaskId/answer"
$Cursor = 0L
$StartedAt = [DateTimeOffset]::UtcNow

while ($true) {
    $Elapsed = ([DateTimeOffset]::UtcNow - $StartedAt).TotalSeconds
    if ($Elapsed -ge $TimeoutSeconds) {
        throw "Timed out after $TimeoutSeconds seconds; taskId=$TaskId cursor=$Cursor"
    }

    try {
        $Status = Invoke-RestMethod `
            -Method Get `
            -Uri "$StatusUri`?afterSequence=$Cursor&eventLimit=100" `
            -Headers $Headers `
            -TimeoutSec 30
    } catch {
        Write-RunLog ("poll request failed; will retry taskId={0} cursor={1} error={2}" -f `
            $TaskId, $Cursor, $_.Exception.Message)
        Start-Sleep -Seconds $PollIntervalSeconds
        continue
    }

    foreach ($Event in @($Status.data.events)) {
        $Payload = if ($null -eq $Event.payload) { "" } else {
            ($Event.payload | ConvertTo-Json -Depth 8 -Compress)
        }
        Write-RunLog ("event sequence={0} type={1} status={2} tool={3} payload={4}" -f `
            $Event.sequence, $Event.type, $Event.status, $Event.toolName, $Payload)
    }

    if ($Status.data.eventCursor -gt $Cursor) {
        $Cursor = [long]$Status.data.eventCursor
    }

    Write-RunLog ("poll taskId={0} state={1} status={2} terminal={3} cursor={4}" -f `
        $TaskId, $Status.data.canonicalState, $Status.data.status, $Status.data.terminal, $Cursor)

    if ($Status.data.terminal) {
        $Answer = Invoke-RestMethod `
            -Method Get `
            -Uri $AnswerUri `
            -Headers $Headers `
            -TimeoutSec 30
        $AnswerJson = $Answer | ConvertTo-Json -Depth 12
        Write-RunLog "answer=$AnswerJson"
        if (-not $Answer.data.ready) {
            throw "Task reached a terminal state without an answer; taskId=$TaskId status=$($Answer.data.status)"
        }
        Write-RunLog "debug invocation completed taskId=$TaskId status=$($Answer.data.status)"
        break
    }

    Start-Sleep -Seconds $PollIntervalSeconds
}
