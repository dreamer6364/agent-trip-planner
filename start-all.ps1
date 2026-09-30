<#
.SYNOPSIS
    TripForge 一键启动/停止脚本（整个项目）

.DESCRIPTION
    1) 检查基础设施：Windows 服务 MySQL80 / Redis，Docker 容器 zookeeper / kafka
    2) （可选）构建后端 jar 与前端静态资源
    3) 停掉本项目已有的 Java 进程，再按端口逐个启动 6 个服务
       - 统一注入 UTF-8 编码与高德 Key
       - plan-service 额外注入 LLM Key（缺失会导致 AI 规划失败）
       - 进程独立于当前控制台，关掉本窗口服务不会被杀
       - 日志写入 logs\<服务名>.log / .err.log
    4) 轮询健康检查，输出各服务状态并打开浏览器

.PARAMETER Action
    start   启动（默认）
    stop    停止本项目全部 Java 服务
    restart 停止后重新启动
    status  只查看状态
    tail    按 UTF-8 正确解码查看服务日志（配合 -Service，默认全部服务 spring 日志末 60 行）

.PARAMETER Service
    配合 -Action tail 使用：只查看指定服务（如 plan-service）；缺省查全部

.PARAMETER Build
    启动前构建后端（mvn -o package -DskipTests）；jar 缺失时会自动构建，无需该参数

.PARAMETER BuildFrontend
    构建前端并镜像同步到 gateway/src/main/resources/static（改了前端源码后需要）

.PARAMETER NoBrowser
    启动完成后不自动打开浏览器

.PARAMETER SkipInfra
    跳过 MySQL/Redis/Docker 检查

.EXAMPLE
    .\start-all.ps1
    .\start-all.ps1 -Build -BuildFrontend
    .\start-all.ps1 -Action restart
    .\start-all.ps1 -Action status
#>
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status', 'tail')]
    [string]$Action = 'start',

    [string]$Service = '',

    [switch]$Build,
    [switch]$BuildFrontend,
    [switch]$NoBrowser,
    [switch]$SkipInfra,

    [int]$WaitSeconds = 150
)

$Root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$JarSuffix = '-1.0.0-SNAPSHOT.jar'
$LogDir    = Join-Path $Root 'logs'

# 高德 Web 服务 Key（地理编码/路径规划/POI 搜索，缺失会导致地图地标缺失与路程 0 分钟）
$AmapKey = $null
$LlmKey  = $null
# Load secrets from .env (not committed, see .gitignore) - whitelisted keys only
$envFile = Join-Path $Root '.env'
$allowedEnvKeys = @('AMAP_API_KEY', 'ZHIPU_API_KEY', 'MYSQL_PASSWORD')
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$' -and $allowedEnvKeys -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    $AmapKey = $env:AMAP_API_KEY
    $LlmKey  = $env:ZHIPU_API_KEY
}
if (-not $AmapKey -or -not $LlmKey) {
    Write-Warning '.env is missing AMAP_API_KEY / ZHIPU_API_KEY - map and LLM features may fail'
}
if (-not $env:MYSQL_PASSWORD) {
    Write-Warning '.env is missing MYSQL_PASSWORD - services will fail to connect to MySQL (application.yml has no password default)'
}
# 智谱 LLM Key（plan-service 规划调用）
$LlmKey  = $LlmKey

$Services = @(
    @{ Name = 'auth-service';         Port = 8081; Llm = $false },
    @{ Name = 'trip-service';         Port = 8082; Llm = $false },
    @{ Name = 'plan-service';         Port = 8083; Llm = $true  },
    @{ Name = 'planning-worker';      Port = 8084; Llm = $false },
    @{ Name = 'notification-service'; Port = 8085; Llm = $false },
    @{ Name = 'gateway';              Port = 8086; Llm = $false }
)

$CoreContainers = @('trip-planner-zookeeper', 'trip-planner-kafka')

# ---------------------------------------------------------------- 输出辅助
function Write-Info([string]$Msg) { Write-Host $Msg -ForegroundColor Cyan }
function Write-Ok([string]$Msg)   { Write-Host ("  [OK] " + $Msg) -ForegroundColor Green }
function Write-Note([string]$Msg) { Write-Host ("  [--] " + $Msg) -ForegroundColor DarkGray }
function Write-Warn2([string]$Msg){ Write-Host ("  [!!] " + $Msg) -ForegroundColor Yellow }
function Write-Bad([string]$Msg)  { Write-Host ("  [XX] " + $Msg) -ForegroundColor Red }

