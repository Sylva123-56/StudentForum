# Run with PowerShell 7. Keep this file beside Start-StudentForum.cmd.
[CmdletBinding()]
param(
    [ValidateSet('All', 'Backend', 'Frontend')]
    [string] $Service = 'All',
    [switch] $CheckOnly
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$project = 'E:\StudentForum'
$javaHome = 'C:\Program Files\Java\jdk-21'
$mavenRepository = 'D:\Develop\Apache_Maven_Repo'

function Resolve-Application([string] $Name) {
    $command = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        throw "Cannot find $Name. Please check your system/user PATH."
    }
    return $command.Source
}

try {
    if ($PSVersionTable.PSVersion.Major -lt 7) {
        throw 'Please launch this script with PowerShell 7 (pwsh.exe).'
    }
    if (-not (Test-Path -LiteralPath $project -PathType Container)) {
        throw "Project directory not found: $project"
    }

    # Reload saved PATH values so a stale Explorer/app environment does not
    # keep using a removed Maven installation. Retain process-only entries last.
    $pathParts = @(
        [Environment]::GetEnvironmentVariable('Path', 'Machine')
        [Environment]::GetEnvironmentVariable('Path', 'User')
        $env:Path
    )
    $env:Path = (($pathParts -join ';') -split ';' |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique) -join ';'
    Set-Location -LiteralPath $project

    if ($Service -in @('All', 'Backend')) {
        if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe') -PathType Leaf)) {
            throw "JDK 21 not found: $javaHome"
        }
        if (-not (Test-Path -LiteralPath (Join-Path $project 'pom.xml') -PathType Leaf)) {
            throw 'Backend pom.xml not found.'
        }
        if (-not (Test-Path -LiteralPath $mavenRepository -PathType Container)) {
            throw "Maven repository directory not found: $mavenRepository"
        }
        $env:JAVA_HOME = $javaHome
        $env:Path = "$javaHome\bin;$env:Path"
        $env:DB_USER = 'root'
        $env:DB_PASSWORD = '1234'
        $maven = Resolve-Application 'mvn.cmd'
    }
    if ($Service -in @('All', 'Frontend')) {
        if (-not (Test-Path -LiteralPath (Join-Path $project 'package.json') -PathType Leaf)) {
            throw 'Frontend package.json not found.'
        }
        # Use npm.cmd, not npm.ps1, to avoid unrelated script-policy failures.
        $npm = Resolve-Application 'npm.cmd'
        $null = Resolve-Application 'node.exe'
    }

    if ($CheckOnly) {
        Write-Host "Project: $project"
        if ($maven) {
            Write-Host "JAVA_HOME: $env:JAVA_HOME"
            Write-Host "Maven: $maven"
            Write-Host "Maven repository: $mavenRepository"
        }
        if ($npm) { Write-Host "npm: $npm" }
        Write-Host 'Preflight checks passed. No services were started.' -ForegroundColor Green
        return
    }

    if ($Service -eq 'All') {
        $pwsh = Join-Path $PSHOME 'pwsh.exe'
        foreach ($name in @('Backend', 'Frontend')) {
            # Each service gets its own visible PowerShell 7 console. -NoExit
            # preserves the log and error message if a service stops.
            $arguments = @(
                '-NoLogo', '-NoProfile', '-NoExit',
                '-ExecutionPolicy', 'Bypass',
                '-File', ('"{0}"' -f $PSCommandPath),
                '-Service', $name
            )
            Start-Process -FilePath $pwsh -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Normal | Out-Null
        }
        Write-Host 'Backend and frontend consoles launched.' -ForegroundColor Green
        return
    }

    $Host.UI.RawUI.WindowTitle = "StudentForum $Service"
    Write-Host "Starting StudentForum $Service..." -ForegroundColor Cyan
    Write-Host "Working directory: $project"
    if ($Service -eq 'Backend') {
        Write-Host "Maven: $maven"
        Write-Host "Maven repository: $mavenRepository"
        & $maven "-Dmaven.repo.local=$mavenRepository" 'spring-boot:run'
    } else {
        Write-Host "npm: $npm"
        & $npm 'run' 'dev'
    }
    $serviceExitCode = $LASTEXITCODE
    Write-Host "`n$Service stopped (exit code: $serviceExitCode)." -ForegroundColor Yellow
    Write-Host 'You can inspect the log above, or close this window.'
} catch {
    Write-Host "`nStudentForum launcher failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($CheckOnly) { throw }
    if ($Service -eq 'All') { $null = Read-Host 'Press Enter to close' }
    # Service consoles stay open because they were started with -NoExit.
}
