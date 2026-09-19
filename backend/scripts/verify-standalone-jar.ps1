#requires -Version 5.1
<#
.SYNOPSIS
  Verify that the packaged jar runs with NO external database: the "bare java -jar" path.

.DESCRIPTION
  This guards a bug that was actually hit: with com.h2database:h2 declared as
  <scope>test</scope>, the unit tests all pass (Maven's test classpath includes test-scoped
  dependencies) but spring-boot repackage never puts h2-*.jar into the executable jar. The
  default datasource IS H2, so a bare `java -jar` died with
  ClassNotFoundException: org.h2.Driver and exit code 1. Unit tests cannot see that
  difference -- only inspecting the artifact and actually booting it can.

  Three layers:
    L1 artifact : BOOT-INF/lib must contain the H2 driver (offline, instant)
    L2 startup  : boots with DB_* cleared, /health returns 200, log shows jdbc:h2:mem
    L3 read/write: GET /api/me returns 200 -- that path writes an anonymous identity row,
                  so it only succeeds if Flyway migrated and JPA really works on the
                  in-memory database

.PARAMETER Port
  Port used for the check. Default 8099, to stay clear of a long-running instance on 8080.

.PARAMETER Build
  Run `mvn -DskipTests package` first. Default is to verify the jar that already exists.
  Note: if another process is currently running that same jar, Windows file locking makes
  the repackage step fail -- stop it first.

.PARAMETER TimeoutSeconds
  How long to wait for the service to become healthy. Default 120.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File backend/scripts/verify-standalone-jar.ps1 -Build

.NOTES
  Windows PowerShell 5.1 only (no pwsh 7 on this machine), so keep this file pure ASCII:
  5.1 decodes BOM-less files as ANSI and would mangle non-ASCII text into parse errors.
  The default execution policy blocks scripts, hence -ExecutionPolicy Bypass.
#>
param(
    [int]$Port = 8099,
    [switch]$Build,
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$backendDir = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $backendDir 'target\codecompass-backend-0.0.1-SNAPSHOT.jar'
$script:failures = 0

function Write-Ok($Message) { Write-Host "  [ok]   $Message" -ForegroundColor Green }
function Write-Bad($Message) { Write-Host "  [FAIL] $Message" -ForegroundColor Red; $script:failures++ }
function Write-Info($Message) { Write-Host "         $Message" -ForegroundColor DarkGray }
function Write-Head($Message) { Write-Host "`n$Message" -ForegroundColor Cyan }

function Get-Url {
    # Invoke-WebRequest rather than System.Net.Http.HttpClient: the latter needs
    # Add-Type -AssemblyName System.Net.Http on PowerShell 5.1. Only status codes and ASCII
    # fields are asserted here, so the known Latin-1 mis-decoding of UTF-8 bodies is harmless.
    param([string]$Url, [int]$TimeoutSec = 15)
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content }
    } catch {
        $raw = $_.Exception.Response
        if ($raw) {
            return [pscustomobject]@{ Status = [int]$raw.StatusCode; Body = '' }
        }
        return $null
    }
}

if ($Build) {
    Write-Head '== build =='
    & mvn -q -f (Join-Path $backendDir 'pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { Write-Bad "mvn package failed (exit $LASTEXITCODE)"; exit 1 }
    Write-Ok 'mvn package succeeded'
}

if (-not (Test-Path $jarPath)) {
    Write-Bad "artifact not found: $jarPath"
    Write-Info 'run: mvn -f backend/pom.xml -DskipTests package   (or pass -Build)'
    exit 1
}

# ---------- L1: is the artifact self-contained? ----------
Write-Head '== L1 artifact: does BOOT-INF/lib carry the H2 driver =='
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
$libs = @($zip.Entries |
    Where-Object { $_.FullName -like 'BOOT-INF/lib/*' } |
    ForEach-Object { $_.Name })
$zip.Dispose()

$h2 = @($libs | Where-Object { $_ -match '^h2-.*\.jar$' })
$mysql = @($libs | Where-Object { $_ -match '^mysql-connector-j-.*\.jar$' })
Write-Info "dependencies inside the jar: $($libs.Count)"
if ($h2.Count -gt 0) {
    Write-Ok "H2 driver present: $($h2 -join ', ')"
} else {
    Write-Bad 'no H2 driver in BOOT-INF/lib -- the default datasource is H2, so a bare run must fail'
    Write-Info 'check whether com.h2database:h2 in backend/pom.xml is scoped to runtime'
}
if ($mysql.Count -gt 0) {
    Write-Ok "MySQL driver present: $($mysql -join ', ')"
} else {
    Write-Bad 'MySQL driver missing (connecting to a real database would fail)'
}

# ---------- L2/L3: boot once with DB_* cleared ----------
Write-Head '== L2 startup / L3 read-write: with DB_* unset =='
$saved = @{}
foreach ($name in @('DB_URL', 'DB_USER', 'DB_PASSWORD')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $null)
}
Write-Info 'cleared DB_URL / DB_USER / DB_PASSWORD for this process'

