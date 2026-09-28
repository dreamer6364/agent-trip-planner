@echo off
chcp 65001 >nul 2>&1
cd /d D:\agent-trip-planner

set DB_URL=jdbc:mysql://localhost:3306/trip_planner?useSSL=false^&allowPublicKeyRetrieval=true^&serverTimezone=Asia/Shanghai
set DB_USER=root
set DB_PASSWORD=12345678mxy
set REDIS_HOST=localhost
set REDIS_PORT=6379

java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar auth-service\target\auth-service-1.0.0-SNAPSHOT.jar --server.port=8081
