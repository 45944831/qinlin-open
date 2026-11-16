package com.wb.qinlin;

import android.content.Context;

/**
 * 会话看护：把「服务端票据失效」和「本机被登出」拆成两件事。
 *
 * <p>背景：网关鉴权失败时返回 401「您还没有登录，或者登录已超时，请重新登录！」。
 * 旧实现一遇到这个码就地 clearSession()，把一次服务端失效直接变成不可恢复的
 * 「本机被登出」，只能重新收短信；网关偶发误判或网络抖动也会造成同样后果。</p>
 *
 * <p>2026-11-12 真机结论：服务端按<b>账号维度单会话</b>——官方 App 用微信 OAuth
 * 重新登录后，本 APK 同一枚 sessionId 立刻变成 401，说明新登录会作废旧凭证；
 * 官方之所以「一直在线」，靠的是多套静默重登渠道（一键 / 微信 / 厂商账号），
 * 而不是后台保活。因此<b>不做定时探活、不做后台唤醒</b>：轮询挡不住互踢，只会耗电。</p>
 *
 * <p>本类只在「按需」时刻发一次只读 checkLogin：</p>
 * <ol>
 *   <li>{@link #probeIfStale}：冷启动 / 回前台，带短节流防抖，及时反映是否被踢。</li>
 *   <li>{@link #expiredAfterRetry}：业务请求遇到 401 后强制二次确认，
 *      成功视为网关误判、不动登录态；失败才标记过期。</li>
 *   <li>{@link #retry}：用户点「重试连接」或点小部件开门前，强制探活。</li>
 *   <li>标记过期只写标志位，<b>绝不删除 sessionId</b>；只有主动「退出登录」才真清凭证。</li>
 * </ol>
 *
 * <p>所有公开方法会发网络请求，调用方必须放在后台线程。</p>
 */
public final class SessionGuard {

    /**
     * 前台按需探活的防抖间隔（5 分钟）。只用于避免用户反复切前后台时重复发请求，
     * 不再是「保活周期」：本类没有任何定时器，也不会被后台广播唤醒。
     */
    private static final long FOREGROUND_INTERVAL_MS = 5L * 60 * 1000;

    private static final int OK = 0;
    private static final int EXPIRED = 1;
    private static final int UNKNOWN = 2;
    /** 业务明确拒绝（可换接口再试）；网络异常则不再补发，避免白等一轮超时。 */
    private static final int BIZ_REJECT = 3;
    private static final int NET_FAIL = 4;

    private SessionGuard() {}

    /**
     * 真正发一次只读探活，并把结果落到本机状态。
     * @param force true 忽略前台防抖（401 二次确认 / 重试连接 / 小部件开门前）
     */
    private static synchronized int probe(Context c, boolean force) {
        final Context app = c.getApplicationContext();
        final String token = Prefs.session(app);
        if (token.length() == 0) return UNKNOWN;

        long now = System.currentTimeMillis();
        if (!force) {
            // Prefs.lastKeepAlive* 现在只是「上次探活时间戳」，用于前台防抖，
            // 不再代表任何后台保活周期。
            long lastAlive = Prefs.lastKeepAlive(app);
            long lastAttempt = Prefs.lastKeepAliveTry(app);
            long lastTry = Math.max(lastAlive, lastAttempt);
            long age = now - lastTry;
            // 2026-11-12 真机上「已登录态回前台」反复观察不到 checkLogin，而这条分支
            // 以前是静默 return，无法区分「防抖命中」和「线程根本没跑」。
            // 这里只记时间戳与判定结果（不含凭证），便于现场一眼定位。
            AppLog.i(AppLog.LOGIN, "前台探活判定 | now=" + AppLog.time(now)
                    + " | lastAlive=" + AppLog.time(lastAlive)
                    + " | lastTry=" + AppLog.time(lastAttempt)
                    + " | 距上次=" + age + "ms | 阈值=" + FOREGROUND_INTERVAL_MS + "ms | "
                    + (age < FOREGROUND_INTERVAL_MS ? "命中防抖，跳过本次请求" : "超出防抖窗口，发起 checkLogin"));
            if (age < FOREGROUND_INTERVAL_MS) {
                return Prefs.sessionExpired(app) ? EXPIRED : OK;
            }
        }
        Prefs.setLastKeepAliveTry(app, now);
        // 小部件 / 门列表是独立进程入口，openid 必须与登录时一致
        QinlinApi.setOpenid(Prefs.openid(app));

        int r = touch(token, true);
        if (r == BIZ_REJECT) {
            // 真机已确认 checkLogin 在有效凭证下返回 code:0（data 为固定常量 7776000）；
            // 若将来上游改签名导致它给不出结论，退回已验收过的只读小区列表兜底。
            AppLog.w(AppLog.LOGIN, "checkLogin 未给出结论 → 回退 communityInfo 探活");
            r = touch(token, false);
        }
        if (r == OK) {
            Prefs.setLastKeepAlive(app, now);
            if (Prefs.sessionExpired(app)) {
                Prefs.setSessionExpired(app, false);
                AppLog.ok(AppLog.LOGIN, "会话探活成功 → 已自动恢复登录态（无需重新短信登录）");
            }
            return OK;
        }
        if (r == EXPIRED) {
            Prefs.setSessionExpired(app, true);
            AppLog.w(AppLog.LOGIN, "会话探活：服务端判定凭证已失效 → 只标记过期，本机凭证保留");
            return EXPIRED;
        }
        return UNKNOWN;   // 业务拒绝 / 网络异常：不动登录态，由调用方决定
    }

