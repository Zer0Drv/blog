# 新文章订阅（#20）

登录用户可订阅全站的新公开文章。站内通知和邮件订阅独立开关，存入 user 的通知偏好字段；GET /users/me/preferences 也返回 articleNotifyEnabled 和 articleEmailNotifyEnabled。现有 emailNotifyEnabled 继续控制评论邮件，不代替新文章邮件开关。

邮件订阅只发到当前账号邮箱，开启前须通过独立 article-subscription 场景的邮箱验证码。复用既有 60 秒发码限制、10 分钟有效期和 5 次失败作废策略；不接受客户端传入其他人的邮箱。OAuth 占位邮箱不能开启邮件订阅。未配置 SMTP 时按既有开发模式记录日志，不伪装为实际发送。

| 方法 | 路径 | 权限与行为 |
|---|---|---|
| GET | /subscriptions/me | 登录，返回 enabled/emailEnabled |
| PUT | /subscriptions/me | 登录，{enabled: boolean}；关闭时同时关闭邮件 |
| POST | /subscriptions/email-code | 登录，向账号邮箱发送确认码 |
| POST | /subscriptions/email-confirm | 登录，{code: 6位数字}，验码后开启邮件 |
| DELETE | /subscriptions/email | 登录，单独关闭邮件，保留站内订阅 |
| POST | /subscriptions/unsubscribe | 公开，{token: 32位随机令牌}，幂等关闭站内和邮件订阅 |
| GET | /admin/subscriptions/stats | ADMIN，订阅人数、待处理文章、站内投递、真实邮件、开发邮件及失败记录 |

退订令牌是随机能力令牌，只能关闭订阅，不能登录或读取账号资料。邮件链接进入公开确认页，GET 不执行退订；用户点击确认才发 POST（沿用全局 X-Requested-With 防护）。退订清除令牌，重新订阅生成新令牌，旧链接不能取消新的订阅。

文章模块通过 ArticlePublicationCandidate 契约事件，在公开文章 create/update/updateStatus 的事务内写 subscription_publication。队列故障使该保存事务回滚，SMTP 故障不影响文章保存。消费者异步轮询（默认 60 秒）；V14 把迁移前已上线文章标为 DONE，避免补发历史内容，把存量未来定时文章标为 PENDING。

发送前经 ArticleCatalog.findVisibleFeedEntry 统一检查公开可见性，发送每个收件人前再次复查。PRIVATE/PASSWORD/草稿/下架/逻辑删除不会发送；隐藏任务等再次公开事件后恢复排队。订阅时间晚于本次发布时间的用户不补发；编辑已到点文章不重置受众，完成的文章不因编辑/重新发布重发。导入等保留旧发布时间的首次发布，以入队时刻作为订阅受众截点。

publication 租约通过数据库条件更新竞争，10 分钟有效，处理批次与收件人时续租；失效租约可被其他实例接管，旧实例不能完成新实例任务。delivery 的 (article_id,user_id) 主键记录站内与邮件状态，站内失败、SMTP 失败最多重试 3 次；邮件重试沿用已成功的站内结果，避免重复通知。站内通知复用通知中心去重与 WS 推送。

邮件发送前原子预留 subscription_mail_budget 额度：每个自首次预留起算的 24h 窗口最多 10 次发送尝试，失败也占用额度；额度不足跳过该邮件，保留站内通知。SMTP 发送成功与数据库记录不是一个事务，进程若恰在二者之间退出，恢复后可能重复发邮件；该边界遵循至少一次投递，不承诺外部 SMTP 恰好一次。

配置：SUBSCRIPTION_WORKER_ENABLED=true、SUBSCRIPTION_POLL_MS=60000；邮件沿用 spring.mail.*，from 取 spring.mail.username。订阅偏好字段禁止被普通 updateById 写回，避免资料/改密等整行更新覆盖并发退订；订阅服务通过显式 wrapper 修改这些字段。

上线前应用 V14。排查时查看 subscription_publication（PENDING/RUNNING/HIDDEN/DONE）、subscription_delivery（状态/次数/发送时间）及 subscription_mail_budget。失败不会无限轰炸；管理员统计的 failed 为当前失败记录，emailsDev 表示日志兜底而非真实送达。
