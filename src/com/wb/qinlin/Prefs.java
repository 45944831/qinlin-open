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
    public static void setPhone(Context c, String v) {
        SharedPreferences.Editor ed = sp(c).edit();
        SecureStore.put(c, ed, "phone", v);
        ed.apply();
    }

    public static String session(Context c) { return SecureStore.get(c, sp(c), "session", ""); }

    /**
     * 保存凭证时顺手记下「写入时间」与「登录时的 openid 指纹」。
     * 覆盖安装 / 设备标识变化后，日志据此判断服务端是否可能拒绝旧会话。
     */
    public static void setSession(Context c, String v) {
        SharedPreferences.Editor ed = sp(c).edit();
        SecureStore.put(c, ed, "session", v);
        ed.putLong("session_at", System.currentTimeMillis())
                .putString("session_openid_fp", AppLog.fp(openid(c)))
                .putBoolean("session_expired", false)
                .putLong("last_keep_alive", System.currentTimeMillis())
                .apply();
    }
    public static long sessionSavedAt(Context c) { return sp(c).getLong("session_at", 0); }
    public static String sessionOpenidFp(Context c) {
        return sp(c).getString("session_openid_fp", "");
    }

    /**
     * 退出登录：清凭证、小区名与门列表缓存（缓存属于该账号，不能带给下一个账号），
     * 但保留手机号 / openid / 默认门，方便下次免输入重登。
     */
    public static void clearSession(Context c) {
        sp(c).edit().remove("session").remove("cname")
                .remove("session_at").remove("session_openid_fp")
                .remove("door_cache").remove("w_last")
                .remove("session_expired").remove("last_keep_alive")
                .remove("last_keep_alive_try").remove("login_data").apply();
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
     * 桌面小部件「上次开门结果」：格式 {@code ok|时间戳|文案}，只有一条，覆盖写。
     * 小部件没打开过时为空串，界面据此显示「点按钮直接开门」。
     */
    public static String widgetLast(Context c) { return sp(c).getString("w_last", ""); }
    public static void setWidgetLast(Context c, String v) {
        sp(c).edit().putString("w_last", v == null ? "" : v).apply();
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

    /** 设备标识只生成一次并持久化：登录与后续请求必须用同一个 openid。 */
    public static String openid(Context c) {
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
    }

    // ---------- 每个小部件独立绑定的门（P4：支持同屏放多个不同门） ----------

    /** 某 widgetId 绑定的门 JSON：{"cid":..,"did":..,"n":..,"k":..}；未绑定返回空串。 */
    private static String widgetDoorKey(int appWidgetId) { return "wd_" + appWidgetId; }

    public static String widgetDoor(int appWidgetId, Context c) {
        return SecureStore.get(c, sp(c), widgetDoorKey(appWidgetId), "");
    }

    /** value 为门 JSON；传空串/null 表示解绑（该 widget 回退到全局默认门）。 */
    public static void setWidgetDoor(int appWidgetId, Context c, String value) {
        SharedPreferences.Editor ed = sp(c).edit();
        SecureStore.put(c, ed, widgetDoorKey(appWidgetId), value == null ? "" : value);
        ed.apply();
    }

    /** 删除某 widget 的绑定（小组件从桌面移除时调用）。 */
    public static void clearWidgetDoor(int appWidgetId, Context c) {
        sp(c).edit().remove(widgetDoorKey(appWidgetId)).apply();
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
