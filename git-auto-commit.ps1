param(
    [string]$Message = "",
    [string[]]$Path = @("."),
    [switch]$Push,
    [string]$Remote = "origin",
    [string]$Branch = "",
    [switch]$CleanupIgnored,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    & git @Arguments
    $ExitCode = $LASTEXITCODE
    if ($ExitCode -ne 0 -and -not $AllowFailure) {
        throw "git $($Arguments -join ' ') failed with exit code $ExitCode."
    }

    if ($AllowFailure) {
        return $ExitCode
    }
}

function Get-GitOutput {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $Output = & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }

    return ($Output | Out-String).Trim()
}

$RepoRoot = Get-GitOutput -Arguments @("rev-parse", "--show-toplevel")
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    throw "Current directory is not inside a Git repository."
}

Push-Location $RepoRoot
try {
    $Path = @($Path | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $CleanupModeStaged = $false
    $UnrelatedStagedPaths = @()

    $UserName = Get-GitOutput -Arguments @("config", "--get", "user.name")
    $UserEmail = Get-GitOutput -Arguments @("config", "--get", "user.email")
    if ([string]::IsNullOrWhiteSpace($UserName) -or [string]::IsNullOrWhiteSpace($UserEmail)) {
        throw "Git user.name or user.email is not configured. Configure them before committing."
    }

    Write-Host "Repository: $RepoRoot"

    if ($CleanupIgnored) {
        $TrackedIgnored = @(git ls-files -ci --exclude-standard)
        if ($LASTEXITCODE -ne 0) {
            throw "git ls-files -ci --exclude-standard failed with exit code $LASTEXITCODE."
        }

        $StagedIgnoredDeletes = @(git diff --cached --name-only --diff-filter=D | Where-Object {
            git check-ignore --no-index -q -- $_
            $LASTEXITCODE -eq 0
        })

        $CleanupPaths = @(".gitignore") + $TrackedIgnored + $StagedIgnoredDeletes |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -Unique

        if ($CleanupPaths.Count -eq 0) {
            Write-Host "No ignored tracked files found."
            return
        }

        Write-Host "Mode: cleanup ignored tracked files"
        Write-Host "Paths: $($CleanupPaths -join ', ')"

        if ($DryRun) {
            Write-Host ""
            Write-Host "Dry run. These cleanup changes would be considered:"
            Invoke-Git -Arguments (@("status", "--short", "--") + $CleanupPaths)
            return
        }

        if (-not $DryRun) {
            $StagedBeforeCleanup = @(git diff --cached --name-only)
            if ($LASTEXITCODE -ne 0) {
                throw "git diff --cached --name-only failed with exit code $LASTEXITCODE."
            }

            $CleanupPathSet = @{}
            foreach ($CleanupPath in $CleanupPaths) {
                $CleanupPathSet[$CleanupPath.Replace("\", "/")] = $true
            }

            $UnrelatedStagedPaths = @($StagedBeforeCleanup | Where-Object {
                -not $CleanupPathSet.ContainsKey($_.Replace("\", "/"))
            })

            if ($UnrelatedStagedPaths.Count -gt 0) {
                Invoke-Git -Arguments (@("restore", "--staged", "--") + $UnrelatedStagedPaths) | Out-Null
            }
        }

        if ($TrackedIgnored.Count -gt 0) {
            Invoke-Git -Arguments (@("rm", "--cached", "--") + $TrackedIgnored) | Out-Null
        }

        if (Test-Path ".gitignore") {
            Invoke-Git -Arguments @("add", "--", ".gitignore") | Out-Null
        }

        $Path = $CleanupPaths
        $CleanupModeStaged = $true
        if ([string]::IsNullOrWhiteSpace($Message)) {
            $Message = "chore: cleanup ignored generated files"
        }
    }
    else {
        Write-Host "Paths: $($Path -join ', ')"
    }

    if ($DryRun) {
        Write-Host ""
        Write-Host "Dry run. These changes would be considered:"
        Invoke-Git -Arguments (@("status", "--short", "--") + $Path)
        return
    }

    if (-not $CleanupModeStaged) {
        Invoke-Git -Arguments (@("add", "-A", "--") + $Path) | Out-Null
    }

    $HasStagedChanges = (Invoke-Git -Arguments (@("diff", "--cached", "--quiet", "--") + $Path) -AllowFailure) -ne 0
    if (-not $HasStagedChanges) {
        Write-Host "No staged changes to commit."
        return
    }

    if ([string]::IsNullOrWhiteSpace($Message)) {
        $Timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
        $Message = "chore: auto commit $Timestamp"
    }

    Write-Host ""
    Write-Host "Staged changes:"
    Invoke-Git -Arguments (@("diff", "--cached", "--stat", "--") + $Path)

    Write-Host ""
    Write-Host "Committing: $Message"
    if ($CleanupIgnored) {
        Invoke-Git -Arguments @("commit", "-m", $Message)
    }
    else {
        Invoke-Git -Arguments (@("commit", "-m", $Message, "--") + $Path)
    }

    if ($UnrelatedStagedPaths.Count -gt 0) {
        Invoke-Git -Arguments (@("add", "-A", "--") + $UnrelatedStagedPaths) | Out-Null
    }

    if ($Push) {
        if ([string]::IsNullOrWhiteSpace($Branch)) {
            $Branch = Get-GitOutput -Arguments @("branch", "--show-current")
        }

        if ([string]::IsNullOrWhiteSpace($Branch)) {
            throw "Cannot resolve current branch. Pass -Branch explicitly when pushing from detached HEAD."
        }

        Write-Host ""
        Write-Host "Pushing to $Remote/$Branch ..."
        Invoke-Git -Arguments @("push", $Remote, $Branch)
    }
}
finally {
    Pop-Location
}
