# 亲邻云端接口契约与防失效基线

> 采集于 2026-11-11。**全部为无凭证探测**，不含账号、小区、门禁真实值。
> 目的：把「接口是否真实存在」从推测变成可重复验证的事实，上游改版时能第一时间发现。

## 1. 结论：可以拿到真实接口，且不需要 root 或抓包

三个互相印证的来源：

1. **自家 APK 真机实测**：真实短信登录成功、`sessionId` 落本机、读到小区列表与 2 扇门，
   并于 2026-11-11 **实测成功打开物理门** —— 主路径不是从文档猜的，是端到端跑通的。
2. **官方 APK 静态提取**：`edoor.apk`（v5.3.2，134MB）里 Flutter AOT 的 URL 仍是明文，
   共提取 **98 条规范化接口路径**，其中 37 条与开门 / 小区 / 登录相关。
3. **无凭证路由探测**：对 14 条候选路径发空 body + 无效 `sessionId`，
   **10 条 ALIVE（进入鉴权层）、4 条 404**。

由此确认：本 APK 当前用的 4 个接口**全部真实存在**，包括此前一直未现场验证的开门接口
`/api/open/doorcontrol/v2/open`（返回 `code:401 未登录`，说明路由有效）。

## 2. 当前使用的接口契约

| 用途 | 方法 | 路径 | 关键参数 | 无凭证响应 |
| --- | --- | --- | --- | --- |
| 短信验证码 | POST | `gateway2.qinlinkeji.com/member/sms/sendSecurityCode` | 加密手机号、`appid`、`sign` | **刻意不探测** |
| 登录 | POST | `/api/app/v1/login` | 加密手机号、验证码、`sign` | `HTTP 200` / `code:121` 签名相关 |
| 会话探活 | POST | `/api/app/v1/checkLogin` | 仅 URL query 的 `sessionId` + 标准签名 | 有效凭证 `HTTP 200` / `code:0` / `data:7776000`；无凭证或已超时 `code:401` |
| 小区列表 | POST | `/api/app/user/v2/communityInfo` | `timestamp`、`appChannel` 等 | `HTTP 200` / `code:401` |
| 门列表 | POST | `/api/app/user/v2/queryAllUserDoorByCache` | `communityId` | `HTTP 200` / `code:401` |
| 开门 | POST | `/api/open/doorcontrol/v2/open` | `appChannel`、`communityId`、`doorControlId` | `HTTP 200` / `code:401` |

BASE 为 `https://mobileapi3.qinlinkeji.com`。通用约定：

- `sessionId` 走 URL query；开门参数也在 query，POST 无 body。
- `sign` = 参数按 key 字典序拼 `k=v&...` + 后缀，MD5 转大写；普通后缀 `key=qiAnlPinP`，
  短信后缀 `appsecret=<APP_SECRET>`。
- 手机号 AES-128-ECB / PKCS7，十六进制大写；设备头 `qvendor/openid/qversioncode/...` **必须小写**。
- 具体常量与组包方式见 `src/com/wb/qinlin/QinlinApi.java`。

## 3. 官方 APK 里的路径形态（重要）

官方二进制里存的是**去掉前缀的片段**，例如 `/user/v2/communityInfo`、`/doorcontrol/v2/open`、
`/open/doorcontrol/openTheDoor`。**直接拿片段去请求会 404**，必须拼上 `/api/app` 或 `/api/open` 前缀。
这也解释了为什么静态清单里看不到 `/api/app/user/v2/communityInfo` 这样的完整路径。

官方 APK 中与开门 / 小区 / 登录相关的路径（37 条，节选全量）：

