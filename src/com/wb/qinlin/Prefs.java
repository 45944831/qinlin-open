package com.wb.qinlin;

import android.content.Context;
import android.content.SharedPreferences;

/** 仅在本机保存会话与默认门，不上传任何数据。 */
public final class Prefs {
    private static final String P = "qinlin";
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /** App 启动时调用：把历史明文凭证一次性升级为 Keystore 密文（幂等、失败不影响原值）。 */
    public static void migrateSecureFields(Context c) {
        SecureStore.migrate(c, sp(c), "session", "login_data", "phone");
    }

    // 敏感字段（手机号 / sessionId / 登录原文）经 Keystore 加密落盘；
    // SecureStore 读取时自动兼容历史明文，覆盖安装或加密不可用时都不会丢凭证。
    public static String phone(Context c) { return SecureStore.get(c, sp(c), "phone", ""); }

    public static String session(Context c) { return SecureStore.get(c, sp(c), "session", ""); }

    /**
     * 登录成功：手机号与新凭证在<b>同一次提交</b>里落盘。
     *
     * <p>旧实现先在「发送验证码」时就写手机号、登录成功后才写 session，
     * 一旦登录失败就会留下「手机号已换、凭证还是旧账号」的半状态；
     * 而且 doLogin 里比较的 prevPhone 已经是新号，换账号清门的分支永远不可达。</p>
     *
     * <p>这里是<b>唯一</b>的凭证写入入口：不再提供单独写 phone / session 的方法，
     * 从源头上杜绝两个字段不同步。</p>
     */
    public static void setLogin(Context c, String phone, String token) {
        SharedPreferences.Editor ed = sp(c).edit();
        SecureStore.put(c, ed, "phone", phone == null ? "" : phone);
        SecureStore.put(c, ed, "session", token);
        ed.putLong("session_at", System.currentTimeMillis())
                .putString("session_openid_fp", AppLog.fp(openid(c)))
                .putBoolean("session_expired", false)
                .putLong("last_keep_alive", System.currentTimeMillis())
                // 新凭证的有效期要等下一次探活才知道，先清掉旧值避免展示过期信息
                .remove("session_ttl_s").remove("session_ttl_at")
                .apply();
    }
    public static long sessionSavedAt(Context c) { return sp(c).getLong("session_at", 0); }
    public static String sessionOpenidFp(Context c) {
        return sp(c).getString("session_openid_fp", "");
    }

    /**
     * 服务端下发的会话有效期（秒），来自 checkLogin 的 data（真机实测 7776000 = 90 天）。
     * -1 表示还没探活过 / 上游没给这个字段。它是**滑动窗口**：任意一次带凭证的请求都会续上，
     * 所以只有长期不用才会自然到期，界面用它回答「凭证还剩多久」。
     */
    public static int sessionTtlSeconds(Context c) { return sp(c).getInt("session_ttl_s", -1); }

    public static long sessionTtlAt(Context c) { return sp(c).getLong("session_ttl_at", 0); }

    /**
     * 剩余有效期的估算（秒）：原始 TTL 减去「自上次探活以来流逝的时间」。
     *
     * <p>滑动窗口的准确剩余价值只有服务端知道；这里至少不能把 80 天前探活拿到的
     * 「90 天」一直原样显示成「凭证 90 天」——那会让用户以为永远不过期。
     * 任意一次带凭证的请求都会续期，所以这个值是偏保守的下界，用来提示「快到期」足够。</p>
     */
    public static int sessionTtlRemainSeconds(Context c) {
        int total = sessionTtlSeconds(c);
        if (total <= 0) return -1;
        long at = sessionTtlAt(c);
        // 纯计算交给 core.Verify（含「系统时间回拨不凭空变长」的保护），便于桌面单测
        return com.wb.qinlin.core.Verify.ttlRemain(
                total, at <= 0 ? 0L : at / 1000L, System.currentTimeMillis() / 1000L);
    }

    public static void setSessionTtl(Context c, int seconds) {
        if (seconds <= 0) return;
        sp(c).edit().putInt("session_ttl_s", seconds)
                .putLong("session_ttl_at", System.currentTimeMillis()).apply();
    }

    /** 「90 天」这类可读文案；未知返回空串。按实际流逝时间衰减，不再永远显示原始 TTL。 */
    public static String sessionTtlText(Context c) {
        int s = sessionTtlRemainSeconds(c);
        if (s < 0) return "";
        if (s == 0) return "已到期";
        long days = s / 86400L;
        if (days >= 1) return days + " 天";
        long hours = s / 3600L;
        return hours >= 1 ? hours + " 小时" : Math.max(1, s / 60L) + " 分钟";
    }

