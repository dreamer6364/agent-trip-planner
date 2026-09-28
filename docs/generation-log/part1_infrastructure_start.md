# 第一部分：项目骨架 & 基础设施 - 起始文档

## 生成范围
- Docker Compose 编排文件
- MySQL 初始化脚本 (DDL + 基础数据)
- 公共模块 (common-module): 统一响应、异常处理、工具类、配置类
- Maven 父工程 pom.xml
- 环境变量模板
- Git 忽略文件

## 技术栈版本
- Java 21
- Spring Boot 3.2.x
- Spring Cloud 2023.x
- MySQL 8.0
- Redis 7
- Kafka 3.6
- Maven 3.9+

## 目录结构预期
```
trip-planner/
├── pom.xml                          # 父工程
├── docker-compose.yml               # 本地开发编排
├── .env.example                     # 环境变量模板
├── .gitignore
├── common-module/                   # 公共模块
│   ├── src/main/java/com/tripplanner/common/
│   │   ├── annotation/
│   │   ├── config/
│   │   ├── exception/
│   │   ├── response/
│   │   ├── util/
│   │   └── CommonModuleApplication.java
│   └── pom.xml
├── auth-service/
├── trip-service/
├── plan-service/
├── geo-service/
├── planning-worker/
├── notification-service/
├── gateway/
└── frontend/
```