```
/api/app/v1/login                                   /api/app/v1/oneClick/login
/api/app/v2/oneClick/appLogin                       /api/app/v2/oneClick/bindAndLogin
/api/app/v1/appleLogin                              /api/app/v1/bindOpenId
/api/app/v1/userCommunity/getCommunity              /api/app/v1/userCommunity/getMyCommunityAccessInfo
/api/app/v1/userCommunity/getMyCommunityAccessInfoByCommunityId
/api/app/v1/userCommunity/getNeedRenewalApplyList
/api/app/v1/doorOpenApply/findRecordByUserId       /api/app/v1/doorOpenApply/getReApplyDetailByApplyId
/api/app/v1/doorOpenApply/cancelOpenDoorPermissionApply
/api/app/v1/doorOpenApply/countApplyRecordsWithStatusChange
/api/app/v1/doorOpenApply/deleteApplyRecord        /api/app/v1/doorOpenApply/findRecordDeatilByApplyId
/api/app/v1/visitorInvite/findUserPermission       /app/v1/housePrice/findCommunity
/doorcontrol/queryDoorControlInfo                   /doorcontrol/v1/cancelCustomName
/doorcontrol/v1/updateCustomName                    /doorcontrol/v2/open
/open/doorcontrol/openTheDoor                       /open/1
/user/v1/communityInfo                              /user/v1/bluetoothOpenDoorLog
/user/v1/checkHasAllPermission                      /user/v1/doorSort
/user/v1/hasFaceCameraByUserCommunity              /user/family/communityApplyList
/user/family/deleteUserAccess                      /user/v2/communityInfo
/user/v2/getUserCommunityExpiryStatus              /user/v2/queryAllUserDoorByCache
/user/v2/queryUserDoorByCacheNew                   /user/v2/removeDisableDoorControl
/user/v2/searchAllUserDoors
```

与「登录态保持」直接相关的另外几条线索（上表之外）：

```
app/v1/checkLogin                         app/v1/oneClick/appLogin
app/api/app/v2/oneClick/appLogin          _updateCacheSessionId
getSessionId                              CheckLoginRedirectMixin / checkLoginAndRedirect
```

- 无凭证实测：`/api/app/v1/checkLogin` → 200 / `code:401`「登录已超时」；
  `/api/app/v1/oneClick/appLogin` → 200 / `code:121` 签名时间戳不能为空；
  而 `/api/app/v2/oneClick/appLogin` → 404。即官方二进制里同时存在「片段」与
  「带 app/api 前缀」两种拼法，**必须以无凭证探测结果为准**，不能照抄片段。
- `oneClick/appLogin` 属于「一键 / 静默登录」入口，可能触发账号动作，**不纳入日常探测基线**，
  本 APK 也没有调用它（短信验证码登录模型拿不到运营商一键登录凭证）。

复现提取：解压 `edoor.apk`，对 `.so` / `assets/**` / `.xml` / `.json` 的字节流用
`/[A-Za-z0-9_.{}$/-]{5,110}` 扫描，保留以 `/api|/open|/doorcontrol|/user|/member|/app` 开头、
且不含 `.cc/.cpp/.h`、`/Users/`、`.so` 等噪音的串，去重即可。

## 4. 无凭证探测结果（2026-11-11）

`ALIVE` = HTTP 200 且返回业务错误码（说明路由存在、已进入鉴权/业务层）；
`GONE` = HTTP 404（Spring 未找到路由）。完整机器可读基线见 `docs/api-baseline.json`（15 条，
其中 `checkLogin` 为 2026-11-11 会话保活改造新增；`oneClick/appLogin` 按上节说明刻意不纳入）。

| 路径 | 角色 | HTTP | code | 状态 |
| --- | --- | --- | --- | --- |
| `/api/app/v1/login` | 主 | 200 | 121 | ALIVE |
| `/api/app/v1/checkLogin` | 探活 | 200 | 401 | ALIVE |
| `/api/app/user/v2/communityInfo` | 主 | 200 | 401 | ALIVE |
| `/api/app/user/v2/queryAllUserDoorByCache` | 主 | 200 | 401 | ALIVE |
| `/api/open/doorcontrol/v2/open` | 主 | 200 | 401 | ALIVE |
| `/api/doorcontrol/v2/open` | 备用 | 200 | 401 | ALIVE |
| `/api/open/doorcontrol/openTheDoor` | 备用 | 200 | 401 | ALIVE |
| `/api/app/user/v1/communityInfo` | 备用 | 200 | 401 | ALIVE |
| `/api/app/user/v2/queryUserDoorByCacheNew` | 备用 | 200 | 401 | ALIVE |
| `/api/app/user/v2/searchAllUserDoors` | 备用 | 200 | 401 | ALIVE |
| `/api/app/user/v1/bluetoothOpenDoorLog` | 备用 | 200 | 401 | ALIVE |
| `/api/app/doorcontrol/v2/open` | 对照 | 404 | 404 | GONE |
| `/api/user/v2/communityInfo` | 对照 | 404 | 404 | GONE |
| `/api/app/v1/userCommunity/getCommunity` | 对照 | 404 | 404 | GONE |
| `/api/app/doorcontrol/queryDoorControlInfo` | 对照 | 404 | 404 | GONE |