    /**
     * 退出登录：清凭证、小区名与门列表缓存（缓存属于该账号，不能带给下一个账号），
     * 但保留手机号 / openid / 默认门，方便下次免输入重登。
     *
     * <p>这里<b>不清理</b>每个小组件单独绑定的门（{@code wd_<id>}）：退出后用同一账号
     * 重新登录是最常见的路径，清掉会让组件静默回落成默认门——标签写着「车库闸」，
     * 实际去开默认门。跨账号仍由 {@link #clearDoor} 在登录时清理（见 doLogin 的 switched 判断）。</p>
     */
    public static void clearSession(Context c) {
        sp(c).edit().remove("session").remove("cname")
                .remove("session_at").remove("session_openid_fp")
                .remove("door_cache").remove("w_last")
                .remove("session_expired").remove("last_keep_alive")
                .remove("last_keep_alive_try").remove("login_data")
                .remove("session_ttl_s").remove("session_ttl_at").apply();
    }

    /**
     * 清掉所有按 appWidgetId 存储的门绑定与开门结果（<b>换账号登录</b>时由 {@link #clearDoor} 调用）。
     * 老版本只清了全局 w_last，按 id 的 w_last_&lt;id&gt; / wd_&lt;id&gt; 会残留给下一账号。
     * 退出登录不调用：同账号重登应当保留每个组件各自绑定的门。
     * 组件名与主题色是外观偏好、与账号无关，刻意保留。
     */
    private static void clearWidgetScopedData(Context c) {
        SharedPreferences sp = sp(c);
        SharedPreferences.Editor ed = sp.edit();
        boolean changed = false;
        for (String k : sp.getAll().keySet()) {
            if (k != null && (k.startsWith("wd_") || k.startsWith("w_last_")
                    || k.startsWith("wa_"))) {
                ed.remove(k);
                changed = true;
            }
        }
        if (changed) ed.apply();
    }

    // ---------- 会话过期标记与探活（与「退出登录」严格区分） ----------

    /**
     * 服务端判定「登录已超时」时只打标记、不删凭证：
     * 网关偶发误判或网络恢复后，一次探活就能自动回到登录态，用户不必重新收短信。
     * 只有用户主动点「退出登录」才真正 clearSession()。
     */
    public static boolean sessionExpired(Context c) {
        return sp(c).getBoolean("session_expired", false);
    }

    public static void setSessionExpired(Context c, boolean v) {
        sp(c).edit().putBoolean("session_expired", v).apply();
    }

    /** 界面判定登录态：有凭证且未被标记过期。 */
    public static boolean loggedIn(Context c) {
        return session(c).length() > 0 && !sessionExpired(c);
    }

    /** 上次探活成功时间；用于前台节流，避免每次 onResume 都发请求。 */
    public static long lastKeepAlive(Context c) {
        return sp(c).getLong("last_keep_alive", 0);
    }

    public static void setLastKeepAlive(Context c, long v) {
        sp(c).edit().putLong("last_keep_alive", v).apply();
    }

    /** 上次探活尝试时间（含失败），避免断网时每 30 分钟被反复唤醒也只记一次。 */
    public static long lastKeepAliveTry(Context c) {
        return sp(c).getLong("last_keep_alive_try", 0);
    }

    public static void setLastKeepAliveTry(Context c, long v) {
        sp(c).edit().putLong("last_keep_alive_try", v).apply();
    }

    /**
     * 登录接口 data 的完整原文（只存本机，不上传、不进日志）。
     * 当前只用 sessionId；留着它是为了日后确认上游是否下发长效凭证 / 续期票据，
     * 有了它才有可能像官方 App 那样静默续期，而不是每次超时都收短信。
     */
    public static String loginData(Context c) { return SecureStore.get(c, sp(c), "login_data", ""); }

    public static void setLoginData(Context c, String v) {
        SharedPreferences.Editor ed = sp(c).edit();
        SecureStore.put(c, ed, "login_data", v == null ? "" : v);
        ed.apply();
    }

    /**
     * 桌面小部件「上次开门结果」：格式 {@code ok|时间戳|文案}，按 appWidgetId 独立存储。
     * 每个组件只反映「通过该组件发生」的开门结果——多个组件绑不同门时互不串扰。
     * 小部件没打开过时为空串，界面据此显示「点按钮直接开门」。
     *
     * <p>旧版曾用全局键 {@code w_last} 让所有组件共读一条，升级后不再读取它：
     * 若沿用会把同一条结果同时显示到每个组件上，正是要修掉的串扰。</p>
     */
    private static String widgetLastKey(int appWidgetId) { return "w_last_" + appWidgetId; }

    public static String widgetLast(Context c, int appWidgetId) {
        return sp(c).getString(widgetLastKey(appWidgetId), "");
    }

