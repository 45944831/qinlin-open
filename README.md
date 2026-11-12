# 亲邻开门 · 无广告 APK

手机本机直连亲邻云端网关的极简开门 App：不启动官方 `com.qinlin.edoor`，广告 SDK 不运行，
不需要服务器 / 域名 / HTTPS 证书 / Docker，也没有第三方库。

- 纯 Android / JDK 标准库，离线构建（`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`）
- 短信登录一次，`sessionId` 只存本机 SharedPreferences（`allowBackup=false`）
- 不做后台保活：只在启动 / 回前台 / 开门前按需检查会话；服务端判失效只标记、不删凭证，可点「重试连接」恢复
- 主界面一键开门；门列表放在二级页面，主界面只显示当前选中的门
- 4 种尺寸桌面小组件（1×1 / 2×1 / 4×1 / 4×2）；分类诊断日志在独立页面
- 开门成功弹「已开门」横幅，状态框保留脱敏后的服务端原文
- 应用内 / 桌面小组件开门都计入同一份本机记录（标注来源），今日次数与最近记录同步

> 开门指令必须经亲邻服务端实时校验，**不可能离线开门**。
> 平台返回成功 ≠ 物理门已打开，请现场确认并保留钥匙兜底。

**验收状态（2026-11-11）**：已在真机上实测成功打开门，端到端链路有效。

## 构建

```bash
python build-apk.py              # 产物 qinlin-open.apk（debug 签名）
adb install -r qinlin-open.apk
```

依赖 JDK 17、Android SDK `build-tools/29.0.3`、`tools/android.jar`（API 33），
路径在 `build-apk.py` 顶部按本机修改。**仓库不含 SDK 文件与签名私钥**，首次构建需准备：

```bash
mkdir -p tools && cd tools
curl -LO https://dl.google.com/android/repository/platform-33_r02.zip
python -c "import zipfile; z=zipfile.ZipFile('platform-33_r02.zip'); open('android.jar','wb').write(z.read('android-13/android.jar'))"
```

`build/debug.keystore` 由脚本自动生成。**新密钥与旧 APK 签名不同**，覆盖安装可能需先卸载旧包
（会丢失本机登录凭证）。

## 使用

1. 输入手机号 → 获取验证码 → 登录
2. 「选择门」进入二级列表，点选设为默认门（保存 `communityId` / `doorControlId`）
3. 回主界面点「开门」；若服务端判登录失效，先点「重试连接」，仍不行再重新短信登录
   （原因与处理见下节「登录为什么会失效」）
4. 可选：桌面添加小组件，直接点小组件开门

## 登录为什么会失效（真机实测结论）

**结论：绝大多数「自动被登出」不是凭证到期，也不是本 APK 没做后台保活，
而是服务端按「账号维度单会话」互斥——任何一次新登录都会作废旧 `sessionId`。**

2026-11-12 真机互踢实验：`checkLogin` 对同一枚凭证返回 `code:0`；随后在官方 App
里用微信重新登录一次，约 1 分钟后同一枚凭证立刻变成 `code:401`
「您还没有登录，或者登录已超时」。对照日志还显示：空闲 13 小时 36 分仍能开门，
而短空闲后反而失效 —— **与空闲时长无关**，早期「48 小时绝对 TTL」的猜测已被推翻。

官方 App 之所以看起来「一直在线」，是靠一键登录 / 微信 OAuth / 厂商账号等多套
静默重登渠道自动换新票；本 APK 不复制该机制，也不做后台轮询
（轮询挡不住互踢，只会耗电）。官方 App 改用短信验证码登录也走同一个
`/api/app/v1/login`，不会拿到长效凭证，同样会触发互踢。

| 会让本 APK 失效 | 不会让本 APK 失效 |
| --- | --- |
| 在官方 App 重新登录（微信 / 一键 / 短信均可），**最主要原因** | 覆盖安装、重启手机、长时间不使用（实测 13h36m 空闲仍有效） |
| 官方 App 重装 / 换设备登录 / 服务端风控强制重登 | 只用 App 开门而不用小组件（小部件只是第一个发现 401，不是原因） |
| 上游协议变更 / 风控拦截（表现类似，但 `code` 不同，看日志区分） | 网关偶发误判的 401：本 APK 会二次确认，确认误判就自动恢复 |
| 服务端确实回收会话（较少见，日志表现为 `checkLogin` 也返回 401） | 除主动「退出登录」外的任何本地操作（凭证一直保留） |

