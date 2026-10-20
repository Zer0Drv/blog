# 备份与隔离恢复演练（#58）

`BackupRestoreRealIT` 实际调用 MySQL 8.4 的 `mysqldump` / `mysql`，备份并恢复数据库、
本地附件和 MinIO 对象。它只处理自动生成的合成测试数据；不读取业务库、业务桶、生产 `.env`
或现有宿主上传目录。没有新增依赖、生产接口或生产恢复命令。

## 复现与证据

JDK 25、Maven、Docker Compose，在后端仓库根目录运行：

```sh
docker compose -f compose.real-it.yaml up -d --wait --wait-timeout 150
mvn -B -Preal-it -Dtest=BackupRestoreRealIT verify
docker compose -f compose.real-it.yaml down --remove-orphans
```

测试失败后同样执行最后一步。完整 `mvn -B -Preal-it verify` 自动包含本演练；现有必需
`real-dependencies` CI job 执行并上传 `target/real-it-reports/`，保留七天。
快速测试 `RestoreDrillSnapshotTest` 覆盖三个备份文件逐个丢失/损坏、清单缺项/路径注入、非法目标名称。

演练固定使用 `compose.real-it.yaml` 的 **blog-real-it** 项目：MySQL 23307、Redis 26379 DB15、
MinIO 29000，均绑定 loopback。此用例不接受生产连接或凭据覆盖；也不接受其他 Compose 项目名。
MySQL 客户端在该项目自己的 mysql 容器内执行，无需宿主安装 mysql 工具。
ProcessBuilder 传递参数和文件流，不经过 shell 或 PowerShell 文本重定向，避免 SQL 编码损坏。
显式空 `--env-file` 防止 CLI 读取宿主 `.env`。端口被占用或服务不匹配时失败，不复用业务服务。

主要证据 `target/real-it-reports/restore-drill.json` 包含运行 ID、UTC 时间、MySQL/Flyway 版本、
三个文件的 SHA-256、每张表的行数/逻辑行 SHA-256、源数据已移除的断言结果、恢复结果和清理结果。
`backupMillis`、`restoreDataMillis`、`restoreAndVerifyMillis` 是本轮小样本实测耗时，
后者包含应用启动和 HTTP 校验；不代表业务 RPO/RTO 或大规模数据恢复承诺。

本地原始 SQL、附件和清单位于忽略目录 `target/restore-drill/<UUID>/`，不会作为 CI 报告上传。
客户端 stderr 单独保存为报告目录内的 `restore-dump-stderr.log` / `restore-reload-stderr.log`，供 CI 失败诊断。
失败时报告标记 `failed`；新一轮开始先删除旧的成功报告。
CI artifact 的来源以名称中的精确提交和 Actions run 为准，不能用七天诊断保留期限替代业务备份保留策略。

## 实际流程

1. 生成 UUID，只创建 `blog_it_source<UUID>` / `blog_it_restore<UUID>` 数据库和 `blog-it-<UUID>` 桶。
   数据库从当前 V1–V16 Flyway 脚本建立，种入 BCrypt 用户、Unicode/emoji 正文、分类/标签关系、
   历史版本、回收站、评论、独立页面、配置与审计、已完成订阅状态，以及本地/MinIO 附件引用。
2. 应用尚未启动，源数据没有并发写入。SQL 使用 `--single-transaction --quick`，包含 routines、
   events、triggers，显式 `--no-tablespaces --set-gtid-purged=OFF`。只导出本轮随机 schema，
   不加 `--databases`，因此导入时可选择另一个随机 schema，不在 SQL 中重写业务标识。
   导出非零退出或超时立即失败。
3. 复制本地文件，通过真实 MinIO SDK 下载对象，持久化并重新读取三个成员的 SHA-256 清单。
   SQL、本地附件、MinIO 附件必须全部存在、非空且校验通过，任何一项失败都会在恢复写入前中止。
4. 删除本轮源数据库、本地源文件、源对象及源桶，并断言它们已经不存在。
   之后恢复只使用快照：SQL 导入新建随机 schema，本地文件恢复到另一目录，MinIO 重新创建同名桶并恢复相同 key。
   桶名/key 保持原值，原文内的测试附件 URL 无需改写。
5. 对比所有表完整逻辑行的 SHA-256 与行数，校验代表性关系、附件记录/字节、BCrypt、真实 MATCH 全文检索、Flyway validate，
   重复 migrate 必须无变更。随后真实启动应用连接恢复库，执行 HTTP 登录、匿名读取恢复文章、
   匿名下载本地和 MinIO 图片并逐字节比对。桶的匿名只读策略由既有应用启动自检重建。
