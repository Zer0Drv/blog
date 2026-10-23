# 所有者、会话与本机运维

适用范围：#26、#82、#83；仅当前单机 Docker 部署。AI 功能不在本次范围。

## 发布与会话迁移

先部署后端（Flyway V18–V20），再部署配套前端。V18 清除评论通知的历史摘要，
V19 增加刷新会话及用户会话版本，V20 增加所有者操作锁与审计。迁移前按备份文档
保存数据库；回滚旧后端必须按兼容矩阵验证，不应在旧权限实现上继续使用 SUPER_ADMIN。

新 access token 最长 15 分钟，refresh cookie 为 HttpOnly、SameSite=Strict、
路径 `/api/auth/refresh`，绝对有效期 30 天，轮换不会延长期限。数据库仅存刷新令牌哈希。
重复使用旧 refresh 会撤销该用户全部会话；并发标签页通过 Web Locks 排队，
不支持 Web Locks 的浏览器在同一令牌并发刷新时可能要求重新登录。
旧 access token 在原有效期内仍按数据库角色鉴权，首次到期后需重新登录获得 refresh。
退出登录撤销当前设备的整个刷新链，其他设备保留；改密、权限变化、封禁、二次验证变更
走现有全用户吊销事件，同时增加 `session_version`、撤销 refresh 并关闭实时会话。
新 token 的角色来自签发时的受控数据库记录，每次验证检查版本与账号状态。
禁止用直接 SQL 修改角色代替管理 API，否则无法同步版本与实时连接。

同一数据库和 Redis 的实例通过 `blog:session-revoked:v1` 广播关闭远端实时连接；
消息仅包含实例 ID 和用户 ID 或 jti，不传凭据。事务提交后广播，远端不重复增版本或回发。
Pub/Sub 非持久，断线时仍由共享黑名单和每秒连接校验兜底；网关透传 Cookie，
不独立缓存权限。多机部署应使用同一 Redis 和数据库，本机重启功能不适用于多机编排。

## 首个所有者与故障恢复

| 操作者 | 普通用户/作者 | 普通管理员 | 超级管理员 |
| --- | --- | --- | --- |
| USER/AUTHOR | 仅自身既有授权操作 | 无管理权限 | 无管理权限 |
| ADMIN | 既有管理权限 | 禁止互改 | 禁止处置 |
| SUPER_ADMIN | 继承既有管理权限 | 专用应急操作，须重新验证 | 禁止互改和自我处置 |

所有权转移只从当前 SUPER_ADMIN 转给活跃 ADMIN，不能用于修改另一个 SUPER_ADMIN。

升级不会自动将 ADMIN 提升为 SUPER_ADMIN，也没有默认恢复口令。
在有交互终端的主机上，以正确的数据库、Redis、JWT/TOTP 密钥等既有运行配置启动同版本 jar：

```powershell
java -jar target/blog-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none --blog.owner.recovery-command=true
```

jar 文件名以本次构建结果为准。Docker 部署可使用同版本镜像及生产 compose 的 backend 配置：

```powershell
docker compose -f docker-compose.yml -f docker-compose.prod.yml run --rm --no-deps backend --spring.main.web-application-type=none --blog.owner.recovery-command=true
```

必须保留交互终端；不要重定向输入或将密码放进命令行、环境变量、脚本。
命令不监听 HTTP。输入已有用户名和 `INITIALIZE`（仅系统无所有者时提升已有 ADMIN），
或 `RECOVER`（只能恢复已有 SUPER_ADMIN），再输入 6–72 字符新密码。
恢复重置封禁/密码/TOTP，吊销旧会话，留下 operator=0 审计；完成后重新绑定 TOTP。
掌握主机与数据库权限是此恢复流程的信任边界，应限制主机登录权限。

