# 亲邻开门 · 无广告 APK

手机本机直连亲邻云端网关的极简开门 App：不启动官方 `com.qinlin.edoor`，广告 SDK 不运行，
不需要服务器 / 域名 / HTTPS 证书 / Docker，也没有第三方库。

- 纯 Android / JDK 标准库，离线构建（`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`）
- 短信登录一次，`sessionId` 只存本机 SharedPreferences（`allowBackup=false`）
- 主界面一键开门；门列表放在二级页面，主界面只显示当前选中的门
- 4 种尺寸桌面小组件（1×1 / 2×1 / 4×1 / 4×2）；分类诊断日志在独立页面
- 开门成功弹「已开门」横幅，状态框保留脱敏后的服务端原文

> 开门指令必须经亲邻服务端实时校验，**不可能离线开门**。
> 平台返回成功 ≠ 物理门已打开，请现场确认并保留钥匙兜底。

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
3. 回主界面点「开门」；凭证失效会提示重新登录
4. 可选：桌面添加小组件，直接点小组件开门

## 协议要点

协议事实参考 MIT 项目 `burgesswu/ha-qinlin` 与公开资料，代码为独立编写。

- 网关 `https://mobileapi3.qinlinkeji.com`；短信走 `gateway2.qinlinkeji.com`
- 登录 `/api/app/v1/login` → 小区 `/api/app/user/v2/communityInfo` →
  门列表 `/api/app/user/v2/queryAllUserDoorByCache` → 开门 `/api/open/doorcontrol/v2/open`
- `sign` = 参数按 key 排序拼 `k=v&...` 加后缀，MD5 转大写；手机号 AES-128-ECB / PKCS7
- 设备请求头必须**小写**；`sessionId` 走 URL query

## 风险与边界

- **非官方客户端**，伪装官方 4.9.3 并使用其协议常量；上游变更 / 风控会使其失效。
- 参考项目 `CIoaRaShuYing/qinlinopen` 无 LICENSE，本项目未复制其源码。
- 仅供本人账号、本人门禁使用，请确认符合 App 用户协议与物业规定。
- `sessionId` 明文存本机，避免在已 root / 已解锁设备长期存放。
- 首次实测务必人在门内、带实体钥匙，并保留官方 App 兜底。
- `QinlinApi.java` 内的 APPID / APP_SECRET / AES 密钥是**上游公开协议常量**（非个人凭证），
  代码运行必需，故保留明文。

## 目录

| 路径 | 内容 |
| --- | --- |
| `src/com/wb/qinlin/` | 全部 Java 源码（界面、协议、缓存、日志、小组件） |
| `res/` | XML 布局 / drawable / 小组件配置 |
| `build-apk.py` | 离线构建脚本 |
| `docs/DEVLOG.md` | 完整排查与真机实测记录（按时间追加，897 行） |

`docs/DEVLOG.md` 中的小区名、门名 / 门 ID、账号与设备标识均为示例值（已脱敏）。
