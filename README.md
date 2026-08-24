# Guard Find Backend

`guard-find-backend` 是 Guard Find 管理后台服务端，为 `guard-find-frontend` 提供运营、权限、租户、系统配置和审计等 API。工程采用 Java 17 / Spring Boot 多模块 Maven reactor。

## 模块与制品

- `guard-find-backend-api`：可部署后台 API，薄包主类为 `com.guardfind.backend.GuardFindBackendApplication`，根制品为 `com.guardfind.backend:guard-find-backend`。
- `guard-find-backend-system`：认证、RBAC、菜单和系统管理能力。
- `guard-find-backend-common`：跨模块基础设施与 CRUD 基类。
- `guard-find-backend-plugin`：open、tenant、schedule、generator 插件聚合及其 `guard-find-backend-*` 子制品。
- `guard-find-backend-extension`：扩展聚合；`guard-find-backend-extension-schedule-server` 的启动类为 `com.guardfind.backend.extension.scheduling.GuardFindBackendScheduleApplication`。

## 本地构建与启动

```powershell
mvn -B -Dspotless.apply.skip=true -DskipTests package
# 或在 IDE 运行 com.guardfind.backend.GuardFindBackendApplication
```

主服务使用独立的 `guard-find-backend` 应用标识、数据库和模块坐标。通过 `DB_*`、`REDIS_*`、`SCHEDULE_*` 环境变量配置基础设施；thin jar 输出至 `guard-find-backend-api/target/app/`。

## 容器部署

`docker/docker-compose.yml` 的 Compose 项目名为 `guard-find-backend`，所有服务使用 `guard-find-backend-*` 前缀。为与用户侧服务共存，MySQL、Redis、API、调度服务分别映射 `23306`、`26379`、`28000/27889`、`28001/27888`。部署前需填入密码和生产配置。

## 归因与许可

本工程保留上游许可证头和 Apache-2.0 许可文本。它依赖第三方 `top.continew.starter`（ContiNew Starter）；该外部依赖的 Maven 坐标、配置前缀和 API 名称不属于 Guard Find 的内部命名迁移范围。
