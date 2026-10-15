# 真实依赖回归（#58）

默认 `mvn -B verify` 保留快速单测、H2/MockMvc 与架构检查。
新增的 `mvn -B -Preal-it verify` 单独选择 `*RealIT`，启动真实随机端口的应用，
通过 JDK HTTP/WebSocket 客户端连接真实 MySQL 8.4、Redis 7、MinIO。
它不继承 `IntegrationTestSupport`，不导入 Redis 深桩，不运行 H2 schema 初始化，
Flyway 必须真正执行生产迁移。没有新增 Java 库或测试框架。

## 本地复现

在仓库根目录运行（JDK 25、Maven、Docker Compose v2）：

```sh
docker compose -f compose.real-it.yaml up -d --wait --wait-timeout 150
mvn -B -Preal-it verify
docker compose -f compose.real-it.yaml down --remove-orphans
```

即使测试失败也应执行最后一步；只停止 `compose.real-it.yaml` 的独立项目。
它没有 `container_name` 或持久命名卷，不调用默认 `docker-compose.yml`，
不停止 `blog-backend/blog-mysql/blog-redis/blog-minio` 等现有容器。
端口只绑定 loopback：MySQL **23307**、Redis **26379**、MinIO **29000**。
MySQL/MinIO 使用 tmpfs；Redis 不落盘。停止并移除容器后测试数据消失。
这些服务的公开固定口令仅用于隔离测试，不读取生产凭据，也不供生产部署。

测试有两个独立阶段：

- `InfrastructureRealIT`：Flyway 从空库建立当前 schema，真实 HTTP / Cookie / 安全链 / Redis / 对象存储。
- `MigrationUpgradeRealIT`：每个案例新建 `blog_it_upgrade_<随机 UUID>`，先迁移到 V13 或 V15，
  插入代表性数据，再升级到当前版本，校验数据保留、校验和与重复迁移无变更。最后仅删除该随机 schema。

## 隔离与启动保护

测试配置是显式的 `classpath:/application-real-it.yaml`，不合并 `application-dev.yaml`
或快速测试的 H2 配置。Maven profile 明确启用运行标记，固定 fork 的 JVM 时区为 UTC，
与测试 MySQL 的 UTC 时区和 JDBC 配置一致。

`RealInfrastructureSafety` 在 Flyway、MinIO bucket 自检前执行，拒绝：

- 非 MySQL 驱动、非 loopback 主机、低于 20000 的共享端口；
- 不以 `blog_it` 开头的数据库、非专用 Redis 端口或 Redis DB 非 15；
- 非每轮随机生成的 `blog-it-<UUID>` 图片桶；
- 关闭 Flyway/MinIO/全文搜索或启用 H2 schema 初始化的配置。

默认无需环境变量；如需在自己的隔离环境调整端口/密码，可设置
`REAL_IT_MYSQL_URL`、`REAL_IT_DB_PASSWORD`、`REAL_IT_REDIS_PORT`、`REAL_IT_MINIO_ENDPOINT`。
必须同时调整对应 compose 映射/测试服务，并保持 loopback、高端口、`blog_it` 数据库与 UTC。
切勿把这些变量指向现有业务数据实例。测试不会跳过不可用服务，连不上或配置不安全会失败。

每个业务测试创建独立用户，清理只按该用户删除其文章/版本/引用/附件/队列和已知 Redis 键，
不执行 TRUNCATE、FLUSHDB 或 Flyway clean。每轮图片桶使用随机名字，结束后只删除自身桶内对象及桶。
MinIO 临时目录的 uid/gid 与既有镜像用户匹配，不需要修改开发环境卷权限。

## 当前业务覆盖

| 范围 | 实际断言 |
| --- | --- |
| 登录/吊销 | HTTP 登录与 HttpOnly Cookie、Redis 用户 token 索引/TTL、登出黑名单、用户全部 token 吊销 |
| 验证码 | 失败计数及 TTL、HTTP 获取验证码、正确登录消费验证码、不可复用、真实 Redis TTL 到期 |
| 附件 | multipart 上传到 MinIO、匿名下载字节一致、移出库不立即删对象、到期复检/物理删除/回收字节 |
| 草稿保护 | 真实 HTTP 自动保存落 Redis，草稿单独引用的 MinIO 对象不能被到期删除 |
| 系统参数 | 重启型存储配置修改不能把当前运行实例的上传改路由到新后端 |
| 搜索 | 真正 `MATCH...AGAINST`/ngram 索引；草稿、私密、口令、定时、回收站、下架文章均不进入公开搜索 |
| WebSocket | 真实握手/心跳、登出后关闭连接，吊销 Cookie 再次握手失败 |
| CSRF | 真实 Cookie 写请求缺少自定义头时被拒绝 |
| 迁移升级 | 用户密码/分类/标签关系/历史版本/回收站/附件 key/评论/页面/站点设置，V15 参数覆盖与审计保持不变 |
| 旧订阅数据 | V13 升级后旧公开文章标记完成、未来定时文章继续待发送，防历史内容误重发 |

到期物理删除案例使用真实对象指纹和显式的历史观察期种子，不等待七天，也不降低应用的观察期。
24 小时年龄限制和 7 天标记/恢复策略继续由现有快速集成测试覆盖。

## CI 与证据

`ci.yml` 保留现有 `build` 和空库 `flyway-migration`，新增独立 `real-dependencies` job：

1. 启动有健康检查的隔离 compose 项目，并等待 ready；
2. 执行 profile（找不到测试也会失败）；
3. 无论成功失败都收集 Maven 日志、JUnit XML/文本、服务日志/状态、基础设施版本与升级检查结果；
4. 上传 `real-dependencies-<精确 SHA>` artifact，保留七天；最后停止该测试项目。

本地同样可检查 `target/real-it-reports/`。其中 `infrastructure.json` 记录真实 JDBC 驱动、MySQL 版本、
Redis PONG、随机桶名和迁移版本；`upgrade-v13.txt` / `upgrade-v15.txt` 记录升级与幂等结果。
日志只来自测试实例，不收集宿主应用 `.env` 或生产日志。

这完成 #58 中真实依赖 profile/job 与带数据升级检查的增量。浏览器 E2E、精确 SHA 发布门禁、
网关发布冒烟、现场备份恢复演练和前端性能/分享 metadata 仍由 #58 后续跟踪，不能据此关闭整个 issue。
当前测试不是生产恢复演练，也不验证真实 SMTP、GitHub OAuth 提供商或跨实例消息广播。

实现参照 [Spring Boot 真实端口测试](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
和 [Surefire 测试选择](https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html)。