$outLog = Join-Path $env:TEMP "cc-standalone-$Port.out.log"
$errLog = Join-Path $env:TEMP "cc-standalone-$Port.err.log"
Remove-Item $outLog, $errLog -ErrorAction SilentlyContinue

function Show-LogTail {
    foreach ($file in @($outLog, $errLog)) {
        if (-not (Test-Path $file)) { continue }
        Write-Info "--- $file ---"
        Get-Content $file -ErrorAction SilentlyContinue |
            Select-String -Pattern 'ERROR|Caused by|Exception|APPLICATION FAILED' |
            Select-Object -First 8 |
            ForEach-Object { Write-Info $_.Line.Trim() }
    }
}

$proc = $null
try {
    $proc = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $jarPath, "--server.port=$Port") `
        -PassThru -NoNewWindow -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    Write-Info "pid=$($proc.Id)  log=$outLog"

    $base = "http://127.0.0.1:$Port"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $health = $null
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { break }
        $health = Get-Url "$base/health" -TimeoutSec 5
        if ($health -and $health.Status -eq 200) { break }
        Start-Sleep -Milliseconds 700
    }

    if ($proc.HasExited) {
        $proc.WaitForExit(1000) | Out-Null
        Write-Bad "process exited early with code $($proc.ExitCode)"
        Show-LogTail
    } elseif (-not $health -or $health.Status -ne 200) {
        Write-Bad "timed out after ${TimeoutSeconds}s waiting for /health to return 200"
        Show-LogTail
    } else {
        Write-Ok "GET /health 200: $($health.Body)"
        if ($health.Body -match 'codecompass-backend') {
            Write-Ok 'service name in the response matches'
        } else {
            Write-Bad 'response does not contain the service name'
        }

        $logText = Get-Content $outLog -Raw -ErrorAction SilentlyContinue
        if ($logText -match 'jdbc:h2:mem') {
            Write-Ok 'log confirms the in-memory H2 default (no external database)'
        } else {
            Write-Bad 'log does not mention jdbc:h2:mem, cannot confirm the default datasource'
        }
        if ($logText -match 'Successfully applied|Migrating schema|Current version of schema') {
            Write-Ok 'Flyway ran its migrations on H2'
        } else {
            Write-Bad 'no Flyway migration recorded in the log'
        }

        $me = Get-Url "$base/api/me"
        if ($me -and $me.Status -eq 200) {
            Write-Ok "GET /api/me 200 (anonymous identity written then read back)"
            Write-Info $me.Body
        } else {
            $code = if ($me) { $me.Status } else { 'no response' }
            Write-Bad "GET /api/me expected 200, got $code -- database read/write is not working"
            Show-LogTail
        }
    }
} finally {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        $proc.WaitForExit(15000) | Out-Null
        Write-Info 'stopped the verification process'
    }
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
}

Write-Head '== result =='
if ($script:failures -eq 0) {
    Write-Host 'PASS: bare java -jar runs with no database' -ForegroundColor Green
    exit 0
}
Write-Host "FAIL: $($script:failures) assertion(s) failed" -ForegroundColor Red
exit 1