function Write-Title([string]$Msg) {
    Write-Host ""
    Write-Host ("===== " + $Msg + " =====") -ForegroundColor White
}

# ---------------------------------------------------------------- 基础探测
function Get-JavaExe {
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($base in @('C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft')) {
        if (-not (Test-Path $base)) { continue }
        $hit = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($hit) { return $hit }
    }
    return $null
}

function Test-Port([int]$Port) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return ($null -ne $c)
}

function Get-PortPid([int]$Port) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.OwningProcess }
    return $null
}

function Test-HttpUp([int]$Port, [int]$TimeoutSec = 3) {
    foreach ($path in @('/actuator/health', '/')) {
        try {
            $r = Invoke-WebRequest -Uri ("http://localhost:" + $Port + $path) -UseBasicParsing -TimeoutSec $TimeoutSec
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) { return $true }
        } catch { }
    }
    return $false
}

# 只返回“本项目”的 Java 进程（命令行里带本项目根路径 + -jar），避免误杀其他应用
function Get-OurJava {
    try {
        $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue
    } catch { return @() }
    $rootPattern = '*' + $Root + '*'
    @($procs | Where-Object {
        $_.CommandLine -and ($_.CommandLine -like $rootPattern) -and ($_.CommandLine -like '*-jar*')
    })
}

# ---------------------------------------------------------------- 基础设施
function Ensure-Infra {
    Write-Title '检查基础设施'

    # 1) Windows 服务（应用实际连的是 3306 的本机 MySQL 与 6379 的本机 Redis）
    foreach ($svcName in @('MySQL80', 'MySQL', 'Redis')) {
        $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
        if ($null -eq $svc) { continue }
        if ($svc.Status -ne 'Running') {
            try {
                Start-Service -Name $svcName -ErrorAction Stop
                Write-Ok ("Windows 服务已启动: " + $svcName)
            } catch {
                Write-Warn2 ("无法启动 Windows 服务 " + $svcName + " : " + $_.Exception.Message)
            }
        } else {
            Write-Ok ("Windows 服务运行中: " + $svcName)
        }
    }

    if (Test-Port 3306) { Write-Ok 'MySQL 已监听 :3306' } else { Write-Bad 'MySQL 未监听 :3306' }
    if (Test-Port 6379) { Write-Ok 'Redis 已监听 :6379' }   else { Write-Bad 'Redis 未监听 :6379' }

    # 2) Docker（Kafka 规划任务队列必需）
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        Write-Warn2 '未找到 docker 命令，跳过容器检查（Kafka 需自行确认 :9092）'
        return
    }

    $running = @()
    try { $running = @(docker ps --format '{{.Names}}') } catch { $running = @() }
    $missing = @($CoreContainers | Where-Object { $running -notcontains $_ })

    if ($missing.Count -eq 0) {
        Write-Ok 'Docker 容器运行中: zookeeper, kafka'
    } else {
        Write-Info ("启动 Docker 容器: " + ($missing -join ', '))
        Push-Location $Root
        try { docker compose up -d mysql redis zookeeper kafka | Out-Null } catch { Write-Warn2 $_.Exception.Message }
        Pop-Location
        $deadline = (Get-Date).AddSeconds(90)
        do {
            Start-Sleep -Seconds 3
            try { $running = @(docker ps --format '{{.Names}}') } catch { $running = @() }
            $missing = @($CoreContainers | Where-Object { $running -notcontains $_ })
        } while ($missing.Count -gt 0 -and (Get-Date) -lt $deadline)

        if ($missing.Count -eq 0) { Write-Ok 'Docker 依赖容器已就绪' }
        else { Write-Warn2 ("容器未就绪: " + ($missing -join ', ')) }
    }

    $kafkaDeadline = (Get-Date).AddSeconds(60)
    while (-not (Test-Port 9092) -and (Get-Date) -lt $kafkaDeadline) { Start-Sleep -Seconds 3 }
    if (Test-Port 9092) { Write-Ok 'Kafka 已监听 :9092' } else { Write-Warn2 'Kafka 未监听 :9092' }
}

