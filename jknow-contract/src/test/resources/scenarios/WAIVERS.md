# 挂账清单（WAIVERS）

> waiver 语义：命中的步骤在 Diff（`--left --right` 目录比对——jknow 对表为 golden-rust 权威面
> vs jknow 实录；`--dual` 双打形态兼容保留）与 Replayer 中
> **跳过比对并单列报告（[WAIVE]）——不算差异也不算通过**，显式挂账。
> 键写法等价形都可（`admin-users/tokens-list` / `admin-users/tokens-list.json` /
> `golden/admin-users/tokens-list.json`），装载器统一归一化；族目录单族比对时键无族前缀，
> 全树比对时键含 `<族>/<步>`——装载器不做前缀裁剪，按比对形态给键。
> 装载器（Waivers.load）一行一键：生效清单每行第一格=单个键，同组多键请拆多行共用原因码。
> 临时挂账可不走本清单：`--waive <键>#<原因码>` 可重复参数（与 `--waivers` 本清单叠加，命令行优先）。
> 每条挂账必须有原因码与说明；核销（转回正常比对）时删行并在当期任务报告记录。

## 生效清单

P3 golden 复活批 T2（2026-11-23）新增八组挂账——全部是「jknow 实录实测逐步定形」的产物，
键为 golden 权威面（golden-rust）侧遗留步（剧本已删步，Diff 时按「仅左侧存在」命中）；
装载器按键单行装载（Waivers.load 一行一键），同组多行共用原因码。

P3 golden 复活批 T3（2026-11-23）全量对表新增 107 行（11 组原因码一次落账，全树
`--left golden-rust --right <实录> --waivers` 形态）——首轮 Diff 108 差异逐例归因后除
health 残面（version 0.1.0→1.0.0，权威面文件随本轮修正为 jknow 产品版本，不挂账）外全部
显式挂账：62 行 `authority-java-side-absent`（权威面缺步：源仓录制期 target:java 单侧样本
不进权威面——documents 14/review 23/sources-ingest 13/workspace-kb 9/chat-misc 3，
T2 ontology import 两步同码扩面）＋5 行 `heart-skeleton-surface`（heart 快照骨架：权威面
未随迁 heart 快照，无左侧对应文件）＋24 行预期结构签名（`fake-200-ruoyi-entry` 10 +
`admin-shape-rewritten` 14，T2 §3/§1 处置的对表落账）＋16 行环境/身份/投影面
（`env-user-set` 1、`demo-seed-visible` 2、`identity-account-naming` 8、
`role-projection-precedence` 1、`env-workspace-set` 1、`time-window-relative` 1、
`push-chain-evidence-gap` 2）。终审收尾（同日）补挂 2 行 `pat-surface-retired`（见生效清单
末尾）——生效清单 T3 后合计 192 行（T2 83 行中 12 行为休眠键：
权威面无对应文件、实录亦不产该步，留引擎扩展后核销对账）。

安全注记（T3/运维知会）：`multi-user-single-token` 组的预建用户 `contract_viewer`（sys_user 固定
utopia_uuid `0190d3e0-0000-7d5a-8000-00000000cafe`，role 2，dept 103）口令串**复用 admin 的
bcrypt**——即演示库第二个已知口令（admin123）的账号，仅作剧本 sql 步预建装置；viewer 登录面
已挂账退役，改随机口令不影响现有保留步，如需收敛请改口令并同步 graph/workspace-kb 剧本模板。