    public static void setWidgetLast(Context c, int appWidgetId, String v) {
        sp(c).edit().putString(widgetLastKey(appWidgetId), v == null ? "" : v).apply();
    }

    /**
     * 门列表缓存（JSON 文本）。打开门列表时先渲染它，再后台刷新，
     * 避免每次都要等一轮「小区 + 逐小区门列表」的网络请求。
     */
    public static String doorCache(Context c) { return sp(c).getString("door_cache", ""); }
    public static void setDoorCache(Context c, String v) {
        sp(c).edit().putString("door_cache", v == null ? "" : v).apply();
    }

    /**
     * 上次成功的「小区列表请求姿势」下标（0 JSON/数值 / 1 表单 / 2 URL参数 / 3 JSON/全字符串）。
     * 下次优先用它，正常情况第一次就成功，省掉最多 3 次串行兜底请求。
     */
    public static int commMode(Context c) { return sp(c).getInt("comm_mode", -1); }
    public static void setCommMode(Context c, int v) {
        sp(c).edit().putInt("comm_mode", v).apply();
    }

    /** 安装戳（versionCode/lastUpdateTime）：日志用来识别覆盖安装与版本变化。 */
    public static String logStamp(Context c) { return sp(c).getString("log_stamp", ""); }
    public static void setLogStamp(Context c, String v) {
        sp(c).edit().putString("log_stamp", v == null ? "" : v).apply();
    }

    /** 登录后从 communityInfo 读回的小区名，用于顶部登录态卡片显示。 */
    public static String communityName(Context c) {
        return sp(c).getString("cname", "");
    }
    public static void setCommunityName(Context c, String v) {
        sp(c).edit().putString("cname", v == null ? "" : v).apply();
    }

