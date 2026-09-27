[CmdletBinding()]
param(
    [switch]$SkipWarmup
)

$ErrorActionPreference = 'Stop'

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$sessionHome = Join-Path $workspaceRoot 'target\codex-home'
$jgitConfigHome = Join-Path $sessionHome '.config'
$mavenRepository = Join-Path $workspaceRoot '.m2'

New-Item -ItemType Directory -Force -Path $sessionHome, $jgitConfigHome, $mavenRepository | Out-Null

# These settings apply to Maven launched by this script. Codex commands use the
# same values through their command environment; they do not require a machine-wide change.
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$sessionHome"
$env:XDG_CONFIG_HOME = $jgitConfigHome
$env:MAVEN_ARGS = "-Dmaven.repo.local=$mavenRepository"

Write-Host "Codex Maven home: $sessionHome"
Write-Host "Codex JGit config home: $jgitConfigHome"
Write-Host "Codex Maven repository: $mavenRepository"

if (-not $SkipWarmup) {
    & mvn install -DskipTests=true
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Write-Host 'Preflight complete. This script does not install hooks or change machine-wide settings.'
