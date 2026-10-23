# 非 AI issue 本地验收记录（2026-10-23）

## #26 最终鉴权范围（2026-10-23 所有者确认）

所有者确认保留实时撤销检查，并以此替代原任务三的纯 claims/仅短 TTL 方案。
带 `session_version` 的新 token 从 `roles` 声明装配权限；解码阶段仍检查数据库中的
账号状态、会话版本及 Redis 吊销记录。历史 token 没有版本声明时保留读库角色兼容路径。
登录及 refresh 签发读取当前角色和版本；角色变更必须走受控 API 以触发会话撤销。
每请求仍有数据库查询，不将此实现描述为零数据库鉴权。

核对基线为 `04c5b51323fcf63f75f0b9b4b6d9eaf27a960b90`，相关实现位于
`DatabaseRoleJwtAuthenticationConverter`、`BlacklistJwtValidator`、`TokenServiceImpl`
和 `RefreshSessionService`。本次只同步验收范围和注释，不改鉴权行为。
[对应主分支 CI](https://github.com/Zer0Drv/blog/actions/runs/39960310478) 已成功；
部署、所有者初始化与业务验收分别记录，不以文档更新代替现场验收。

## 合并后的补充验收

原实现已合并：后端 [#84](https://github.com/Zer0Drv/blog/pull/84) 为
`0a460f008d0f29d140f5fcfc0c8828f89bbca1ca`，前端
[#81](https://github.com/Zer0Drv/blog-ui/pull/81) 为
`b0eff683513eb6833d7b1a0844d70a3fa8be5836`。
[后端 CD](https://github.com/Zer0Drv/blog/actions/runs/39888516016) 与
[前端 CD](https://github.com/Zer0Drv/blog-ui/actions/runs/39888577872) 均成功。
2026-10-23 19:16（Asia/Shanghai）只读现场核验：两端提交吻合，Flyway V20，
checksum `-1668881442`，无失败迁移；首页、模块 JS/CSS、发布清单、公开配置、
文章 API 与文章 HTML 均为 200。现场未执行配置写入或生产重启。

补充范围位于 `test/non-ai-acceptance`，精确提交的检查以对应 PR CI 为准：

- MySQL/Redis/MinIO 全套 16/16：新增两个不同目标同时争夺所有权，只有一次成功且仍有活跃所有者；
  删除评论与通知投递并发后没有正文快照，双重撤回只有一条审计且未读数正确。
- 后端全量打包 586/586（含 ArchUnit）；附件套件 24/24，覆盖 HTML/SVG/PDF/ZIP
  的预览和下载拒绝，未改变对象或观察期。
- Chromium 原有 7 条与新增友链用例分别通过。新用例使用隔离数据，覆盖保存前、
  保存后另一标签页、刷新、直接 URL、公开 API 与重新开启，最后恢复测试配置。
- 前端最终全量 63 文件、442/442，生产构建通过。重启组件 8 项包括请求单飞、
  卸载忽略旧响应、失败、断线、旧实例、超时、参数未生效、提交后网关 502 保留任务继续核验。
- Python 运维 11/11；Docker 演练实际重启隔离容器一次，启动时间改变，重复处理不再重启，
  恢复网关后持久报告为 RECOVERED。容器名和健康目标仅在演练中映射，未触碰生产。
- #78 所有者已确认保持图片范围，界面新增类型和不支持提示；不再把通用附件管理列为本项待办。

首次 SUPER_ADMIN 初始化需所有者在主机交互终端选择已有账号并输入新密码；本轮不替用户指定账号或密码。
可选运维 worker 仍默认关闭，生产重启/配置生效需启用后验收。#58 的实际回滚、最终现场恢复验收
与顺延的定时备份仍保留，未把实验环境结果当作生产 RPO/RTO 达标。下文为首次实现时的历史记录。

## 首次实现记录（合并前）

本轮基线为 backend `de1a03295f617ef98337f202ee5cde9e412ee8b9`、
frontend `438f8df0e8c849754b1bdcf6531afef5b811cb6f`，实现位于两仓 `fix/non-ai-issues`。
以下为提交前工作树验证；精确提交的远端 CI 以 PR 检查为准，不能将本地结果当作已发布。

| 验证 | 结果 |
| --- | --- |
| 后端 `mvn -B package` | 582/582，通过 ArchUnit 原有边界；含无 HTTP 恢复上下文、会话轮换/重放/退出、通知隐私、附件权限、所有者与重启请求幂等 |
| 前端 `pnpm test --maxWorkers=2` | 63 文件、436/436 |
| 前端 `pnpm build` | 通过；既有大 chunk 提示保留 |
| `mvn -B -Preal-it verify` | 14/14，真实 MySQL/Redis/MinIO，V20 升级、并发 refresh 重放、设备退出隔离、备份恢复 |
| `pnpm test:e2e` | Chromium 7/7，真实接口与 Cookie，包括 TOTP、所有者重新验证、冻结/解冻及旧凭据拒绝 |
| Python 运维测试 | 11/11，含锁冲突、网关失败与中断不重试 |
| Node 发布/回滚认证测试 | 3/3；这是脚本验证，不是生产回滚 |
| Docker 网关 IP 变化演练 | 原 IP `172.23.0.2` → `172.23.0.4`，复现失败；reload 后首页、入口脚本、release.json、站点配置、文章列表、文章 HTML 全部恢复 |

原始本地日志分别为两仓 `non-ai-*.log`、前端 `e2e-artifacts/` 与后端
`target/blog-gateway-it-*/report.json`；均为忽略文件，不提交业务数据或凭据。
新增网关演练已纳入 CI real-it job，并保留脱敏 JSON 证据。

## 交付对应

- blog-ui #77：保存后共享配置更新、跨标签页失效、刷新重查、直接访问与后端公开 API 一致。
- blog-ui #78：头像放大、清理图片鉴权原文件预览/下载、空/失效/已删除占位。
  当前清理器只收集受管理的图片键，没有扩展任意非图片扫描或 HTML/SVG 执行。
- blog #80：恢复流程刷新网关解析并做完整冒烟；失败不覆盖上一成功记录。
- blog #81：通知不存评论快照、动态状态占位、单条撤回与审计，已打开列表重读收敛。
- blog #26：短 access、轮换 refresh、重复使用撤销、跨实例撤销广播；新 token 角色声明
  仍配合每次请求的持久会话版本/账号状态检查，避免仅依靠 TTL 留出误放行窗口。
- blog #83：按所有者确认的“仅处置普通管理员、禁止互改超级管理员、独立转移”范围实现。
- blog #82：按确认的本机 Docker worker 范围实现，默认未启用；真实生产重启不在本轮执行。

## 发布与保留项

需所有者审核两仓 PR 后，先后端 V18–V20 再前端；初始化所有者、启用可选 worker、
现场恢复与故障操作见 [运维说明](owner-and-local-operations.md)。现有 ADMIN 不自动升级。
本轮未修改生产账号、未重启生产容器、未安装计划任务。#19 已有 TOTP 实现通过本轮回归，
不重复开发；#58 中定时备份沿用所有者暂不启用的决定，实际生产回滚与最终现场验收仍待完成。
因此这些 issue 不因本地通过而自动关闭，PR 使用 Refs 保留所有者验收。
