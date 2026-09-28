Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3

$javaPath = "C:\Program Files\Java\jdk-21.0.10\bin\java.exe"
# Load secrets from .env (not committed, see .gitignore) - whitelisted keys only
$AmapKey = $null; $llmKey = $null
$envFile = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '.env'
$allowedEnvKeys = @('AMAP_API_KEY', 'ZHIPU_API_KEY')
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$' -and $allowedEnvKeys -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    $AmapKey = $env:AMAP_API_KEY
    $llmKey  = $env:ZHIPU_API_KEY
}

$services = @(
    @{name="auth-service"; dir="auth-service"; port=8081; llm=$false},
    @{name="trip-service"; dir="trip-service"; port=8082; llm=$false},
    @{name="plan-service"; dir="plan-service"; port=8083; llm=$true},
    @{name="planning-worker"; dir="planning-worker"; port=8084; llm=$false},
    @{name="notification-service"; dir="notification-service"; port=8085; llm=$false},
    @{name="gateway"; dir="gateway"; port=8086; llm=$false}
)

foreach ($svc in $services) {
    $extra = ""
    if ($svc.llm) {
        $extra = " -DLLM_API_KEY=$llmKey -DZHIPU_API_KEY=$llmKey"
    }
    Start-Process cmd -ArgumentList "/c", "cd /d D:\agent-trip-planner\$($svc.dir) && `"$javaPath`" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Damap.api-key=$amapKey$extra -jar target\$($svc.dir)-1.0.0-SNAPSHOT.jar > stdout.log 2>&1"
    Write-Host "Starting $($svc.name) on port $($svc.port)..."
}

Start-Sleep -Seconds 25

foreach ($svc in $services) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$($svc.port)/actuator/health" -UseBasicParsing -TimeoutSec 5
        Write-Host "$($svc.name): UP"
    } catch {
        try {
            $r = Invoke-WebRequest -Uri "http://localhost:$($svc.port)/" -UseBasicParsing -TimeoutSec 3
            Write-Host "$($svc.name): UP (root)"
        } catch {
            Write-Host "$($svc.name): DOWN"
        }
    }
}
