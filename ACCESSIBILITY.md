# 无障碍说明

本仓库维护博客后端、部署与运维工具以及开发文档。我们希望 API 和命令行输出提供可读的错误信息，让用户仅凭文本也能判断问题和下一步操作。

## 当前验证范围

跨仓浏览器回归通过配套 Vue 前端，在 Chromium 中验证登录、阅读和编辑等流程。屏幕阅读器与仅键盘操作目前缺少系统性覆盖。博客页面的交互与已知验证边界见配套仓库的说明。[前端无障碍说明](https://github.com/Zer0Drv/blog-ui/blob/main/ACCESSIBILITY.md) · [浏览器回归夹具](docs/browser-e2e-fixture.md)

## 反馈障碍

API 错误难以理解、命令输出依赖颜色、文档缺少文字步骤或后端响应影响辅助技术时，请提交 Issue。说明你要完成的任务、发生障碍的接口或文档、项目版本和复现步骤。可附经脱敏的请求与响应示例。阅读页、编辑器或管理后台的交互障碍可提交到前端仓库。[后端问题入口](https://github.com/Zer0Drv/blog/issues/new/choose) · [前端问题入口](https://github.com/Zer0Drv/blog-ui/issues/new/choose)

涉及错误输出、通知和文档的变更，请在 PR 中说明受影响的使用场景和验证结果。[贡献指南](CONTRIBUTING.md)