## 5. 备用路径与降级策略

| 用途 | 主路径 | 备选 1 | 备选 2 |
| --- | --- | --- | --- |
| 开门 | `/api/open/doorcontrol/v2/open` | `/api/doorcontrol/v2/open` | `/api/open/doorcontrol/openTheDoor` |
| 小区列表 | `/api/app/user/v2/communityInfo` | `/api/app/user/v1/communityInfo` | — |
| 门列表 | `/api/app/user/v2/queryAllUserDoorByCache` | `/api/app/user/v2/queryUserDoorByCacheNew` | `/api/app/user/v2/searchAllUserDoors` |

⚠️ **备用路径只证明路由存在，参数与响应结构未必兼容**。开门**不做自动降级**：误切可能造成
物理开门或重复指令。切换前必须人在门内、带实体钥匙兜底，逐条真机验证后再改代码。

## 6. 失效预警规则

`api-probe.py` 只比对 `state` 与 `code`，不比对 `message`（网关多实例下文案不稳定，
实测同一 `/api/app/v1/login` 先后返回「签名不能为空」与「签名算法版本不能为空」）。

| 变化 | 含义 | 动作 |
| --- | --- | --- |
| `ALIVE → GONE` (404) | 路由被下线 | 立刻按第 5 节验证备选路径 |
| `401 → 121` 或其它 | 签名 / 鉴权逻辑变更 | 检查 `sign` 后缀、请求头、必填字段 |
| `control` 路径 `GONE → ALIVE` | 上游新增了官方在用的路由 | 跟进新版协议，评估迁移 |
| 全部 `ERR` / 超时 | 域名、TLS 或网络策略变化 | 检查 DNS、证书、代理 |

退出码：`0` 一致、`2` 有变化（便于接自动化）、`1` 无基线或执行出错。

```bash
python api-probe.py                       # 日常：只测 4 个主路径
python api-probe.py --all                 # 官方 App 升级后：全量 14 条
python api-probe.py --all --save-baseline # 确认无误后更新基线
```

## 7. 为什么不能抓官方 App 的 HTTPS 明文

- 真机**未 root**，无法安装系统 CA 或注入 TLS 栈。
- 官方 `res/xml/network_security_config.xml` 的字符串池只有
  `base-config` / `cleartextTrafficPermitted` / `network-security-config`，
  **没有 `trust-anchors` / `user` / `certificates`** → 默认只信系统 CA，用户 CA（Fiddler 等）不生效。
- 带 `certificates src="user"` 的是 `bfmob_network_security_config.xml`，属于广告 SDK 自己的配置，
  不是主 App 的业务请求。
- 因此 MITM 抓明文这条路走不通；**静态提取 + 无凭证探测反而更稳定、零副作用**。

## 8. 维护流程（官方 App 每次升级后）

1. `adb pull` 新 APK，按第 3 节重新提取路径清单，与本文对照找出新增 / 消失的路由。
2. `python api-probe.py --all`，看退出码与差异列表。
3. 有变化 → 按第 5 节候选，真机在门内逐条验证（只读接口先行，开门最后）。
4. 确认新契约后更新 `QinlinApi.java` 与本文，再 `--all --save-baseline` 固化基线。

## 9. 边界与风险

- 探测**不带任何真实凭证**，不触发开门、不发送短信（短信接口刻意排除，避免真实短信 / 风控）。
- 「路由存在」≠「参数正确」≠「物理门打开」：本 APK 的 4 条主路径已由真机端到端验收通过
  （2026-11-11 实测开门成功）；此约束现在主要针对**备用路径**，切换后仍须重新现场验收。
