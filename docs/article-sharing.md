# 文章原始 HTML 与分享 metadata（#58）

采用后端装配当前 SPA 入口的方式：Nginx 将 `/article/{id|slug}` 交给后端，
后端读取运维配置 `blog.seo.frontend-index-url` 对应的当前 `index.html`，
保留应用根节点、脚本和样式，只替换标题、description、Open Graph 和 canonical。
无需 SSR 框架或新增依赖，不执行浏览器 JavaScript 即可读取分享信息。

生产 Compose 配置入口为 `http://minio:9000/blog-frontend/index.html`。
本地独立运行后端若要测试 HTML，需明确配置同一属性指向开发或 preview 服务的 `/index.html`。
入口 URL 不取自请求，禁止重定向，限制连接/读取等待时间和 1 MiB 大小；入口不可用时返回
503、Retry-After 和 no-store，避免拿旧页面或错误文档冒充当前发布。

文章投影经 `ArticleCatalog` 转发既有 `ArticleVisibility`。仅 PUBLIC、已发布且到点、未删除的
文章进入 metadata；PRIVATE、PASSWORD（包括已解锁用户）、草稿、下架、定时中、已删除及不存在
均返回通用站点 head、noindex、无文章 canonical，SPA 仍可正常处理登录/口令页面。
网关清除该 HTML 请求的 Cookie 和 Authorization，并禁用缓存。任何请求身份都不会扩大分享可见性。

canonical 只使用站点配置 `site.base_url` 的 HTTP(S) origin 和规范 slug（缺失时用 id），
不信任请求 Host；配置不合法时返回通用 noindex。标题与摘要取纯文本并通过 DOM 属性编码，
不渲染文章正文。站点配置需填写公开正式域名；默认 localhost 仅适合本机验证。

前端 SPA 导航后额外匿名读取同一 HTML，复制允许的 head 字段，不执行或插入脚本。
这样 canonical 和隐私规则只有一处实现。代价是每次文章导航增加一次入口 HTML 请求。
切换路由立即移除上篇 metadata，并取消/忽略旧请求；网络故障及旧后端返回错误时保留通用 head。
静态资源和编辑器渲染行为不变。受限正文仍由原有 API 权限/口令流程保护。

发布顺序：先部署后端镜像和新增入口环境变量，再更新并验证 Nginx 配置，最后部署前端。
旧前端可使用新 HTML，原有 `/api/articles` 契约不变；新前端在旧后端上退化为通用 metadata。
旧后端没有 HTML 路由时，网关将 401/404 回退为静态 SPA；文章仍可阅读，但分享 metadata 退化为站点通用信息。
数据 schema 无变更。尚未执行生产网关切换与外部分享平台缓存刷新。

自动验收包括原始 HTML 的 id/slug canonical、恶意标题/摘要、Host 注入、所有非公开状态，
以及入口失败/重定向/过大响应；前端覆盖公开→受限切换、旧响应竞态、网络故障和禁止脚本复制。
