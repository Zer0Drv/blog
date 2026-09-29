# blog — 动态博客后端

个人博客后端服务。**当前进度：M1（用户体系 + 鉴权骨架）、M2（内容模块）、M3（互动模块）、M4（社交模块）、M5（管理后台）已完成**，需求基线见 [`docs/requirements-v1.md`](docs/requirements-v1.md)。

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 25 | |
| Spring Boot | 4.1.1 | 需求文档写的是 4.1.4，Maven Central / 阿里云当前最新 4.1.x 只到 **4.1.1**（4.1.4 未发布），故按最新可用版定型，见 `pom.xml` 注释 |
| Spring Security | 7.x（随 Boot） | STATELESS + `oauth2-resource-server`，HS256 JWT |
| MyBatis-Plus | 3.5.17 | `mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser` |
| MySQL | 8.4 LTS | Docker 容器 `blog-mysql`，宿主端口 **3307** |
| Flyway | 随 Boot | MySQL 支持模块名为 **`flyway-mysql`**（需求文档写的 `flyway-database-mysql` 双仓 404 实测不存在） |
| Redis | — | 验证码、防刷限流 |
| 其他 | — | MapStruct-Plus 1.5.2、Hutool 5.8.47、Lombok、Spring Modulith 2.1.1、devtools |

## 目录结构

按业务模块分包（为后续 Spring Modulith 边界做准备）：

```
src/main/java/com/zer0drv/blog/
├── BlogApplication.java
├── auth/                      认证模块
│   ├── controller/AuthController.java
│   ├── dto/                   RegisterDTO / UserLoginDTO / EmailCodeDTO / ChangePasswordDTO
│   ├── service/               AuthService / EmailCodeService / TokenService (+ impl)
│   └── validator/BlacklistJwtValidator.java
├── user/                      用户领域
│   ├── domain/User.java       enums/UserRole.java（ADMIN / AUTHOR / USER）
│   ├── mapper/UserMapper.java
│   ├── bo/SecurityUser.java   vo/UserVO.java
│   └── service/               UserService / CustomUserDetailsService (+ impl)
├── article/                   文章模块（M2）
│   ├── controller/ArticleController.java
│   ├── domain/                Article / ArticleTag
│   ├── dto/                   ArticleSaveDTO / ArticleStatusDTO
│   ├── enums/                 ArticleStatus / EditorType
│   ├── mapper/                ArticleMapper / ArticleTagMapper
│   ├── service/               ArticleService (+ impl)
│   └── vo/                    ArticleListVO / ArticleDetailVO / ArticleAuthorVO
├── tag/                       标签模块（M2，完整 CRUD）
├── category/                  分类模块（M2，树形结构，完整 CRUD）
├── comment/                   评论模块（M3，两层楼中楼 + 时间/热度排序 + @）
│   ├── controller/CommentController.java
│   ├── domain/Comment.java    enums/CommentStatus（NORMAL/FOLDED）/ CommentSort（time_desc/time_asc/hot）
│   ├── dto/CommentCreateDTO.java
│   ├── mapper/CommentMapper.java
│   ├── service/               CommentService (+ impl)
│   └── vo/                    CommentVO / CommentUserVO
├── interaction/               互动模块（M3，文章点赞/收藏 + 评论点赞 + 我的收藏）
│   ├── controller/InteractionController.java
│   ├── domain/                ArticleLike / ArticleFavorite / CommentLike（实体不映射 deleted，取消走物理删除）
│   ├── mapper/                ArticleLikeMapper / ArticleFavoriteMapper / CommentLikeMapper
│   └── service/               InteractionService (+ impl)
├── upload/                    图片上传（M2，本地存储 /uploads/**）
├── social/                    社交模块（M4，关注/Feed、私信、通知中心）
├── admin/                     管理后台（M5）
│   ├── controller/            AdminUser / AdminArticle / AdminComment / AdminSensitiveWord / AdminStats
│   ├── domain/SensitiveWord.java
│   ├── dto/                   RoleUpdateDTO / TopDTO / RecommendDTO / SensitiveWordAddDTO
│   ├── service/               AdminUser / AdminArticle / AdminComment / SensitiveWord / AdminStats (+ impl)
│   └── vo/                    AdminCommentVO / SensitiveWordVO / StatsOverviewVO / StatsRecentVO
├── common/
│   ├── exception/             BusinessException / GlobalExceptionHandler
│   ├── response/              Result<T> / StatusCode / PageResult<T>
│   └── util/JwtSubjects.java
└── config/                    JwtConfig / MybatisPlusConfig / SecurityConfig / WebMvcConfig
```