# ---------------------------------------------------------------- 构建
function Get-MvnExe {
    if ($env:MVN_CMD -and (Test-Path $env:MVN_CMD)) { return @{ Kind = 'mvn'; Path = $env:MVN_CMD } }
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return @{ Kind = 'mvn'; Path = $cmd.Source } }

    # 目录结构: ~/.m2/wrapper/dists/<dist>/<hash>/apache-maven-x.y.z/bin/mvn.cmd
    $dist = Get-ChildItem (Join-Path $env:USERPROFILE '.m2\wrapper\dists') -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($dist) { return @{ Kind = 'mvn'; Path = $dist } }

    $wrapper = Join-Path $Root '.mvn\wrapper\maven-wrapper.jar'
    if (Test-Path $wrapper) { return @{ Kind = 'wrapper'; Path = $wrapper } }
    return $null
}

function Build-Backend {
    Write-Title '构建后端 (mvn -o package -DskipTests)'
    $mvn = Get-MvnExe
    $java = Get-JavaExe
    if (-not $mvn -and -not $java) {
        Write-Bad '找不到 Maven 也找不到 Java，无法构建'
        exit 1
    }

    Push-Location $Root
    try {
        if ($mvn -and $mvn.Kind -eq 'mvn') {
            Write-Info ("使用 Maven: " + $mvn.Path)
            & $mvn.Path -o -q package -DskipTests
        } elseif ($mvn) {
            Write-Info '使用 Maven Wrapper'
            # -D 参数必须整体加引号，否则 PowerShell 会把它拆成多个参数
            & $java "-Dmaven.multiModuleProjectDirectory=$Root" -cp $mvn.Path org.apache.maven.wrapper.MavenWrapperMain -o -q package -DskipTests
        } else {
            Write-Info '使用 Maven Wrapper'
            & $java "-Dmaven.multiModuleProjectDirectory=$Root" -cp (Join-Path $Root '.mvn\wrapper\maven-wrapper.jar') org.apache.maven.wrapper.MavenWrapperMain -o -q package -DskipTests
        }
        if ($LASTEXITCODE -eq 0) { Write-Ok '后端构建完成' }
        else { Write-Bad ("后端构建失败，exit=" + $LASTEXITCODE); exit 1 }
    } finally {
        Pop-Location
    }
}

function Build-Frontend {
    Write-Title '构建前端 (npm run build → gateway static)'
    $feDir = Join-Path $Root 'frontend-new'
    Push-Location $feDir
    try {
        npm run build
        if ($LASTEXITCODE -ne 0) { Write-Bad '前端构建失败'; exit 1 }
    } finally { Pop-Location }

    $dist   = Join-Path $Root 'frontend-new\dist'
    $static = Join-Path $Root 'gateway\src\main\resources\static'
    # 注意：本机 robocopy 不支持 /NJW（会报“无效参数 #8”并以 16 退出）
    robocopy $dist $static /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { Write-Bad ('robocopy 失败 exit=' + $LASTEXITCODE); exit 1 }
    Write-Ok '前端静态资源已同步到 gateway/static'
}

# ---------------------------------------------------------------- 启停
function Stop-OurServices {
    Write-Title '停止本项目 Java 服务'
    $procs = Get-OurJava
    if ($procs.Count -eq 0) {
        Write-Ok '没有正在运行的 TripForge Java 进程'
        return
    }
    foreach ($p in $procs) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Ok ('已停止 PID ' + $p.ProcessId + ' : ' + (($p.CommandLine -split '--server\.port=')[1] -split '\s')[0])
        } catch {
            Write-Warn2 ('停止 PID ' + $p.ProcessId + ' 失败: ' + $_.Exception.Message)
        }
    }

    $deadline = (Get-Date).AddSeconds(25)
    foreach ($s in $Services) {
        while ((Test-Port $s.Port) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
        if (Test-Port $s.Port) { Write-Warn2 ('端口仍被占用: ' + $s.Port) }
    }
    Write-Ok '停止完成'
}

