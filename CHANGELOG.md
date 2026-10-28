# Changelog

本仓库的显著变更记录。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 搜索支持分类、标签、含结束日的发布时间范围、相关度/时间排序及稳定分页；返回纯文本命中摘要和实际搜索模式，短词/特殊字符使用字面量 LIKE，统一限制公开可见性（Refs #95）。

- 增加 V20 精确前后端组合的生产预览浏览器回归与不可变镜像证书，验证原始成功 CD、推送摘要和测试源码身份；生产回滚与备份策略继续单独验收（Refs #58）。

- 补齐真实 MySQL 所有权转移、通知删除/投递/撤回竞态，以及独立 Docker worker 单次重启验收；明确拒绝非受管理图片的预览与下载（Refs #81、#82、#83、Zer0Drv/blog-ui#78）。

- 增加短期 access 与轮换 refresh 会话、重放吊销、SUPER_ADMIN 受控处置和无 HTTP 的主机恢复命令（Refs #26、#83）。
- 增加可选本机运维 worker，仅允许后端重启并验证实例、配置与统一网关恢复（Refs #82）。
- 增加清理对象的鉴权预览/下载、评论通知动态占位及单条撤回审计；修复友情链接开关缓存和冷备后网关 DNS 恢复（Refs #80、#81、Zer0Drv/blog-ui#77、Zer0Drv/blog-ui#78）。

- 新增本机隔离冷备份、校验、健康恢复与任务安装脚本，按 RPO 24 小时/RTO 2 小时记录现场目标；异地存储和外部通知尚未配置，首次现场恢复验收仍待执行（Refs #58）。
- 发布回滚核对原始 CI、镜像摘要、前后端兼容矩阵和数据库版本；发布与备份共享现场互斥锁（Refs #58）。

- 文章深链接的原始 HTML 提供公开标题、摘要、Open Graph 与 canonical；复用既有公开可见性，受限内容使用通用 noindex，网关与响应禁止缓存（Refs #58，见 `docs/article-sharing.md`）。

- 新增 TOTP 绑定、密码/GitHub 登录第二因子、一次性恢复码及会话吊销；V17、独立环境加密密钥和部署回滚边界见 `docs/totp.md`（Refs #19）。