SUPER_ADMIN 继承管理员功能；专用操作只允许处置其他普通 ADMIN，要求当前密码、
已开启的第二因子及完整目标用户名确认。支持冻结/解冻、降级、退出会话、随机化密码
后由目标通过邮箱找回、修复邮箱、重置 TOTP。不输出临时密码。
不能自我处置、互改 SUPER_ADMIN；TRANSFER 为独立动作，将活跃 ADMIN 提升为所有者，
原所有者降为 ADMIN，双方旧会话失效。操作锁防止并发处置造成所有者丢失。
`owner_operation_audit` 只记录操作者、目标、动作、结果和时间，不记录口令、验证码或密钥。

## 可选的本机受控重启

默认关闭；普通管理员能看到不可用原因，只有 SUPER_ADMIN 能提交。
独立 Python worker 没有网络端口，仅能执行固定的 `docker restart --time 30 blog-backend`，
Web 应用不挂载 Docker socket。主机须具备 Python、Docker CLI 及既有部署权限。

1. 创建 `blog/.operations`，限制访问为部署账号和容器 uid 10001，并允许二者读写。
2. 在独立前台终端启动 worker（实际锁路径必须与 CD/备份一致）：

   ```powershell
   python deploy/application-restart-worker.py --directory E:\dev\zer0drv\blog\.operations --lock E:\dev\zer0drv\.deployment.lock
   ```

3. 维护窗口用 `docker-compose.yml`、`docker-compose.prod.yml` 和
   `docker-compose.operations.yml` 三个 overlay 启动 backend；后续发布始终保留第三个文件。
   未接入 overlay 的既有 CD 仍会关闭此可选能力，应先调整部署命令再启用。
4. 管理端先保存配置；输入密码、二次验证码与 `RESTART_BACKEND` 后提交。
   配置未保存、worker 心跳失效、其他发布/备份持锁时拒绝执行。

请求 UUID 对应 `.operations/<uuid>.json` 持久结果和数据库 RESTART/ACCEPTED 审计。
worker 先记录 started，再执行一次；中断后不自动重复重启。成功需后端健康、
`nginx -t`、网关 reload 和首页/入口脚本/样式/release.json/配置/文章列表/文章 HTML
全部通过。前端还检查实例 ID 已变化、保存值已生效且不再要求重启。
断连、超时、HTTP 恢复本身均不表示成功；失败查看本地报告和容器日志，人工验证后处理。

worker 崩溃会保留 `worker.lock`。确认原进程已退出、没有发布/备份/重启在执行后，
仅清理该任务的陈旧锁；存在 `request/started` 时先核查实际容器状态，禁止盲目删除并重试。
保留结果文件用于追溯。worker 不注册开机任务，本次也不启用已顺延的定时备份。

## 通知、附件与网关验收

评论通知不再保存/推送/邮件发送原文摘要；已删、管理员移除、文章不可见时读取统一占位，
清除跳转目标。管理员可按评论查询并撤回单条通知，撤回幂等且留审计；已发送外部邮件无法召回。
已打开通知页在刷新、实时事件或周期重读后更新，不对浏览器已经显示的历史内容作追溯抹除承诺。
附件清理预览只接受受管理的图片键，校验状态、类型和 10 MB 上限；凭管理员 Cookie
读取原字节，no-store/nosniff，下载不改变观察期。按 2026-10-23 所有者确认，
本项保持图片范围：界面展示扩展名类型；HTML、SVG、PDF、压缩包等未管理文件
不提供预览或下载，不扩展上传、扫描或删除规则。

冷备恢复服务后还验证统一网关，失败保留上次成功记录。隔离 IP 变更回归命令：

```powershell
python deploy/gateway-recovery-drill.py
```

演练只创建随机测试网络与容器，不停止生产容器；报告位于 target 下。
报告同时包含 worker 实际单次重启、容器启动时间变化及恢复后的网关检查。
测试将固定生产容器名映射到本轮隔离容器，并使用 Docker 健康检查；
它验证 worker 的执行和结果持久化，不替代生产后端配置生效或主机进程安装验收。
生产业务数据恢复、历史版本回滚、所有者现场验收仍按 #58 的剩余验收清单执行。