- 协议常量来自公开项目，上游可随时变更或加风控。本基线提供的是**早发现 + 有备选**，
  不保证永不失效；保留官方 App 与实体钥匙作为最终兜底。

## 10. 登录态为什么会「自动被登出」：单点互踢与按需探活

### 证据链（2026-11-11）

- 网关鉴权失败统一返回 `code:401`「**您还没有登录，或者登录已超时，请重新登录！**」——
  **服务端会话本身有超时**，不是本机凭证被系统清掉。
- 旧实现里 4 个入口（主界面开门 / 启动静默补小区名 / 门列表 / 小部件开门）一遇 401 就
  `Prefs.clearSession()` 并切回登录表单，把「服务端票据超时」直接变成不可恢复的
  「本机被登出」，只能重新收短信；网关偶发误判、网络抖动也会造成同样后果。
- 旧实现完全没有后台活动：4 份 `widget_*_info.xml` 都是 `updatePeriodMillis=0`，
  Manifest 无 Service / Alarm / 定时任务，App 只在用户主动操作时发一次请求。
  会话长时间空闲 → 必然超时 → 下次一开门就被登出。
- 官方 APK（v5.3.2）静态提取到 `app/v1/checkLogin`、`_updateCacheSessionId`、
  `getSessionId`、`CheckLoginRedirectMixin`、`oneClick/appLogin` 等登录态机制，
  加上官方 App 的推送与后台活动，用户因此感知不到过期。

> ⚠️ **2026-11-12 修正**：上面「空闲超时 → 必然被登出」只是当时的推断。
> 10:21 互踢实验证实主因是服务端**按账号单会话**，官方 App 重新登录即作废旧凭证；
> 真机还观察到空闲 13h36m 成功、仅空闲 8h23m 反而 401，与空闲超时模型矛盾。

### 本版采取的策略（2026-11-12 10:40 定稿）

1. `/api/app/v1/checkLogin` 只做**按需**只读检查：冷启动 / 回前台（5 分钟防抖）、
   401 二次确认、点「重试连接」、点小部件开门前。**不设定时器、不注册后台服务**；
   4 份 `widget_*_info.xml` 一律 `updatePeriodMillis=0`。理由是服务端按账号单会话，
   后台轮询挡不住互踢，只会耗电。若 `checkLogin` 被业务拒绝，回退到已验收的
   `communityInfo` 只读接口兜底。
2. 业务请求收到 401 后强制再单独探活一次做**二次确认**：探活成功判定为网关误判，
   不改动登录态。
3. 确认失效也**只写 `session_expired` 标志，绝不删 `sessionId`**；登录表单出现
   「重试连接」，探活成功即自动恢复登录态。只有主动点「退出登录」才真清凭证。
4. 登录时把完整 `data` 留在本机（字段名进日志）——实测只有 `flag` + `sessionId`，
   **没有长效凭证 / refresh 字段**，短信登录模型无法静默重登。

> ✅ **2026-11-12 10:40 已落地**：单点互踢证实后，30 分钟周期轮询与 4 份小部件
> `updatePeriodMillis=1800000` 已全部移除，`SessionGuard` 改为按需探活（前台 5 分钟防抖），
> `DEV-DIAG` 诊断入口也已整体移除。第 2/3 条（401 二次确认、凭证保留、重试连接）
> 已端到端验证通过，继续保留。本轮只改源码 + 全量编译，**未打包、未覆盖安装**。

### 假设验证进度（2026-11-12 10:11 真机实测，详见 DEVLOG 同日 10:07 段）

