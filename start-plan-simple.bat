@echo off
chcp 65001 >nul 2>&1
cd /d D:\agent-trip-planner

set DB_URL=jdbc:mysql://localhost:3306/trip_planner?useSSL=false^&allowPublicKeyRetrieval=true^&serverTimezone=Asia/Shanghai
set DB_USER=root
set DB_PASSWORD=12345678mxy
set REDIS_HOST=localhost
set REDIS_PORT=6379

for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if /i "%%A"=="LLM_API_KEY" set "LLM_API_KEY=%%B"
    if /i "%%A"=="ZHIPU_API_KEY" set "ZHIPU_API_KEY=%%B"
    if /i "%%A"=="AMAP_API_KEY" set "AMAP_API_KEY=%%B"
)

java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar plan-service\target\plan-service-1.0.0-SNAPSHOT.jar --server.port=8083