## 接口（M1 + M2 + M3 + M4 + M5）

统一响应体 `Result<T>{code, data, message}`，`code == "200"` 为成功；登录/注册返回 `data.access_token`。
分页统一 `PageResult<T>{records, total, page, size}`。

### M1 用户体系

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/auth/email-code` | 发送注册邮箱验证码 | 公开 |
| POST | `/auth/register` | 邮箱验证码注册（成功即登录，直接返 token） | 公开 |
| POST | `/auth/login` | 账号密码登录 | 公开 |
| POST | `/auth/logout` | 登出（token 加入黑名单） | 需登录 |
| GET | `/auth/me` | 当前登录用户信息 | 需登录 |
| PUT | `/auth/password` | 修改密码（成功后需重新登录） | 需登录 |
| GET | `/actuator/health` | 健康检查 | 公开 |

### M2 内容模块

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/articles?page=1&size=10&keyword=&tagId=&categoryId=` | 已发布文章分页（is_top DESC + publish_time 倒序；keyword 模糊匹配 title/summary） | 公开 |
| GET | `/articles/{id}` | 文章详情（匿名/非作者仅 PUBLISHED 可见；作者本人/ADMIN 可看任意状态） | 公开 |
| GET | `/articles/mine?page=1&size=10&status=` | 本人文章（含草稿/下架，status 可空） | ADMIN/AUTHOR |
| POST | `/articles` | 新建文章（ArticleSaveDTO，允许直接发布） | ADMIN/AUTHOR |
| PUT | `/articles/{id}` | 编辑文章（仅本人或 ADMIN，否则 40301） | ADMIN/AUTHOR |
| DELETE | `/articles/{id}` | 删除文章（仅本人或 ADMIN） | ADMIN/AUTHOR |
| PUT | `/articles/{id}/status` | body `{status}` 上架/下架/回草稿；首次发布写 publish_time | ADMIN/AUTHOR |
| GET | `/tags` | 全部标签 `[{id,name}]` | 公开 |
| POST | `/tags` `{name}` | 新建标签（name 唯一） | ADMIN/AUTHOR |
| PUT | `/tags/{id}` | 编辑标签 | ADMIN/AUTHOR |
| DELETE | `/tags/{id}` | 删除标签（同时清理文章关联） | ADMIN/AUTHOR |
| GET | `/categories` | 分类树 `[{id,name,parentId,sort,children}]` | 公开 |
| POST | `/categories` `{name,parentId?,sort?}` | 新建分类（parentId 缺省 0=根） | 仅 ADMIN |
| PUT | `/categories/{id}` | 编辑分类 | 仅 ADMIN |
| DELETE | `/categories/{id}` | 删除分类（有子分类或文章引用时拒绝） | 仅 ADMIN |
| POST | `/upload/image` | 上传图片（multipart 字段 `file`；jpg/png/gif/webp ≤5MB；返回 `{url}`） | 需登录 |
| GET | `/uploads/**` | 上传文件静态访问（存 `uploads/yyyyMM/`，`BLOG_UPLOAD_DIR` 可覆盖根目录） | 公开 |

**ArticleSaveDTO**：`{title* (≤200), content*, editorType*: MARKDOWN|RICHTEXT, summary (≤500，缺省取 content 纯文本前200字), cover, categoryId, tagIds: [Long], status: DRAFT|PUBLISHED}`。

**VO 形状**：`ArticleListVO{id,title,summary,cover,categoryId,categoryName,tags:[{id,name}],author:{id,username,nickname,avatar},status,isTop,isRecommended,publishTime,createTime,updateTime}`；`ArticleDetailVO` 追加 `{content,editorType}`。

