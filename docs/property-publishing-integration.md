# 房源运营发布接入说明

## 交付边界

发布中心可在未接入外部账号时使用：维护多个平台账号、从房源生成文案、审核与排期、按账号跟踪任务、下载素材清单并人工发布、登记真实作品链接。抖音自动调用通路默认关闭。小红书、视频号和其他平台采用人工发布流程，不把“生成草稿”“导出素材”标记为已经发布。

用户已确认当前没有已申请的开放平台应用，先完成发布中心与接入配置。本次没有使用真实授权账号发布任何外部内容。实际开放权限、账号资质和内容场景仍需对应平台审核；部署代码不代表已经获得权限。

## 已核验的官方能力（2026-11-18）

- [抖音创建图文](https://partner.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/video-management/douyin/create-image-text/create-image-text)：`POST https://open.douyin.com/api/douyin/v1/video/create_image_text/`，需要申请 `video.create.bind` 并取得用户授权；`open_id` 为查询参数，`access-token` 为请求头。`image_list` 使用上传后返回的图片标识，`text` 是标题/正文；每次最多 30 张图片，发布阶段单图最多 20MB。接口成功意味着进入平台审核流程。
- [抖音图片上传](https://developer.open-douyin.com/docs/resource/zh-CN/dop/develop/openapi/video-management/douyin/create-image-text/image-upload)：`POST https://open.douyin.com/api/douyin/v1/video/upload_image/`，multipart 文件字段为 `image`；使用同样的授权信息，响应图片标识在 `data.image.image_id`。本项目按后续发布接口的限制把素材控制在 20MB/张，只接收 JPEG/PNG。
- [抖音发布能力使用规范](https://partner.open-douyin.com/docs/resource/zh-CN/dop/operation-standard/platform-capabilities/useclue)：开放范围、原创性与内容场景有约束，文档对营销类内容作出限制。因此房源推广不能仅凭获得通用发布权限就开启；必须先向平台确认该应用及房产运营场景可用，再配置 `housing-approved`。
- [小红书授权范围](https://openaccount.xiaohongshu.com/docs/scope)：公开文档列出的当前范围以基础身份信息为主，不能据此推定普通应用已获得代发笔记权限。本实现没有臆造小红书发布接口，也不保存账号密码、Cookie 或模拟浏览器登录。

后续接入正式服务商时，应增加独立适配器并保留审核、幂等和不确定结果的处理规则；不能让任意外部 HTTP 地址充当“发布接口”。

## 服务端配置

`PublishingProperties` 使用 Spring Boot 前缀 `guardfind.publishing`。默认 `enabled=false`；配置文件及密钥放在部署环境的受限配置目录/密钥系统，不要提交到 Git。后台只录入 `credentialKey` 引用名称，不能填写 token。

示例为服务端外部配置文件，保留 `${...}` 占位符，由环境变量注入实际值：

```yaml
guardfind:
  publishing:
    enabled: ${PUBLISHING_ENABLED:false}
    media-hosts:
      - images.your-owned-domain.example
    douyin-accounts:
      office1:
        open-id: ${DOUYIN_OFFICE1_OPEN_ID:}
        access-token: ${DOUYIN_OFFICE1_ACCESS_TOKEN:}
        expires-at: ${DOUYIN_OFFICE1_EXPIRES_AT:}
        scope-confirmed: ${DOUYIN_OFFICE1_SCOPE_CONFIRMED:false}
        housing-approved: ${DOUYIN_OFFICE1_HOUSING_APPROVED:false}
```

1. `media-hosts` 填可信自有图片存储/CDN 的精确域名，小写，不带协议、路径或通配符。图片必须为无需登录的 HTTPS 地址；拒绝跳转、内网地址、带用户信息或非 443 端口的 URL。不将任意用户可控制的域名加入白名单。生产网络层还应限制服务到内网的访问，避免 DNS 配置错误。
2. `office1` 是后台抖音账号的凭据引用名称，每个实际抖音授权账号使用独立引用。`open-id` 与 `access-token` 必须来自同一应用、同一授权账号；不能拿客户端 token 代替用户授权 token。
3. `expires-at` 是 ISO 8601 UTC 时间，如 `2026-12-01T00:00:00Z`。剩余有效期不足 5 分钟、字段缺失或解析失败时，转为待人工处理。示例时间仅说明格式，必须填写本次授权真实到期时间。
4. `scope-confirmed=true` 仅在实际获批 `video.create.bind` 且用户已授权时设置。`housing-approved=true` 仅在平台确认应用可用于本房产运营场景后设置。默认均为 false。
5. 以上直接授权配置不包含 OAuth 授权页面、自动刷新 token 或授权回调。到期后必须按官方授权流程重新获取/更新服务器密钥并重新加载应用配置。不要把密钥粘贴到聊天、后台账号名称、任务备注或故障日志。

可通过 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/secure/guardfind-publishing.yml` 加载上述配置。无需配置真实授权也可以使用人工发布。`PUBLISHING_ENABLED` 等名称来自上述占位符示例，并不是额外硬编码的配置入口。

## 状态与重复发布

- `SUBMITTED`：官方接口明确返回作品标识，仍需平台审核；不能等同于公开可见。
- `FAILED`：在准备/上传素材阶段失败，或平台明确拒绝创建。可由管理员修正后重试。
- `UNKNOWN`：创建请求超时、响应格式不明确或返回成功却缺少作品标识。平台可能已经收到请求，禁止盲目重复提交，先在创作者中心核对。
- `MANUAL_REQUIRED`：账号使用人工方式、无可用授权、平台准入关闭或授权过期。运营下载素材清单后，在对应账号创作者中心发布，再回填真实链接。
- `SUBMITTED/UNKNOWN` 可由运营在平台核实后登记“未发布/审核未通过”，填写原因后关闭任务；不会直接重发。确认公开发布须提供与目标平台匹配的 HTTPS 作品链接。

自动建稿仅覆盖开启设置之后新建且上架、正常接待的房源；历史房源在发布中心手动选择建稿。自动建稿始终进入待审核状态。已有人工账号接入抖音接口时，先完成或取消其未完成任务，再切换授权配置；已有任务的账号不允许更换平台或账号标识。

同一请求及同账号相同内容去重；排期、审核和结果处理使用版本校验。专用工作池最多并行处理三个任务，同账号至少间隔五分钟，数据库账号行锁与任务状态校验防止多实例重复领取。超过十五分钟未返回的提交转为结果待核实。最多五次明确失败重试，修改草稿后重新审核可开启新的尝试，原处理记录保留。单个自动建稿失败不会停止其他任务派发。

提交前及图片上传后均核对房源状态、内容快照和账号启用状态。网络调用期间仍可能发生外部审核或房源状态变化，已对外提交的作品需运营通过留存链接核实并撤下；本次未实现平台自动删除作品。

图片准备有 3 分钟预算，并为单次 HTTP 连接/读取设置超时。创建请求不自动重试。素材下载不附加业务登录信息，抖音 token 只发送至固定的官方 API 主机；错误记录仅保留可诊断错误码，不保存平台原始响应、token 或异常正文。

## 启用和真实验收

先执行 `property_publishing.sql` 数据库迁移、部署管理后台，确认账号权限按角色配置。保持自动外发开关关闭，使用测试房源完成多账号草稿、审核、定时任务、素材下载和结果回填检查；确认房源下架/已租/内容变更会阻止旧任务继续发送。

在平台准入及账号授权完成后，选择一个专门的测试账号，核对文案、图片权利及内容场景，由运营在发布中心明确批准一次任务，再打开服务端自动调用开关。先确认上传、创建、平台审核与作品链接各自的结果，再扩大账号范围。检验授权过期、平台拒绝、请求超时、重复操作和账号停用；不通过批量重试测试真实接口。

单元测试使用受控响应和模拟 HTTPS 连接，验证接口字段、权限前提、素材限制、超时归类和敏感信息处理，不向任何真实账号发送内容。真实 MySQL、部署网络/CDN、平台授权及正式账号联调仍是部署验收项。
