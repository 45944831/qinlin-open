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
 *   <li>{@link #probeNow}：用户点「重试连接」或点小部件开门前，强制探活并返回完整结果码。</li>
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

    /**
     * 探活结果。公开给调用方区分「真失效」与「网络/网关暂时给不出结论」：
     * 旧实现把三态压成 boolean，弱网时 {@code retry()==false} 会被小部件当成
     * 「凭证超时」，提示用户去重新登录——这是误报。
     */
    public static final int OK = 0;
    public static final int EXPIRED = 1;
    public static final int UNKNOWN = 2;
    /** 业务明确拒绝（可换接口再试）；网络异常则不再补发，避免白等一轮超时。 */
    public static final int BIZ_REJECT = 3;
    public static final int NET_FAIL = 4;

    private SessionGuard() {}

    /**
     * 保护「探活占位」，不保护网络 IO：
     * 以前 {@code probe} 整个方法是 synchronized static，两个入口并发时后到的线程
     * 会被前一个请求最长 22 秒的网络等待挡住（小部件广播预算只有约 10 秒）。
     */
    private static final Object PROBE_LOCK = new Object();
    private static boolean probing = false;
    /** 最近一次探活的结论：并发调用方等不到时用它，避免拿「上次残留的过期标记」冒充新结论。 */
    private static int lastProbeResult = UNKNOWN;
    /**
     * 那次探活针对的凭证。等待方拿到结论前必须比对它和当前 session：
     * 探活期间用户可能已经重新登录，旧凭证的 OK 不能拿去放行新凭证的开门。
     */
    private static String lastProbeToken = "";

    /**
     * 真正发一次只读探活，并把结果落到本机状态。
     * @param force true 忽略前台防抖（401 二次确认 / 重试连接 / 小部件开门前）
     * @param quick true 用「小部件预算」的短超时（见 QinlinApi.WIDGET_PROBE_*）
     */
    private static int probe(Context c, boolean force, boolean quick) {
        final Context app = c.getApplicationContext();
        final String token;
        final long now;
        synchronized (PROBE_LOCK) {
            token = Prefs.session(app);
            if (token.length() == 0) return UNKNOWN;

            now = System.currentTimeMillis();
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
            if (probing) {
                // 已有探活在进行：不重复发请求，但也不能拿「上次残留的过期标记」当本次结论
                // （那会把正在飞、很可能成功的探活误报成「服务端判定失效」）。
                // 这里短暂等它的真实结果，等不到就返回 UNKNOWN（调用方按「无结论」处理）。
                AppLog.i(AppLog.LOGIN, "另一次探活正在进行 → 等待其结论（最多 2 秒）");
                long deadline = System.currentTimeMillis() + 2000L;
                long left = 2000L;
                while (probing && left > 0) {
                    try {
                        PROBE_LOCK.wait(left);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    left = deadline - System.currentTimeMillis();
                }
                if (!probing) {
                    // 结论必须属于「当前仍在用的凭证」：探活期间用户重新登录时，
                    // 旧凭证的 OK/EXPIRED 都不能套到新凭证上，否则会把未验证的新会话
                    // 当成有效而放行开门。等待方本次持有的凭证是进来那一瞬间的 token。
                    if (token.equals(lastProbeToken)) {
                        AppLog.i(AppLog.LOGIN, "等到并发探活结果=" + lastProbeResult);
                        return lastProbeResult;
                    }
                    AppLog.w(AppLog.LOGIN, "并发探活的结论属于旧凭证 → 丢弃（本次返回 UNKNOWN）");
                    return UNKNOWN;
                }
                AppLog.w(AppLog.LOGIN, "等待并发探活超时 → 本次不做结论（UNKNOWN）");
                return UNKNOWN;
            }
            Prefs.setLastKeepAliveTry(app, now);
            probing = true;
        }

        int result = UNKNOWN;
        try {
            // 小部件 / 门列表是独立进程入口，openid 必须与登录时一致
            QinlinApi.setOpenid(Prefs.openid(app));

            int r = touch(app, token, true, quick);
            if (r == BIZ_REJECT) {
                // 真机已确认 checkLogin 在有效凭证下返回 code:0（data 为固定常量 7776000）；
                // 若将来上游改签名导致它给不出结论，退回已验收过的只读小区列表兜底。
                AppLog.w(AppLog.LOGIN, "checkLogin 未给出结论 → 回退 communityInfo 探活");
                r = touch(app, token, false, quick);
            }
            // 探活是异步的：期间用户可能已经重新登录，this token 已被新凭证替换。
            // 此时结果属于旧凭证，绝不能拿它去标记/恢复新会话的登录态。
            if (!token.equals(Prefs.session(app))) {
                AppLog.w(AppLog.LOGIN, "探活期间凭证已被替换 → 丢弃本次结果（不修改登录态）");
                result = UNKNOWN;
            } else if (r == OK) {
                Prefs.setLastKeepAlive(app, now);
                if (Prefs.sessionExpired(app)) {
                    Prefs.setSessionExpired(app, false);
                    AppLog.ok(AppLog.LOGIN, "会话探活成功 → 已自动恢复登录态（无需重新短信登录）");
                }
                result = OK;
            } else if (r == EXPIRED) {
                Prefs.setSessionExpired(app, true);
                AppLog.w(AppLog.LOGIN, "会话探活：服务端判定凭证已失效 → 只标记过期，本机凭证保留");
                result = EXPIRED;
            } else {
                // 业务拒绝 / 网络异常：原样返回，由调用方区分提示；不动登录态
                result = r == BIZ_REJECT ? BIZ_REJECT : NET_FAIL;
            }
        } finally {
            synchronized (PROBE_LOCK) {
                lastProbeResult = result;
                lastProbeToken = token;   // 与结论一起发布，供等待方校验归属
                probing = false;
                PROBE_LOCK.notifyAll();   // 唤醒正在等结论的并发探活
            }
        }
        return result;
    }

    /** 单次只读请求。useCheckLogin=false 时用已真机验收的小区列表接口。 */
    private static int touch(Context c, String token, boolean useCheckLogin, boolean quick) {
        final int connectMs = quick ? QinlinApi.WIDGET_PROBE_CONNECT_MS : QinlinApi.PROBE_CONNECT_MS;
        final int readMs = quick ? QinlinApi.WIDGET_PROBE_READ_MS : QinlinApi.PROBE_READ_MS;
        String tag = useCheckLogin ? "checkLogin" : "communityInfo";
        try {
            if (useCheckLogin) {
                // checkLogin 的 data 是会话剩余有效期（秒），顺手记下来给界面用
                int ttl = QinlinApi.checkLogin(token, connectMs, readMs);
                if (ttl > 0) {
                    Prefs.setSessionTtl(c, ttl);
                    if (ttl < 30L * 86400L) {
                        AppLog.w(AppLog.LOGIN, "⚠ 会话剩余有效期仅 " + Prefs.sessionTtlText(c)
                                + "：长期未使用会自然过期，建议打开官方 App 或本 App 走一次请求续期");
                    }
                }
            }
            // checkLogin 给不出结论时只发一次短超时的 communityInfo：
            // communities() 会串行试 4 种姿势（各 5s 连接 + 15s 读），
            // 探活路径不能背这个预算——小部件开门的广播前台时限只有约 10 秒。
            else QinlinApi.communitiesProbe(token, connectMs, readMs);
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
        return probe(c, force, false) == OK;
    }

    /**
     * 强制探活并返回完整结果码（{@link #OK} / {@link #EXPIRED} / {@link #NET_FAIL} …）。
     * 需要区分「凭证真失效」和「网络异常、暂时给不出结论」的调用方用它，
     * 不要再把返回值压成 boolean——弱网下 false 会被误读成掉登录。
     */
    public static int probeNow(Context c) {
        return probe(c, true, false);
    }

    /**
     * 小部件路径专用：同样的三态结论，但用短超时预算。
     * 广播窗口只有约 10 秒，探活用默认的 5s + 6s 会把后面的开门挤出去。
     */
    static int probeNowQuick(Context c) {
        return probe(c, true, true);
    }

    /**
     * 业务请求遇到 401 后调用：强制再探活一次做二次确认。
     *
     * @return true = 确认已超时（已标记过期、凭证仍保留，应切回登录表单）；
     *         false = 刚才是误判/网络抖动且探活成功，登录态保持不变。
     */
    public static boolean expiredAfterRetry(Context c) {
        return expiredAfterRetry(c, false);
    }

    /** 小部件路径的 401 二次确认：短超时，避免把广播窗口用完。 */
    static boolean expiredAfterRetryQuick(Context c) {
        return expiredAfterRetry(c, true);
    }

    static boolean expiredAfterRetry(Context c, boolean quick) {
        final Context app = c.getApplicationContext();
        // 记录发起确认时的凭证：确认过程中用户可能已经重新登录成功，
        // 那时旧请求的 401 属于旧凭证，绝不能拿来标记新会话过期。
        final String before = Prefs.session(app);
        if (before.length() == 0) {
            // 本机没有凭证：登录态本来就是「未登录」，再标记过期只会多出一个
            // 永远点不好的「重试连接」入口（没凭证可探活）。
            AppLog.w(AppLog.LOGIN, "401 二次确认：本机已无凭证，无需标记过期");
            return true;
        }
        int r = probe(c, true, quick);
        if (!before.equals(Prefs.session(app))) {
            AppLog.w(AppLog.LOGIN, "401 二次确认期间凭证已被替换（用户重新登录）→ 忽略旧请求的失效结论");
            return false;
        }
        if (r == OK) {
            AppLog.w(AppLog.LOGIN, "401 二次确认：单独探活成功 → 判定为网关偶发误判，登录态保留");
            return false;
        }
        if (r != EXPIRED) {
            // 探活接口自身没给结论（网络异常 / 参数不兼容 / 已有探活在跑），以原请求的 401 为准，
            // 保守标记过期但不删凭证，用户仍可点「重试连接」恢复。
            Prefs.setSessionExpired(app, true);
            AppLog.w(AppLog.LOGIN, "401 二次确认：探活无结论（"
                    + (r == NET_FAIL ? "网络异常" : r == BIZ_REJECT ? "网关拒绝" : "并发中")
                    + "）→ 按原 401 标记过期，凭证仍保留");
        }
        return true;
    }
}