- 新增合成数据的隔离恢复演练：真实 MySQL dump/reload、本地与 MinIO 附件完整性校验，删除源数据后恢复并通过真实应用 HTTP 验收；复用 real-it CI 保存证据，现场策略与 RPO/RTO 仍待确认（Refs [#58](https://github.com/Zer0Drv/blog/issues/58)）。
- 新增仅位于测试源码的浏览器 E2E 后端启动器，复用真实服务和基础设施保护，固定隔离端口并生成测试账号/文章；没有新增后端依赖、生产端点或鉴权绕过（Refs [#58](https://github.com/Zer0Drv/blog/issues/58)）。

### Changed

- 按所有者确认，将本机所有者初始化/恢复密码长度调整为 6–64 字符，同步提示与边界回归（Refs #83）。
- 本机显式创建 `.operations/enabled` 后，后端正常发布和回滚保留独立运维 worker 挂载，避免下一次部署关闭受控重启入口（Refs #82）。

- 修复文章 HTML 网关代理的 Host，避免上游名称中的下划线触发 HTTP 400；旧后端缺少文章 HTML 路由时回退 SPA。

- 补齐 README 的 V13–V16 迁移清单与 v1.1.0 交付范围，区分顺延需求、质量验证和业务数据恢复边界（Refs [#58](https://github.com/Zer0Drv/blog/issues/58)）。

## [1.1.0] - 2026-10-16

### Added

- 文章 slug 固定链接：保留 ID 路由，新增 slug 访问、唯一校验及 RSS/sitemap 固定链接（[#14](https://github.com/Zer0Drv/blog/issues/14)，[PR #52](https://github.com/Zer0Drv/blog/pull/52)）。
- 密码保护与私密文章：统一公众可见性、口令限流和短期访问令牌，保护列表、搜索、Feed 与详情出口（[#15](https://github.com/Zer0Drv/blog/issues/15)，[PR #54](https://github.com/Zer0Drv/blog/pull/54)）。
- Markdown 导入导出增强：front matter 元数据、批量 zip、逐条失败明细及外链图片转存提示（[#16](https://github.com/Zer0Drv/blog/issues/16)，[PR #53](https://github.com/Zer0Drv/blog/pull/53)）。
- 友情链接、自定义页面与导航管理；已发布自定义页面可公开访问并收录 sitemap（[#17](https://github.com/Zer0Drv/blog/issues/17)、[#18](https://github.com/Zer0Drv/blog/issues/18)，[PR #51](https://github.com/Zer0Drv/blog/pull/51)、[PR #55](https://github.com/Zer0Drv/blog/pull/55)）。
- 新文章订阅：站内订阅、邮箱确认、公开退订、持久推送、失败重试、邮件额度与管理统计（[#20](https://github.com/Zer0Drv/blog/issues/20)，[PR #59](https://github.com/Zer0Drv/blog/pull/59)）。
- 系统参数配置中心：20 项参数白名单、校验、重置、缓存与跨实例刷新、变更审计及消费侧接入（[#23](https://github.com/Zer0Drv/blog/issues/23)，[PR #64](https://github.com/Zer0Drv/blog/pull/64)）。
- 附件孤儿清理：本地/MinIO 扫描、完整引用保护、7 天观察期、恢复保留、物理删除重试与回收统计；普通删除先移出附件库（[#22](https://github.com/Zer0Drv/blog/issues/22)，[PR #65](https://github.com/Zer0Drv/blog/pull/65)）。
- 隔离 MySQL/Redis/MinIO 的真实依赖回归，覆盖 HTTP/Cookie/吊销、验证码 TTL、对象与草稿引用保护、全文搜索、WebSocket，以及 V13/V15 代表性数据升级与幂等检查（[#58](https://github.com/Zer0Drv/blog/issues/58)，[PR #67](https://github.com/Zer0Drv/blog/pull/67)）。

### Changed

- 发布前复用同 SHA 完整 CI；构建、部署前核对镜像身份并保存证据，保留 production 审批。支持通过 `sha-<8位>` 历史镜像回滚（[PR #43](https://github.com/Zer0Drv/blog/pull/43)、[PR #68](https://github.com/Zer0Drv/blog/pull/68)）。
- MinIO 升级至 9.0.3，适配 `PutObjectArgs.stream` 的 Long 参数；同步升级 okhttp-jvm 5.5.0、MapStruct Plus 1.5.3、Hutool Core/Captcha 5.8.48（[PR #44](https://github.com/Zer0Drv/blog/pull/44)–[PR #48](https://github.com/Zer0Drv/blog/pull/48)）。
- 完善架构、发布回滚、Dependabot 与提交规范文档；固化 Issue 阶段/结项评论、六类元数据维护规则与 PR 清单（[#69](https://github.com/Zer0Drv/blog/issues/69)，[PR #70](https://github.com/Zer0Drv/blog/pull/70)、[PR #71](https://github.com/Zer0Drv/blog/pull/71)）。

### Fixed

- WebSocket 已建立连接随 token 到期、登出及用户级吊销失效，补齐发送/心跳复检和并发回归（[#56](https://github.com/Zer0Drv/blog/issues/56)，[PR #63](https://github.com/Zer0Drv/blog/pull/63)）。
- 完整保存支持清空文章分类，恢复未分类历史版本时正确写入 NULL（[#57](https://github.com/Zer0Drv/blog/issues/57)，[PR #62](https://github.com/Zer0Drv/blog/pull/62)）。
- 附件清理显式使用 JDBC 时间读取，兼容 MySQL DATETIME 返回 LocalDateTime，修复到期删除服务器错误（[#66](https://github.com/Zer0Drv/blog/issues/66)，[PR #67](https://github.com/Zer0Drv/blog/pull/67)）。
- 修复部署机 Git Bash 路径失效导致的 CD 故障（[#60](https://github.com/Zer0Drv/blog/issues/60)，[PR #61](https://github.com/Zer0Drv/blog/pull/61)）。

升级顺序、验证依据与尚未交付范围见 [v1.1.0 发布说明](docs/releases/v1.1.0.md)。

## [1.0.0] - 2026-10-12

首个流程化版本锚点：功能完整 + 架构深化 + 开发流程建设完成。

### 功能

- 文章：创作/双编辑器支持、版本历史、自动保存、定时发布、回收站、全文搜索、归档、导入导出
- 互动：评论（审核/敏感词/楼中楼）、点赞、收藏、关注流、私信、通知中心（WS 实时 + 邮件）
- 管理端：用户/评论/文章/统计/站点设置/附件库
- SEO：sitemap、RSS/Atom、OG meta
- 鉴权：HttpOnly Cookie + JWT 黑名单、图形验证码、GitHub OAuth、角色实时读库装配

### 架构深化（#24、#25、#34）

- 文章域：`ArticleVisibility` 可见性谓词单一出处；`article.api` 契约包（ArticleCatalog/ArticleCascade/ArticleNotificationPort 等 7 端口），`IService` CRUD 泄漏收口
- 评论准入：`CommentCreateResult` 判定结论随行（敏感词判定每条一次）；词库缓存；`SensitiveWordChecker` 破 comment→admin 循环
- 通知枢纽：`NotificationIntent` 类型化意图（dedupe 内建）；落库/WS/邮件三通道隔离投递，失败可观测不连坐
- 验证码：`CaptchaGuard` 收编全部端点编排（含登录双维度）
- comment 数据归属：comment_like 写路径与 Mapper 归位；详情计数端口化
- ArchUnit 边界红线 5 条进 CI，豁免清单 2 项只缩不扩

### 流程

- Issue → 审核 → 分支 → CI 门禁 → PR → 审批合并全流程；commit 规范（Conventional Commits 中文摘要）
- CI：build（编译+299 例测试）+ flyway-migration 双 job；CD：GHCR 镜像 + 自托管 runner 部署 + 环境审批

[Unreleased]: https://github.com/Zer0Drv/blog/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/Zer0Drv/blog/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Zer0Drv/blog/tree/v1.0.0
