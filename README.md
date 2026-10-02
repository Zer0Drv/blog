# blog — 动态博客后端

[![CI](https://github.com/Zer0Drv/blog/actions/workflows/ci.yml/badge.svg)](https://github.com/Zer0Drv/blog/actions/workflows/ci.yml)

基于 Spring Boot 4 + MyBatis-Plus 的个人博客后端：内容管理、评论互动、关注/私信/通知社交体系、SEO 三件套与管理后台，单体应用、按业务模块分包（Spring Modulith 边界）。前端仓库：[Zer0Drv/blog-ui](https://github.com/Zer0Drv/blog-ui)。需求基线见 [`docs/requirements-v1.md`](docs/requirements-v1.md)。

## 功能特性

### 内容
- 文章 CRUD：Markdown / 富文本双编辑器模式，草稿 / 发布 / 下架状态流转，置顶与推荐位
- **版本历史**：每次保存自动快照（版本号递增），可查看任意版本并一键恢复（恢复也留痕）
- **自动保存**：草稿自动保存到 Redis（TTL 2h），正式保存后自动清除
- **定时发布**：`publishTime` 可选，公众可见性统一为 `status=PUBLISHED AND publish_time<=now`
- **回收站**：文章/评论删除仅逻辑删，可恢复（回草稿）或物理删除（级联）
- **全文搜索**：`content_text` 纯文本冗余 + ngram FULLTEXT；`blog.search.fulltext-enabled=false` 时降级三路 LIKE
- **归档**：按月分组的文章归档；标签 / 树形分类完整管理
- **存储型 XSS 防线**：富文本（RICHTEXT）内容入库前经 jsoup `Safelist.relaxed()` 白名单清洗；Markdown 源码不清洗（渲染侧由前端 DOMPurify 兜底），配合 CSP 响应头构成纵深防御
- **文章导入导出**：单篇文章导出为 `.md` / `.html` 文件（含 YAML front matter）；从 `.md/.markdown/.txt/.html` 文件导入为草稿（见「文章导入导出」一节）

### 互动
- 评论两层楼中楼：时间正/倒序 + 热度排序、@ 提及、主评论内嵌前 3 条回复
- **审核队列**：站点开关 `comment.review_required` 开启后新评论先落 PENDING，管理端 approve / reject
- 文章点赞 / 收藏、评论点赞、浏览量统计、我的收藏列表
- 敏感词过滤：评论命中则折叠进审核（接口正常返回、message 覆盖提示），私信命中直接拒绝

### 社交
- 关注 / 取关、粉丝与关注列表、用户主页、关注作者的文章 Feed
- 一对一私信：会话列表、消息分页、已读回执
- 通知中心：评论回复 / @提及 / 点赞 / 关注 / 私信五类通知，未读数、单条与全部已读
- **WebSocket 实时推送**：`/ws` 推送私信与通知，握手从 Cookie 读 `AUTH_TOKEN` 鉴权（不再接受 query 参数 token），前端断线自动回退轮询
- 评论邮件通知：异步发送，用户在通知偏好中可关闭；未配 SMTP 走 dev 日志兜底

### 站点与 SEO
- RSS / Atom 订阅（Rome，最近 20 篇可见文章）、sitemap.xml（≤1000 条）、robots.txt
- 站点设置中心：7 个内置配置键，公开接口只出白名单 5 键，管理端读写全量
- 附件库：上传即落库，支持分组、搜索、换组、逻辑删除（不删存储对象）

### 基础设施
- 邮箱验证码注册 / 登录 / 找回密码，HS256 JWT 鉴权，登出黑名单
- **认证 Cookie 化**：登录 / 注册 / OAuth 换码成功统一 `Set-Cookie: AUTH_TOKEN=<jwt>; HttpOnly; Secure; Path=/; Max-Age=<token有效期秒>`（SameSite 默认 Strict）；资源服务器优先读 Cookie，兼容 `Authorization: Bearer`（过渡期）；mutating 请求强制 `X-Requested-With: XMLHttpRequest` 自定义头作 CSRF 纵深
- **token 吊销**：颁发时按 user→jti 集合（Redis Set）记录，封禁 / 解封 / 角色变更 / 改密 / 重置密码即整批拉黑，历史 token 立即 401
- **密钥零硬编码**：`jwt.secret`、数据库 / MinIO 口令全部环境变量注入；未配置或密钥强度不足（<32 字节）启动 fail-fast；初始管理员由启动引导重建/轮换（随机密码打印一次日志或 `ADMIN_INITIAL_PASSWORD` 注入）
- GitHub OAuth 登录（授权码流程由 Spring Security 托管，自动注册 `gh_` 账号；成功后回跳一次性换码 `?code=`，前端调 `/auth/oauth/exchange` 换 Cookie，token 不再经 URL 传递）
- 自适应图形验证码：按场景 + 来源频率计数，超阈值才要求验证码；登录按 IP + 用户名双维度计数（伪造 XFF 不可绕过，XFF 仅在显式开启 `blog.security.trust-proxy-headers` 时才被信任）；邮箱验证码连续失败 5 次作废
- MinIO 对象存储（默认）/ 本地磁盘上传（fallback），bucket 创建由后端启动自检保证
- Flyway 迁移、全局逻辑删除约定、统一响应体与分页结构

## 文章导入导出

### 导出：`GET /articles/{id}/export?format=md|html`

- **可见性与文章详情完全一致**：已发布文章匿名可导出；草稿 / 下架仅作者本人或 ADMIN，其余调用方与「文章不存在」同错误码（`40030`）
- `format` 省略时按文章 `editorType` 默认（MARKDOWN→`md`，RICHTEXT→`html`）；显式跨格式请求不渲染转换，直接给正文源文本
- 响应为文件下载：`Content-Type: text/markdown; charset=UTF-8` 或 `text/html; charset=UTF-8`，`Content-Disposition: attachment; filename*=UTF-8''<URL编码标题>.<ext>`（标题为空时用 `article-<id>`）
- 文件开头带 YAML front matter：`---\ntitle: <标题>\ndate: <创建时间 ISO>\n---\n\n`

### 导入：`POST /articles/import`（`multipart/form-data`，字段名 `file`）

- **权限**：仅 ADMIN / AUTHOR（其余角色 `40300`，匿名 `401`）；作为 mutating 请求需携带 `X-Requested-With: XMLHttpRequest`
- **白名单**：`.md` / `.markdown` / `.txt` → 建 MARKDOWN 草稿；`.html` / `.htm` → 建 RICHTEXT 草稿（入库前走与正文保存相同的 jsoup `Safelist.relaxed()` 清洗）；其他扩展名 → `40023`「仅支持 .md/.markdown/.txt/.html 文件」
- **限制**：非空、≤2MB（超过 → `40023`「文件过大，最大 2MB」），UTF-8 解码
- **标题**：Markdown 取正文首个一级标题（`# 标题`，该行从正文移除）；否则用文件名去扩展名；超长截断至 200 字符
- 一律创建为 **DRAFT 草稿**（作者 = 当前用户），响应结构与创建文章一致（`Result<ArticleDetailVO>`）；属文章操作，**不计入附件上传日配额**

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 25 | |
| Spring Boot | 4.1.1 | 需求文档写的 4.1.4 未发布，按当时最新可用 4.1.x 定型（见 `pom.xml` 注释） |
| Spring Security | 7.x（随 Boot） | STATELESS + `oauth2-resource-server`（JWT，Cookie/Bearer 双通道）+ `oauth2-client`（GitHub 登录） |
| MyBatis-Plus | 3.5.17 | `mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser` |
| MySQL | 8.4 LTS | 主存储，ngram FULLTEXT 支撑全文搜索 |
| Flyway | 随 Boot | MySQL 支持模块为 `flyway-mysql`（`flyway-database-mysql` 不存在，双仓 404 实测） |
| Redis | 7 | 验证码、防刷限流、token 黑名单与用户活跃 jti 集合、OAuth 一次性换码、自动保存草稿 |
| MinIO | 8.5.17（SDK） | 对象存储，镜像用 `alpine/minio`（官方镜像已 EOL 下架） |
| 其他 | — | MapStruct-Plus 1.5.2、Hutool 5.8.47（含 captcha）、jsoup 1.18.3（富文本清洗）、Rome 2.1.0、Spring Modulith 2.1.1、Lombok |

## 快速开始

前置：JDK 25、Maven、Docker。

```bash
cp .env.example .env       # 填写 DB_PASSWORD / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / JWT_SECRET 等
# JWT_SECRET 生成：openssl rand -base64 48
docker compose up -d        # 起 MySQL 8.4（宿主 3307）+ Redis（6379）+ MinIO（9000/9001）
export $(grep -v '^#' .env | xargs)   # 或直接 export 各变量后启动应用
mvn spring-boot:run         # 或 java -jar target/blog-0.0.1-SNAPSHOT.jar
```

- 服务端口 **8082**；默认数据源 `localhost:3307/blog_dev`（账号口令由 `.env` 注入，**仓库不再内置任何默认口令/密钥**）
- **`JWT_SECRET` 必须注入**（≥32 字节强随机值），未配置或强度不足时应用启动直接失败（fail-fast）并打印明确提示
- **初始管理员**：库内无 ADMIN（或 `admin` 仍为历史公开种子口令）时，启动期自动重建/轮换：密码取 `ADMIN_INITIAL_PASSWORD`，未注入则随机生成并**仅打印一次到启动日志**，请首登后立即通过「修改密码」更换
- **邮件 fallback**：未配置 SMTP 时验证码与通知邮件不发送；验证码仅写入 Redis（日志不再打印明文），本地联调用 `redis-cli GET blog:email-code:<scene>:<email>` 读取；接真实邮件取消 `application-dev.yaml` 中 `spring.mail.*` 注释并配置 `MAIL_USERNAME` / `MAIL_PASSWORD` 即可
- **本地上传目录** `./uploads`（`BLOG_UPLOAD_DIR` 可覆盖，`MINIO_ENABLED=false` 时的磁盘 fallback）为运行时目录，已 gitignore 不入库
- **MinIO 首次起容器必做一步**：`alpine/minio` 以 `100:101` 运行，而 Docker 新建命名卷属主是 `root:root` → 容器反复重启并报 `unable to create (/data/.minio.sys/tmp) file access denied`。修法（一次性，2026-10-02 实测新卷同样复现）：
  ```bash
  docker run --rm -v blog_blog-minio-data:/data alpine:3.20 sh -c 'chown -R 100:101 /data'
  docker restart blog-minio
  ```

### GitHub OAuth 接入

1. GitHub → Settings → Developer settings → OAuth Apps → New OAuth App：
   - Homepage URL：`http://localhost:5173`
   - Authorization callback URL：`http://localhost:8082/login/oauth2/code/github`
2. 配置环境变量 `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET`（`application-dev.yaml` 默认占位值 `replace-me`，仅保证无凭据时可启动）
3. 回跳地址可用 `OAUTH_SUCCESS_REDIRECT` / `OAUTH_FAILURE_REDIRECT` 覆盖（默认 `http://localhost:5173/oauth/callback` 与 `/login?error=oauth_failed`）

登录流程：前端直连 `/oauth2/authorization/github` → GitHub 授权 → 回调 `/login/oauth2/code/github` → 按 `github_id` 查库（不存在则自动注册，用户名 `gh_+login`，隐藏邮箱时用 `gh_{id}@oauth.local` 占位，密码留空仅能 OAuth 登录）→ 签发本站 JWT 并暂存一次性 code（Redis，TTL 60s）→ 302 回前端 `/oauth/callback?code=...` → 前端调 `POST /auth/oauth/exchange`（body `{"code":"..."}`）换取 HttpOnly Cookie（响应体仅含 `expiresIn`，不含 token）。

## 配置

### 环境变量

| 分组 | 变量 | 默认值 | 说明 |
|---|---|---|---|
| 数据库 | `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `3307` / `blog_dev` | MySQL 连接 |
| | `DB_USERNAME` | `root` | |
| | `DB_PASSWORD` | **无默认（必填）** | compose 与应用共用；未设置时 compose 报错拒绝启动 |
| Redis | `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| JWT | `JWT_SECRET` | **无默认（必填）** | ≥32 字节强随机值；未配置/强度不足启动 fail-fast |
| MinIO | `MINIO_ENABLED` | `true` | `false` 时上传回退本地磁盘（`/uploads/**`） |
| | `MINIO_ENDPOINT` / `MINIO_PUBLIC_URL` | `http://localhost:9000` | 后者为拼返回 URL 的浏览器可达地址，生产需指向对外地址 |
| | `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | **无默认（必填）** | compose 与应用共用；未设置时 compose 报错拒绝启动 |
| | `MINIO_BUCKET` | `blog-images` | 创建与匿名下载策略由后端启动自检（幂等）保证 |
| 上传 | `BLOG_UPLOAD_DIR` | `./uploads` | 本地上传根目录（运行时目录，已 gitignore） |
| | `UPLOAD_DAILY_LIMIT` | `100` | 每用户每日上传次数上限（Redis 计数） |
| 初始管理员 | `ADMIN_USERNAME` / `ADMIN_INITIAL_PASSWORD` | `admin` / 空 | 后者留空时随机生成并仅打印一次到启动日志 |
| | `ADMIN_BOOTSTRAP_ENABLED` | `true` | 初始管理员引导总开关 |
| OAuth | `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | `replace-me` | GitHub OAuth App 凭据 |
| | `OAUTH_SUCCESS_REDIRECT` / `OAUTH_FAILURE_REDIRECT` | 见上文 | 登录回跳地址 |
| 认证 Cookie | `AUTH_COOKIE_NAME` / `AUTH_COOKIE_SECURE` / `AUTH_COOKIE_SAME_SITE` | `AUTH_TOKEN` / `true` / `Strict` | 前后端跨站点部署时 SameSite 置 `None`（必须 HTTPS）；生产 `SECURE` 必须为 `true` |
| 安全开关 | `TRUST_PROXY_HEADERS` | `false` | 仅部署在可信反向代理后时置 `true`（见下） |
| | `CSRF_HEADER_CHECK_ENABLED` | `true` | mutating 请求强制 `X-Requested-With: XMLHttpRequest` |
| 邮件 | `MAIL_USERNAME` / `MAIL_PASSWORD` | — | 需同时取消 `application-dev.yaml` 中 `spring.mail.*` 注释 |

### 反向代理部署（获取真实客户端 IP）

默认配置下客户端 IP 一律取 `remoteAddr`，伪造 `X-Forwarded-For` **无法**绕过验证码与频率限制。应用部署在可信反向代理（Nginx 等）之后时才需要：

```yaml
server:
  forward-headers-strategy: framework   # 由 Spring 统一改写 remoteAddr/X-Forwarded-*
blog:
  security:
    trust-proxy-headers: true           # 或环境变量 TRUST_PROXY_HEADERS=true
```

同时确保反代覆写（而非透传）客户端发来的 `X-Forwarded-For`。即使不开该开关，登录也有按用户名维度的独立失败计数兜底。

### 业务开关（`application.yaml`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `blog.captcha.code-ttl-seconds` | 300 | 图形验证码 Redis TTL，一次性，校验即删 |
| `blog.captcha.scenes.*` | login 5、register 3、email-code 5、password-reset 3、comment 10 次 / 600s | 各场景触发阈值与计数窗口（login 按 IP + 用户名双维度计数） |
| `blog.search.fulltext-enabled` | `true` | `false` 时全文搜索降级 title/summary/content_text 三路 LIKE（H2 测试走兜底） |
| `blog.upload-daily-limit` | `100` | 每用户每日上传次数上限 |
| `blog.security.trust-proxy-headers` | `false` | 是否信任 X-Forwarded-For（见上节） |
| `blog.security.csrf-header-check-enabled` | `true` | CSRF 头检查开关（测试环境关闭，由专门测试类开启验证） |
| `blog.auth.cookie.*` | 见环境变量表 | 认证 Cookie 名称 / Secure / SameSite |
| `blog.admin.bootstrap-enabled` 等 | 见环境变量表 | 初始管理员引导 |
| `jwt.secret` / `jwt.expiration-ms` | 无默认（必填）/ 3600000 | **必须经环境变量注入强随机值，仓库不落任何明文密钥** |

### 安全响应头

统一由 SecurityConfig 下发：CSP `default-src 'self'; img-src 'self' data: http: https:; style-src 'self' 'unsafe-inline'`（img-src 放行图床/MinIO 独立域名，可按部署收紧）、`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`、HSTS（仅 HTTPS 请求生效）。

### 密码策略

注册 / 改密 / 重置统一：8~64 位且须同时包含字母和数字（`RegisterDTO.PASSWORD_PATTERN`）。历史弱口令账号不受影响（登录 DTO 不校验强度），建议改密后自然升级。

## 项目结构

按业务模块分包（15 个模块、22 个 Controller）：

```
src/main/java/com/zer0drv/blog/
├── BlogApplication.java
├── auth/          认证：注册/登录/登出/找回密码、邮箱验证码、Token 黑名单与用户 jti 集合、认证 Cookie、OAuth 换码
├── user/          用户领域：User 实体、SecurityUser、个人资料与通知偏好
├── article/       文章：CRUD、版本历史、自动保存、定时发布、回收站、搜索、归档、富文本 XSS 清洗
├── tag/           标签 CRUD
├── category/      树形分类 CRUD
├── comment/       评论：两层楼中楼、排序、审核联动
├── interaction/   点赞/收藏（文章+评论）、我的收藏
├── social/        关注/Feed、私信、通知中心、WebSocket 推送、邮件通知
├── admin/         管理后台：用户/文章/评论/敏感词/统计/站点配置/附件
├── site/          站点设置中心（site_config，内存缓存读）
├── seo/           RSS/Atom/sitemap/robots
├── attachment/    附件库：上传落库、分组管理
├── upload/        图片上传（MinIO / 本地磁盘，每用户日配额）
├── common/        Result/PageResult、全局异常、自适应验证码、JwtSubjects/IpUtils
└── config/        Security / Jwt / MybatisPlus / WebMvc / WebSocket / MinIO / Async / Cookie解析 / CSRF头检查 / 代理信任 / 管理员引导
```

## API 概览

统一响应体 `Result<T>{code, data, message}`（`code == "200"` 成功）；分页统一 `PageResult<T>{records, total, page, size}`；登录/注册返回 `data.access_token`（过渡期保留）并同时 `Set-Cookie: AUTH_TOKEN`（HttpOnly）。`/admin/**` 由 SecurityConfig 统一 `hasRole("ADMIN")` 保护。**所有 mutating 请求（POST/PUT/DELETE/PATCH，认证入口除外）必须携带 `X-Requested-With: XMLHttpRequest` 头，缺失一律 403。**

### 认证与用户

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/email-code` | 发送注册邮箱验证码（同邮箱 60s 限一次） | 公开 |
| POST | `/auth/register` | 邮箱验证码注册（成功即登录：返 token + Set-Cookie） | 公开 |
| POST | `/auth/login` | 账号密码登录（返 token + Set-Cookie；IP+用户名双维度失败计数） | 公开 |
| POST | `/auth/logout` | 登出（token 加黑名单 + 清除 Cookie） | 登录 |
| GET | `/auth/me` | 当前登录用户信息（前端用它判断登录态） | 登录 |
| PUT | `/auth/password` | 修改密码（成功吊销全部历史 token） | 登录 |
| POST | `/auth/password-reset-code` | 找回密码：发验证码（无论是否注册都返回「若该邮箱已注册，验证码已发送」） | 公开 |
| POST | `/auth/password-reset` | 找回密码：验证码 + 新密码重置（成功吊销全部历史 token） | 公开 |
| POST | `/auth/oauth/exchange` | OAuth 一次性换码：`{"code":"..."}` → Set-Cookie，body 仅 `expiresIn` | 公开 |
| GET | `/auth/captcha?scene=` | 获取图形验证码 | 公开 |
| GET | `/auth/captcha/required?scene=` | 预检当前来源是否需验证码 | 公开 |
| PUT | `/users/me` | 编辑个人资料（nickname/avatar/bio） | 登录 |
| GET/PUT | `/users/me/preferences` | 通知偏好（`emailNotifyEnabled`） | 登录 |
| GET | `/actuator/health` | 健康检查 | 公开 |

### 内容

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/articles` | 已发布文章分页（置顶优先；keyword/tagId/categoryId 过滤） | 公开 |
| GET | `/articles/search` | 全文搜索（ngram FULLTEXT / LIKE 兜底） | 公开 |
| GET | `/articles/archives` | 按月归档 | 公开 |
| GET | `/articles/{id}` | 详情（匿名仅可见已发布） | 公开 |
| GET | `/articles/mine` | 本人文章（支持伪状态 TRASH） | ADMIN/AUTHOR |
| POST/PUT | `/articles` `/articles/{id}` | 新建 / 编辑（保存自动快照版本；支持定时发布；富文本入库前 XSS 清洗） | ADMIN/AUTHOR |
| DELETE | `/articles/{id}` | 删除（逻辑删，进回收站） | 本人/ADMIN |
| PUT | `/articles/{id}/status` | 上架 / 下架 / 回草稿 | ADMIN/AUTHOR |
| GET | `/articles/{id}/versions` `/versions/{version}` | 版本列表 / 版本详情 | 本人/ADMIN |
| POST | `/articles/{id}/restore/{version}` | 恢复到指定版本（留痕，内容过 XSS 清洗） | 本人/ADMIN |
| PUT/GET | `/articles/{id}/autosave` | 自动保存草稿写入 / 读取（Redis，TTL 2h） | 本人/ADMIN |
| POST | `/articles/{id}/restore` | 回收站恢复（回草稿） | 本人/ADMIN |
| DELETE | `/articles/{id}/force` | 物理删除（级联） | 本人/ADMIN |
| GET | `/articles/{id}/export?format=md\|html` | 导出为文件（front matter + 正文原文；可见性同详情） | 同详情 |
| POST | `/articles/import` | 导入 .md/.markdown/.txt/.html 为 DRAFT 草稿（≤2MB，详见「文章导入导出」） | ADMIN/AUTHOR |
| GET/POST/PUT/DELETE | `/tags` `/tags/{id}` | 标签管理（name 唯一） | 读公开 / 写 ADMIN·AUTHOR |
| GET/POST/PUT/DELETE | `/categories` `/categories/{id}` | 分类树管理 | 读公开 / 写仅 ADMIN |
| POST | `/upload/image` | 上传图片（jpg/png/gif/webp ≤5MB，返回 `{url, id}` 并落附件库；每用户每日限 100 次） | 登录 |
| GET | `/uploads/**` | 本地存储文件的静态访问（MinIO 模式下不经过此路径） | 公开 |

### 互动

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/comments?articleId=&sort=&page=&size=` | 主评论分页（sort：time_desc/time_asc/hot，内嵌前 3 条回复） | 公开 |
| GET | `/comments/{rootId}/replies` | 某主评论的全部回复（时间正序） | 公开 |
| POST | `/comments` | 发表评论（敏感词命中折叠 / 审核开关开启落 PENDING） | 登录 |
| DELETE | `/comments/{id}` | 删除评论（连带回复逻辑删） | 本人/ADMIN |
| POST/DELETE | `/articles/{id}/like` | 文章点赞 / 取消 | 登录 |
| POST/DELETE | `/articles/{id}/favorite` | 文章收藏 / 取消 | 登录 |
| POST/DELETE | `/comments/{id}/like` | 评论点赞 / 取消 | 登录 |
| GET | `/articles/mine/favorites` | 我的收藏分页 | 登录 |

### 社交

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST/DELETE | `/users/{id}/follow` | 关注（幂等）/ 取关 | 登录 |
| GET | `/users/{id}/followers` `/following` | 粉丝 / 关注分页 | 公开 |
| GET | `/users/{id}/profile` | 用户主页（含关注数、文章数、followed） | 公开 |
| GET | `/users/{id}/articles` | 该用户已发布文章分页 | 公开 |
| GET | `/feed` | 关注作者的文章 Feed | 登录 |
| GET | `/notifications` `/notifications/unread-count` | 通知分页 / 未读数 | 登录 |
| PUT | `/notifications/{id}/read` `/read-all` | 标记已读 / 全部已读 | 登录 |
| GET | `/messages/conversations` | 会话列表（含未读数） | 登录 |
| GET/POST | `/messages` | 与某人的消息分页 / 发送私信 | 登录 |
| PUT | `/messages/read?peerId=` | 会话消息全部置已读 | 登录 |
| GET | `/ws` | WebSocket 实时推送（私信 + 通知；握手从 Cookie 读 AUTH_TOKEN） | 登录 |

通知触发点：评论回复（`COMMENT_REPLY`）、@提及（`MENTION`）、文章点赞（`ARTICLE_LIKE`）、关注（`FOLLOW`）、私信（`PRIVATE_MESSAGE`）；创建失败仅 log.warn 不回滚主业务，自己给自己不发，点赞/关注防重。

### 站点与 SEO

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/rss.xml` `/atom.xml` | 订阅输出（Rome，最近 20 篇可见文章） | 公开 |
| GET | `/sitemap.xml` | 站点地图（首页 + 可见文章，≤1000 条） | 公开 |
| GET | `/robots.txt` | 爬虫规则 | 公开 |
| GET | `/site/config` | 站点公开配置（白名单 5 键） | 公开 |
| GET/PUT | `/admin/site/config` | 站点配置全量读 / 写 | ADMIN |
| GET | `/attachments` `/attachments/groups` | 附件分页（分组/搜索）/ 分组列表 | 登录 |
| POST/PUT/DELETE | `/attachments/groups[/{id}]` | 分组 CRUD | 登录 |
| PUT/DELETE | `/attachments/{id}` | 换组 / 逻辑删除（不删存储对象） | 登录 |

### 管理后台（均仅 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/users` | 用户分页（keyword/role/status 过滤） |
| PUT | `/admin/users/{id}/ban` `/unban` `/role` | 封禁 / 解封 / 改角色（不能操作 ADMIN 与自己；操作即吊销目标全部 token） |
| GET | `/admin/articles` | 全状态文章分页（支持伪状态 TRASH） |
| PUT | `/admin/articles/{id}/top` `/recommend` `/offline` | 置顶 / 推荐位 / 强制下架 |
| GET | `/admin/comments` | 评论分页（含 FOLDED/PENDING/TRASH） |
| PUT | `/admin/comments/{id}/approve` `/reject` | 审核通过（补发通知+邮件）/ 拒绝（折叠） |
| PUT | `/admin/comments/{id}/fold` `/unfold` | 折叠 / 恢复 |
| DELETE/POST | `/admin/comments/{id}` `/restore` `/force` | 逻辑删 / 恢复 / 物理删 |
| GET/POST/DELETE | `/admin/sensitive-words[/{id}]` | 敏感词库管理 |
| GET | `/admin/stats/overview` `/recent` | 数据总览 / 最新动态 |
| GET/DELETE | `/admin/attachments[/{id}]` | 全量附件管理 |

## 数据库

Flyway 迁移位于 `src/main/resources/db/migration/`：

| 迁移 | 内容 |
|---|---|
| V1 `init_user` | `user` 表（逻辑删除 `deleted`、`uk_username`、`uk_email`、`idx_github_id`） |
| V2 `seed_admin` | 种子管理员（**历史公开口令已废弃**：已应用的迁移不改内容，启动期由 `AdminAccountInitializer` 检测公开种子哈希并原地轮换/重建，密码随机生成打印日志或取 `ADMIN_INITIAL_PASSWORD`） |
| V3 `content` | `category` / `tag`（`uk_name`）/ `article` / `article_tag`（`uk_article_tag`） |
| V4 `interaction` | `article` 加 `view_count`；`comment` / `article_like` / `article_favorite` / `comment_like` |
| V5 `social` | `follow`（`uk_follow`）/ `notification` / `private_message` |
| V6 `admin` | `article` 加 `is_top` / `is_recommended`；`sensitive_word`（`uk_word`） |
| V7 `p0_article` | `article_version` 版本快照表；`article` 加 `content_text` + ngram FULLTEXT 索引 |
| V8 `p0_comment` | `user` 加 `email_notify_enabled`（通知偏好，存量默认开） |
| V9 `p0_infra` | `site_config`（7 个内置键）+ `attachment` / `attachment_group` 附件库两表 |

逻辑删除约定：全局字段 `deleted`（`0` 未删 / `1` 已删）；`Article`/`ArticleVersion`/`Comment`/`Attachment`/`SiteConfig` 使用实体级 `@TableLogic`（原因见实战笔记）。点赞/收藏类关联实体不映射 `deleted`，取消走物理删除。

## 测试与 CI

- **30 个测试类**：服务层单测 + MockMvc 接口级集成测试（H2 内存库替代 MySQL，全文搜索自动降级 LIKE 兜底）
- 本地执行：`mvn verify`
- CI（`.github/workflows/ci.yml`）双 job：
  - `build`：JDK 25 下 `mvn -B verify`（编译 + 全部测试）
  - `flyway-migration`：真实 MySQL 8.4 service 容器，用 `flyway/flyway:10` 镜像跑全部迁移，校验 `db/migration` 的 SQL 可执行

## 实战笔记（踩过的坑）

- **JWT 角色声明必须是 `roles`**：角色放在自定义声明 `roles`（值形如 `ROLE_ADMIN`）。Spring 默认的 `JwtGrantedAuthoritiesConverter` 只读 `scope`/`scp` 且前缀 `SCOPE_`，因此 `application.yaml` 显式配置 `authorities-claim-name: roles` + `authority-prefix: ""`，否则 `hasRole('ADMIN')` 一律 403。
- **`/error` 必须放行**：错误转发（ERROR dispatch）同样经过安全链，不放行会把真实异常改写成 401。
- **CSRF 两层防线**：Cookie 认证后浏览器自动携带凭据，第一层是 SameSite=Strict Cookie（跨站不带），第二层是 `CsrfHeaderCheckFilter` 强制 mutating 请求携带 `X-Requested-With: XMLHttpRequest` 自定义头（跨站表单无法伪造自定义头）；项目无 Cookie 会话，故仍关闭 Spring Security 的会话型 CSRF。
- **`jwt.secret` 无默认值**：必须环境变量注入（≥32 字节），未配置/强度不足启动 fail-fast；compose 与 `.env.example` 同样不落任何默认口令。
- **X-Forwarded-For 默认不信任**：仅在 `blog.security.trust-proxy-headers=true`（可信反代后）才解析，伪造 XFF 无法绕过验证码/限流；登录另有按用户名维度的失败计数兜底。
- **MinIO 官方镜像已 EOL**：`minio/minio` 与 `minio/mc` 从 Docker Hub 整体下架，compose 改用 pin 版本的 `alpine/minio`（非 root 运行；从旧官方镜像迁移数据卷需先 `down -v` 重建）；bucket 创建与匿名下载策略改由后端启动自检（`MinioConfig#minioBucketCheck`，幂等）保证。
- **MP 全局 `logic-delete-field` 配置实测不生效**，上述五个实体改用实体级 `@TableLogic`。
- **`publish_time` 写入统一截断到秒**：DATETIME 秒精度会四舍五入，带纳秒的 `now()` 可能进位到未来，导致定时发布可见性谓词竞态。
- **Spring Boot 4.1.4 不存在**：需求文档指定 4.1.4，但 Maven Central / 阿里云 4.1.x 仅到 4.1.1；Flyway 的 MySQL 模块名是 `flyway-mysql`（`flyway-database-mysql` 双仓 404）。
- **`.env` 里 `GITHUB_CLIENT_ID=` 留空会炸启动**：空值会覆盖 `application-dev.yaml` 的 `replace-me` 占位，`clientRegistrationRepository` 装配时抛 `Client id of registration 'github' must not be empty`（2026-10-02 实测）。不接 OAuth 就保持注释，别留空赋值（`.env.example` 已改）。
- **MinIO 起不来先查卷属主**：见「快速开始」的 `chown -R 100:101` 一步；`file access denied` 不是镜像坏了。
- **`spring.docker.compose.enabled=false`**：Boot 不会隐式拉起容器，基础设施一律靠显式 `docker compose up -d`；
  容器名/端口与手工 `docker run` 起的旧容器冲突时，先 `docker rm -f blog-mysql` 再 compose（否则容器名占用报错）。

## 路线图

已完成：用户体系与鉴权、内容模块、互动模块、社交模块（含 WebSocket 实时推送）、管理后台、自适应验证码，以及对标 WordPress/Halo 的 P0 补缺（版本历史、自动保存、定时发布、回收站、全文搜索、归档、评论审核 + 邮件通知、SEO 三件套、站点设置中心、附件库）。

后续候选（P1）：文章 slug 固定链接、密码保护/私密文章、友情链接、自定义页面、TOTP 两步验证、订阅推送、AI 摘要/评论审核增强、附件对象物理清理、首登强制改密标记（需前后端字段契约）。
