# blog — 动态博客后端

个人博客后端服务。**当前进度：M1（用户体系 + 鉴权骨架）、M2（内容模块）已完成**，需求基线见 [`docs/requirements-v1.md`](docs/requirements-v1.md)。

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
├── upload/                    图片上传（M2，本地存储 /uploads/**）
├── common/
│   ├── exception/             BusinessException / GlobalExceptionHandler
│   ├── response/              Result<T> / StatusCode / PageResult<T>
│   └── util/JwtSubjects.java
└── config/                    JwtConfig / MybatisPlusConfig / SecurityConfig / WebMvcConfig
```

## 接口（M1 + M2）

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
| GET | `/articles?page=1&size=10&keyword=&tagId=&categoryId=` | 已发布文章分页（publish_time 倒序；keyword 模糊匹配 title/summary） | 公开 |
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

**VO 形状**：`ArticleListVO{id,title,summary,cover,categoryId,categoryName,tags:[{id,name}],author:{id,username,nickname,avatar},status,publishTime,createTime,updateTime}`；`ArticleDetailVO` 追加 `{content,editorType}`。

防刷：同一邮箱 **60 秒内**只能发一次验证码（Redis 计数，`EmailCodeServiceImpl.LIMIT_TTL`）。

## 数据库

Flyway 迁移位于 `src/main/resources/db/migration/`：

- `V1__init_user.sql` — `user` 表（含逻辑删除 `deleted`、`uk_username`、`uk_email`、`idx_github_id`）
- `V2__seed_admin.sql` — 种子管理员 `admin / admin123`（BCrypt）
- `V3__content.sql` — M2 内容模块：`category` / `tag`（`uk_name`）/ `article`（`idx_status_publish`、`idx_author`、`idx_category`）/ `article_tag`（`uk_article_tag`）

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

## 配置注意事项（踩过的坑）

- **JWT 角色声明必须是 `roles`**：本项目把角色放在自定义声明 `roles`（值形如 `ROLE_ADMIN`）。Spring 默认的 `JwtGrantedAuthoritiesConverter` 只读 `scope`/`scp` 且默认前缀 `SCOPE_`，因此 `application.yaml` 里显式配了 `authorities-claim-name: roles` + `authority-prefix: ""`，否则 `hasRole('ADMIN')` 会一律 403。
- **`/error` 必须放行**：错误转发（ERROR dispatch）同样经过安全链，不放行会把真实异常改写成 401。
- **CSRF 关闭的前提**是无 Cookie 会话、凭据走 `Authorization` 头、无 `formLogin`；一旦改回 Cookie 认证必须恢复 CSRF。
- **`jwt.secret` 目前是 dev 明文默认值**，上生产前必须替换为环境变量注入的强随机值。

## 路线图

- **M1 骨架** ✅ 工程脚手架、Flyway 初始化、邮箱验证码注册 / 登录、JWT 安全层
- **M2 内容** ✅ 文章 CRUD（Markdown/富文本双模式、图片上传、标签分类）、首页 / 列表 / 详情
- **M3 互动** 评论（两层楼中楼 + 时间/热度双排序 + @）、点赞收藏、浏览量
- **M4 社交** 关注 + Feed、私信（v1 轮询）、通知中心
- **M5 后台** 文章管理、评论治理、用户管理、站点数据 dashboard
