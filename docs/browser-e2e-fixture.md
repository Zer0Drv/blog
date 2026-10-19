# 隔离浏览器测试后端（#58）

`src/test/java/.../realit/BrowserE2EServer` 是仅供前端 Playwright 使用的测试启动器，复用真实应用与 `RealInfrastructureSafety`，没有测试专用 HTTP 接口或认证绕过，不进入生产 jar。新增依赖仅在前端（所有者于 2026-10-19 确认），后端没有新增库或构建插件。

在本仓运行 `mvn -B -DskipTests package`，编译生产 jar 和 `target/test-classes`。前端启动器使用现有 [Spring Boot PropertiesLauncher](https://docs.spring.io/spring-boot/specification/executable-jar/property-launcher.html) 的 `loader.path` / `loader.main` 加载测试 main，随后通过真实 HTTP、安全链与 Cookie 操作应用。

启动器拒绝命令行覆盖，要求前端生成的 32 位随机 `BROWSER_E2E_RUN`。固定 loopback 28082、MySQL 23307/blog_it、Redis 26379/DB15、MinIO 29000 和随机 `blog-it-<run>` 桶；配置在启动前经过现有基础设施保护。关闭管理员引导、订阅 worker 和测试范围外的 SMTP 健康探针，保持 Flyway、MySQL 全文索引、MinIO、验证码与写请求自定义头检查。

应用启动后在隔离数据库种入 ADMIN/AUTHOR/USER 账号及独立文章，真实登录密码仅为公开的测试夹具密码。`target/browser-e2e/fixture.json` 输出账号名、文章 ID 和本轮 ID，不含会话或生产凭据。前端管理随机名称的独立 compose 项目，所有数据使用 tmpfs；测试结束仅停止自身 Java/Vite 子进程和本轮 compose 项目，不调用默认 compose，不读取部署 `.env`，不触碰已有博客容器或卷。

前端完整执行命令、浏览器范围、失败日志/截图/trace 和精确提交记录见配套前端 `docs/browser-e2e.md`。后端自身仍执行 `mvn -B verify` 和现有真实依赖 CI；`-DskipTests package` 仅是浏览器 job 中对已固定后端提交的夹具编译步骤，不能代替后端完整 CI。
