# 开发流程规范

本仓库遵循完整软件开发流程，一切变更走 Issue → 审核 → 分支 → PR → 审批合并，禁止直推 main。

1. **Issue 先行**：任何功能、修复、重构都必须先创建 Issue，说明问题、方案与影响面，经仓库所有者审核确认后方可动工。无 Issue 不动代码。
2. **分支**：审核通过后从最新 main 切出特性分支，命名 `<type>/<scope>`（feat/ fix/ refactor/ test/ docs/ chore）。
3. **PR**：完成后推送分支并创建 Pull Request。描述必须包含：目的、变更清单、行为等价性论证（重构类）、测试结果。
4. **质量门禁**：全量测试通过；ArchUnit 边界红线不得新增豁免（豁免只允许缩小）；不引入未经 Issue 讨论的新依赖/新框架。
5. **合并**：PR 由仓库所有者审批后合并（squash 或 merge 由所有者定）；合并后删除特性分支（远端已开启自动删除）。
6. **提交信息**：Conventional Commits + 中文摘要——
   - 格式：`<type>(<scope>): <中文摘要>`；type 英文小写，取值：feat / fix / refactor / test / docs / chore / ci / build / perf
   - scope 为模块名（article、comment、session、editor……），无明确模块时省略
   - 摘要：中文、动词开头、一句话说清"做了什么"，不以句号结尾；不混用中英文摘要
   - 正文（可选但推荐）：动机 + 方案要点，破折号列表，72 字符换行
   - 关联 Issue：正文末行 `Refs #N`（关联）或 `Closes #N`（关闭）；一个 commit 只做一件事
   - 反例（历史遗留，不再出现）：纯英文摘要、`fix:` 后无摘要正文、scope 与模块名不一致
7. **优先级管理**：Issue 必须打优先级 label——P0（线上故障/安全问题，立即处理）/ P1（本迭代应完成）/ P2（backlog 择机）；类型 label：bug / enhancement / refactor。
8. **Hotfix 通道**：线上紧急问题允许加急——Issue 可先开后补（合并后 24h 内补登），分支命名 `hotfix/<scope>`，PR 标题带 `[hotfix]` 前缀优先评审；**CI 门禁与所有者审批不可跳过**。
9. **发布与回滚**：完成一组有价值变更后在 main 打 semver tag（`vX.Y.Z`）并同步更新 `CHANGELOG.md`（Keep a Changelog 格式，Unreleased 段随 PR 累积）；每次 CD 构建产物带 `sha-<8位>` 标签长期保留（后端镜像在 GHCR，前端 dist 归档 MinIO `releases/` 前缀）；回滚 = 手动运行 CD（workflow_dispatch）并填入目标 tag，见 cd.yml 头部说明。
10. **AI 协作者同样遵守本规范**——包括不直推 main、不跳过 Issue、不自行合并。