function Start-Services {
    Write-Title '启动服务'

    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

    $java = Get-JavaExe
    if (-not $java) {
        Write-Bad '未找到 Java 21，请设置 JAVA_HOME'
        exit 1
    }
    Write-Info ('Java: ' + $java)

    foreach ($s in $Services) {
        $moduleDir = Join-Path $Root $s.Name
        $jar       = Join-Path $moduleDir ('target\' + $s.Name + $JarSuffix)

        if (-not (Test-Path $jar)) {
            Write-Bad ($s.Name + ' jar 不存在: ' + $jar + '  （加 -Build 先构建）')
            continue
        }
        if (Test-Port $s.Port) {
            Write-Warn2 ($s.Name + ' 端口 ' + $s.Port + ' 已在监听，跳过（如需重启请用 -Action restart）')
            continue
        }

        # Spring 自身日志单独落盘，便于事后排查（stdout 文件可能因块缓冲延迟出现）
        # 注意：JVM 参数必须放在 -jar 之前，否则会被当成程序参数被 Spring 忽略
        $springLog = Join-Path $LogDir ($s.Name + '-spring.log')
        if ($springLog -match '\s') {
            $logArg = ('-Dlogging.file.name="' + $springLog + '"')
        } else {
            $logArg = ('-Dlogging.file.name=' + $springLog)
        }

        $jvmArgs = @(
            '-Dfile.encoding=UTF-8',
            '-Dsun.jnu.encoding=UTF-8',
            ('-Damap.api-key=' + $AmapKey),
            $logArg
        )
        if ($s.Llm) {
            $jvmArgs += @(
                ('-DLLM_API_KEY=' + $LlmKey),
                ('-DZHIPU_API_KEY=' + $LlmKey)
            )
        }
        $javaArgs = $jvmArgs + @('-jar', $jar, ('--server.port=' + $s.Port))

        $outLog = Join-Path $LogDir ($s.Name + '.log')
        $errLog = Join-Path $LogDir ($s.Name + '.err.log')
        Remove-Item $outLog, $errLog, $springLog -ErrorAction SilentlyContinue

        try {
            Start-Process -FilePath $java -ArgumentList $javaArgs `
                -WorkingDirectory $moduleDir `
                -WindowStyle Hidden `
                -RedirectStandardOutput $outLog `
                -RedirectStandardError $errLog -ErrorAction Stop | Out-Null
            Write-Ok ($s.Name + ' 已启动 → http://localhost:' + $s.Port + '   日志: logs\' + $s.Name + '-spring.log')
        } catch {
            Write-Bad ($s.Name + ' 启动失败: ' + $_.Exception.Message)
        }
        Start-Sleep -Milliseconds 700
    }
}

function Wait-Health {
    param([int]$Timeout)
    Write-Title ('等待服务就绪 (最多 ' + $Timeout + ' 秒)')

    $deadline = (Get-Date).AddSeconds($Timeout)
    $pending  = @{}
    foreach ($s in $Services) { $pending[$s.Name] = $true }
    $tick = 0

    while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
        foreach ($s in $Services) {
            if (-not $pending.ContainsKey($s.Name)) { continue }
            if ((Test-Port $s.Port) -and (Test-HttpUp $s.Port)) {
                $pending.Remove($s.Name)
                Write-Ok ($s.Name + '  UP  :' + $s.Port)
            }
        }
        if ($pending.Count -eq 0) { break }
        $tick++
        if ($tick % 3 -eq 0) {
            $names = ($pending.Keys | Sort-Object) -join ', '
            Write-Note ('等待中 (' + [int]((Get-Date) - $deadline.AddSeconds(-$Timeout)).TotalSeconds + 's): ' + $names)
        }
        Start-Sleep -Seconds 3
    }
    return @($pending.Keys)
}

function Show-Status {
    Write-Title '服务状态'
    $allUp = $true
    $rows = @()
    foreach ($s in $Services) {
        $listening = Test-Port $s.Port
        $http      = if ($listening) { Test-HttpUp $s.Port } else { $false }
        $pid2      = if ($listening) { Get-PortPid $s.Port } else { $null }
        $state     = if ($http) { 'UP' } elseif ($listening) { 'LISTEN(未就绪)' } else { 'DOWN' }
        if (-not $http) { $allUp = $false }
        $rows += [pscustomobject]@{
            服务 = $s.Name
            端口 = $s.Port
            PID  = $pid2
            状态 = $state
        }
    }
    $rows | Format-Table -AutoSize | Out-String | Write-Host
    if ($allUp) { Write-Ok '全部服务正常' } else { Write-Warn2 '存在未就绪的服务' }
    return $allUp
}

function Open-Browser {
    if ($NoBrowser) { return }
    try { Start-Process 'http://localhost:8086' } catch { Write-Warn2 ('打开浏览器失败: ' + $_.Exception.Message) }
}

function Show-Summary {
    Write-Host ""
    Write-Host '===== 完成 =====' -ForegroundColor White
    Write-Host '  前端(网关) : http://localhost:8086'
    Write-Host '  Auth       : http://localhost:8081'
    Write-Host '  Trip       : http://localhost:8082'
    Write-Host '  Plan       : http://localhost:8083'
    Write-Host '  Worker     : http://localhost:8084'
    Write-Host '  Notification: http://localhost:8085'
    Write-Host '  日志目录    : ' $LogDir
    Write-Host '  停止服务    : .\start-all.ps1 -Action stop' -ForegroundColor DarkGray
    Write-Host ""
}

# 日志文件为 UTF-8（JVM 已带 -Dfile.encoding=UTF-8），PS5.1 的 Get-Content 缺省按 ANSI 解码会产生乱码，这里强制 UTF-8
function Show-Tail {
    param([int]$Lines = 60)
    $names = if ($Service) { @($Service) } else { @($Services | ForEach-Object { $_.Name }) }
    foreach ($n in $names) {
        $springLog = Join-Path $LogDir ($n + '-spring.log')
        $outLog    = Join-Path $LogDir ($n + '.log')
        $path = if (Test-Path $springLog) { $springLog } elseif (Test-Path $outLog) { $outLog } else { $null }
        if (-not $path) { Write-Warn2 ($n + ': 无日志文件'); continue }
        Write-Host ('===== ' + (Split-Path $path -Leaf) + ' (末 ' + $Lines + ' 行) =====') -ForegroundColor Cyan
        Get-Content -Path $path -Encoding UTF8 -Tail $Lines | Write-Host
    }
}

# ---------------------------------------------------------------- 主流程
$sw = [System.Diagnostics.Stopwatch]::StartNew()

switch ($Action) {
    'status' {
        $ok = Show-Status
        if ($ok) { exit 0 } else { exit 1 }
    }

    'tail' {
        Show-Tail -Lines 60
        exit 0
    }

    'stop' {
        Stop-OurServices
        Show-Status | Out-Null
        exit 0
    }

    'restart' {
        Stop-OurServices
        # 与 start 相同的构建顺序：前端 → 同步 static → 打包后端
        if ($BuildFrontend) { Build-Frontend }
        if ($Build -or $BuildFrontend) { Build-Backend }
        if (-not $SkipInfra) { Ensure-Infra }
        Start-Services
        $pending = Wait-Health -Timeout $WaitSeconds
        $ok = Show-Status
        if ($pending.Count -gt 0) { Write-Warn2 ('未就绪: ' + ($pending -join ', ') + '，可查看 logs\*.log 与 logs\*.err.log') }
        Show-Summary
        if ($ok) { Open-Browser }
        if ($ok) { exit 0 } else { exit 1 }
    }

    default {
        # ---- start ----
        $missingJar = @($Services | Where-Object {
            -not (Test-Path (Join-Path $Root ($_.Name + '\target\' + $_.Name + $JarSuffix)))
        })
        $needBackend = $Build -or $BuildFrontend -or ($missingJar.Count -gt 0)

        # jar 正在被运行中的服务占用会导致打包失败（Unable to rename *.jar），先停再建
        if ($needBackend -and (Get-OurJava).Count -gt 0) {
            Write-Note '构建前先停止本项目服务（避免 jar 被占用导致打包失败）'
            Stop-OurServices
        }
        if ($missingJar.Count -gt 0 -and -not $Build) {
            Write-Note ('jar 缺失: ' + (($missingJar | ForEach-Object { $_.Name }) -join ', ') + ' → 自动构建')
        }

        # 顺序固定：前端构建 → 同步 static → 打包后端，新静态资源才会进 gateway jar
        if ($BuildFrontend) { Build-Frontend }
        if ($needBackend) { Build-Backend }
        if (-not $SkipInfra) { Ensure-Infra }

        Start-Services
        $pending = Wait-Health -Timeout $WaitSeconds
        $ok = Show-Status
        if ($pending.Count -gt 0) {
            Write-Warn2 ('未就绪: ' + ($pending -join ', '))
            Write-Note ('查看日志: logs\' + (($pending | Select-Object -First 1) + '.err.log'))
        }
        Show-Summary
        Write-Host ('  耗时 ' + [int]$sw.Elapsed.TotalSeconds + ' 秒') -ForegroundColor DarkGray
        if ($ok) { Open-Browser; exit 0 } else { exit 1 }
    }
}
