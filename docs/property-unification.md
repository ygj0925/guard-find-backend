# 房源服务统一迭代

## 服务边界与契约

- 管理后台和微信小程序共同使用 `guard-find-backend` 的数据库及房源服务。
- 小程序接口 `/miniapp/auth`、`/miniapp/property`、`/miniapp/im`、`/miniapp/notices`；管理接口 `/system/property`、`/system/im`。
- App 继续使用 `guard-find-server` 的 `/app/**`，不修改 App 的服务地址。
- 小程序身份与后台管理员身份分开，Sa-Token 登录域为 `miniapp`；房东 ID 是小程序用户 ID，不能填写 sys_user ID。
- 房源业务为平台级数据，仅默认租户后台可管理。独立业务表不经过租户行拦截，不开放给其他租户管理。
- 列表 page 从 1 开始，size 上限 100；消息游标按 ID 升序，首次/增量最多 100 条，客户端持续拉取直到不足一页。
- ID 在客户端作为字符串保存，避免 Java Long 转 JavaScript Number 丢失精度。
- 房源状态 1 草稿 / 2 上架 / 3 下架；仅上架内容公开，房东可查看自己的非公开房源。
- 预约状态 1 待确认 / 2 已确认 / 3 已拒绝 / 4 已取消；房东确认或拒绝，预约人取消。后台可按相同状态规则处理。

## 部署与数据

新增 Liquibase changeset 只作用于 backend 数据库。server 历史数据不会自动跨数据库共享；上线前按实际数据归属安排用户、房源、收藏、历史、预约及 IM 数据的一致性迁移，并保留原 ID。不要把 server 的整个业务 SQL 导入 backend。

backend 微信登录需要 `MINIAPP_WECHAT_APP_ID` 和 `MINIAPP_WECHAT_APP_SECRET`。开发默认 backend 端口 8000；生产小程序通过 VITE_SERVER_BASEURL 及微信各渠道变量配置已备案的 HTTPS 域名，并配置微信合法请求域名。微信登录身份与旧 server token 不通用，切换后需要重新登录。

## 已发现的问题与修复范围

房源地址校验错位、租赁方式筛选缺失、可选信息无法清空、图片空数组不生效、草稿详情泄露；收藏/历史列表未过滤非公开数据；预约并发覆盖；聊天已读接口缺少成员检查、会话删除影响对方摘要、消息无限加载、客服会话扫描截断；前端轮询串话与 ID 精度风险。迭代覆盖这些路径和相关前端入口，验证结果见后续记录。

## 功能与接口对应

下面路径均由 backend 提供，响应统一沿用项目 `R` 包装。分页返回 `data.list`、`data.total`。

| 功能 | 小程序 | 管理后台 |
| --- | --- | --- |
| 新增房源 | POST `/miniapp/property` | POST `/system/property`，额外传 `ownerId` |
| 编辑基本信息 | PUT `/miniapp/property/{id}`，仅本人 | PUT `/system/property/{id}` |
| 图片上传 | POST `/miniapp/property/images` | POST `/system/property/images` |
| 房源列表 | GET `/miniapp/property/list`，仅上架 | GET `/system/property/page` |
| 详情 | GET `/miniapp/property/{id}` | GET `/system/property/{id}` |
| 我的房源 | GET `/miniapp/property/mine` | 列表可查看所有房源 |
| 上下架、草稿 | POST `/miniapp/property/{id}/status` | POST `/system/property/{id}/status` |
| 删除 | DELETE `/miniapp/property/{id}` | DELETE `/system/property/{id}` |
| 收藏切换 | POST `/miniapp/property/{id}/favorite` | — |
| 收藏、浏览历史 | GET `/miniapp/property/favorites`、`/history` | — |
| 创建预约 | POST `/miniapp/property/{id}/booking` | — |
| 预约列表 | GET `/miniapp/property/bookings?role=guest或owner` | GET `/system/property/bookings` |
| 处理预约 | POST `/miniapp/property/booking/{id}/status` | POST `/system/property/booking/{id}/status` |
| 房源管家 | 详情展示管家身份 | GET `/system/property/manager`、POST `/system/property/manager/{userId}` |
| 用户私信、客服 | `/miniapp/im/**` | `/system/im/**`，客服会话分页 |
| 业务通知 | `/miniapp/notices/**` | — |

图片使用 multipart 字段 `file`，仅 JPG/JPEG/PNG，每张不超过 5MB，校验真实图片格式和尺寸；每套房源最多 9 张。两个入口复用后台默认存储，上传目录由服务端按端别、用户和日期生成，不能由客户端指定。需要在“存储管理”中配置可公开访问的 HTTP(S) 地址，并在微信后台配置 uploadFile/downloadFile 合法域名。

