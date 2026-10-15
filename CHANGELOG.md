# Changelog

本仓库的显著变更记录。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 附件孤儿清理（#22）：本地/MinIO 扫描、文章/历史版本/草稿/回收站引用保护、7 天观察期、恢复保留、跨实例引用互斥、持久化删除重试与确认回收字节；普通删除改为先移出附件库

- 系统参数配置中心（#23）：独立配置/审计表、20 项参数白名单、类型与范围校验、缓存和跨实例刷新、重置与配置优先级；接入验证码、认证、上传、搜索消费者，存储切换后按附件原后端清理
- 新文章订阅（#20）：站内订阅、邮箱确认与匿名退订、持久推送队列与定时文章支持、失败重试/邮件频率限制、管理员推送统计；发送前复核公开可见性

- 自定义页面（#18）：独立页面表与管理员 CRUD、Markdown/富文本内容、发布/下架、slug 唯一校验；站点导航配置与公开读取按发布状态过滤，sitemap 收录已发布页面

- 密码保护 / 私密文章（#15）：`article` 表新增 `visibility`（PUBLIC/PASSWORD/PRIVATE）与 `access_password_hash` 列（V12，BCrypt 哈希）；公众可见性谓词 `ArticleVisibility` 收紧为仅 PUBLIC 出公众出口（列表/Feed/归档/搜索/RSS/sitemap 口径一致，标题零泄露）；PASSWORD 文章详情（id/slug 双路由）出「需口令」极简视图，新增公开端点 `POST /articles/{id}/password` 校验口令（CaptchaGuard 双维度限流防爆破）并签发 2h 访问令牌（Redis），详情经 `X-Article-Access-Token` 头放行；PRIVATE 对公众与「不存在」同语义；编辑器保存支持可见性与口令设置（口令留空保持现状，切出 PASSWORD 自动清哈希）
- 文章 slug 固定链接（#14）：article 表新增 slug 列（V11；唯一索引含回收站占位，存量回填 `post-{id}`）；发布/保存校验格式与唯一性，未指定按标题清洗自动生成（纯中文标题 `post-{id}` 兜底，冲突追加序号）；新增公开详情 `GET /articles/slug/{slug}`（旧 `/{id}` 继续可用）；RSS/sitemap 文章链接改用 slug；列表/详情/归档 VO 带出 slug
- 友情链接：`friend_link` 表 + 公众端只读列表（仅启用、排序升序）+ 管理端 CRUD/启停；新增 `site.friend_links_enabled` 配置键联动站点设置展示位（#17）
- 文章 Markdown 导入导出增强（#16）：导出 front matter 扩展为标题/slug/发布时间/更新时间/状态/标签/分类/摘要/封面；新增批量导出 zip（`GET /articles/export?ids=`，作者仅本人、管理员全部）；导入支持解析 front matter 自动填充元数据（slug 透传创建链路校验、标签按名匹配缺失自动创建、分类按名匹配不自动创建、date 回填 publish_time、status 忽略一律草稿），新增 zip 批量导入（逐条容错返回失败明细，防 Zip Slip 与解压炸弹），外链图片保留并随响应返回待转存清单
- CD 回滚能力：`workflow_dispatch` 填 `rollback_tag` 直部 GHCR 历史镜像，image 构建自动跳过（#43）
- 开发流程建设：CONTRIBUTING 规范、Issue/PR 模板、main 分支保护（必过 CI、禁直推/强推）、Dependabot 周更 + 安全扫描、优先级标签与 Projects 看板（#37、#38）

### Fixed

- WebSocket 已建立连接随 token 到期、登出及用户级吊销失效；发送与心跳前复检共享黑名单，同步吊销关闭对应连接，补充真实 WS 与并发回归（#56）
- 文章完整保存支持清空分类（`categoryId` 为 null 或省略），恢复未分类历史版本时正确写入 NULL；保留其他局部更新的字段策略（#57）
- minio 8.6.0 编译失败：okhttp 5 KMP 根模块空壳 jar，显式声明 okhttp-jvm（#39）
- minio 9.0.3 API 变更适配：`PutObjectArgs.stream` partSize 改 Long（#90）
- 消除测试代码两处 javac varargs 警告（#42）

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

[Unreleased]: https://github.com/Zer0Drv/blog/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Zer0Drv/blog/releases/tag/v1.0.0
