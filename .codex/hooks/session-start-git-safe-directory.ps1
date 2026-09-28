$ErrorActionPreference = 'Stop'

function Write-HookWarning([string]$message) {
    @{ systemMessage = $message } | ConvertTo-Json -Compress | Write-Output
}

$hookInput = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($hookInput)) {
    Write-HookWarning 'Git workspace preflight received no session data. Check the project SessionStart hook.'
    exit 0
}

$session = $hookInput | ConvertFrom-Json
$directory = [string]$session.cwd
if ([string]::IsNullOrWhiteSpace($directory)) {
    Write-HookWarning 'Git workspace preflight could not find the session directory in hook input.'
    exit 0
}

$candidate = [System.IO.DirectoryInfo]::new([System.IO.Path]::GetFullPath($directory))
$repoRoot = $null
while ($null -ne $candidate) {
    if (Test-Path -LiteralPath (Join-Path $candidate.FullName '.git')) {
        $repoRoot = $candidate.FullName
        break
    }
    $candidate = $candidate.Parent
}

if ($null -eq $repoRoot) {
    exit 0
}

$hasSafeDirectory = $false
$count = 0
if ([int]::TryParse($env:GIT_CONFIG_COUNT, [ref]$count)) {
    for ($index = 0; $index -lt $count; $index++) {
        $key = [Environment]::GetEnvironmentVariable("GIT_CONFIG_KEY_$index")
        $value = [Environment]::GetEnvironmentVariable("GIT_CONFIG_VALUE_$index")
        $normalizedValue = $value.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        if ($key -ieq 'safe.directory' -and $normalizedValue -ieq $repoRoot) {
            $hasSafeDirectory = $true
            break
        }
    }
}

if (-not $hasSafeDirectory) {
    Write-HookWarning "Git safe.directory was not injected for this workspace ($repoRoot). Check .codex/config.toml and restart the session."
    exit 0
}

Push-Location -LiteralPath $repoRoot
try {
    $null = & rtk git status --short 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-HookWarning "RTK could not access the Git workspace ($repoRoot). Check the project shell environment settings."
    }
}
finally {
    Pop-Location
}