**失效后的表现与处理**

1. 界面显示「本机凭证仍在，但上次被服务端判为失效（常见原因：官方 App 重新登录踢掉本会话）」；
   **只标记过期，不删除 `sessionId`**，只有主动「退出登录」才真清凭证。
2. 先点「凭证已失效？点此重试连接」：用 `checkLogin` 二次确认，
   网关误判 / 网络恢复时原地回到登录态，不必重新收短信。
3. 确认仍返回 401（确属被踢），才重新收一次短信登录。
4. 想少被踢：日常固定只用本 APK；一旦在官方 App 里登录过，就预期本 APK 需要重登，
   不要让两个 App 长期同时保持登录态。

> 登录响应只有 `flag` 与 `sessionId`，没有 refresh token / expiresIn 之类的长效票据，
> 因此本 APK 无法像官方 App 那样静默续期；这是协议限制，不是实现缺陷。

## 协议要点

协议事实参考 MIT 项目 `burgesswu/ha-qinlin` 与公开资料，代码为独立编写。

- 网关 `https://mobileapi3.qinlinkeji.com`；短信走 `gateway2.qinlinkeji.com`
- 登录 `/api/app/v1/login` → 按需会话检查 `/api/app/v1/checkLogin` →
  小区 `/api/app/user/v2/communityInfo` →
  门列表 `/api/app/user/v2/queryAllUserDoorByCache` → 开门 `/api/open/doorcontrol/v2/open`
- `sign` = 参数按 key 排序拼 `k=v&...` 加后缀，MD5 转大写；手机号 AES-128-ECB / PKCS7
- 设备请求头必须**小写**；`sessionId` 走 URL query

## 风险与边界

- **非官方客户端**，伪装官方 4.9.3 并使用其协议常量；上游变更 / 风控会使其失效。
- 参考项目 `CIoaRaShuYing/qinlinopen` 无 LICENSE，本项目未复制其源码。
- 仅供本人账号、本人门禁使用，请确认符合 App 用户协议与物业规定。
- `sessionId` 明文存本机，避免在已 root / 已解锁设备长期存放。
- 开门已真机验收通过；仍建议保留实体钥匙与官方 App 兜底（上游变更、凭证过期、设备异常时）。
- **服务端按账号单会话**：在官方 App 里重新登录（含微信 / 一键登录）会立刻作废本 APK 的凭证。
  避免两个 App 同时保持登录态；被踢后先点「重试连接」确认，必要时重新短信登录。
  官方 App 靠多套静默重登渠道维持「一直在线」，本 APK 不复制该机制，也不做后台轮询耗电。
  完整触发场景、真机互踢证据与恢复步骤见上文「登录为什么会失效（真机实测结论）」。
- `QinlinApi.java` 内的 APPID / APP_SECRET / AES 密钥是**上游公开协议常量**（非个人凭证），
  代码运行必需，故保留明文。

## 目录

| 路径 | 内容 |
| --- | --- |
| `src/com/wb/qinlin/` | 全部 Java 源码（界面、协议、缓存、日志、小组件） |
| `res/` | XML 布局 / drawable / 小组件配置 |
| `build-apk.py` | 离线构建脚本 |
| `docs/DEVLOG.md` | 完整排查与真机实测记录（按时间追加，已脱敏） |
| `docs/API-CONTRACT.md` | 真实接口契约、官方 APK 路径清单、备用路径与防失效规则 |
| `docs/api-baseline.json` | 无凭证探测基线（15 条） |
| `api-probe.py` | 接口探测脚本：`python api-probe.py`，无凭证、只读、不触发开门 |

接口变更检测见 `docs/API-CONTRACT.md`：官方 App 升级后跑 `python api-probe.py --all`，
退出码 `2` 即表示上游接口已变化。

`docs/DEVLOG.md` 中的小区名、门名 / 门 ID、账号与设备标识均为示例值（已脱敏）。
