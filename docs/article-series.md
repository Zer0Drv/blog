# 文章系列（#94）

系列是文章域内的有序集合，一篇文章可属于多个系列。V21 增加 `article_series` 与 `article_series_item`，不会修改既有文章、文章链接或分类。

## 归属与保存

- AUTHOR 管理自己的系列；ADMIN/SUPER_ADMIN 可管理全部系列。创建者即所属作者，归属不可转移；管理员编辑他人的系列时仍只能收录该作者的未删除文章。第一版不支持跨作者策展。
- 标题最长 200、简介 `description` 最长 2000、封面 URL 最长 512、slug 最长 200；slug 为小写字母/数字/单连字符，`manage`、`by-article` 保留。slug 必填且系列内全局唯一，与文章 slug 命名空间独立。
- `DRAFT` 和 `PUBLISHED` 为系列状态；已公开的空系列有效，目录显示 0 篇。草稿、私密、口令保护、下架、未到发布时间的文章可以编排，但不会通过系列公开。
- POST 创建时 `revision` 可省略或为 0；PUT 和 DELETE 必须携带当前 revision。PUT 同时替换元数据与完整有序 `articleIds`（最多 200、不得重复）。系列行锁和 revision 校验保证并发旧表单失败，更新和关系替换在同一事务完成；40900 提示重新加载，客户端保留未保存输入。
- 删除系列为物理删除，仅删除集合及成员关系，不删除文章，无系列回收站，删除后 slug 可重用。文章回收站/状态变更保留关系，公共查询立即过滤；文章彻底删除同事务清理成员关系。
- 系列封面保存经过附件清理互斥保护；附件引用扫描保留所有尚存在系列的封面（含草稿）。系列删除或移除封面后不再因该系列保留附件。

## HTTP 契约

响应沿用 `Result` envelope，业务错误通过 `code` 表达（HTTP 200）。`40085` 系列不存在/未公开；`40086` slug 冲突；`40023` 参数错误或选择非所属作者/已删除文章；`40301` 非所属作者且非管理员；`40900` revision 冲突。安全链的匿名/角色拒绝为 HTTP 401/403。

| 方法与路径 | 行为 |
| --- | --- |
| GET `/series?page=1&size=20` | 公开系列 `PageResult`：id/slug/title/description/cover/ownerId/ownerName/articleCount/updateTime |
| GET `/series/{slug}` | 上述信息与有序公开 `articles`：id/slug/title/publishTime |
| GET `/series/by-article/{id}` | 公开系列成员信息：id/slug/title/articleCount/previous/next；源文章不公开或不存在返回空数组 |
| GET `/series/manage?page=1&size=20&all=false` | 自己的系列，另含 status/revision；all=true 仅管理员可用 |
| GET `/series/manage/{id}` | 管理详情，有序 articles 另含 status/visibility/visible；articleCount 仍为公开篇数 |
| GET `/series/manage/candidates?seriesId=&keyword=&page=1&size=20` | 所属作者未删除文章候选，seriesId 省略时取当前用户；keyword 为字面量标题匹配 |
| POST `/series/manage` | 创建并返回管理详情 |
| PUT `/series/manage/{id}` | 原子更新并返回管理详情 |
| DELETE `/series/manage/{id}?revision=0` | 删除集合 |

保存体为 `{title,description,cover,slug,status,articleIds,revision}`。分页 size 为 1–100，page 为 1–1000000，候选关键词最长 200。管理 articles 数组的 ID 顺序就是保存的 articleIds，不公开数据库 position。

公开文章目录、数量和上下篇均实时复用 `ArticleVisibility.SQL`：PUBLIC、PUBLISHED、已到发布时间、未删除。过滤在计算相邻关系之前完成，不暴露隐藏章节的标题、位置或数量；公共响应不包含 revision、状态或受限文章占位。单个公开响应在只读事务内使用同一数据库快照，不缓存计数。站点地图经 `article.api.SeriesCatalog` 加入最多 1000 个 PUBLISHED 系列，空系列仍收录。

## 验证与发布

`SeriesIntegrationTests` 覆盖 H2 HTTP 权限、参数边界、排序/移除、冲突回滚、公开过滤、复用成员、字面量候选、物理删除级联和 sitemap。`SeriesRealIT` 覆盖真实 MySQL 的并发保存和附件保护。完整验证沿用 `mvn verify`，真实依赖按 `docs/real-dependency-tests.md` 执行。

先部署含 V21 的后端，再部署系列前端。V21 为新增表，旧后端回滚可忽略这些表，但旧版本不识别系列封面引用；存在系列封面时回滚后不得运行旧附件清理器。保留表数据，禁止通过手工 DROP 回滚已保存内容。
