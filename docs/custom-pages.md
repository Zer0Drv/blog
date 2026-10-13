# 自定义页面（#18）

采用独立 `custom_page` 表，与文章 slug、状态、审核、社交、统计隔离。复用前端 Markdown/富文本编辑器和 DOMPurify 预览组件；富文本保存经后端 jsoup 白名单清洗。页面只保存当前内容，版本历史仍属于文章功能。

所有 `/admin/pages` 路径仅 ADMIN 可操作，AUTHOR 无页面管理权限。slug 必填、最多 200 字符，仅小写字母/数字与段间单连字符，全局唯一范围是页面表；页面与文章可使用相同 slug，路由分别为 `/pages/{slug}` 与 `/article/{slug}`。逻辑删除保留 slug，避免旧链接被其他页面重新占用。

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/admin/pages?page=1&size=20` | 分页列表，不下发正文；size 1–500 |
| GET | `/admin/pages/{id}` | 管理详情，含草稿/下架内容 |
| POST | `/admin/pages` | 新建页面，返回 id |
| PUT | `/admin/pages/{id}` | 保存内容与状态，返回 id |
| DELETE | `/admin/pages/{id}` | 逻辑删除 |
| GET | `/pages/{slug}` | 公开详情，仅 PUBLISHED 且未删除 |
| GET | `/site/page-navigation` | 按站点配置顺序返回已发布页面的标题/slug 投影 |

保存请求：`{slug, title, content, editorType, status}`。`editorType` 为 MARKDOWN/RICHTEXT；`status` 为 DRAFT/PUBLISHED/OFFLINE。未知 slug、未发布页面与已删除页面均返回业务码 40079，冲突 slug 返回 40080，非法参数返回 40023。

站点配置新增 `site.page_navigation`，值为至多 20 个唯一 slug 的 JSON 数组，例如 `["about","contact"]`，默认 `[]`。管理端可选择并上移/下移排序。该原始配置不进入公开配置白名单：公众入口在读取时与已发布页面求交集，隐藏草稿、下架、删除与不存在的 slug。修改页面 slug 后须在站点设置重新选择导航入口。配置格式在任何站点写操作前校验，非法导航不导致其他配置部分写入。

公开详情、导航与 sitemap 共用页面发布查询谓词，页面不进入文章 RSS/Atom、搜索、Feed 或评论互动。sitemap 最多追加 1000 个已发布页面，使用 `/pages/{slug}` 与更新时间。

上线前应用 Flyway V13；存量文章数据不变。页面编辑有未保存修改的离开确认，保存成功后清除修改标记；管理端编辑模式对已有页面锁定，避免把 Markdown 直接当 HTML 保存。
