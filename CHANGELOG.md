# Changelog

本仓库的显著变更记录。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 文章 slug 固定链接（#14）：article 表新增 slug 列（V11；唯一索引含回收站占位，存量回填 `post-{id}`）；发布/保存校验格式与唯一性，未指定按标题清洗自动生成（纯中文标题 `post-{id}` 兜底，冲突追加序号）；新增公开详情 `GET /articles/slug/{slug}`（旧 `/{id}` 继续可用）；RSS/sitemap 文章链接改用 slug；列表/详情/归档 VO 带出 slug
- CD 回滚能力：`workflow_dispatch` 填 `rollback_tag` 直部 GHCR 历史镜像，image 构建自动跳过（#43）
- 开发流程建设：CONTRIBUTING 规范、Issue/PR 模板、main 分支保护（必过 CI、禁直推/强推）、Dependabot 周更 + 安全扫描、优先级标签与 Projects 看板（#37、#38）

### Fixed

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