    /** 单次只读请求。useCheckLogin=false 时用已真机验收的小区列表接口。 */
    private static int touch(String token, boolean useCheckLogin) {
        String tag = useCheckLogin ? "checkLogin" : "communityInfo";
        try {
            if (useCheckLogin) QinlinApi.checkLogin(token);
            // checkLogin 给不出结论时只发一次短超时的 communityInfo：
            // communities() 会串行试 4 种姿势（各 5s 连接 + 15s 读），
            // 探活路径不能背这个预算——小部件开门的广播前台时限只有约 10 秒。
            else QinlinApi.communitiesProbe(token);
            AppLog.i(AppLog.LOGIN, "会话探活成功（" + tag + "），服务端凭证仍有效");
            return OK;
        } catch (QinlinApi.ApiException e) {
            if ("SESSION_EXPIRED".equals(e.code)) return EXPIRED;
            AppLog.w(AppLog.LOGIN, "会话探活 " + tag + " 被业务拒绝 [" + e.code + "]");
            return BIZ_REJECT;
        } catch (Exception e) {
            AppLog.w(AppLog.LOGIN, "会话探活 " + tag + " 网络异常 | " + AppLog.stack(e));
            return NET_FAIL;
        }
    }

    /**
     * 按需探活。调用方在后台线程执行；返回值表示「会话当前是否仍有效」。
     * force=false 时带 {@link #FOREGROUND_INTERVAL_MS} 防抖（冷启动 / 回前台用），
     * force=true 用于 401 二次确认、重试连接、小部件开门前。
     * 成功恢复登录态时，调用方需要刷新一次界面。
     */
    public static boolean probeIfStale(Context c, boolean force) {
        return probe(c, force) == OK;
    }

    /**
     * 业务请求遇到 401 后调用：强制再探活一次做二次确认。
     *
     * @return true = 确认已超时（已标记过期、凭证仍保留，应切回登录表单）；
     *         false = 刚才是误判/网络抖动且探活成功，登录态保持不变。
     */
    public static boolean expiredAfterRetry(Context c) {
        int r = probe(c, true);
        if (r == OK) {
            AppLog.w(AppLog.LOGIN, "401 二次确认：单独探活成功 → 判定为网关偶发误判，登录态保留");
            return false;
        }
        if (r == UNKNOWN) {
            // 探活接口自身没给结论（网络异常 / 参数不兼容），以原请求的 401 为准，
            // 保守标记过期但不删凭证，用户仍可点「重试连接」恢复。
            Prefs.setSessionExpired(c.getApplicationContext(), true);
            AppLog.w(AppLog.LOGIN, "401 二次确认：探活无结论 → 按原 401 标记过期，凭证仍保留");
        }
        return true;
    }

    /** 用户点「重试连接」：强制探活，返回是否已恢复登录态。 */
    public static boolean retry(Context c) {
        return probe(c, true) == OK;
    }
}
