package com.wb.qinlin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 桌面小部件「快捷开门」——四种样式共用这一套逻辑。
 *
 * 设计要点：
 * 1. 只有一个开门入口（主按钮），点下去直接下发指令，**不打开 App**，不打断用户当前在做什么；
 * 2. 结果就地反馈：按钮先变「开门中…」，结果写回同一张卡片的说明行，不依赖 Toast
 *    （小部件是在后台被点的，部分 ROM 会吞掉后台 Toast）；
 * 3. 不轮询、不定时唤醒（updatePeriodMillis=0），只在开门 / 换门 / 回到 App 时刷新；
 * 4. 开门逻辑与 App 内完全一致：同一个 {@link QinlinApi#openDoor}、同一份日志、同样的前置校验，
 *    不做第二套实现，避免两边行为漂移。
 *
 * 四种样式（各自一个 provider，挑选列表里是四条独立条目）：
 * <ul>
 *   <li>{@link OpenWidgetSquare} 1×1：整格一个按钮，最小；</li>
 *   <li>{@link OpenWidgetMini} 2×1：门名 + 胶囊按钮，一行高；</li>
 *   <li>{@link OpenWidgetBar} 4×1：门名 / 上次结果 + 胶囊按钮，一行高；</li>
 *   <li>{@link OpenWidget} 4×2：完整卡片（门名 + 大按钮 + 上次结果），信息最全。</li>
 * </ul>
 */
public class OpenWidget extends AppWidgetProvider {

    /** 点主按钮时发给 {@link WidgetOpenReceiver} 的动作：故意用小部件自己的私有广播。 */
    public static final String ACTION_OPEN = "com.wb.qinlin.action.WIDGET_OPEN";

    /** 本机开门记录的入口标记（常量收敛在 {@link OpenRecord}，这里只做转发）。 */
    static final String FROM_WIDGET = OpenRecord.FROM_WIDGET;
    static final String FROM_APP = OpenRecord.FROM_APP;

    private static final int REQ_OPEN = 3201;
    private static final int REQ_APP = 3202;

    private static final int MODE_NORMAL = 0;
    private static final int MODE_BUSY = 1;

    /** 版式标记：与 provider 一一对应，也决定用哪份 layout。 */
    static final int STYLE_CARD = 0;
    static final int STYLE_SQUARE = 1;
    static final int STYLE_MINI = 2;
    static final int STYLE_BAR = 3;

    /**
     * 正在开门的时间戳。只用于「同一进程内防连点」：
     * 桌面上按钮大、拇指容易重复落点，连点会下发多条开门指令。
     */
    private static volatile long inFlight = 0L;

    /** 子类覆盖：自己属于哪种版式。 */
    protected int style() {
        return STYLE_CARD;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        update(context, mgr, ids, style());
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids, int style) {
        if (ids == null || ids.length == 0) return;
        Context c = ctx.getApplicationContext();
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(c, MODE_NORMAL, style));
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 对外刷新入口 ----------

    /**
     * 把桌面上**每一种样式**的实例都刷成最新状态。
     * 调用点：App 回到前台、登录/退出、换门、App 内开门完成。
     */
    public static void refreshAll(Context context) {
        if (context == null) return;
        updateAll(context.getApplicationContext(), MODE_NORMAL);
    }

    /**
     * 记录一次开门结果（成功 / 失败都记），一次写两份：
     * ① 本机开门记录文件 —— 主界面「最近开门记录 / 今日开门 / 上次开门」读它；
     * ② 桌面卡片「上次：…」状态。
     *
     * 应用内开门也走这里，两个入口共用同一个写入点，不会再出现「小部件开了门但 App 里查不到」。
     */
    static void noteResult(Context c, boolean ok, String text, int costMs, String from) {
        inFlight = 0L;
        try {
            OpenRecord.add(c, ok, costMs, recordDoor(c),
                    text == null ? "" : text, from == null ? "" : from);
        } catch (Throwable ignored) {
            // 记录写入失败绝不能影响开门结果本身
        }
        try {
            Prefs.setWidgetLast(c, (ok ? "1" : "0") + "|" + System.currentTimeMillis()
                    + "|" + (text == null ? "" : text));
        } catch (Throwable ignored) {
        }
        updateAll(c, MODE_NORMAL);
    }

    /** 记录里的门名：与主界面原本的记录规则一致；没存名字就退回门 ID。 */
    private static String recordDoor(Context c) {
        String name = Prefs.doorName(c);
        if (name.length() == 0) {
            int did = Prefs.doorId(c);
            return did > 0 ? "门 #" + did : "";
        }
        return shortName(name);
    }

    /** 请求已发出、结果未回：先把按钮切成「开门中…」，点完立刻有反馈。 */
    static void markBusy(Context context) {
        inFlight = System.currentTimeMillis();
        updateAll(context.getApplicationContext(), MODE_BUSY);
    }

    /** 上一次开门是否还在途中（25 秒兜底，避免异常中断后永久卡在「开门中」）。 */
    static boolean inFlight() {
        long t = inFlight;
        return t != 0L && System.currentTimeMillis() - t < 25000L;
    }

    // ---------- 视图构建 ----------

    /** 四种样式一起刷：样式多了以后，不能只更新自己那一个 provider。 */
    private static void updateAll(Context c, int mode) {
        updateProvider(c, OpenWidget.class, STYLE_CARD, mode);
        updateProvider(c, OpenWidgetSquare.class, STYLE_SQUARE, mode);
        updateProvider(c, OpenWidgetMini.class, STYLE_MINI, mode);
        updateProvider(c, OpenWidgetBar.class, STYLE_BAR, mode);
    }

    private static void updateProvider(Context c, Class<?> cls, int style, int mode) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, cls));
            if (ids == null || ids.length == 0) return;   // 这种样式桌面上没放，跳过
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, build(c, mode, style));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            AppLog.w(AppLog.UI, "刷新桌面小部件失败(" + cls.getSimpleName() + ")：" + t);
        }
    }

    /** 包内可见：便于用真实的 RemoteViews 渲染路径做本地版式核对。 */
    static RemoteViews build(Context c, int mode) {
        return build(c, mode, STYLE_CARD);
    }

    static RemoteViews build(Context c, int mode, int style) {
        State s = readState(c, mode);
        switch (style) {
            case STYLE_SQUARE:
                return buildSquare(c, s);
            case STYLE_MINI:
                return buildMini(c, s);
            case STYLE_BAR:
                return buildBar(c, s);
            default:
                return buildCard(c, s);
        }
    }

    /** 四种样式共享的状态读取：门名 / 是否就绪 / 是否开门中 / 上次结果文案。 */
    private static final class State {
        boolean logged;
        boolean ready;
        boolean busy;
        String door = "";
        String meta = "";
        String state = "";
        int stateColor = 0xFF5A6B85;
    }

    private static State readState(Context c, int mode) {
        State s = new State();
        final String token = Prefs.session(c);
        final int cid = Prefs.communityId(c);
        final int did = Prefs.doorId(c);
        final String doorName = Prefs.doorName(c);
        final String kind = Prefs.doorKind(c);

        s.logged = token.length() > 0;
        s.ready = s.logged && cid > 0 && did > 0;
        s.busy = mode == MODE_BUSY;

        if (s.ready) {
            s.door = doorName.length() > 0 ? shortName(doorName) : ("门 #" + did);
            s.meta = kind.length() > 0 ? kind : "已选门";
        } else {
            s.door = s.logged ? "还没选门" : "未登录";
            s.meta = s.logged ? "去选门" : "去登录";
        }

        if (s.busy) {
            s.state = "正在下发开门指令…";
            s.stateColor = 0xFFB3261E;
        } else if (!s.ready) {
            s.state = s.logged ? "选好门后，这里就能一键开门" : "登录并选门后，这里直接开门";
            s.stateColor = 0xFF2563EB;
        } else {
            String[] p = Prefs.widgetLast(c).split("\\|", 3);
            if (p.length == 3) {
                boolean ok = "1".equals(p[0]);
                long ts = 0L;
                try {
                    ts = Long.parseLong(p[1]);
                } catch (Throwable ignored) {
                }
                s.state = "上次：" + when(ts) + " · " + p[2];
                s.stateColor = ok ? 0xFF137333 : 0xFFB3261E;
            } else {
                s.state = "点按钮直接开门，无需打开应用";
            }
        }
        return s;
    }

    /** 4×2 完整卡片。 */
    private static RemoteViews buildCard(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open);

        rv.setTextViewText(R.id.w_door, s.door);
        rv.setTextViewText(R.id.w_meta, s.meta);
        rv.setTextColor(R.id.w_door, s.ready ? 0xFF16233B : 0xFF5A6B85);
        rv.setTextColor(R.id.w_meta, s.ready ? 0xFF8A94A6 : 0xFF2563EB);
        rv.setTextColor(R.id.w_dot, s.ready ? 0xFF137333 : 0xFF9AA7BC);

        applyButton(rv, s, R.drawable.widget_btn_red, R.drawable.widget_btn_busy,
                R.drawable.widget_btn_blue, "开 门", "开门中…", "打开应用");

        rv.setTextViewText(R.id.w_state, s.state);
        rv.setTextColor(R.id.w_state, s.stateColor);

        PendingIntent piApp = appIntent(c);
        PendingIntent piOpen = openIntent(c);
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? piOpen : piApp);
        rv.setOnClickPendingIntent(R.id.w_header, piApp);
        rv.setOnClickPendingIntent(R.id.w_state, piApp);
        return rv;
    }

    /** 1×1：整格一个按钮，没有第二块文字的空间。 */
    private static RemoteViews buildSquare(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open_square);

        if (s.busy) {
            rv.setTextViewText(R.id.w_btn, "开门中");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 12f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_sq_busy);
        } else if (s.ready) {
            rv.setTextViewText(R.id.w_btn, "开门");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 13f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_sq_red);
        } else {
            // 1×1 只有 40dp 宽，三个字（去选门）会溢出格子，收成两个字的动作词
            rv.setTextViewText(R.id.w_btn, s.logged ? "选门" : "登录");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 12.5f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_sq_blue);
        }
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? openIntent(c) : appIntent(c));
        return rv;
    }

    /** 2×1：整条一个按钮（这个宽度放不下门名，索性把面积全给按钮）。 */
    private static RemoteViews buildMini(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open_mini);
        if (s.busy) {
            rv.setTextViewText(R.id.w_btn, "开门中…");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 13f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_busy);
        } else if (s.ready) {
            rv.setTextViewText(R.id.w_btn, "开 门");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 14f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_red);
        } else {
            rv.setTextViewText(R.id.w_btn, s.logged ? "去选门" : "去登录");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 12.5f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_blue);
        }
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? openIntent(c) : appIntent(c));
        return rv;
    }

    /** 4×1：左边门名 + 上次结果（两行），右边胶囊按钮。 */
    private static RemoteViews buildBar(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open_bar);

        rv.setTextViewText(R.id.w_door, s.door);
        rv.setTextColor(R.id.w_door, s.ready ? 0xFF16233B : 0xFF5A6B85);
        rv.setTextColor(R.id.w_dot, s.ready ? 0xFF137333 : 0xFF9AA7BC);
        rv.setTextViewText(R.id.w_meta, shortState(s));
        rv.setTextColor(R.id.w_meta, s.stateColor);

        applyPill(rv, s, "开 门", "开门中…");
        PendingIntent piApp = appIntent(c);
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? openIntent(c) : piApp);
        rv.setOnClickPendingIntent(R.id.w_header, piApp);
        rv.setOnClickPendingIntent(R.id.w_meta, piApp);
        return rv;
    }

    /** 三种按钮状态（可开 / 开门中 / 未就绪）统一套用。 */
    private static void applyButton(RemoteViews rv, State s, int red, int busy, int blue,
                                    String openText, String busyText, String appText) {
        if (s.busy) {
            rv.setTextViewText(R.id.w_btn, busyText);
            rv.setInt(R.id.w_btn, "setBackgroundResource", busy);
        } else if (s.ready) {
            rv.setTextViewText(R.id.w_btn, openText);
            rv.setInt(R.id.w_btn, "setBackgroundResource", red);
        } else {
            rv.setTextViewText(R.id.w_btn, appText);
            rv.setInt(R.id.w_btn, "setBackgroundResource", blue);
        }
    }

    /** 横条 / 迷你版的胶囊按钮：字更小，未就绪时收成「去登录 / 去选门」。 */
    private static void applyPill(RemoteViews rv, State s, String openText, String busyText) {
        if (s.busy) {
            rv.setTextViewText(R.id.w_btn, busyText);
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 11.5f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_busy);
        } else if (s.ready) {
            rv.setTextViewText(R.id.w_btn, openText);
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 13f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_red);
        } else {
            rv.setTextViewText(R.id.w_btn, s.logged ? "去选门" : "去登录");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 11.5f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_blue);
        }
    }

    /** 迷你 / 横条只有一行说明位：「上次：今天 09:41 · 已开门」压成「今天 09:41 · 已开门」。 */
    private static String shortState(State s) {
        String t = s.state;
        if (t != null && t.startsWith("上次：")) t = t.substring(3);
        return t;
    }

    private static PendingIntent appIntent(Context c) {
        return PendingIntent.getActivity(c, REQ_APP,
                new Intent(c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openIntent(Context c) {
        return PendingIntent.getBroadcast(c, REQ_OPEN,
                new Intent(c, WidgetOpenReceiver.class).setAction(ACTION_OPEN),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- 开门执行 ----------

    /**
     * 在小部件里真正下发开门指令。
     * 前置校验与 App 内 doOpen() 保持一致：无凭证 / 未选门 / 无网络都当场回话，
     * 不让用户对着桌面卡片干等 15 秒超时。
     */
    static void openNow(Context context) {
        final Context c = context.getApplicationContext();
        AppLog.init(c);
        final String trace = AppLog.newTrace();
        AppLog.bind(trace);
        try {
            QinlinApi.setOpenid(Prefs.openid(c));

            final String token = Prefs.session(c);
            final int cid = Prefs.communityId(c);
            final int did = Prefs.doorId(c);

            if (token.length() == 0) {
                AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：本机无凭证（未登录）");
                // 被拦下也算一次尝试：写一条失败记录，App 里能看到「为什么没开」
                noteResult(c, false, "未登录", 0, FROM_WIDGET);
                toast(c, "请先在应用里登录");
                return;
            }
            if (cid <= 0 || did <= 0) {
                AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：未选择门 cid=" + cid + " did=" + did);
                noteResult(c, false, "未选门", 0, FROM_WIDGET);
                toast(c, "请先在应用里选择要控制的门");
                return;
            }
            if (!Net.online(c)) {
                AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：当前无网络（" + Net.describe(c) + "）");
                noteResult(c, false, "无网络", 0, FROM_WIDGET);
                toast(c, "当前没有网络，无法开门");
                return;
            }

            AppLog.i(AppLog.OPEN, trace, "桌面小部件触发开门 | session=" + AppLog.fp(token)
                    + " | cid=" + cid + " did=" + did
                    + " | 门=" + Prefs.doorName(c)
                    + " | 网络=" + Net.describe(c)
                    + " | 入口=桌面小部件（未打开界面）");

            final long t0 = System.currentTimeMillis();
            try {
                String raw = QinlinApi.openDoor(token, cid, did);
                long cost = System.currentTimeMillis() - t0;
                AppLog.ok(AppLog.OPEN, "小部件开门：服务端已接受指令（耗时 " + cost
                        + "ms，不等于物理门已开）| 原文：" + raw);
                noteResult(c, true, "已开门", (int) cost, FROM_WIDGET);
                toast(c, "已发出开门指令，请确认门是否打开");
            } catch (QinlinApi.ApiException e) {
                int cost = (int) (System.currentTimeMillis() - t0);
                if ("SESSION_EXPIRED".equals(e.code)) {
                    Prefs.clearSession(c);
                    AppLog.w(AppLog.LOGIN, trace, "小部件开门：凭证已失效 → 已清除本机凭证，请重新登录");
                    noteResult(c, false, "凭证失效", cost, FROM_WIDGET);
                    toast(c, "凭证已失效，请打开应用重新登录");
                } else {
                    AppLog.e(AppLog.ERR, trace, "小部件开门失败 [" + e.code + "] " + e.getMessage());
                    noteResult(c, false, "失败 " + e.code, cost, FROM_WIDGET);
                    toast(c, "开门失败：" + e.code);
                }
            } catch (Exception e) {
                AppLog.e(AppLog.ERR, trace, "小部件开门异常 " + AppLog.stack(e));
                noteResult(c, false, "网络异常",
                        (int) (System.currentTimeMillis() - t0), FROM_WIDGET);
                toast(c, "开门失败：网络异常，请检查网络");
            }
        } finally {
            AppLog.unbind();
        }
    }

    // ---------- 小工具 ----------

    /** 小部件版面窄，门名只留最后一段：「楼栋门 · 1栋2单元」→「1栋2单元」。 */
    private static String shortName(String name) {
        int p = name.indexOf("  [在线:");
        if (p > 0) name = name.substring(0, p);
        int sep = name.indexOf(" · ");
        return sep > 0 ? name.substring(sep + 3) : name;
    }

    /** 状态行用的短时间：刚刚 / N 分钟前 / 14:05 / 昨天 22:10 / 11-08 09:41。 */
    private static String when(long ts) {
        if (ts <= 0L) return "刚刚";
        long d = System.currentTimeMillis() - ts;
        if (d < 0L) d = 0L;
        if (d < 60000L) return "刚刚";
        if (d < 3600000L) return (d / 60000L) + "分钟前";

        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        if (sameDay(now, then)) return hm.format(new Date(ts));
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(now, then)) return "昨天 " + hm.format(new Date(ts));
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ts));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** 后台线程也能提示：统一 post 到主线程。 */
    private static void toast(final Context c, final String s) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
