package com.wb.qinlin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.widget.RemoteViews;

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
 * 3. 不轮询、不定时唤醒（updatePeriodMillis=0）：服务端按账号单会话，新登录会作废旧凭证，
 *    后台探活挡不住互踢。卡片只在开门 / 换门 / 回到 App 时刷新，会话检查同样按需触发；
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

    // requestCode 分段：开门 4xxxx、配置 5xxxx，各自再叠加 widgetId，避免不同 widget/动作互相覆盖
    private static final int REQ_OPEN = 40000;
    private static final int REQ_APP = 3999;
    private static final int REQ_CONFIG = 50000;

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
        // 只按本机状态重画卡片：不发网络请求、不唤醒后台。
        // 会话检查是「按需」的——用户点开门时 openNow() 会自己探活一次。
        update(context, mgr, ids, style());
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        // 小部件从桌面移除：清掉各自的门绑定，避免残留（四种 provider 都会继承此方法）
        if (appWidgetIds == null) return;
        Context c = context.getApplicationContext();
        for (int id : appWidgetIds) {
            try {
                Prefs.clearWidgetDoor(id, c);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void update(Context ctx, AppWidgetManager mgr, int[] ids, int style) {
        if (ids == null || ids.length == 0) return;
        Context c = ctx.getApplicationContext();
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(c, MODE_NORMAL, style, id));
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
     * 记录一次开门结果。只写两份中的相关部分：
     * ① 本机开门记录文件 —— 仅「成功」才写（对齐 QinlinOpen：失败尝试不刷屏历史，
     *    失败详情仍完整保留在 AppLog 里，可随时查看）；主界面「今日开门」等只读这个文件；
     * ② 桌面卡片「上次：…」状态 —— 成功失败都更新，让卡片即时反馈本次结果。
     *
     * 应用内开门也走这里，两个入口共用同一个写入点，不会再出现「小部件开了门但 App 里查不到」。
     */
    static void noteResult(Context c, boolean ok, String text, int costMs, String from) {
        noteResult(c, ok, text, costMs, from, null);
    }

    /** 带门名的版本：小部件开门用绑定门名写记录，避免多个门共用默认门文案。 */
    static void noteResult(Context c, boolean ok, String text, int costMs, String from,
                           String doorNameOverride) {
        inFlight = 0L;
        if (ok) {
            try {
                String doorName = (doorNameOverride != null && doorNameOverride.length() > 0)
                        ? shortName(doorNameOverride) : recordDoor(c);
                OpenRecord.add(c, true, costMs, doorName,
                        text == null ? "" : text, from == null ? "" : from);
            } catch (Throwable ignored) {
                // 记录写入失败绝不能影响开门结果本身
            }
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
                    mgr.updateAppWidget(id, build(c, mode, style, id));
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
        return build(c, mode, style, AppWidgetManager.INVALID_APPWIDGET_ID);
    }

    /** 带 appWidgetId 的构建：读取该 widget 绑定的门（未绑定回退全局默认门）。 */
    static RemoteViews build(Context c, int mode, int style, int appWidgetId) {
        State s = readState(c, mode, appWidgetId);
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
        int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        boolean logged;
        boolean ready;
        boolean busy;
        String door = "";
        String meta = "";
        String state = "";
        int stateColor = 0xFF5A6B85;
    }

    /** 某个 widget 实际要控制的门：优先用绑定门，未绑定回退全局默认门。 */
    static final class BoundDoor {
        int cid;
        int did;
        String name = "";
        String kind = "";
        boolean bound;   // true=该 widget 显式绑定过；false=回退默认门
    }

    /** 解析 widget 绑定门；JSON 损坏或未绑定时安全回退全局默认门。 */
    static BoundDoor boundDoor(Context c, int appWidgetId) {
        BoundDoor b = new BoundDoor();
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            String raw = Prefs.widgetDoor(appWidgetId, c);
            if (raw != null && raw.length() > 0) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(raw);
                    b.cid = o.optInt("cid", 0);
                    b.did = o.optInt("did", 0);
                    b.name = o.optString("n", "");
                    b.kind = o.optString("k", "");
                    b.bound = b.cid > 0 && b.did > 0;
                } catch (Throwable t) {
                    b.bound = false;
                }
            }
        }
        if (!b.bound) {
            b.cid = Prefs.communityId(c);
            b.did = Prefs.doorId(c);
            b.name = Prefs.doorName(c);
            b.kind = Prefs.doorKind(c);
        }
        return b;
    }

    private static State readState(Context c, int mode, int appWidgetId) {
        State s = new State();
        s.appWidgetId = appWidgetId;
        BoundDoor b = boundDoor(c, appWidgetId);
        final int cid = b.cid;
        final int did = b.did;
        final String doorName = b.name;
        final String kind = b.kind;

        s.logged = Prefs.loggedIn(c);
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
        PendingIntent piOpen = openIntent(c, s.appWidgetId);
        PendingIntent piConfig = configIntent(c, s.appWidgetId);
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? piOpen : piConfig);
        rv.setOnClickPendingIntent(R.id.w_header, piConfig);   // 点门名=给这个 widget 换门
        rv.setOnClickPendingIntent(R.id.w_state, piConfig);
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
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? openIntent(c, s.appWidgetId) : configIntent(c, s.appWidgetId));
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
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? openIntent(c, s.appWidgetId) : configIntent(c, s.appWidgetId));
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
        PendingIntent piConfig = configIntent(c, s.appWidgetId);
        rv.setOnClickPendingIntent(R.id.w_btn, s.ready && !s.busy ? openIntent(c, s.appWidgetId) : piConfig);
        rv.setOnClickPendingIntent(R.id.w_header, piConfig);   // 点门名=给这个 widget 换门
        rv.setOnClickPendingIntent(R.id.w_meta, piConfig);
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

    private static PendingIntent openIntent(Context c, int appWidgetId) {
        // requestCode 用 widgetId 区分，确保多个小部件的 PendingIntent 互不覆盖
        int req = REQ_OPEN + Math.max(0, appWidgetId);
        return PendingIntent.getBroadcast(c, req,
                new Intent(c, WidgetOpenReceiver.class).setAction(ACTION_OPEN)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 点门名/头部：打开门列表的「小部件配置模式」，为这个 widget 单独选门。 */
    private static PendingIntent configIntent(Context c, int appWidgetId) {
        int req = REQ_CONFIG + Math.max(0, appWidgetId);
        return PendingIntent.getActivity(c, req,
                new Intent(c, DoorListActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(DoorListActivity.EXTRA_WIDGET_ID, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- 开门执行 ----------

    /**
     * 在小部件里真正下发开门指令。
     * 前置校验与 App 内 doOpen() 保持一致：无凭证 / 未选门 / 无网络都当场回话，
     * 不让用户对着桌面卡片干等 15 秒超时。
     */
    static void openNow(Context context, int appWidgetId) {
        final Context c = context.getApplicationContext();
        AppLog.init(c);
        final String trace = AppLog.newTrace();
        AppLog.bind(trace);
        try {
            QinlinApi.setOpenid(Prefs.openid(c));

            String token = Prefs.session(c);
            BoundDoor door = boundDoor(c, appWidgetId);
            final int cid = door.cid;
            final int did = door.did;
            final String boundDoorName = door.name;

            if (!Prefs.loggedIn(c)) {
                // 凭证被判超时但本机仍保留：先用它探活一次，恢复就直接继续开门，
                // 用户不必为了点一下桌面按钮先回 App 重新收短信。
                if (token.length() > 0 && SessionGuard.retry(c)) {
                    AppLog.ok(AppLog.LOGIN, trace, "小部件开门前探活成功 → 凭证恢复，继续开门");
                    token = Prefs.session(c);
                    refreshAll(c);
                } else {
                    AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：未登录或凭证超时且探活未恢复");
                    // 被拦下也算一次尝试：写一条失败记录，App 里能看到「为什么没开」
                    noteResult(c, false, token.length() > 0 ? "凭证超时" : "未登录", 0, FROM_WIDGET, boundDoorName);
                    toast(c, token.length() > 0
                            ? "登录已失效，请打开应用点「重试连接」"
                            : "请先在应用里登录");
                    return;
                }
            }
            if (cid <= 0 || did <= 0) {
                AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：未选择门 cid=" + cid + " did=" + did);
                noteResult(c, false, "未选门", 0, FROM_WIDGET, boundDoorName);
                toast(c, "请先在应用里选择要控制的门");
                return;
            }
            if (!Net.online(c)) {
                AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：当前无网络（" + Net.describe(c) + "）");
                noteResult(c, false, "无网络", 0, FROM_WIDGET, boundDoorName);
                toast(c, "当前没有网络，无法开门");
                return;
            }

            AppLog.i(AppLog.OPEN, trace, "桌面小部件触发开门 | session=" + AppLog.fp(token)
                    + " | cid=" + cid + " did=" + did
                    + " | 门=" + (boundDoorName.length() > 0 ? boundDoorName : ("门 #" + did))
                    + " | 绑定=" + door.bound
                    + " | 网络=" + Net.describe(c)
                    + " | 入口=桌面小部件（未打开界面）");

            final long t0 = System.currentTimeMillis();
            try {
                String raw = QinlinApi.openDoor(token, cid, did);
                long cost = System.currentTimeMillis() - t0;
                AppLog.ok(AppLog.OPEN, "小部件开门：服务端已接受指令（耗时 " + cost
                        + "ms，不等于物理门已开）| 原文：" + raw);
                noteResult(c, true, "已开门", (int) cost, FROM_WIDGET, boundDoorName);
                toast(c, "已发出开门指令，请确认门是否打开");
            } catch (QinlinApi.ApiException e) {
                int cost = (int) (System.currentTimeMillis() - t0);
                if ("SESSION_EXPIRED".equals(e.code)) {
                    boolean dead = SessionGuard.expiredAfterRetry(c);
                    AppLog.w(AppLog.LOGIN, trace, dead
                            ? "小部件开门：确认登录超时 → 只标记过期，本机凭证保留"
                            : "小部件开门：401 判定为网关误判，登录态保留");
                    noteResult(c, false, "凭证失效", cost, FROM_WIDGET, boundDoorName);
                    toast(c, dead ? "登录已失效，请打开应用点「重试连接」"
                                 : "刚才是网关临时异常，请再点一次");
                    if (dead) refreshAll(c);
                } else {
                    AppLog.e(AppLog.ERR, trace, "小部件开门失败 [" + e.code + "] " + e.getMessage());
                    noteResult(c, false, "失败 " + e.code, cost, FROM_WIDGET, boundDoorName);
                    toast(c, "开门失败：" + e.code);
                }
            } catch (Exception e) {
                AppLog.e(AppLog.ERR, trace, "小部件开门异常 " + AppLog.stack(e));
                noteResult(c, false, "网络异常",
                        (int) (System.currentTimeMillis() - t0), FROM_WIDGET, boundDoorName);
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

    /** 后台线程也能提示：统一由 Ui 切主线程（ColorOS 上不 cancel 上一条）。 */
    private static void toast(final Context c, final String s) {
        Ui.toast(c, s);
    }
}
