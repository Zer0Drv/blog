# 后端发布门禁（#58）

## 正常发布

CD 的依赖顺序为 `gate → ci → image → deploy`。`ci` 调用同一提交内的完整 `.github/workflows/ci.yml`，执行 `build`、`flyway-migration`、`real-dependencies`；后续加入该 CI 的必需 job 也会随调用执行。所有必需 job 和测试步骤不得通过条件跳过或忽略错误。CI 仍独立响应 push/PR，所以涉及后端产物的 main 提交会另运行一次发布专用 CI，代价是重复执行，收益是不依赖检查名轮询、旧成功记录或跨运行时序。

测试、迁移和镜像构建 checkout 均固定为调用方的 `github.sha`。镜像 job 在完整 CI 成功后才运行，并再次核对工作区 HEAD；CI 未结束、失败、取消或被跳过均不允许正常构建/部署。手动正常发布同样经过完整 CI。手动发布和回滚都只允许选择 main。

构建为镜像写入 `org.opencontainers.image.revision=<完整 SHA>`。部署机拉取后，在更新容器前核对镜像 revision 与本次通过 CI 的 SHA；不一致或没有标记即失败。保留现有 `sha-<8位>`、`latest` 镜像标签，部署仍使用明确的 SHA 标签。CD 的 `backend-build-<完整 SHA>` artifact 保留 90 天，记录提交、镜像坐标和包含发布专用 CI 的工作流运行链接；部署摘要另记录拉取镜像的 revision 与 registry digest。

`production` 环境审批保持原位；所有者审核合并 PR 后仍需按环境设置审批部署。main push 的 `[skip cd]` 标记继续跳过 CD，独立 CI 照跑；手动运行是显式发布请求，不继承提交消息中的跳过标记。

## 回滚边界

非空 `rollback_tag` 必须为 `sha-` 加 8 位小写十六进制标识。回滚继续跳过当前提交的 CI/镜像重建，在 production 审批后拉取所选历史镜像，保留现有健康检查。不要把当前工作流 SHA 当成历史镜像的构建 SHA；摘要分别记录两者。

门禁上线前的历史镜像可能缺少 revision 或历史 CI 证据。为保持已有手动回滚可用，本次没有对历史镜像强制新增标记校验，也没有自动回滚或声称完成历史产物认证。操作者仍须核对目标镜像对应的成功 CI/CD 记录及 migration 兼容性。Flyway 前向迁移不会随应用镜像回滚；不得自动降级 schema。

## 验证与未完成范围

`ReleaseWorkflowTest` 校验实际工作流依赖、完整 CI 的不可跳过约束、checkout SHA、production 环境，并在子进程运行工作流中的真实门控/部署脚本。Docker 调用由函数替身接管，覆盖 main/非 main、正常/跳过/手动/回滚、非法标签、镜像 SHA 匹配/错配/缺失，验证拒绝时不更新容器。运行：`mvn -B -Dtest=ReleaseWorkflowTest test`；Windows 如未安装部署配置指定的 Bash，可指定 `-Dworkflow.test.bash=<Git Bash 路径>`。

PR CI 可验证复用流程的各项内容与门禁回归；真正的 CD 调用链和 production 审批需在合并后首次符合路径过滤的 main 发布中确认。本地验证不会运行生产发布脚本或修改博客容器。

两仓正常发布门禁与前端部署后网关冒烟已合并；当前组合的 SHA/schema 记录也已建立。完整可复用 CI 现在另包含必需的隔离 `browser-e2e` job，后端独立发布必须等待真实浏览器验证通过。其他跨仓过渡组合、历史产物认证、回滚后现场验证和备份恢复演练仍由 #58 跟踪；后端部署后的现场检查仍只有 actuator，不能把隔离 E2E 当成实际网关发布后验收。