| 假设 | 实测结果 | 结论 |
| --- | --- | --- |
| 服务端是「空闲滑动续期」 | 11-10 空闲 13h36m 仍成功、11-11 只空闲 8h23m 反而 401；`checkLogin` 三次都返回固定 `data:7776000` | **未定论**。「约 48h 绝对 TTL」需重新审视：若 7776000 是 90 天秒数，11-11 的 401 更像单点互踢 / 服务端会话重置 |
| `checkLogin` 在有效 session 下返回 code 0 并刷新 TTL | 有效凭证下 `{"code":0,"data":7776000,"message":"操作成功"}`（HTTP 200 / 292ms），接口可用、无需回退 | **code 0 已证实**；是否刷新 TTL 未证实 |
| 业务响应顶层会下发新 `sessionId`（官方 `_updateCacheSessionId` 线索） | `checkLogin` 响应只有 `code/data/message`，无 `sessionId` | **不轮换票据**，该假设排除 |
| 登录响应含长效 token / refresh 字段 | `{"flag":1,"sessionId":"***"}` | **不含**，短信登录模型无法静默重登 |
| 官方 App 与本 APK 并存会单点互踢 | 10:18:56 `code:0` → 官方 App 微信 OAuth 重登 10:20:50 → 10:21:39 同一凭证 `code:401` | **已证实**：服务端按账号单会话，跨登录渠道也互踢，新登录即作废旧凭证 |
| 30 分钟探活能阻止掉线 | 互踢已证实；无论 TTL 绝对还是滑动，新会话创建都会让旧凭证失效 | **不能，已移除**（10:40 改为按需探活） |
| 401 后不丢凭证 + 重试连接可恢复 | `sim401` → 二次确认判为误判 → 登录态保留；`mark_expired` → 凭证仍在；`retry` → 自动恢复同一凭证；互踢后真实失效下 `retry` 仍 `401`、凭证保留且不误恢复 | **全链路通过** |

### 真机验证清单（进度）

1. ~~重新登录看响应字段~~ → 已由 11-12 09:29 导出日志确认：只有 `flag` + `sessionId`。
2. ~~探活是否真的在跑~~ → 已确认：`state` 显示 `lastAlive=lastTry=09:47:55`，首次启动即探活成功，
   之后落在 30 分钟节流窗口内；`probe` 强制探活也成功。上一轮「探活未触发」为误判。
3. ~~401 → 二次确认 → 保留凭证 → 重试恢复~~ → 已用 `sim401` + `mark_expired` + `retry` 全链路验证通过
   （当前凭证仍有效，「服务端已彻底过期后重新短信登录」这一分支待自然过期或互踢实验后再验）。
4. ~~官方 App 重登 → 本 APK 立刻只读请求~~ → 10:21 已做，`code:401`，**单点互踢证实**；
   顺带在真实失效下验证了 `retry` 不会误恢复、凭证不丢。
5. ~~静置观察 ~30 分钟探活与耗电~~ → 结论已定：互踢挡不住，周期轮询已移除，无需再观察。
6. ~~移除周期轮询、改为按需探活~~ → 10:40 完成，**10:57 已打包并覆盖安装**
   （见 DEVLOG 同日 10:57 段）；dex 校验确认 `diag_action` / `widget-keepalive` 已消失、
   `probeOnForegroundAsync` / `probeIfStale` 已生效，4 份 xml 周期值均为 0。
7. ~~新包覆盖安装与冒烟回归~~ → 10:57:30 完成：数据完整保留、无崩溃、新文案生效；
   冷启动按需探活发**一次** `checkLogin` → 服务端仍 `401` →「只标记过期，本机凭证保留」，
   与方案 B 设计一致。剩余：重新短信登录后的开门 / 401 链路、前台 5 分钟防抖、
   耗电对比（需用户配合收验证码）。
8. 待做（可选）：自制 APK 重新短信登录后反向观察官方 App 是否被踢（按单会话推测会）。

### 状态更新（2026-11-13）

上表第 7 条「剩余：重新短信登录后的开门 / 401 链路、前台 5 分钟防抖、耗电对比」
已有结论：11:03 用户重新短信登录后登录态恢复；11:34 冷启动 `前台探活判定`
显示超窗发起 `checkLogin` 并收到 `code:0`、命中防抖时跳过，防抖链路已真机验证；
11:33 覆盖安装后登录态与数据完整保留。**仍未验证**：新包下物理开门复测、耗电对比、
反向互踢。

10:40 段的「本轮只改源码 + 全量编译，未打包、未覆盖安装」是当时状态，后续已于
10:57 / 11:33 两次覆盖安装，11:45 推送 GitHub（commit `861aaa1`）。
完整时间线与逐条勘误见 `DEVLOG.md` 末尾「2026-11-13 状态勘误」。