    /**
     * 设备标识只生成一次并持久化：登录与后续请求必须用同一个 openid。
     * synchronized：首次并发调用（主界面探活 + 小部件广播）可能各生成一个，
     * 后写的覆盖先写的，导致同一份凭证带着两个不同 openid 发请求。
     */
    public static synchronized String openid(Context c) {
        SharedPreferences p = sp(c);
        String v = p.getString("openid", "");
        if (v.length() == 0) {
            byte[] b = new byte[8];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x & 0xFF));
            v = sb.toString();
            p.edit().putString("openid", v).apply();
        }
        return v;
    }

    public static int communityId(Context c) { return sp(c).getInt("cid", 0); }
    public static int doorId(Context c) { return sp(c).getInt("did", 0); }
    public static String doorName(Context c) { return sp(c).getString("dname", ""); }
    public static void setDoor(Context c, int cid, int did, String name) {
        sp(c).edit().putInt("cid", cid).putInt("did", did).putString("dname", name).apply();
    }

    /**
     * 是否还需要走「首次选门」引导。
     * 只要本机还没有可用的门（门 id / 小区 id 缺失），或这扇门不是当前手机号确认过的
     * （换账号登录），就再引导一次；否则冷启动与后续登录都直接进主界面。
     */
    public static boolean needsOnboarding(Context c) {
        String phone = phone(c);
        if (phone.length() == 0) return true;
        if (communityId(c) <= 0 || doorId(c) <= 0) return true;
        return !phone.equals(doorConfirmedPhone(c));
    }

    /** 记录「此手机号已确认过要控制的门」：确认后不再自动弹门列表。 */
    public static String doorConfirmedPhone(Context c) {
        return sp(c).getString("door_confirmed_phone", "");
    }
    public static void setDoorConfirmed(Context c, String phone) {
        sp(c).edit().putString("door_confirmed_phone", phone == null ? "" : phone).apply();
    }

    /** 换账号登录时清掉上一账号的门选择与确认标记，避免把别人的门当默认开门。 */
    public static void clearDoor(Context c) {
        sp(c).edit().remove("cid").remove("did").remove("dname")
                .remove("dkind").remove("donline")
                .remove("w_last")
                .remove("door_confirmed_phone").apply();
        clearWidgetScopedData(c);
    }

    // ---------- 每个小部件独立绑定的门（P4：支持同屏放多个不同门） ----------

    /** 某 widgetId 绑定的门 JSON：{"cid":..,"did":..,"n":..,"k":..}；未绑定返回空串。 */
    private static String widgetDoorKey(int appWidgetId) { return "wd_" + appWidgetId; }

    public static String widgetDoor(int appWidgetId, Context c) {
        return SecureStore.get(c, sp(c), widgetDoorKey(appWidgetId), "");
    }

    /**
     * 该组件是否<b>存着一份非空的绑定数据</b>（只看原始存储值，不解密）。
     * 用于区分三种情况：
     * ① 键不存在 / 值是空串（主动解绑）→ false，可以安全回退默认门；
     * ② 值非空但解密/解析失败 → true，<b>不能</b>回退默认门（否则会开错门）。
     */
    public static boolean hasWidgetDoor(int appWidgetId, Context c) {
        try {
            String raw = sp(c).getString(widgetDoorKey(appWidgetId), "");
            return raw != null && raw.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** value 为门 JSON；传空串/null 表示解绑（该 widget 回退到全局默认门）。 */
    public static void setWidgetDoor(int appWidgetId, Context c, String value) {
        SharedPreferences.Editor ed = sp(c).edit();
        SecureStore.put(c, ed, widgetDoorKey(appWidgetId), value == null ? "" : value);
        ed.apply();
    }

    /**
     * 小组件自定义显示名（设置界面里改）。空串=未设置，界面回退到按样式生成的默认名。
     * 只是界面标签，不含凭证，普通 SharedPreferences 即可。
     */
    private static String widgetNameKey(int appWidgetId) { return "wn_" + appWidgetId; }

    /** 组件名最长 5 个字符（按 Unicode 码点计，1 个汉字=1 个字符，emoji 也算 1 个）。 */
    public static final int WIDGET_NAME_MAX = 5;

    /** 按码点截断到 5 个字符，防止超长名字挤爆桌面卡片。emoji 代理对不会被截成半截。 */
    public static String limitWidgetName(String v) {
        // 实现移到 core.Text（纯函数、可被 run-tests.py 覆盖），这里只保留对外签名
        return com.wb.qinlin.core.Text.limitCodepoints(v, WIDGET_NAME_MAX);
    }

    public static String widgetName(int appWidgetId, Context c) {
        // 读取也截断，兼容旧版本写入的超长名字
        return limitWidgetName(sp(c).getString(widgetNameKey(appWidgetId), ""));
    }

    public static void setWidgetName(int appWidgetId, Context c, String v) {
        sp(c).edit().putString(widgetNameKey(appWidgetId), limitWidgetName(v)).apply();
    }

    /** 小组件主题色索引（0~5 对应 res/drawable 的 widget_*_cN）。未设置为 0=默认红。 */
    private static String widgetColorKey(int appWidgetId) { return "wc_" + appWidgetId; }

    /*
     * 组件开门的「尝试标记」：值格式 {@code <进程标签>|<开始时间戳>}。
     *
     * 为什么需要落盘：goAsync() 的广播窗口只有约 10 秒，而弱网下「探活 + 开门」可能更长；
     * 进程在请求途中被系统回收时，finally 里的 finishRequest 根本不会执行，
     * 卡片就会永久停在「开门中…」（用户既不知道指令是否送达，也无法再点）。
     * 落盘后渲染时能识别两种残留：
     *   ① 进程标签已变 → 进程重启过，那次请求必然已中断；
     *   ② 同一进程但超过兜底时长 → 线程异常消失。
     * 两种情况都显示「上次尝试已中断，请重试」，而不是一直等下去。
     */
    private static String widgetAttemptKey(int appWidgetId) { return "wa_" + appWidgetId; }

    public static String widgetAttempt(int appWidgetId, Context c) {
        return sp(c).getString(widgetAttemptKey(appWidgetId), "");
    }

    public static void setWidgetAttempt(Context c, int appWidgetId, String v) {
        sp(c).edit().putString(widgetAttemptKey(appWidgetId), v == null ? "" : v).apply();
    }

    public static void clearWidgetAttempt(Context c, int appWidgetId) {
        sp(c).edit().remove(widgetAttemptKey(appWidgetId)).apply();
    }

    public static int widgetColor(int appWidgetId, Context c) {
        return sp(c).getInt(widgetColorKey(appWidgetId), 0);
    }

    public static void setWidgetColor(int appWidgetId, Context c, int idx) {
        sp(c).edit().putInt(widgetColorKey(appWidgetId), idx).apply();
    }

    /** 删除某 widget 的全部配置（小组件从桌面移除时调用）。 */
    public static void clearWidgetDoor(int appWidgetId, Context c) {
        sp(c).edit().remove(widgetDoorKey(appWidgetId))
                .remove(widgetNameKey(appWidgetId))
                .remove(widgetColorKey(appWidgetId))
                .remove(widgetLastKey(appWidgetId))
                .remove(widgetAttemptKey(appWidgetId)).apply();
    }

    /** 选中的门所属区域与在线状态：主界面卡片用来「显示这扇门」。旧数据为空时会退化成只显示 id。 */
    public static String doorKind(Context c) { return sp(c).getString("dkind", ""); }
    public static String doorOnline(Context c) { return sp(c).getString("donline", ""); }
    public static void setDoorMeta(Context c, String kind, String online) {
        sp(c).edit()
                .putString("dkind", kind == null ? "" : kind)
                .putString("donline", online == null ? "" : online)
                .apply();
    }
}