| 步骤 | 原因码 | 说明 |
|---|---|---|
| auth-login/login | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth-login/me | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/oidc-status | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/oidc-start | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/oidc-callback | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/oidc-exchange | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/login-wrong-password | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/register-duplicate | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/register-bad-email | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/register-short-password | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/register-missing-display-name | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/login | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/me | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/me-patch | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/me-patch-empty-name | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/password-wrong-current | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/password-set-temp | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/password-revert | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/logout | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/me-after-logout | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/oidc-me | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| auth/oidc-me-unlink | auth-retired | /api/v1/auth/* 域 jknow 设计性退役（P1 源仓口径），路由不存在语义不可录；未认证探测的假 200 实形由 auth.yaml 保留扫描步承载。恢复路径=引擎/业务域实装后核销。 |
| graph/viewer-kb-hidden-404 | multi-user-single-token | 引擎单 token 模型：bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），步级覆盖落空，第二用户身份不可达；viewer 本体已由剧本 sql 步预建（contract_viewer 固定 utopia_uuid+admin bcrypt+role 2），成员矩阵步以 admin 身份保留。 |
| graph/viewer-overview-ok | multi-user-single-token | 引擎单 token 模型：bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），步级覆盖落空，第二用户身份不可达；viewer 本体已由剧本 sql 步预建（contract_viewer 固定 utopia_uuid+admin bcrypt+role 2），成员矩阵步以 admin 身份保留。 |
| graph/viewer-entity-type-write-403 | multi-user-single-token | 引擎单 token 模型：bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），步级覆盖落空，第二用户身份不可达；viewer 本体已由剧本 sql 步预建（contract_viewer 固定 utopia_uuid+admin bcrypt+role 2），成员矩阵步以 admin 身份保留。 |
| graph/viewer-rebuild-403 | multi-user-single-token | 引擎单 token 模型：bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），步级覆盖落空，第二用户身份不可达；viewer 本体已由剧本 sql 步预建（contract_viewer 固定 utopia_uuid+admin bcrypt+role 2），成员矩阵步以 admin 身份保留。 |
| workspace-kb/viewer-kb-hidden | multi-user-single-token | 引擎单 token 模型：bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），步级覆盖落空，第二用户身份不可达；viewer 本体已由剧本 sql 步预建（contract_viewer 固定 utopia_uuid+admin bcrypt+role 2），成员矩阵步以 admin 身份保留。 |
| workspace-kb/viewer-read-ok | multi-user-single-token | 引擎单 token 模型：bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），步级覆盖落空，第二用户身份不可达；viewer 本体已由剧本 sql 步预建（contract_viewer 固定 utopia_uuid+admin bcrypt+role 2），成员矩阵步以 admin 身份保留。 |
| workspace-kb/viewer-write-forbidden | multi-user-single-token | 引擎单 token 模型：bearer 恒占第一个 Authorization 头（服务端 getHeader 取第一个，实录已证），步级覆盖落空，第二用户身份不可达；viewer 本体已由剧本 sql 步预建（contract_viewer 固定 utopia_uuid+admin bcrypt+role 2），成员矩阵步以 admin 身份保留。 |
| sources-ingest/push-created | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/push-unchanged | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/push-updated | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/push-stale-token-401 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/push-bogus-token-401 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/statements-push-created | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/statements-push-unchanged | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/statements-push-unknown-key | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| sources-ingest/statements-wrong-kind-404 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| chat-misc/mcp-initialize | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| chat-misc/mcp-tools-list | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| chat-misc/mcp-unknown-method | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-ledger/push-statements | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-axioms/push-obs-1 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-axioms/push-obs-2 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-errata/push-obs-1 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-errata/errata-retract-applied | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-merge/push-obs-1 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-merge/review-fixture | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-merge/review-decide-merge | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-replay/push-obs-1 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-replay/push-obs-2 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-replay/push-obs-3 | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-replay/find-obs-3-doc | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-replay/delete-obs-3-doc | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| heart-replay/restore-obs-3-doc | step-auth-override-dead | 来源密钥/个人令牌走 Authorization 头（getHeader 取第一个），引擎 bearer 恒占第一个头、步级 token 落空（双头 401/单头 200 实录已证）；heart 推送的下游步因输入失供一并退役——heart 五剧本退化为装置+快照骨架。 |
| chat-misc/kb-events-401 | sse-unauth-single-token | SSE 未认证 401 探测在 bearer 会话下不可录（引擎无去会话机制；认证态打 SSE 会挂流到超时）；认证后流 deferred 原口径不变（COVERAGE.md D1-D3）。 |
| chat-misc/alerts-events-401 | sse-unauth-single-token | SSE 未认证 401 探测在 bearer 会话下不可录（引擎无去会话机制；认证态打 SSE 会挂流到超时）；认证后流 deferred 原口径不变（COVERAGE.md D1-D3）。 |
| chat-misc/conversation-stream-401 | sse-unauth-single-token | SSE 未认证 401 探测在 bearer 会话下不可录（引擎无去会话机制；认证态打 SSE 会挂流到超时）；认证后流 deferred 原口径不变（COVERAGE.md D1-D3）。 |
| admin-users/tokens-list | admin-pat-ratchet | ruoyi_login 后剧本身份是共享 admin：PAT 行只打戳不删行（源仓 pat-ratchet 同款棘轮），行数无法按剧本构造钉死；单 token 模型下不能以第二身份录。引擎身份扩展后以专用 PAT 用户恢复。 |
| admin-users/token-issue | admin-pat-ratchet | ruoyi_login 后剧本身份是共享 admin：PAT 行只打戳不删行（源仓 pat-ratchet 同款棘轮），行数无法按剧本构造钉死；单 token 模型下不能以第二身份录。引擎身份扩展后以专用 PAT 用户恢复。 |
| admin-users/tokens-list-after-issue | admin-pat-ratchet | ruoyi_login 后剧本身份是共享 admin：PAT 行只打戳不删行（源仓 pat-ratchet 同款棘轮），行数无法按剧本构造钉死；单 token 模型下不能以第二身份录。引擎身份扩展后以专用 PAT 用户恢复。 |
| admin-users/token-revoke | admin-pat-ratchet | ruoyi_login 后剧本身份是共享 admin：PAT 行只打戳不删行（源仓 pat-ratchet 同款棘轮），行数无法按剧本构造钉死；单 token 模型下不能以第二身份录。引擎身份扩展后以专用 PAT 用户恢复。 |
| admin-users/token-revoke-again-404 | admin-pat-ratchet | ruoyi_login 后剧本身份是共享 admin：PAT 行只打戳不删行（源仓 pat-ratchet 同款棘轮），行数无法按剧本构造钉死；单 token 模型下不能以第二身份录。引擎身份扩展后以专用 PAT 用户恢复。 |
| admin-users/admin-ds-create-forbidden | admin-ds-create-writes | admin 身份下是真建源（200，每轮写一行 data_sources，剧本无清理步）——保录制幂等不录；建源/删源实形 200/{"ok":true} 已探针验证，挂载 happy 轮一并定形（COVERAGE.md D11 同源）。 |
| graph/owner-login-back | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| graph/owner-login-final | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| graph/viewer-login | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| graph/viewer-login-again | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| graph/viewer-me | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| workspace-kb/viewer-login-first | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| workspace-kb/viewer-me | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| workspace-kb/owner-login-back | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| workspace-kb/viewer-login-second | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| workspace-kb/owner-login-back-second | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| chat-misc/login | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| heart-axioms/login | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| heart-errata/login | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| heart-ledger/login | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| heart-merge/login | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |
| heart-replay/login | auth-retired | 剧本内 /api/v1/auth/login|me 会话步随源仓 auth 域退役（jknow 无路由），setup 换 ruoyi_login 后由引擎承担登录；权威面遗留样本挂账。 |

| documents/jobs-requeue-all-forbidden | admin-requeue-all-writes | admin 身份下 POST /api/v1/jobs/requeue 是真写（探针实录 200 {"requeued":47}——全实例 job 重排队），403 语义随 admin 失效；为保录制幂等与演示库态不录。 |
| ontology/import-preview | authority-java-side-absent | 权威树（golden-rust）缺该步（源仓录制期它是 target:java 单侧样本，只进 golden-java 不进权威面）；jknow 侧实录 200（multipart 预览 plan），留作右侧新增。T3 若启用 golden-java 合并对照可核销。 |
| ontology/import-apply | authority-java-side-absent | 同上（落库回执 200 {import_id,...}）。 |
| chat-misc/chat-stop-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| chat-misc/conversation-delete-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| chat-misc/conversation-rename-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-delete-again | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-delete | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-extract | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-purge-conflict | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-purge | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-reprocess | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-restore-conflict | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-restore-purged-409 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/doc-restore | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/retry-failed | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/upload-duplicate | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/upload-empty-file | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/upload-zero-kb-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| documents/upload | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/alignment-kind-word-decide | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/alignment-phrase-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/alignment-rule-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/conflict-resolve-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/consistency-check | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/errata-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/fact-close-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/fact-confirm-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/fact-reject-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/inference-run | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/kb-create | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/merge-revert-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-agent-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-batch-empty-422 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-batch-unknown-ids | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-decide-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-defect-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-mapping-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-pending-decide-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-violation-bad-resolution-422 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/review-violation-unknown-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/ws-create | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| review/ws-delete | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/api-create | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/api-rotate-token | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/folder-create | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/folder-delete-again-404 | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/folder-delete | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/folder-patch | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/folder-re-extract | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/folder-sync | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/kb-ingest-created | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/kb-ingest-empty-filename | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/kb-ingest-unchanged | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/missing-cleanup | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| sources-ingest/statements-create | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/kb-create-second | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/kb-create-with-packs | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/kb-create | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/kb-delete-default-refused | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/kb-delete-second | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/kb-patch | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/ws-create | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/ws-delete | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| workspace-kb/ws-rename | authority-java-side-absent | 权威面缺步：源仓录制期该步为 target:java 单侧样本只进 golden-java 不进权威面；jknow 实录（target=both）为右侧新增证据，T2 ontology import 两步同码——启用 golden-java 合并对照时可核销 |
| heart-axioms/axioms-snapshot.snapshot | heart-skeleton-surface | heart 快照骨架面：引擎单 token 模型推送链退役（step-auth-override-dead 连锁），jknow 侧为装置+空库骨架快照；权威面未随迁 heart 快照样本（无左侧对应文件），0054 陈述信封（e/s/n）证据缺口留引擎步级鉴权扩展后重录 |
| heart-errata/errata-snapshot.snapshot | heart-skeleton-surface | heart 快照骨架面：引擎单 token 模型推送链退役（step-auth-override-dead 连锁），jknow 侧为装置+空库骨架快照；权威面未随迁 heart 快照样本（无左侧对应文件），0054 陈述信封（e/s/n）证据缺口留引擎步级鉴权扩展后重录 |
| heart-ledger/ledger-snapshot.snapshot | heart-skeleton-surface | heart 快照骨架面：引擎单 token 模型推送链退役（step-auth-override-dead 连锁），jknow 侧为装置+空库骨架快照；权威面未随迁 heart 快照样本（无左侧对应文件），0054 陈述信封（e/s/n）证据缺口留引擎步级鉴权扩展后重录 |
| heart-merge/merge-snapshot.snapshot | heart-skeleton-surface | heart 快照骨架面：引擎单 token 模型推送链退役（step-auth-override-dead 连锁），jknow 侧为装置+空库骨架快照；权威面未随迁 heart 快照样本（无左侧对应文件），0054 陈述信封（e/s/n）证据缺口留引擎步级鉴权扩展后重录 |
| heart-replay/replay-snapshot.snapshot | heart-skeleton-surface | heart 快照骨架面：引擎单 token 模型推送链退役（step-auth-override-dead 连锁），jknow 侧为装置+空库骨架快照；权威面未随迁 heart 快照样本（无左侧对应文件），0054 陈述信封（e/s/n）证据缺口留引擎步级鉴权扩展后重录 |
| auth/alerts-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/jobs-noop-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/kb-export-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/kb-mcp-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/kb-readiness-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/kb-search-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/me-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/ontology-packs-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/users-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| auth/workspaces-401 | fake-200-ruoyi-entry | 未认证扫描步的若依假 200 形：jknow 走若依认证入口点（HTTP 200+{msg,code:401}），权威面为源仓 401 形——T2 §3 (a) 处置的预期结构签名（剧本 expect 已按实形改写），非回归 |
| admin-users/admin-create-user-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-deactivate-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-deployment-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-deployment-put-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-ds-delete-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-ds-grant-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-ds-grants-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-ds-list-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-ds-revoke-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-ds-test-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-oidc-identities-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-oidc-unbind-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/admin-reactivate-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/users-deactivated-forbidden | admin-shape-rewritten | admin 面 403 实形改写签名：jknow ruoyi admin 是真 admin（可读面 200/未实装写端点 500/不存在路由 404），权威面为源仓非 admin 合同账号 403 形——T2 admin 实形处置的预期签名 |
| admin-users/users-list | env-user-set | 组织用户集环境差（源仓先例码）：jknow 组织挂若依 admin+seed 用户+contract 预建账号，权威面为源仓共享实例合同账号集——账号集随环境漂移，M1 任务 5 同款豁免。 |
| alerts/alerts-page | demo-seed-visible | 演示态环境面：jknow 剧本身份 admin 名下可见 seed 演示库的 3 条待审勘误告警（审查队列 3 待审的告警投影），权威面为源仓合同账号干净态——身份+seed 态差，非契约回归 |
| alerts/alerts-unread | demo-seed-visible | 演示态环境面：jknow 剧本身份 admin 名下可见 seed 演示库的 3 条待审勘误告警（审查队列 3 待审的告警投影），权威面为源仓合同账号干净态——身份+seed 态差，非契约回归 |
| ontology/imports-list-after-apply | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/kb-audit | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/kb-members-after-remove | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/kb-members-initial | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/kb-members-two | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/my-kbs | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/ws-members-initial | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/ws-members-two | identity-account-naming | 账号/成员显示名身份差：jknow 剧本身份是若依 admin（nick 若依）+ 共享预建 viewer（graph 族先录、nick Contract Graph Viewer），权威面为源仓 per 族合同账号（Contract Wskb/Wskb Viewer 等）——T2 §8-3 身份差类的落地口径 |
| workspace-kb/kb-get | role-projection-precedence | my_role 投影优先级差：权威面（源仓）全局 admin 优先投影 admin，jknow 按 kb_members 成员角色投影 owner（建库者语义更精确）——语义投影分歧非回归。 |
| workspace-kb/workspaces-list-before | env-workspace-set | 组织工作区集环境差：权威面为源仓共享实例残留（Default Workspace+上轮合同工作区），jknow 为 seed 演示工作区（剧本 list 步先于自建工作区）——列表值随环境漂移。 |
| review/review-summary | time-window-relative | decided.daily 为 14 天滚动窗口锚定录制日（首日=录制日-13）：权威面锚 2026-10-30、jknow 锚 2026-11-10，窗口内计数双侧同形（全 0）——时间相对面非契约差。 |
| sources-ingest/api-runs | push-chain-evidence-gap | 推送退役下游证据缺口：api 源 0 推送（step-auth-override-dead 连锁）→ runs 空/doc_count 0，权威面 3 行运行史/1 文档为源仓推送时代存照——引擎步级鉴权扩展重录后核销 |
| sources-ingest/sources-after-delete | push-chain-evidence-gap | 推送退役下游证据缺口：api 源 0 推送（step-auth-override-dead 连锁）→ runs 空/doc_count 0，权威面 3 行运行史/1 文档为源仓推送时代存照——引擎步级鉴权扩展重录后核销 |
| chat-misc/pat-issue | pat-surface-retired | PAT 面属源仓 token 装置（个人令牌现发，target=rust 存照步）：jknow 无对应路由/剧本步（T2 chat-misc 适配已删），终审收尾对单侧存照中两个无主键（pat-issue/pat-revoke）补挂——引擎 PAT 域实装后以专用载体恢复。 |
| chat-misc/pat-revoke | pat-surface-retired | PAT 面属源仓 token 装置（个人令牌撤销，target=rust 存照步）：jknow 无对应路由/剧本步（T2 chat-misc 适配已删），终审收尾对单侧存照中两个无主键（pat-issue/pat-revoke）补挂——引擎 PAT 域实装后以专用载体恢复。 |

（历史：M5 任务 6 挂账清零——pat-ratchet 2 条与 fulltext-store-ownership 1 条均已核销，见下方已核销节。）

## 已核销

| 步骤 | 原因码 | 核销记录 |
|---|---|---|
| admin-users/tokens-list、admin-users/tokens-list-after-issue（两步一行记） | pat-ratchet | M5 任务 6 核销（固定行数处置——剧本改造）：注册体带 `${run_id}` 唯一后缀，每轮全新账号——tokens-list 行数由剧本构造钉死为 0、tokens-list-after-issue 钉死为 1（本轮 issue 的那一枚），全量 body 亦随之确定（掩码前缀在同轮两实现读同一行）。棘轮的来源（固定账号逐轮累积 issue 行，撤销只打戳不删行——tokens.rs:130 语义不变）不再进入录制面。2026-11-13 重录双侧逐键一致，恢复常规比对。 |
| search/search-known-term | fulltext-store-ownership-m2 | M5 任务 6 核销（切流演练重定位）：ingest-doc 与 search-known-term 均转 target:java 单侧验证（wait 步等待 job 静默替代固定 pause）。java 轮（Rust 停机 + Java worker 持写路径）ES 索引自持建成，该步**真命中**（golden-java 样本含命中行）；缺省双打轮 Java worker 关（共享 job 由 Rust 消费进 Tantivy 不进 ES）该步为空集样本——单侧步不参与双打比对。search-no-match 维持 both 正常比对（双侧空集语义等价）。ES 自持写路径证据=2026-11-13 全 19 剧本 java 轮绿 + Diff 0 差异。 |
| workspace-kb/kb-export | rdf-serializer-m1-deferred | M5 任务 1 核销：Java 侧 RDF 导出落地（ExportController + ExportStore 八族分页 + RdfEmitter——Jena StreamRDF 对等 oxrdfio；两道流前预检/四拒导码/审计先行/REPEATABLE READ 单连接流式；rdf.rs 29 内联测试原值移植全绿）。kb-export 步重录双侧真导出 200；**比对口径升级为三元组集归一**（Normalizer.triples：Turtle/JSON-LD 解析回 Model 落排序 N-Triples 行，空白节点按排序后首现重编——Jena 与 oxrdfio 序列化空白/前缀展开顺序不同，字节比必假红），方法记录在剧本 kb-export 步注释。恢复常规比对（2026-11-13 重录 Diff 0 差异）。 |
| auth/oidc-status、oidc-start、oidc-callback、oidc-me、oidc-me-unlink（5 步一行记） | oidc-m1-out-of-scope | M5 任务 2 核销：Java 侧 OIDC 域 8 端点落地（oidc/OidcController——status/start/callback/me/unlink + exchange（0066 受信 audience 换会话，oidc_exchanges 一次性）+ admin identities GET/DELETE；OidcConfig 六环境变量/DiscoveryCache 600s+kid 强刷 60s 冷却/FlowService PKCE S256+nonce+state ct_eq+一次性消费/TokenVerifier RS256 手写验签与 claims 矩阵；SessionFilter 公开四端点精确枚举，me/unlink/管理面回归常规鉴权；AdminUsersController 的 422 占位桩退役）。未配置 IdP 现状双侧同形：status enabled=false、start/callback 303 回跳、me 过会话 enabled=false+linked=false、unlink 422 SSO is not configured；exchange 未配受信表 404（本就不挂账）。auth.yaml 重录（2026-11-13）OIDC 5 步双侧 200/303/422 逐键一致，恢复常规比对。 |

| heart-ledger/ws-create、heart-ledger/kb-create | ws-kb-write-java-deferred | M3 任务 11 核销（条目退役）：heart 系五剧本的 ws/kb 固定装置整体改 **SQL 步**（kb-fixture：工作空间/kb/kb_members 一事务直插，两轮同串执行）——java 轮 Rust 参照实例停机（共享队列双 worker 会抢 job，Java 须消费自己的抽取/物化链，切流预演口径），剧本不再含 target:both 装置步，两个挂账键随步骤一起退役。POST /workspaces 的 Java 实现仍属 M4+（真正核销 ws 写面时另行记录）；快照证据（--writes 两轮 Diff 0 差异）不依赖该端点。 |


| 步骤 | 原因码 | 核销记录 |
|---|---|---|
| ontology/uniqueness-candidates、proposals-stored-empty、questions-list、question-report、imports-list-empty、imports-list-after-apply、rules-list、rule-matches、rule-versions | ontology-read-family-m1-deferred | M4 任务 6 核销（九步一行记）：本体扩展读族九端点落地（OntologyReadFamilyController——uniqueness_candidates 聚合（M3 探测 SQL 接 HTTP，side→axiom 折 functional/inverse_functional）/open_proposals 只摆 agent 提的（四节空数组起步，payload+proposed_by+serves+signatures）/competency_questions 12 列列表+报告聚合（questions 六格 FILTER+uncovered+提案四格与 changed_share 空判 null）/list_imports 近 50/attribute_rules list+matches（per 缺省 20 钳 [1,100]）+versions（labels 按现在查得到的给））。ontology.yaml 重录双打（2026-11-13）九步双侧 200 一致，恢复常规比对。 |
| graph/entity-history-unknown | entity-family-m1-deferred | M3 任务 6 核销：实体历史端点 GET /kbs/{id}/entities/{entity_id}/history（graph_routes.rs:559-571 handler + graph.rs:2232-2423 entity_history SQL——SQL 任务 4 已落 GraphMapper.selectEntityHistory，本任务接 HTTP 路由：per 钳 [1,200]/page≥0/total 含合并撤销三支）。graph.yaml 重录（--writes 缺省双打，2026-11-12）双侧 200 一致，恢复常规比对。 |
| graph/derived-proof-unknown | entity-family-m1-deferred | M3 任务 6 核销：证明链端点 GET /kbs/{id}/derived/{derived_id}/proof（graph_routes.rs:463-473 + reasoning.rs:2802-2812 proof——premise_steps 按 MAX_DEPTH 递归展开/derived_step 读派生步/evidence 逐条）。Rust 200 `{"proof":null}` 与 Java 同形，重录双侧一致。 |
| graph/blocked-proof-unknown | entity-family-m1-deferred | M3 任务 6 核销：幽灵证明链端点 GET /kbs/{id}/violations/{violation_id}/proof（graph_routes.rs:475-483 + reasoning.rs:3066-3083 blocked_proof——前提在违规 path 里，steps_for 展开）。Rust 200 `{"steps":null}` 与 Java 同形，重录双侧一致。 |
| heart-ledger/ledger-snapshot.snapshot | statements-doc-key-order-m2 | M3 任务 1 审查收口核销：StatementsContract.reserialize 改 BTreeMap 字典序输出（doc_time < e < external_id < n < s，与 serde_json 无 preserve_order 的 to_string_pretty 字节对齐）+ SourcesTests.STATEMENTS_STORED 断言同步修正；重录双轮（--writes rust / --writes java）后 Diff 快照步双侧一致（2026-11-11）。挂账生命周期：M3 任务 1 快照器首抓获→审查三重取证坐实→本提交修复核销，全程不到一天——锁 4 尺子价值的直接证据。 |
| sources-ingest/sources-empty | sources-read-family-m1-deferred | M2 任务 4 核销：来源域读面（GET /kbs/{id}/sources 列表——sources.rs:83-143 list 逐字：doc_count/missing_count 子查询 + RSS LATERAL 整块 + config 剔凭据键）随 SourcesController 落地。写步已转 target:java，读步恢复双打常规比对（2026-11-11 重录双侧 200 一致）。 |
| sources-ingest/sources-list-three | sources-read-family-m1-deferred | 同上（三来源列表，SourceView 13 键投影与 Rust serde 字段集一致）。 |
| sources-ingest/folder-token-kind-mismatch-404 | sources-read-family-m1-deferred | 同上（folder 无密钥 → has_push_token 判 false → 404；语义验证不再是偶然同形）。 |
| sources-ingest/api-token | sources-read-family-m1-deferred | 同上（Editor 专用密钥视图端点；ingest_token 经 Normalizer 掩码，跨侧恒等）。 |
| sources-ingest/api-runs | sources-read-family-m1-deferred | 同上（来源运行历史 runs.rs 投影，每源 50 条修剪在 finish_run 收尾）。 |
| sources-ingest/sources-after-delete | sources-read-family-m1-deferred | 同上（删源后列表；documents.source_id FK SET NULL 语义由库约束担保）。 |
| documents/doc-content | d9-upload-no-version-ledger | M2 任务 3 核销：documents 剧本写步转 target:java 时**消除 D9**——Java 上传路径按 create_with_version_and_processing 形状写 document_versions 版本 1 + 同事务入队（DocumentsWriteService.createWithVersion）；双实现共享同一库与 data_dir，读步（both）两侧看到的台账行与 blob 一致，content 由 500 转 200（2026-11-11 重录 documents.yaml 双侧 200，Diff 全绿）。参照实例自身上传路径若仍走 create_from_upload，其**自有**录制（upload 步 target:rust 的形态）才会复现 500——该形态自本任务起不再出现在剧本里。原「参照实例修复后核销」条件由「Java 侧消除 + 写步转移」提前满足。 |
| admin-users/users-list | env-user-set | M1 任务 5 核销：Java 实现 GET /users（members.rs:22-31 org_users SQL 逐字）后做形状专项比对——2026-11-11 对共享库同账号集双侧实测（Java 17001 / Rust 1516 同请求归一化逐键相等，含行序）；行结构 = OrgUser 四键 {id,email,display_name,is_admin}、ORDER BY created_at，与 Rust golden（admin-users/users-list.json）字段集一致。原豁免理由（环境账号集噪声，值差异豁免）随双打转正由 Diff 常规比对承担。 |
