# 发布兼容矩阵与历史产物回滚（#58）

两侧 CD 保留 `production` 审批，不绕过正常发布的同 SHA 完整 CI。历史回滚也必须认证；
缺失证据、日志/产物过期、未知组合或 schema 不匹配时，在更新服务/线上桶前停止。

## 已有可追溯组合

| 前端 SHA | 后端 SHA | schema | 证据 |
| --- | --- | --- | --- |
| `42185de65f161a484188818cca2e1079e0d72a73` | `edaac9bf49d547f8bd38e9b781df5ff2b81921f3` | V17，checksum 1723020122 | 前端 CD 39361834457 的浏览器 E2E，后端测试提交 `ba612d7` 与合并提交具有相同 tree `4fad13d43ef0d57e70e0c0741a824426c7c39e2a` |
| `0c7d424abcfb2c67ddcb9850fdad9d7046ff5e16` | 同上 | 同上 | 前端 CD 39386965775、当前实际网关身份验证 |
| `42185de65f161a484188818cca2e1079e0d72a73` | `028652b908c6dc29a9dc51b06759ff1905267c2a` | 同上 | [干净 checkout 的真实依赖 Chromium 6/6](compatibility-2026-10-20.md) |

第一行的**旧生产冒烟对应后端 f58cacd3/V16**，不能拿那份冒烟认证 V17。
其 V17 证据来自隔离浏览器 E2E 的 `versions.json`、17 次真实迁移日志和用例结果。
这覆盖性能优化发布前后、旧前端运行新后端的组合，不自动推广到任意历史版本。

后端当前已启用 TOTP。V16 和没有第二因子的后端不在允许回滚列表，避免认证能力退化。
回滚永不执行 Flyway 降级或覆盖数据库。增加新的 SHA 组合须先有对应测试/部署证据，
经 PR 审核更新两仓矩阵；未知 SHA 拒绝放行，不靠“版本看起来接近”推断兼容。

仓库内 JSON 是已审核基线。为避免“把当前发布 SHA 写回代码又产生新 SHA”的循环，
新的已验证组合可由所有者将同格式 JSON 写入前端仓库变量 `FRONTEND_ROLLBACK_COMPATIBILITY`
或后端仓库变量 `BACKEND_ROLLBACK_CERTIFICATES`。变量覆盖基线，变更前须保存完整 JSON 与
测试/发布链接到验收记录；它只是批准组合，不替代原始 CI、原始 artifact 或 digest 认证。
没有更新变量时未知新组合仍拒绝回滚，不自动扩大白名单。

## 认证流程

- 前端先下载 MinIO 历史归档到独立 staging，再核对完整文件清单/摘要；
  查询原始成功 main CD，检查完整 build/browser E2E，并下载该 Actions run 的原始 dist。
  两份完整清单必须相等，不能通过修改归档后自行重算摘要绕过认证。
  之后确认当前后端/schema 在矩阵中，才从已认证的本地副本 mirror 到线上桶。
- 后端查询原始成功 main CD 的 build、迁移、真实依赖、浏览器和 image job；
  从原始推送日志验证 tag 与 digest 的绑定。部署使用 `tag@sha256:...`，
  拉取后再次核对镜像完整 revision，不以可变 tag 作为最终身份依据。
- 两侧发布与回滚均经过实际网关 smoke，记录前端 SHA、后端不可变镜像/revision、
  schema/checksum、文章/资源/公开接口；后端还断言实际运行 revision 等于认证目标。
- 两侧 CD 与本机备份共享 `E:\dev\zer0drv\.deployment.lock`，阻止快照期间部署或两仓并发发布。
  锁被占用时失败而非抢占；失败步骤仍释放自己的锁。进程/机器崩溃留下锁时，先核对
  Actions 与 Docker 状态，确认无现场操作后人工清理，不能按时间盲目抢锁。

Actions artifact 保留 90 天；历史日志或原始产物消失后拒绝回滚该版本。
需要更长期回滚能力时，应先将认证证据与产物迁往受控长期存储，不能删除验证步骤。

## 本轮边界

原始成功 CD 的 `sha-edaac9bf` 推送日志明确记录镜像
`sha256:0bd1384db9ac0beddf1031f4f03b7945470d303727a8b9810968c794a9d088a9`。
本机只读认证检查已验证旧前端原始 CI 产物与当前 V17 组合；没有执行生产回滚。
新 metadata 版本需在合并、CI、部署身份确定后增补矩阵，不能将工作分支预先冒充已发布版本。
文章 HTML 的发布顺序和旧后端退化行为见 [article-sharing.md](article-sharing.md)。
