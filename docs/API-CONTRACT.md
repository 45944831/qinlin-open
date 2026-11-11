# 亲邻云端接口契约与防失效基线

> 采集于 2026-11-11。**全部为无凭证探测**，不含账号、小区、门禁真实值。
> 目的：把「接口是否真实存在」从推测变成可重复验证的事实，上游改版时能第一时间发现。

## 1. 结论：可以拿到真实接口，且不需要 root 或抓包

三个互相印证的来源：

1. **自家 APK 真机实测**：真实短信登录成功、`sessionId` 落本机、读到小区列表与 2 扇门 ——
   主路径不是从文档猜的，是跑通过的。
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

复现提取：解压 `edoor.apk`，对 `.so` / `assets/**` / `.xml` / `.json` 的字节流用
`/[A-Za-z0-9_.{}$/-]{5,110}` 扫描，保留以 `/api|/open|/doorcontrol|/user|/member|/app` 开头、
且不含 `.cc/.cpp/.h`、`/Users/`、`.so` 等噪音的串，去重即可。

## 4. 无凭证探测结果（2026-11-11）

`ALIVE` = HTTP 200 且返回业务错误码（说明路由存在、已进入鉴权/业务层）；
`GONE` = HTTP 404（Spring 未找到路由）。完整机器可读基线见 `docs/api-baseline.json`。

| 路径 | 角色 | HTTP | code | 状态 |
| --- | --- | --- | --- | --- |
| `/api/app/v1/login` | 主 | 200 | 121 | ALIVE |
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
- 「路由存在」≠「参数正确」≠「物理门打开」；真实开门仍须本人现场验收。
- 协议常量来自公开项目，上游可随时变更或加风控。本基线提供的是**早发现 + 有备选**，
  不保证永不失效；保留官方 App 与实体钥匙作为最终兜底。
