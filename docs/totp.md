# TOTP 两步验证（#19）

设置页输入当前密码后绑定验证器，输入动态码确认才生效。SHA-1、6 位、30 秒周期，允许前后各一个周期误差；已成功使用的周期不能重放。前端 qrcode 本地生成二维码，不向外部服务发送密钥。

## 部署与密钥

先部署后端 V17/API，再发布前端。启用前通过环境变量 `TOTP_ENCRYPTION_KEY` 提供 32 个加密安全随机字节的 Base64，例如在可信运维终端运行 `openssl rand -base64 32`，将输出直接保存到部署密钥管理系统。不要使用测试密钥或将实际密钥提交仓库、日志、Issue。所有实例使用同一密钥并保持时钟同步。

此密钥独立于 JWT_SECRET。未配置时禁止新绑定；已启用账号仍要求第二因子，不能通过删除密钥降级登录。无效格式/长度导致启动失败，错误或缺失密钥导致解密失败，需恢复原密钥。必须独立安全备份密钥，并与数据库一起验证恢复。本版本不支持在线换钥，不能直接换钥重启，应另行设计全量重加密流程。

V17 增加 user_totp、totp_recovery、totp_challenge。TOTP 密钥以 AES-256-GCM 加密，随机 nonce、用户 ID 作为附加认证数据。恢复码每枚 128 位随机值，仅存 SHA-256，原文仅在开启或重生时返回一次。数据库备份含加密后的 TOTP 秘密和恢复码哈希，应按业务备份保护，不得上传为普通构建 artifact。

**回滚限制：存在已开启 TOTP 的账号时，禁止回滚到不支持 TOTP 的旧后端，否则旧登录路径会绕过第二因子。** 旧前端无法完成新挑战；须完成两仓部署后再开放设置。回滚保留 V17 schema，前后端都应保留第二因子能力。

## 登录与恢复规则

- 密码正确或 GitHub OAuth 成功后，TOTP 账号仅获得五分钟挑战，不签发 JWT/Cookie。OAuth 临时 HTTP 会话在回调清除，仍通过原一次性换码通道传递挑战。前端挑战只存内存，刷新需重新登录。
- 同账号仅一个挑战，新主因子登录替换旧挑战。数据库只存挑战哈希；过期、消费、密码/角色/封禁变更、用户级会话吊销后无效。
- 按用户行串行验证；MySQL 锁定读取获取最新状态，避免 REPEATABLE READ 快照重复消费。连续五次无效验证锁定五分钟；新挑战或重新绑定不重置失败计数，正确第二因子清零。
- 绑定十分钟内确认，需要当前密码和动态码；关闭和重生恢复码需要密码和动态码/恢复码。成功后吊销全部 JWT/WS 会话与待验证挑战，保存恢复码后重新登录。每枚恢复码只可使用一次。
- 改密和邮箱重置保留 TOTP，吊销会话与挑战，不提供邮箱绕过。仅 OAuth 的账号需先通过邮箱重置设置密码。丢失验证器可用恢复码登录、关闭和重新绑定；全部因素遗失时无自动降级或管理员一键绕过接口，需人工身份核验及单独审核的恢复流程。

## 接口

| 接口 | 请求 | 响应 |
| --- | --- | --- |
| GET /auth/totp | 登录态 | enabled、available、recoveryRemaining，无密钥 |
| POST /auth/totp/setup | password | secret、uri |
| POST /auth/totp/confirm | password、code | 十枚恢复码并清 Cookie |
| POST /auth/totp/recovery | password、code | 十枚新恢复码，旧码失效并清 Cookie |
| POST /auth/totp/disable | password、code | 清绑定、恢复码与 Cookie |
| POST /auth/totp/login | challenge、code | 验证后 JWT 与 HttpOnly Cookie |

主因子登录保留原成功响应；需第二因子时返回 `mfa_required: "true"` 和 `challenge`。40081 为无效/过期/重放，40082 为锁定，40083 为已有绑定，40084 为站点未配置。保留现有 CSRF 自定义头检查。不得缓存或记录包含 secret、code、password、challenge 的请求/响应。

## 验证

`mvn -B verify` 包含 RFC 向量（含 2038 年后）、密钥加密与用户绑定、密码/GitHub 挑战、权限、过期、重放、锁定、恢复码哈希/轮换与并发消费。H2/MockMvc 与 Redis 替身不等于真实基础设施验收。

沿用 [真实依赖规程](real-dependency-tests.md) 的 real-it 套件，增加 MySQL 并发消费仅一次成功、Redis 会话吊销与 HTTP 登录回归。固定测试密钥仅用于隔离环境。前端覆盖挑战不提前建立会话、失败重试、二维码绑定、恢复码离开保护及取消关闭。

参考：[RFC 6238](https://www.rfc-editor.org/rfc/rfc6238)、[Google URI 格式](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)、[qrcode API](https://github.com/soldair/node-qrcode)。