防刷：同一邮箱 **60 秒内**只能发一次验证码（Redis 计数，`EmailCodeServiceImpl.LIMIT_TTL`）。

### M4 社交模块

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/users/{id}/follow` | 关注（幂等；不能关注自己 40050） | 需登录 |
| DELETE | `/users/{id}/follow` | 取关（物理删除；未关注 40051） | 需登录 |
| GET | `/users/{id}/followers?page=1&size=10` | 粉丝分页 `FollowUserVO{id,username,nickname,avatar,bio,followed}` | 公开 |
| GET | `/users/{id}/following?page=1&size=10` | 关注分页（VO 同上） | 公开 |
| GET | `/users/{id}/profile` | 用户主页 `{id,username,nickname,avatar,bio,followerCount,followingCount,articleCount,followed}` | 公开 |
| GET | `/users/{id}/articles?page=1&size=10` | 该用户 PUBLISHED 文章分页（ArticleListVO，publish_time 倒序） | 公开 |
| GET | `/feed?page=1&size=10` | 关注作者的 PUBLISHED 文章分页（未关注任何人返回空页） | 需登录 |
| GET | `/notifications?page=1&size=10&type=` | 我的通知分页（create_time 倒序；type 可空过滤） | 需登录 |
| GET | `/notifications/unread-count` | 未读数 `{count}` | 需登录 |
| PUT | `/notifications/{id}/read` | 标记已读（仅本人，否则 40301） | 需登录 |
| PUT | `/notifications/read-all` | 全部已读 | 需登录 |
| GET | `/messages/conversations` | 会话列表 `[{peer,lastMessage:{content,createTime,senderId},unreadCount}]` 按最新消息倒序 | 需登录 |
| GET | `/messages?peerId=&page=1&size=20` | 与某人的消息分页（create_time 倒序；peerId 不能是自己 40053） | 需登录 |
| POST | `/messages` `{receiverId*, content*(≤1000)}` | 发送私信（不能发给自己 40053；内容非法 40052） | 需登录 |
| PUT | `/messages/read?peerId=` | 该会话中发给我的未读消息全部置已读 | 需登录 |

**通知触发点**（创建失败仅 log.warn，不回滚主业务；自己给自己不发）：
- 评论创建：主评论 → 文章作者收 `COMMENT_REPLY`；回复：`replyToUserId` 为空 → root 作者收 `COMMENT_REPLY`，非空 → root 作者收 `COMMENT_REPLY` + 被 @ 人收 `MENTION`（两者同人只发 `MENTION`）
- 文章点赞 → 作者收 `ARTICLE_LIKE`（首次点赞才发；已存在同 actor/article/type 未删通知则跳过，取消再赞不重复发）
- 关注 → 被关注者收 `FOLLOW`（同上防重）
- 私信发送 → 接收者收 `PRIVATE_MESSAGE`（summary = 内容前 50 字）

### M5 管理后台

所有 `/admin/**` 接口由 SecurityConfig 统一 `hasRole("ADMIN")` 保护，Controller 不再加 `@PreAuthorize`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/users?page=1&size=10&keyword=&role=&status=` | 用户分页（keyword 匹配 username/nickname/email；VO = UserVO 含 status） |
| PUT | `/admin/users/{id}/ban` | 封禁（status=1；不能封 ADMIN 40061、不能封自己 40062） |
| PUT | `/admin/users/{id}/unban` | 解封（status=0） |
| PUT | `/admin/users/{id}/role` | body `{role: "AUTHOR"\|"USER"}`；不允许改成 ADMIN、不允许改自己 |
| GET | `/admin/articles?page=1&size=10&status=&keyword=&authorId=` | 全状态文章分页（ArticleListVO 含 isTop/isRecommended） |
| PUT | `/admin/articles/{id}/top` | body `{isTop: 0\|1}` 置顶切换 |
| PUT | `/admin/articles/{id}/recommend` | body `{isRecommended: 0\|1}` 推荐位切换 |
| PUT | `/admin/articles/{id}/offline` | 强制下架（status=OFFLINE，不校验作者） |
| GET | `/admin/comments?page=1&size=10&status=&keyword=&articleId=` | 评论分页（含 FOLDED），VO 冗余 username/articleTitle |
| PUT | `/admin/comments/{id}/fold` | 折叠（status=FOLDED，前台不再展示） |
| PUT | `/admin/comments/{id}/unfold` | 恢复（status=NORMAL） |
| DELETE | `/admin/comments/{id}` | 删除（逻辑删除，连带回复） |
| GET | `/admin/sensitive-words?page=1&size=50&keyword=` | 敏感词分页 |
| POST | `/admin/sensitive-words` | body `{word*}`（重复静默成功） |
| DELETE | `/admin/sensitive-words/{id}` | 逻辑删除 |
| GET | `/admin/stats/overview` | `{userCount, articleCount(PUBLISHED), commentCount(NORMAL), totalViews(SUM view_count), likeCount, favoriteCount, followCount, todayNewUsers, todayNewArticles, todayNewComments, unreadNotificationCount}`（todayXxx 按当天 00:00 起算） |
| GET | `/admin/stats/recent` | `{latestUsers(5), latestArticles(5, 含 authorNickname), latestComments(5, content 截断 30 字)}` |

**敏感词过滤联动**（忽略大小写包含，词表全量加载）：
- 评论发布命中 → 评论以 `FOLDED` 落库、**不触发通知**，接口正常返回但响应 message 覆盖为「包含敏感内容，已进入审核」（前端按 message 提示）
- 私信发送命中 → 直接拒绝，抛 `MESSAGE_SENSITIVE_HIT(40060)`
- 新增错误码：`MESSAGE_SENSITIVE_HIT(40060)`、`CANNOT_OPERATE_ADMIN(40061)`、`CANNOT_OPERATE_SELF(40062)`、`SENSITIVE_WORD_EXISTS(40063)`

**封禁联动**：登录时 `SecurityUser.isEnabled()` 已校验 status，被封用户登录抛 `USER_BANNED(40022)`；已登录被封者的旧 token 不做主动失效（到期自然失效）。

## 数据库

Flyway 迁移位于 `src/main/resources/db/migration/`：

- `V1__init_user.sql` — `user` 表（含逻辑删除 `deleted`、`uk_username`、`uk_email`、`idx_github_id`）
- `V2__seed_admin.sql` — 种子管理员 `admin / admin123`（BCrypt）
- `V3__content.sql` — M2 内容模块：`category` / `tag`（`uk_name`）/ `article`（`idx_status_publish`、`idx_author`、`idx_category`）/ `article_tag`（`uk_article_tag`）
- `V4__interaction.sql` — M3 互动模块：`article` 加 `view_count`；新增 `comment`（`idx_article_parent`、`idx_user`）/ `article_like` / `article_favorite`（`uk_article_user`）/ `comment_like`（`uk_comment_user`）
- `V5__social.sql` — M4 社交模块：`follow`（`uk_follow`）/ `notification`（`idx_user_read`、`idx_user_create`）/ `private_message`
- `V6__admin.sql` — M5 管理后台：`article` 加 `is_top` / `is_recommended`；新增 `sensitive_word`（`uk_word`）

MyBatis-Plus 全局逻辑删除字段为 `deleted`（`0` 未删除 / `1` 已删除）。

## 本地运行

**前置**：Docker（MySQL 8.4 容器 `blog-mysql` 映射宿主 3307、Redis 映射 6379）。

```bash
docker start blog-mysql redis        # 容器已存在时
mvn spring-boot:run                  # 或 java -jar target/blog-0.0.1-SNAPSHOT.jar
```

服务端口 **8082**。默认数据源 `localhost:3307/blog_dev`，`root / blog123`。

可用环境变量覆盖：`DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` `REDIS_HOST` `REDIS_PORT` `BLOG_UPLOAD_DIR`。

**未配置 SMTP 时**：验证码不发送邮件，改为在应用日志打印（`【dev-email-fallback】…`），本地联调无需邮件服务器。要接真实邮件，取消 `application-dev.yaml` 中 `spring.mail.*` 的注释。

**GitHub OAuth**：依赖（`oauth2-client`）与配置位已预留，填入环境变量 `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` 后在 `SecurityConfig` 启用 `oauth2Login` 即可。

## GitHub OAuth 登录（M6）

授权码流程由 Spring Security 托管：前端按钮直连后端 `/oauth2/authorization/github` → GitHub 授权 → 回调 `/login/oauth2/code/github` → `OAuth2LoginSuccessHandler` 按 `github_id` 查库（不存在则自动注册，用户名 `gh_+login`、隐藏邮箱时用 `gh_{id}@oauth.local` 占位、密码留空仅能 OAuth 登录）→ 签发本站 JWT → 302 回前端 `/oauth/callback?token=...`。

**接入步骤**：

1. GitHub → Settings → Developer settings → OAuth Apps → New OAuth App：
   - Homepage URL：`http://localhost:5173`
   - Authorization callback URL：`http://localhost:8082/login/oauth2/code/github`
2. 配置环境变量 `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET`（`application-dev.yaml` 中默认为占位值 `replace-me`，仅保证无凭据时可启动）
3. 回跳地址可用 `OAUTH_SUCCESS_REDIRECT` / `OAUTH_FAILURE_REDIRECT` 覆盖（默认 `http://localhost:5173/oauth/callback` 与 `/login?error=oauth_failed`）

## MinIO 对象存储（M6）

`docker-compose.yml` 已含 `minio`（API `9000`、控制台 `9001`，默认账密 `minioadmin`/`minioadmin123`）与一次性初始化容器 `minio-init`（自动建 `blog-images` bucket 并开放匿名下载）。上传接口在 `blog.minio.enabled=true`（默认）时写入 MinIO 并返回绝对 URL（`{public-url}/{bucket}/{yyyyMM}/{uuid}.{ext}`）；`MINIO_ENABLED=false` 时回退本地磁盘（`/uploads/**`）。

可用环境变量：`MINIO_ENABLED` `MINIO_ENDPOINT` `MINIO_ACCESS_KEY` `MINIO_SECRET_KEY` `MINIO_BUCKET` `MINIO_PUBLIC_URL`（生产环境把 `MINIO_PUBLIC_URL` 指到浏览器可达的对外地址）。

## 配置注意事项（踩过的坑）

- **JWT 角色声明必须是 `roles`**：本项目把角色放在自定义声明 `roles`（值形如 `ROLE_ADMIN`）。Spring 默认的 `JwtGrantedAuthoritiesConverter` 只读 `scope`/`scp` 且默认前缀 `SCOPE_`，因此 `application.yaml` 里显式配了 `authorities-claim-name: roles` + `authority-prefix: ""`，否则 `hasRole('ADMIN')` 会一律 403。
- **`/error` 必须放行**：错误转发（ERROR dispatch）同样经过安全链，不放行会把真实异常改写成 401。
- **CSRF 关闭的前提**是无 Cookie 会话、凭据走 `Authorization` 头、无 `formLogin`；一旦改回 Cookie 认证必须恢复 CSRF。
- **`jwt.secret` 目前是 dev 明文默认值**，上生产前必须替换为环境变量注入的强随机值。

## 路线图

- **M1 骨架** ✅ 工程脚手架、Flyway 初始化、邮箱验证码注册 / 登录、JWT 安全层
- **M2 内容** ✅ 文章 CRUD（Markdown/富文本双模式、图片上传、标签分类）、首页 / 列表 / 详情
- **M3 互动** ✅ 评论（两层楼中楼 + 时间/热度双排序 + @）、点赞收藏、浏览量
- **M4 社交** ✅ 关注 + Feed、私信（v1 轮询）、通知中心
- **M5 后台** ✅ 文章管理（置顶/推荐位/下架）、评论治理（审核 + 敏感词过滤）、用户管理（封禁/角色）、站点数据 dashboard
