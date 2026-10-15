# 系统参数配置中心（#23）

管理页面位于 `/admin/sys/config`，仅 ADMIN 可读取、修改、重置和查看审计。与站点品牌配置 `site_config` 分表；评论审核开关继续由原站点设置维护，避免两处开关相互覆盖。密钥、数据库/MinIO 凭据、OAuth 密钥不在配置白名单。

首批 20 项：六个验证码场景各自的触发阈值和计数窗口、验证码文本有效期、JWT 有效期、Cookie SameSite、每用户每日上传配额、图片大小与扩展名、上传默认存储、MySQL 全文搜索开关。

| 参数 | 类型 | 生效方式 |
|---|---|---|
| `blog.captcha.scenes.{scene}.threshold` / `.window-seconds` | INT | 热生效；已有 Redis 计数窗口保留原 TTL，新窗口采用新配置 |
| `blog.captcha.code-ttl-seconds` | INT | 热生效；只影响新生成验证码 |
| `jwt.expiration-ms` | INT | 重启生效；范围 5 分钟至 7 天，已签发 token 的有效期不变 |
| `blog.auth.cookie.same-site` | STRING | 重启生效；Strict/Lax/None，None 要求环境中的 Secure 为 true |
| `blog.upload-daily-limit` | INT | 热生效；当天计数沿用原值 |
| `blog.upload.max-bytes` | INT | 热生效；1KB 至 5MB，不能突破 multipart 容器上限 |
| `blog.upload.allowed-extensions` | JSON | 热生效；非空、不重复，限 jpg/jpeg/png/gif/webp 安全白名单的子集 |
| `blog.upload.storage` | STRING | 重启生效；LOCAL/MINIO。MINIO 需先在环境配置启用 MinIO 客户端，endpoint/凭据仍从环境读取 |
| `blog.search.fulltext-enabled` | BOOL | 热生效；启用前数据库必须具备现有全文索引 |

验证码场景包括 login、register、email-code、password-reset、comment、article-password。
元数据由代码中的 `SysConfigDefinition` 白名单维护，并在启动时初始化数据库行。数据库 `config_value = NULL` 表示继承部署配置；首次部署不会用数据库种子覆盖既有环境变量。

读取顺序为数据库覆盖 > 定义中的显式环境变量 > 同名 Spring/YAML 属性 > 内置默认值。重置删除数据库覆盖，恢复该顺序，而不是忽略环境变量强制写入内置默认值。
环境变量名见代码中的 `SysConfigDefinition`；例如 `UPLOAD_MAX_BYTES`、`UPLOAD_ALLOWED_EXTENSIONS`（JSON 数组字符串）、`CAPTCHA_LOGIN_THRESHOLD`、`SEARCH_FULLTEXT_ENABLED`。未明确配置 `blog.upload.storage` 时沿用 `blog.minio.enabled` 决定的现有默认后端。

读取使用内存快照，与站点设置相同采用启动加载和写后刷新范式。热生效项本机成功写入后立即发布已提交快照；其他实例每 5 秒从共享数据库重新读取，实际延迟取决于调度与数据库。读取失败保留上次有效快照并记录不含配置值的告警。
重启生效项单独持有进程启动快照。API 同时返回目标 `value`、当前进程 `activeValue` 和 `restartRequired`，管理页显示待重启；修改数据库或刷新缓存不会提前改变正在运行的认证/存储配置。
写入和启动均校验 MinIO/Secure 前置条件；如果部署环境将相关能力关闭，应先重置对应数据库覆盖再重启，避免启动时使用不满足条件的配置。
JWT 缩短后，用户 token 索引不提前缩短已存在的较长 TTL；用户级吊销黑名单覆盖支持的最长有效期，避免重启后的短 TTL 漏掉历史长 token。

存储策略只决定新上传目标。删除旧附件按记录里的 `storage` 选择本地或 MinIO，不依赖当前默认；缺少对应后端时保留待清理对象并告警，不误删其他后端的同名对象。对象孤儿扫描与观察期继续由 #22 跟踪。

接口：

- `GET /admin/sys/config`：分组、类型、说明、范围/正则/选项、当前值、默认/继承值、来源和生效方式。
- `PUT /admin/sys/config`：仅提交需要覆盖的键，整数/布尔/JSON 必须使用原生 JSON 类型；未知键、null、不合法类型或范围拒绝。先完成整批校验，事务内按统一顺序锁定配置行并写入值与审计，失败不发布缓存。
- `DELETE /admin/sys/config/{key}`：重置该键的数据库覆盖，审计记录 RESET。
- `GET /admin/sys/config/audit?page=1&size=20`：分页返回修改人 ID、时间、操作、旧值/新值及来源。管理员并发写入时，审计旧值取已锁定的数据库行。

数据库增加 V15（sys_config/sys_config_audit）。部署先后端迁移并提供 API，再发布前端管理页；旧前端继续工作。回滚应用前保留新增表，不回退已执行的 schema；重启生效项如需恢复，应先重置数据库覆盖再重启。
测试覆盖真实路由和 H2/MyBatis/JDBC 持久化、并发审计、缓存与消费者，以及前端页面交互；真实 MySQL 迁移由 CI 单独检查，不将其等同于真实 MySQL/Redis/MinIO 业务链路验收。
