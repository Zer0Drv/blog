# #58 精确版本兼容验证

2026-10-20 在干净的 detached checkout 中完成旧前端/新后端验证：

- 前端：`42185de65f161a484188818cca2e1079e0d72a73`
- 后端：`028652b908c6dc29a9dc51b06759ff1905267c2a`
- 两侧 `sourceDirty=false`，真实 MySQL、Redis、MinIO 使用独立 Docker 项目。
- run ID：`94922602556c41e7be46a60b78c9f779`；Flyway 成功迁移至 V17。
- Chromium 6/6 通过，82.704245 秒：登录编辑/自动保存恢复/发布搜索、导入保护、图片上传、权限、离线自动保存和 TOTP 全流程。
- 运行后已清理该次独立项目，未修改生产数据库或附件。

本机证据位于 `E:\dev\zer0drv\blog-ui-compat58\e2e-artifacts`：

| 文件 | SHA-256 |
| --- | --- |
| results.xml | `36d787624461c3e0a36f8b03c49e00d8aed996f3e159c49ef05d5f4f2953e3d1` |
| versions.json | `72b101e7e10d7d583ccd57eed0df0292db69ae7b03b0430bde6e0b98907cb197` |

该记录证明上述精确组合的隔离 E2E，通过不代表已经执行生产回滚或现场备份恢复。
