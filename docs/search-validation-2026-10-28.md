# Issue #95 搜索增强验证

关联：https://github.com/Zer0Drv/blog/issues/95 。后端基于 main `b4e06ab`，前端基于 main `e1d9c2d`，两仓使用 `feat/search-enhancements`。

## 行为与边界

- 分类、标签、发布日期范围在查询与计数之前组合过滤。结束日期包含整天；以现有发布时间所在的应用/数据库时区解释，隔离验证环境为 UTC。
- FULLTEXT 复用 MySQL ngram 自然语言搜索，按评分、发布时间、ID 排序；它按分词匹配，不能等同于整串包含匹配。短词、带标点的词、空词与关闭 FULLTEXT 的请求使用转义后的 LIKE。LIKE 请求相关度时返回实际排序 `newest`。
- 最新/最早排序都有 ID 次序作为同时间的稳定顺序。分页属于普通 offset 分页，不承诺并发新增/删除时保持快照。
- 复用 `ArticleVisibility.SQL`，草稿、下架、私密、密码、未来发布、空发布时间与删除文章均不进入结果和计数。
- 命中片段最多 200 个 Unicode 码点，另有最多两个省略号；正文无命中而自定义摘要有命中时展示摘要。前端用文本节点和 `mark` 渲染，保留代码中的字面 HTML，不执行 HTML。
- URL 保存全部已提交条件和页码；前进/后退恢复，分页不使用尚未提交的表单改动。新请求和卸载使旧响应失效。

## 已完成的验证

- 前端 `pnpm exec vitest run --maxWorkers 2`：64 文件、453 测试通过，含 11 项新增搜索行为测试。默认高并发首轮触发既有编辑器导入超时，降并发复跑全量通过，未改变测试超时。
- 前端 `pnpm build`：最终源码生产构建通过，存在既有分包体积提示。
- 后端 `mvn -B -Preal-it -Dtest=SearchRealIT,ArticleSearchTest test`：12 项通过，零失败/错误/跳过。使用独立 MySQL 8.4、Redis 和 MinIO，Flyway V20，不读取生产 `.env` 或数据。
- HTTP 测试包含组合筛选/日期两端/相同时间分页、两种模式、七种受限状态、中文/单字/特殊字符、仅摘要命中、长命中词、无结果及非法输入。
- 后端 `mvn -B verify`：598 项测试全部通过，含 5 项 ArticleBoundaryArchTest；未改动架构测试或增加豁免，JAR 打包成功。日志 `target/issue95-verify.log`。
- Chromium：既有“登录→编辑→自动保存→发布→匿名阅读与搜索”流程通过；新增搜索流程通过，包含真实后端的分类/标签/日期/排序组合、分页与刷新/历史导航、特殊字符摘要命中、无结果与清空条件。已查看桌面与 390px 手机截图，未发现横向溢出。日志为前端 `issue95-e2e.log`（既有流程）与 `issue95-search-e2e-final.log`（修正筛选控件标签后的搜索流程）。

## 代表性查询记录

测量时间：2026-10-28 13:50:54（Asia/Shanghai）。1000 篇文章，每篇正文 1324 字符；关键词“蓝鲸”，分类过滤，最新发布排序，200 条命中。每页 100 条，两页 ID 顺序均验证；每种模式预热一次，再记录 7 次完整 HTTP 查询（包括 count、装配与传输）。

| 模式 | HTTP 中位数 | HTTP 最大值 |
| --- | ---: | ---: |
| FULLTEXT | 39.72 ms | 51.84 ms |
| LIKE | 53.73 ms | 68.38 ms |

这是本机隔离样本的实测，不代表生产 SLA 或大数据量性能承诺。原始记录：`target/real-it-reports/search-benchmark.json`；日志：`target/search-real-it.log`。CI 的真实依赖 artifact 同样收集该 JSON。

## 发布顺序

先后端、后前端；新 API 保留原分页字段，增加可选查询参数和响应字段。无需数据迁移或新依赖。前端 CI 必须固定到本次后端实现提交。合并、生产部署与 issue 关闭仍按所有者审批和验收流程办理。