管理房源新增、编辑、状态、删除、管家与预约处理分别使用独立权限码。编辑时修改状态仍需状态权限，不能通过基本信息接口绕过。仅默认租户可使用这组业务接口，小程序身份和管理身份不能互用。

## 并发与性能调整

- 房源编辑、状态、收藏、预约创建使用行锁和事务；预约状态变更使用条件更新，重复确认/取消不会覆盖其他操作。删除存在进行中预约的房源会明确拒绝。
- 收藏和历史先在 SQL 中过滤已删除/非上架房源再分页，修复列表数量与 total 不一致；房东和预约用户信息批量查询。
- 客服会话在数据库分组、计数和分页，避免全量读取后按 5000 条截断。
- IM 每批最多 100 条，仅将实际返回的对方消息标记已读；删除会话仅隐藏自己的入口，新消息会恢复入口。
- 管理端房源弹窗按需加载，弹窗状态隔离，减少打开弹窗时整张表格重渲染。
- 聊天采用串行轮询，退出页面停止，切换会话丢弃过期响应；接收游标独立于发送回包，防止漏收并发消息。

## 上线步骤与未验证边界

1. 备份 backend/server 数据库；分别审核并运行各自的 Liquibase 变更。backend 新增小程序、房源、互动、IM 表及菜单；server 仅扩展图片字段容量。没有自动执行真实数据库迁移。
2. backend 配置微信 App ID/Secret、默认文件存储与公网地址；管理角色分配新增房源运营权限。
3. 管理端生产环境默认请求 `/api`，需将 `/api` 反向代理到 backend，并去除 `/api` 前缀；也可在构建时设置 `GUARD_FIND_API_BASE_URL`。小程序通过 `.env.production.local` 覆盖 `VITE_SERVER_BASEURL` 及对应微信渠道变量，不能直接发布示例域名。
4. 历史数据若需迁移，先按同一小程序 App ID 下的微信 openid 对齐用户、保留业务 ID，再迁移关联数据。不同微信应用的 openid 不能直接用于合并用户。App 继续连接 server，不会自动与 backend 共享新数据；如需要 App 与小程序也实时共享数据，需要另外确定共享服务或同步方案。
5. 使用真实微信开发者工具完成登录、发布图片、管理端编辑、预约处理、通知和双用户聊天验收。本次本地验证没有调用真实微信服务、对象存储或真实数据库。

## 其他存量问题

旧契约脚本合并 backend 和 server 扫描，会把仅 server 提供的接口误判为管理端可用。本次改为默认只检查 backend，`API_CONTRACT_BACKEND` 可显式指定其他单个仓库。最终 169 个有效前端请求全部匹配，HTTP 方法不匹配、缺失路径、未知权限码、可见菜单缺失页面均为 0。另有 4 个已登记下线的旧聊天接口和 10 个隐藏深链旧页面，沿用现有登记。

此前跨仓库检查发现的 5 个缺失页面（内容审核、举报管理、采集源、采集候选、采集日志）来自 server 的种子菜单，backend 当前菜单不包含它们。如将来需要迁入这些独立业务，应同时迁入后端能力和管理页面，不能仅复制菜单。

## 本地验证记录（2026-11-03）

- backend：49 项测试通过，包含房源校验/权限/预约、图片格式与大小、IM 成员边界、登录域、Bearer 解析和租户隔离；API reactor 编译通过。
- server：真实执行 13 项房源/IM 测试通过（显式 `-Dtests.skip=false`），App `/app/**` 路径保留，App 仓库无改动。
- 小程序：8 个测试文件、35 项测试通过；`pnpm type-check`、本次修改文件的 ESLint 检查、微信小程序 development 构建通过。
- 管理端：TypeScript 检查通过；Biome 无错误，有两处既有 optional-chain 建议；仅 backend 的接口契约检查通过。
- 管理端完整 Jest 回归：14 个测试文件、88 项全部通过。本机首次运行有 2 项图标 UI 测试超过 30 秒；最终使用 `--runInBand --testTimeout=120000` 完整重跑通过。Jest 直接映射与 tsconfig 一致的路径别名，避免 `configUmiAlias` 为单元测试先执行整站准备编译。
- 管理端 `npm run build:zy` 生产构建通过，保留生产 Mako/Tailwind 配置。
- 所有仓库 `git diff --check` 通过。

检查覆盖本地编译、静态契约与自动化回归，不代表完成真实微信、对象存储或数据库的端到端联调，也未进行真实浏览器性能基准测量。