6. 关闭本轮应用，只删除生成的两个数据库和该随机桶；临时本地文件保留在 target 供诊断。
   最后由 CI 或操作者移除该测试 Compose 项目，不触及现有业务容器。

文件 SHA-256 用于发现意外损坏，不提供备份来源认证或防篡改签名。演练不覆盖生产密钥恢复、
完整 MinIO 实例/版本历史/桶元数据、宿主丢失、跨机切换、binlog 时间点恢复、Redis 草稿恢复或真实外部邮件。
HTTP 登录会在专用 Redis DB15 产生测试会话，测试项目销毁后消失。

## 现场策略与恢复目标

2026-10-20 所有者确认按“尚未配置备份”处理，并确定 **RPO 24 小时、RTO 2 小时**。
这两个数值是验收目标，尚不能解释为已实现的恢复能力。之前的合成数据演练不替代现场数据量下的计时验收。

| 项目 | 当前确认状态 |
| --- | --- |
| MySQL / MinIO / 本地附件已有备份与存放位置 | 尚未配置（所有者确认） |
| 频率、异地副本、保留期限、加密与访问权限 | 暂无异地目的地；本机方案见 local-backup-operations.md，尚未部署 |
| 备份失败告警、责任人、定期校验与演练频率 | 暂无外部通知渠道；本机状态任务方案尚未启用 |
| RPO（可接受数据丢失窗口）/ RTO（可接受恢复时间） | 24 小时 / 2 小时（所有者确认） |
| Redis 自动保存草稿是否纳入持久恢复范围 | 待确认 |

现场恢复规程应以本演练的“完整性检查 → 隔离恢复 → 应用验收”为基础，补齐以下实际信息后再验收：

1. 盘点真实数据库/桶/本地上传目录、当前镜像 SHA/schema、配置与密钥保管位置；确定写入冻结方式。
   SQL 快照与文件/对象复制不是跨存储事务，必须冻结相关写入和删除，或使用经验证的共同恢复点方案。
   `--single-transaction` 适用于 InnoDB；导出期间不执行 DDL。不得直接把本测试口令/目标套到生产。
2. 为每个恢复点记录时间、数据库版本/Flyway 校验和、对象清单/大小/摘要、发布产物和配置引用；
   在独立备份介质保留完整性证据并验证可读取性。确定有无 binlog/PITR，缺少时不得宣称具备时间点恢复。
3. 在新建隔离目标恢复，先拒绝缺件/损坏备份，再导入数据库、恢复附件和必须的桶策略/域名/key。
   关闭外发邮件、订阅 worker、第三方回调，避免恢复旧状态后重复投递；核对敏感配置后才启动兼容应用。
   Redis 中有自动保存草稿、验证码和会话吊销状态，不能笼统把全部 Redis 数据视为可丢缓存。
4. 验收登录、公开/受限内容、历史版本、回收站、附件、搜索、配置、订阅幂等与 schema；
   记录真实数据量、恢复点、开始/结束时间、缺失范围，并与已确认 RPO/RTO 对照。
5. 所有者验收后另行决定切换。保留原实例与恢复前快照，记录反向切换条件及写入合并策略；
   不自动覆盖正在使用的库/桶，不自动降级 Flyway schema，不把镜像/dist 回滚当作数据恢复。

本阶段提供可重复的隔离恢复验证与规程基础。#58 的现场备份策略、真实数据恢复演练和业务 RPO/RTO 验收仍未完成。

### 待部署方案的验收约束

- 建议每 12 小时完成一次备份，为失败重试留出余量；监控“最近一次完整且校验通过的恢复点年龄”，
  超过 18 小时告警，超过 24 小时视为 RPO 不达标。单纯配置每日任务不等于满足 RPO。
- 建议保留 14 天完整恢复点；清理前必须存在更新且校验通过的副本，不因本轮失败删除旧副本。
  独立存储目的地与失败通知渠道尚待提供，本机同盘副本不能作为宿主损坏后的恢复依据。
- 同一恢复点必须覆盖 MySQL、MinIO、本地附件，以及 Redis 中的草稿与安全状态。
  当前生产后端没有本地附件挂载卷，需在部署备份前检查 `/app/uploads`，不能默认该路径为空。
- 数据库密码、JWT/TOTP 加密密钥、镜像 digest、前端发布清单和配置版本必须可恢复；
  密钥通过独立的受控保管方式交接，不写入公开验收报告或 CI artifact。
- 使用实际备份在隔离目标完成恢复、应用验收与计时；从宣布恢复开始到业务验收完成不超过 2 小时，
  包含取得备份/密钥/镜像、传输、导入、启动和检查，不只计算 SQL 导入耗时。

工具语义参照 [MySQL 8.4 mysqldump](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)。
