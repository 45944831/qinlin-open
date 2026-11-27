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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
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
     * 主题色：索引与 res/drawable 的 widget_*_cN 一一对应。
     * 用 getIdentifier 按名取资源，避免写死 R.drawable 常量（新增配色不用改 Java）。
     */
    static final String[] COLORS = {"经典红", "海蓝", "松绿", "紫罗兰", "暖橙", "青碧"};
    private static final String BG_RES = "widget_bg_c";
    private static final String BTN_RES = "widget_btn_c";
    private static final String PILL_RES = "widget_pill_c";
    private static final String SQ_RES = "widget_sq_c";

    /** 版式的中文尺寸名，设置界面 Tab 展示用。 */
    static String sizeLabel(int style) {
        switch (style) {
            case STYLE_SQUARE: return "1×1 按钮";
            case STYLE_MINI: return "2×1 长条";
            case STYLE_BAR: return "4×1 横条";
            default: return "4×2 大卡片";
        }
    }

    /** 默认组件名：未自定义时按版式给一个稳定名字。 */
    static String defaultName(int style) { return "开门 · " + sizeLabel(style); }

    /** 桌面上一个真实小组件实例（设置界面列出用）。 */
    static final class Installed {
        public final int id;
        public final int style;
        Installed(int id, int style) { this.id = id; this.style = style; }
        String size() { return sizeLabel(style); }
        String name(Context c) {
            String n = Prefs.widgetName(id, c);
            return n.length() > 0 ? n : defaultName(style);
        }
    }

    /** 枚举桌面上已放置的全部小组件实例（四种 provider 都查一遍）。 */
    static List<Installed> listInstalled(Context c) {
        List<Installed> out = new ArrayList<Installed>();
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        addInstalled(c, mgr, OpenWidget.class, STYLE_CARD, out);
        addInstalled(c, mgr, OpenWidgetBar.class, STYLE_BAR, out);
        addInstalled(c, mgr, OpenWidgetMini.class, STYLE_MINI, out);
        addInstalled(c, mgr, OpenWidgetSquare.class, STYLE_SQUARE, out);
        return out;
    }

    private static void addInstalled(Context c, AppWidgetManager mgr, Class<?> cls,
                                     int style, List<Installed> out) {
        try {
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, cls));
            if (ids == null) return;
            for (int id : ids) out.add(new Installed(id, style));
        } catch (Throwable t) {
            AppLog.w(AppLog.UI, "枚举小组件失败(" + cls.getSimpleName() + ")：" + t);
        }
    }

    /** 安全取 drawable 资源 id：主题色索引越界时回退到 0（默认红）。 */
    private static int colorRes(Context c, String prefix, int colorIdx) {
        int idx = colorIdx;
        if (idx < 0 || idx >= COLORS.length) idx = 0;
        int id = c.getResources().getIdentifier(prefix + idx, "drawable", c.getPackageName());
        return id != 0 ? id : 0;
    }

    /**
     * 在途请求的「代次」：只用于「同一进程内防连点」——桌面按钮大、拇指容易重复落点。
     *
     * <p>2026-11-26 修正（第二轮）：代次仍有，但状态改成 <b>按 appWidgetId 分桶</b>。
     * 旧实现是进程级单值锁，一屏放了「车库闸」「单元门」两个组件时，点其中一个、
     * 另一个在 60 秒内点下去 100% 被静默丢弃（无提示、无卡片变化），
     * 与「一屏多门」的产品定位正相反。现在每个组件各有自己的在途序号与起始时间。</p>
     */
    private static final Object INFLIGHT_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** appWidgetId → 在途序号；不存在 = 该组件空闲。 */
    private static final java.util.HashMap<Integer, Long> INFLIGHT_SEQ =
            new java.util.HashMap<Integer, Long>();
    /** appWidgetId → 起始时间，只作为「线程意外消失」的最后兜底（60s > 探活+开门最坏预算）。 */
    private static final java.util.HashMap<Integer, Long> INFLIGHT_AT =
            new java.util.HashMap<Integer, Long>();
    /** 正在开门的组件集合：只让被点的那个显示「开门中」，其它组件保持正常外观。 */
    private static final java.util.HashSet<Integer> BUSY_IDS =
            new java.util.HashSet<Integer>();
    private static final long INFLIGHT_TIMEOUT_MS = 60000L;

    /**
     * 本进程的随机标签，随「尝试标记」一起落盘。
     *
     * <p>为什么需要它：广播窗口只有约 10 秒，而弱网下开门可能更久。进程在请求途中被
     * 系统回收时，finally 里的 {@link #finishRequest} 根本不会执行，卡片就会永久停在
     * 「开门中…」。渲染时把落盘的标签与当前进程比对：标签不同 = 进程重启过，
     * 那次请求必然已中断，直接显示「上次未收到结果」而不是继续假装在飞。</p>
     */
    private static final String PROC_TAG = java.util.UUID.randomUUID().toString();

    /** 广播里的 id 数组上限：正常用户桌面不可能放这么多组件，超过就是伪造/异常。 */
    private static final int MAX_BROADCAST_IDS = 64;

    /**
     * 重绘串行锁：保证「清锁 + 画正常态」与「加锁 + 画开门中」不会交错落盘。
     * 旧实现在同步块外 updateAll，刚清完锁、还没重绘时来了新点击，
     * 新的 MODE_BUSY 会被这次的 MODE_NORMAL 盖回普通态（卡片看起来没反应）。
     */
    private static final Object PAINT_LOCK = new Object();

    /** 四种版式：provider → 版式，用于「按 appWidgetId 找到它属于哪一份 layout」。 */
    private static final Class<?>[] PROVIDERS = {
            OpenWidget.class, OpenWidgetSquare.class, OpenWidgetMini.class, OpenWidgetBar.class};
    private static final int[] PROVIDER_STYLES = {
            STYLE_CARD, STYLE_SQUARE, STYLE_MINI, STYLE_BAR};

    /** 子类覆盖：自己属于哪种版式。 */
    protected int style() {
        return STYLE_CARD;
    }

    // ---------- 入站校验（provider 是 exported=true） ----------

    /**
     * 导出的 AppWidget provider 会收到系统广播，但第三方也可能用<b>显式广播</b>直达这里
     * （Android 7+ 只限制隐式广播）。不设防的话，一个外部应用可以：
     * ① 反复发 {@code ACTION_APPWIDGET_DELETED}，逐个 id 触发 onDeleted，
     *    把用户设置的组件名 / 主题色 / 门绑定全部清掉；
     * ② 塞一个超大的 {@code EXTRA_APPWIDGET_IDS}，让 onUpdate 在主线程循环几十万次造成 ANR。
     *
     * <p>因此每次都做两件事：<b>id 必须与本 provider 实际拥有的实例取交集</b>
     * （同时天然裁掉超大数组），以及<b>会改数据的动作必须来自 system / root / 本应用</b>。</p>
     *
     * <p>刻意不对 {@code ACTION_APPWIDGET_UPDATE} 做发送方校验：它只是「重画一遍」，
     * 内容完全由本机状态决定，无副作用；而个别第三方启动器会自己补发这条广播，
     * 一律拦掉会让组件在那些 ROM 上停止刷新。</p>
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!trustedWidgetBroadcast(context, intent)) return;
        super.onReceive(context, intent);
    }

    private boolean trustedWidgetBroadcast(Context context, Intent intent) {
        if (intent == null) return false;
        final String action = intent.getAction();
        if (action == null || !action.startsWith("android.appwidget.action.")) return false;

        final boolean isDelete = AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action);
        final int[] own = ownIds(context);
        final int[] wanted = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);

        if (isDelete) {
            // 删除这条特殊：广播送达时该 widget 往往已经从系统列表里消失，
            // 若还去和 own 求交集，keep 会变空 → 清理逻辑永远不执行 → `wd_/wn_/wc_` 残留在
            // 存储里，下一次复用同一个 appWidgetId 的新组件会继承旧门绑定。
            // 所以这里改为「只认可信发送方 + 数量上限 + 去重」，不动 id 本身。
            if (!fromTrustedSender(action)) return false;
            if (wanted == null) return true;   // 少数 ROM 只带单个 id，onDeleted 会拿 null 直接返回
            int[] keep = dedupeCap(wanted, MAX_BROADCAST_IDS);
            if (keep.length == 0) return false;
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, keep);
            return true;
        }

        if (wanted != null) {
            int[] keep = intersect(wanted, own);
            if (keep.length == 0) {
                AppLog.w(AppLog.UI, "忽略组件广播 " + action + "：未命中本 provider 的任何实例");
                return false;
            }
            if (keep.length != wanted.length) {
                AppLog.w(AppLog.UI, "组件广播 " + action + " 的 id 数量 " + wanted.length
                        + " → 收紧为 " + keep.length + "（多出的不属于本 provider）");
            }
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, keep);
            return true;
        }

        // OPTIONS_CHANGED 之类用单个 id
        int single = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (single != AppWidgetManager.INVALID_APPWIDGET_ID) {
            for (int id : own) {
                if (id == single) return fromTrustedSender(action) || isRedrawAction(action);
            }
            AppLog.w(AppLog.UI, "忽略组件广播 " + action + "：id=" + single + " 不属于本 provider");
            return false;
        }

        // ENABLED / DISABLED 不带 id，属于正常情况
        return AppWidgetManager.ACTION_APPWIDGET_ENABLED.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_DISABLED.equals(action);
    }

    /** 去掉非法值与重复项，并把总长度限制在 max 以内（用于删除广播，不能与自有 id 求交集）。 */
    private static int[] dedupeCap(int[] ids, int max) {
        if (ids == null || ids.length == 0 || max <= 0) return new int[0];
        final int cap = Math.min(ids.length, max);
        int[] buf = new int[cap];
        int n = 0;
        outer:
        for (int i = 0; i < cap; i++) {
            int id = ids[i];
            if (id <= 0) continue;
            for (int j = 0; j < n; j++) {
                if (buf[j] == id) continue outer;
            }
            buf[n++] = id;
        }
        if (n == cap) return buf;
        int[] out = new int[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    /** 发送方是否为 system / root / 本应用。 */
    private static boolean fromTrustedSender(String action) {
        final int uid = android.os.Binder.getCallingUid();
        if (uid == android.os.Process.SYSTEM_UID
                || uid == android.os.Process.ROOT_UID
                || uid == android.os.Process.myUid()) {
            return true;
        }
        AppLog.w(AppLog.UI, "忽略组件广播 " + action + "：发送方 uid=" + uid
                + " 既非系统也非本应用（疑似第三方伪造）");
        return false;
    }

    /** 无副作用的「重画」类动作：不校验发送方，避免个别启动器补发时组件停更。 */
    private static boolean isRedrawAction(String action) {
        // AppWidgetProvider 里对应的字符串常量是 "android.appwidget.action.APPWIDGET_UPDATE"，
        // 选项变化那条没有公开常量名，按官方字面量比对（与 AppWidgetProvider 源码一致）。
        return AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || "android.appwidget.action.APPWIDGET_OPTIONS_CHANGED".equals(action);
    }

    private int[] ownIds(Context context) {
        try {
            return AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(new ComponentName(context, getClass()));
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /** 取交集：不在 own 里的 id 丢弃，重复 id 只保留一次。循环上限 = min(请求数, 自有数)。 */
    private static int[] intersect(int[] wanted, int[] own) {
        if (wanted == null || own == null || wanted.length == 0 || own.length == 0) {
            return new int[0];
        }
        java.util.HashSet<Integer> set = new java.util.HashSet<Integer>();
        for (int id : own) set.add(Integer.valueOf(id));
        final int cap = Math.min(wanted.length, own.length);
        int[] buf = new int[cap];
        int n = 0;
        for (int i = 0; i < cap; i++) {
            Integer id = Integer.valueOf(wanted[i]);
            if (!set.remove(id)) continue;      // 不属于本 provider，或重复
            buf[n++] = id.intValue();
        }
        if (n == cap) return buf;
        int[] out = new int[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
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
    /**
     * App 内开门：写本机记录，但<b>不把结果写到桌面卡片</b>——每个组件只反映通过它自己
     * 发生的开门，避免 App 里开一次门把所有组件的结果都改掉。仍走 updateAll 清 busy。
     */
    static void noteResult(Context c, boolean ok, String text, int costMs, String from) {
        noteResult(c, ok, text, costMs, from, null, AppWidgetManager.INVALID_APPWIDGET_ID);
    }

    /**
     * 小部件开门：写本机记录 + 把结果写到<b>被点的那一个</b>组件卡片。
     * 带门名是为了避免多个门共用默认门文案。
     */
    static void noteResult(Context c, boolean ok, String text, int costMs, String from,
                           String doorNameOverride, int appWidgetId) {
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
        // 桌面组件开门结果写回「被点的那一个」卡片。防连点 / busy 的复位不再放这里：
        // 旧实现在 noteResult 里无条件清锁，弱网下先完成的旧请求会把仍在飞的新请求的锁
        // 一起清掉；且前置校验异常时根本走不到 noteResult，锁会卡满兜底时间。
        // 现在统一由 WidgetOpenReceiver 的 finally 调用 finishRequest(seq) 收尾。
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                Prefs.setWidgetLast(c, appWidgetId, (ok ? "1" : "0") + "|"
                        + System.currentTimeMillis() + "|" + (text == null ? "" : text));
            } catch (Throwable ignored) {
            }
            // 不在这里 updateAll：重绘统一交给 Receiver.finally → finishRequest。
            // 若此刻已有更新的请求 markBusy，这里的 MODE_NORMAL 会晚于它的 MODE_BUSY
            // 落到桌面，把新请求刚点亮的「开门中」覆盖回普通态（视觉错乱）。
        } else if (!anyInFlight()) {
            // App 内开门：若桌面仍有请求在飞，别重绘，否则会抹掉它的 busy 视觉反馈
            synchronized (PAINT_LOCK) {
                if (!anyInFlight()) updateAll(c, MODE_NORMAL);
            }
        }
    }

    /**
     * 结束一次小部件开门请求：只有该组件、该代次仍匹配才复位在途锁。
     * @param seq {@link #markBusy} 返回的代次；旧请求结束后新请求已开始时不匹配，不误清新锁。
     */
    static void finishRequest(Context c, int appWidgetId, long seq) {
        synchronized (INFLIGHT_LOCK) {
            Long cur = INFLIGHT_SEQ.get(Integer.valueOf(appWidgetId));
            if (cur == null || cur.longValue() != seq) {
                AppLog.i(AppLog.OPEN, "组件 " + appWidgetId + " 的在途锁已属于更新的请求（seq="
                        + cur + "），本次 seq=" + seq + " 不复位");
                return;
            }
            INFLIGHT_SEQ.remove(Integer.valueOf(appWidgetId));
            INFLIGHT_AT.remove(Integer.valueOf(appWidgetId));
            BUSY_IDS.remove(Integer.valueOf(appWidgetId));
        }
        try {
            Prefs.clearWidgetAttempt(c.getApplicationContext(), appWidgetId);
        } catch (Throwable ignored) {
        }
        // 重绘与 markBusy 的重绘严格串行，避免旧请求的正常态盖掉新请求的「开门中」。
        // 只重画这一个组件：点 A 组件时没必要把桌面上的 B 也重建一遍（IPC 更少、更稳）。
        synchronized (PAINT_LOCK) {
            updateOne(c.getApplicationContext(), appWidgetId, MODE_NORMAL);
        }
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

    /**
     * 请求已发出、结果未回：只把被点的那个按钮切成「开门中…」，其它组件不受影响。
     * @return 本次请求的代次，必须由调用方在 finally 里传给 {@link #finishRequest}。
     */
    static long markBusy(Context context, int appWidgetId) {
        long seq = SEQ.incrementAndGet();
        long now = System.currentTimeMillis();
        synchronized (INFLIGHT_LOCK) {
            INFLIGHT_SEQ.put(Integer.valueOf(appWidgetId), Long.valueOf(seq));
            INFLIGHT_AT.put(Integer.valueOf(appWidgetId), Long.valueOf(now));
            BUSY_IDS.add(Integer.valueOf(appWidgetId));
        }
        // 落一份「尝试标记」：进程若在请求途中被回收，下一次渲染靠它识别「上次没收到结果」，
        // 而不是把卡片永远钉在「开门中…」。
        try {
            Prefs.setWidgetAttempt(context.getApplicationContext(), appWidgetId,
                    PROC_TAG + "|" + now);
        } catch (Throwable ignored) {
        }
        synchronized (PAINT_LOCK) {
            updateOne(context.getApplicationContext(), appWidgetId, MODE_BUSY);
        }
        return seq;
    }

    /** 该组件上一次开门是否还在途中。正常由 finishRequest 收尾；60 秒只兜「线程意外消失」。 */
    static boolean inFlight(int appWidgetId) {
        synchronized (INFLIGHT_LOCK) {
            Long seq = INFLIGHT_SEQ.get(Integer.valueOf(appWidgetId));
            if (seq == null) return false;
            Long at = INFLIGHT_AT.get(Integer.valueOf(appWidgetId));
            long start = at == null ? 0L : at.longValue();
            if (System.currentTimeMillis() - start < INFLIGHT_TIMEOUT_MS) return true;
            // 请求线程若被系统回收没走 finally，超时后强制放行，避免永久卡死。
            AppLog.w(AppLog.OPEN, "组件 " + appWidgetId + " 的在途开门超过 " + INFLIGHT_TIMEOUT_MS
                    + "ms 未收尾（seq=" + seq + "）→ 强制释放锁");
            INFLIGHT_SEQ.remove(Integer.valueOf(appWidgetId));
            INFLIGHT_AT.remove(Integer.valueOf(appWidgetId));
            BUSY_IDS.remove(Integer.valueOf(appWidgetId));
            return false;
        }
    }

    /** 是否还有任意组件在途（App 内开门据此决定要不要重绘桌面）。 */
    static boolean anyInFlight() {
        java.util.ArrayList<Integer> ids;
        synchronized (INFLIGHT_LOCK) {
            if (INFLIGHT_SEQ.isEmpty()) return false;
            ids = new java.util.ArrayList<Integer>(INFLIGHT_SEQ.keySet());
        }
        for (Integer id : ids) {
            if (inFlight(id.intValue())) return true;
        }
        return false;
    }

    /** 该组件是否正在开门（决定卡片是否显示「开门中…」）。 */
    static boolean isBusy(int appWidgetId) {
        synchronized (INFLIGHT_LOCK) {
            return BUSY_IDS.contains(Integer.valueOf(appWidgetId));
        }
    }

    // ---------- 视图构建 ----------

    /** 四种样式一起刷：App 回前台 / 登录 / 换门这类全局状态变化时用。 */
    private static void updateAll(Context c, int mode) {
        for (int i = 0; i < PROVIDERS.length; i++) {
            updateProvider(c, PROVIDERS[i], PROVIDER_STYLES[i], mode);
        }
    }

    /**
     * 只刷一个组件：开门中 / 开门结果只影响被点的那一个。
     * 旧实现每次都 updateAll，桌面上放四个组件就要重建四份 RemoteViews 并跨进程推四次。
     */
    private static void updateOne(Context c, int appWidgetId, int mode) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            updateAll(c, mode);
            return;
        }
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            for (int i = 0; i < PROVIDERS.length; i++) {
                ComponentName cn = new ComponentName(c, PROVIDERS[i]);
                int[] ids = mgr.getAppWidgetIds(cn);
                if (ids == null) continue;
                for (int id : ids) {
                    if (id != appWidgetId) continue;
                    mgr.updateAppWidget(id, build(c, mode, PROVIDER_STYLES[i], id));
                    return;
                }
            }
            AppLog.i(AppLog.UI, "组件 " + appWidgetId + " 已不在桌面上，跳过重绘");
        } catch (Throwable t) {
            AppLog.w(AppLog.UI, "刷新组件 " + appWidgetId + " 失败：" + t);
            updateAll(c, mode);   // 定向失败就退回全量，至少别让界面停在旧状态
        }
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
        State s = readState(c, mode, style, appWidgetId);
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
        /**
         * 凭证被判超时但本机仍保留，且该组件绑着有效的门：
         * 点按钮先探活一次，成功就直接开门，用户不必为了点一下桌面按钮先回 App 重新收短信。
         */
        boolean recoverable;
        boolean busy;
        /**
         * 上一次开门请求没有收尾（进程被回收 / 线程消失），已确认中断。
         * 与 busy 互斥：busy 是「正在飞」，interrupted 是「飞过但没回来」。
         */
        boolean interrupted;
        String door = "";
        String meta = "";
        String state = "";
        // 由 readState 按资源色填充（深色模式下要换成浅色，不能写死 ARGB）
        int stateColor = 0;
        int colorIdx = 0;      // 主题色索引
        String label = "";     // 组件自定义名（含默认名回退）

        /** 按钮是否应当下发开门（就绪，或可尝试自动恢复）。 */
        boolean openable() { return ready || recoverable; }
    }

    /** 某个 widget 实际要控制的门：优先用绑定门，未绑定回退全局默认门。 */
    static final class BoundDoor {
        int cid;
        int did;
        String name = "";
        String kind = "";
        boolean bound;   // true=该 widget 显式绑定过；false=回退默认门
        /**
         * true=绑定过但内容读不出来（Keystore 失效 / 密文损坏 / JSON 损坏）。
         * 此时<b>不能</b>回退默认门：卡片标题还写着旧门名，点下去会开另一扇门。
         */
        boolean broken;
    }

    /**
     * 解析落盘的「尝试标记」{@code <进程标签>|<时间戳>}。
     * @return {起始时间戳, 是否已失效}；时间戳 0 表示没有标记或格式不可识别
     */
    private static long[] parseAttempt(String raw) {
        if (raw == null || raw.length() == 0) return new long[]{0L, 0L};
        int sep = raw.indexOf('|');
        if (sep <= 0) return new long[]{0L, 0L};
        long ts;
        try {
            ts = Long.parseLong(raw.substring(sep + 1));
        } catch (Exception e) {
            return new long[]{0L, 0L};
        }
        if (ts <= 0) return new long[]{0L, 0L};
        long age = System.currentTimeMillis() - ts;
        boolean sameProc = raw.startsWith(PROC_TAG + "|");
        // 进程换了 → 请求必然已中断；同一进程超时 / 时间被大幅回拨 → 也认定失效
        boolean stale = !sameProc || age > INFLIGHT_TIMEOUT_MS || age < -INFLIGHT_TIMEOUT_MS;
        return new long[]{ts, stale ? 1L : 0L};
    }

    /** 解析 widget 绑定门；未绑定回退全局默认门，绑定损坏则拒绝回退（交给用户重选）。 */
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
            if (!b.bound) {
                // 绑过（键存在）却解析不出来：数据已损坏。回退默认门会让「标签写着 A 门、
                // 点下去开 B 门」，比不能开更糟。这里只标 broken，让界面退成「去选门」。
                if (Prefs.hasWidgetDoor(appWidgetId, c)) {
                    b.broken = true;
                    AppLog.w(AppLog.UI, "组件 " + appWidgetId
                            + " 的门绑定已损坏（密文/JSON 解析失败）→ 不回落默认门，提示重新选门");
                    return b;
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

    private static State readState(Context c, int mode, int style, int appWidgetId) {
        State s = new State();
        s.stateColor = c.getColor(R.color.q_sub);
        s.appWidgetId = appWidgetId;
        s.colorIdx = Prefs.widgetColor(appWidgetId, c);
        String custom = Prefs.widgetName(appWidgetId, c);
        s.label = custom.length() > 0 ? custom : defaultName(style);
        BoundDoor b = boundDoor(c, appWidgetId);
        final int cid = b.cid;
        final int did = b.did;
        final String doorName = b.name;
        final String kind = b.kind;

        s.logged = Prefs.loggedIn(c);
        s.ready = s.logged && cid > 0 && did > 0;
        // 凭证还在、只是被服务端标记超时：允许点按钮先探活再开门（openNow 里有完整四态处理）。
        // 旧实现只在应用内做这件事，桌面组件一律把用户赶回 App 收短信。
        s.recoverable = !s.logged && cid > 0 && did > 0 && Prefs.session(c).length() > 0;
        // 在途状态以「该组件自己的锁」为准，不再看这次的 mode：
        // 旧写法 mode==MODE_BUSY && isBusy(id)，任何一次 NORMAL 重绘（App 回前台等）
        // 都会把正在飞的组件画成空闲，用户看到按钮又变回「开门」就去点第二次。
        s.busy = isBusy(appWidgetId);
        if (!s.busy) {
            long[] attempt = parseAttempt(Prefs.widgetAttempt(appWidgetId, c));
            if (attempt[0] > 0) {
                if (attempt[1] == 0) {
                    // 同一进程、未超时，但内存里没有在途锁：极窄的时间窗（刚 markBusy）。
                    // 按「在飞」显示，避免按钮闪一下又能点。
                    s.busy = true;
                } else {
                    // 进程标签不同（重启过）或已超过兜底时长 → 那次请求必然中断了
                    s.interrupted = true;
                    Prefs.clearWidgetAttempt(c, appWidgetId);
                    AppLog.w(AppLog.UI, "组件 " + appWidgetId
                            + " 上一次开门没有收尾 → 标记为「已中断，可重试」");
                }
            }
        }

        if (s.openable()) {
            s.door = doorName.length() > 0 ? shortName(doorName) : ("门 #" + did);
            s.meta = kind.length() > 0 ? kind : "已选门";
        } else if (b.broken) {
            s.door = "门绑定已失效";
            s.meta = "请重新选门";
        } else {
            s.door = s.logged ? "还没选门" : "未登录";
            s.meta = s.logged ? "去选门" : "去登录";
        }

        if (s.busy) {
            s.state = "正在下发开门指令…";
            s.stateColor = c.getColor(R.color.q_fail);
        } else if (s.recoverable) {
            // 凭证状态是会持续存在的条件，优先于「上次没收到结果」这条一次性提示
            s.state = "凭证已超时，点按尝试恢复登录后直接开门";
            s.stateColor = c.getColor(R.color.q_fail);
        } else if (s.interrupted) {
            s.state = "上次开门没收到结果，可能已中断，请重试";
            s.stateColor = c.getColor(R.color.q_fail);
        } else if (!s.ready) {
            s.state = b.broken ? "这个组件绑的门读不出来了，点门名重新选一扇"
                    : (s.logged ? "选好门后，这里就能一键开门" : "登录并选门后，这里直接开门");
            s.stateColor = c.getColor(R.color.q_blue);
        } else {
            String[] p = Prefs.widgetLast(c, appWidgetId).split("\\|", 3);
            if (p.length == 3) {
                boolean ok = "1".equals(p[0]);
                long ts = 0L;
                try {
                    ts = Long.parseLong(p[1]);
                } catch (Throwable ignored) {
                }
                s.state = "上次：" + when(ts) + " · " + p[2];
                s.stateColor = c.getColor(ok ? R.color.q_green : R.color.q_fail);
            } else {
                s.state = "点按钮直接开门，无需打开应用";
            }
        }
        return s;
    }

    /**
     * 主按钮点击分流（四种版式共用，避免同一段三元表达式抄四遍。
     * 旧实现四处各写一份，改一处分流逻辑极易漏改）。
     */
    private static void bindOpenButton(RemoteViews rv, Context c, State s, PendingIntent config) {
        rv.setOnClickPendingIntent(R.id.w_btn,
                s.openable() && !s.busy ? openIntent(c, s.appWidgetId)
                        : guideIntent(c, s, config));
    }

    /**
     * 整卡的读屏描述。
     *
     * <p>桌面在无障碍节点上把「应用名 + 组件根的 contentDescription」<b>直接字符串拼接</b>：
     * 我们不设描述时拼出来就是 {@code qinlinnull}（2026-11-26 真机实测，TalkBack 会照念）。
     * 这里按状态给一句人话，读屏用户也能听出这是哪扇门、点下去会发生什么。</p>
     *
     * <p>描述里刻意不再写应用名（桌面已经拼过一次，否则会念成「qinlinqinlin…」），
     * 开头留一个空格，让拼接结果是「qinlin 快捷开门 · 2栋单元门」而不是连成一串。</p>
     *
     * <p>{@link RemoteViews#setContentDescription} 是 API 31 才新增的方法，
     * 低版本跳过（维持原样，不会比现在更差）。</p>
     *
     * @param rootIsButton 根视图本身是不是开门按钮。4×2 / 4×1 的根是不可点击的容器，
     *                     对它们只能说状态（「可开门」），不能承诺「点按开门」——
     *                     读屏用户双击一个不可点击的容器是没有反应的。
     */
    private static void applyA11y(RemoteViews rv, State s, int rootId, boolean rootIsButton) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return;
        StringBuilder sb = new StringBuilder(" 快捷开门");
        if (s.door.length() > 0) sb.append(" · ").append(s.door);
        if (s.busy) {
            sb.append("，正在开门");
        } else if (s.interrupted) {
            sb.append("，上次开门没收到结果");
        } else if (s.recoverable) {
            sb.append("，登录已超时，可点按尝试恢复");
        } else if (s.openable()) {
            sb.append(rootIsButton ? "，点按开门" : "，可开门");
        } else {
            sb.append(s.logged ? "，还没选门" : "，未登录");
        }
        try {
            rv.setContentDescription(rootId, sb.toString());
        } catch (Throwable t) {
            // 个别 ROM 的 RemoteViews 反射桥接不认这个 action，静默降级即可，不能影响出卡
            AppLog.w(AppLog.UI, "设置组件读屏描述失败：" + t);
        }
    }

    /** 4×2 完整卡片。 */
    private static RemoteViews buildCard(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open);

        // 主题色：卡片底 + 主按钮（就绪态）。未就绪/开门中保持原来的蓝/灰语义色。
        int bg = colorRes(c, BG_RES, s.colorIdx);
        int btn = colorRes(c, BTN_RES, s.colorIdx);
        if (bg != 0) rv.setInt(R.id.w_root, "setBackgroundResource", bg);
        rv.setTextViewText(R.id.w_label, s.label);

        rv.setTextViewText(R.id.w_door, s.door);
        rv.setTextViewText(R.id.w_meta, s.meta);
        rv.setTextColor(R.id.w_door, c.getColor(s.openable() ? R.color.q_ink : R.color.q_sub));
        rv.setTextColor(R.id.w_meta, c.getColor(s.openable() ? R.color.q_muted : R.color.q_blue));
        rv.setTextColor(R.id.w_dot, c.getColor(s.openable() ? R.color.q_green : R.color.q_faint));

        applyButton(rv, s, btn, R.drawable.widget_btn_busy,
                R.drawable.widget_btn_blue, "开 门", "开门中…", "打开应用");

        rv.setTextViewText(R.id.w_state, s.state);
        rv.setTextColor(R.id.w_state, s.stateColor);

        PendingIntent piConfig = configIntent(c, s.appWidgetId);
        // 未就绪时按「缺什么补什么」分流：没登录直达登录表单（按钮文案正是「打开应用」），
        // 已登录只差选门才进门列表。凭证超时（recoverable）仍走开门，由 openNow 先探活。
        bindOpenButton(rv, c, s, piConfig);
        rv.setOnClickPendingIntent(R.id.w_header, piConfig);   // 点门名=给这个 widget 换门
        rv.setOnClickPendingIntent(R.id.w_state, piConfig);
        rv.setOnClickPendingIntent(R.id.w_label, settingsIntent(c, s.appWidgetId)); // 点组件名=进设置
        applyA11y(rv, s, R.id.w_root, false);
        return rv;
    }

    /** 1×1 组件名按码点递减字号：1×1 只有约 40dp 宽，名字越长字越小，保证 5 个汉字也能整格放下。 */
    private static float squareNameSp(String name) {
        int n = name.codePointCount(0, name.length());
        if (n <= 1) return 15f;
        if (n == 2) return 13f;
        if (n == 3) return 11.5f;
        if (n == 4) return 10f;
        return 8f; // 5 个汉字 ×8sp≈40dp，正好占满 1×1 格子
    }

    /** 1×1：整格一个按钮。设了组件名就显示名字（字多自动缩小），否则显示「开门」动作词。 */
    private static RemoteViews buildSquare(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open_square);
        int sq = colorRes(c, SQ_RES, s.colorIdx);

        if (s.busy) {
            // 开门中保留状态反馈，不显示名字
            rv.setTextViewText(R.id.w_btn, "开门中");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 12f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_sq_busy);
        } else if (s.openable()) {
            String custom = Prefs.widgetName(s.appWidgetId, c);
            if (custom.length() > 0) {
                rv.setTextViewText(R.id.w_btn, custom);
                rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, squareNameSp(custom));
            } else {
                rv.setTextViewText(R.id.w_btn, "开门");
                rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 13f);
            }
            rv.setInt(R.id.w_btn, "setBackgroundResource", sq != 0 ? sq : R.drawable.widget_sq_red);
        } else {
            // 未就绪时是引导操作（登录/选门），优先于名字；三个字会溢出，收成两个字
            rv.setTextViewText(R.id.w_btn, s.logged ? "选门" : "登录");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 12.5f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_sq_blue);
        }
        bindOpenButton(rv, c, s, configIntent(c, s.appWidgetId));
        applyA11y(rv, s, R.id.w_btn, true);    // 根视图就是按钮本身，描述直接挂在它上面
        return rv;
    }

    /** 2×1：整条一个按钮（这个宽度放不下门名，索性把面积全给按钮）。 */
    private static RemoteViews buildMini(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open_mini);
        int pill = colorRes(c, PILL_RES, s.colorIdx);
        if (s.busy) {
            rv.setTextViewText(R.id.w_btn, "开门中…");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 13f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_busy);
        } else if (s.openable()) {
            rv.setTextViewText(R.id.w_btn, "开 门");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 14f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", pill != 0 ? pill : R.drawable.widget_pill_red);
        } else {
            rv.setTextViewText(R.id.w_btn, s.logged ? "去选门" : "去登录");
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 12.5f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_blue);
        }
        bindOpenButton(rv, c, s, configIntent(c, s.appWidgetId));
        applyA11y(rv, s, R.id.w_btn, true);    // 根视图就是按钮本身，描述直接挂在它上面
        return rv;
    }

    /** 4×1：组件名 + 门名 + 上次结果，右边胶囊按钮。 */
    private static RemoteViews buildBar(Context c, State s) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_open_bar);

        int bg = colorRes(c, BG_RES, s.colorIdx);
        int pill = colorRes(c, PILL_RES, s.colorIdx);
        if (bg != 0) rv.setInt(R.id.w_root, "setBackgroundResource", bg);
        rv.setTextViewText(R.id.w_label, s.label);

        rv.setTextViewText(R.id.w_door, s.door);
        rv.setTextColor(R.id.w_door, c.getColor(s.openable() ? R.color.q_ink : R.color.q_sub));
        rv.setTextColor(R.id.w_dot, c.getColor(s.openable() ? R.color.q_green : R.color.q_faint));
        rv.setTextViewText(R.id.w_meta, shortState(s));
        rv.setTextColor(R.id.w_meta, s.stateColor);

        applyPill(rv, s, pill != 0 ? pill : R.drawable.widget_pill_red, "开 门", "开门中…");
        PendingIntent piConfig = configIntent(c, s.appWidgetId);
        bindOpenButton(rv, c, s, piConfig);
        rv.setOnClickPendingIntent(R.id.w_header, piConfig);   // 点门名=给这个 widget 换门
        rv.setOnClickPendingIntent(R.id.w_meta, piConfig);
        rv.setOnClickPendingIntent(R.id.w_label, settingsIntent(c, s.appWidgetId)); // 点组件名=进设置
        applyA11y(rv, s, R.id.w_root, false);
        return rv;
    }

    /** 三种按钮状态（可开 / 开门中 / 未就绪）统一套用。 */
    private static void applyButton(RemoteViews rv, State s, int red, int busy, int blue,
                                    String openText, String busyText, String appText) {
        if (s.busy) {
            rv.setTextViewText(R.id.w_btn, busyText);
            rv.setInt(R.id.w_btn, "setBackgroundResource", busy);
        } else if (s.openable()) {
            rv.setTextViewText(R.id.w_btn, openText);
            rv.setInt(R.id.w_btn, "setBackgroundResource", red);
        } else {
            rv.setTextViewText(R.id.w_btn, appText);
            rv.setInt(R.id.w_btn, "setBackgroundResource", blue);
        }
    }

    /** 横条 / 迷你版的胶囊按钮：字更小，未就绪时收成「去登录 / 去选门」。 */
    private static void applyPill(RemoteViews rv, State s, int pillRes,
                                  String openText, String busyText) {
        if (s.busy) {
            rv.setTextViewText(R.id.w_btn, busyText);
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 11.5f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", R.drawable.widget_pill_busy);
        } else if (s.openable()) {
            rv.setTextViewText(R.id.w_btn, openText);
            rv.setTextViewTextSize(R.id.w_btn, TypedValue.COMPLEX_UNIT_SP, 13f);
            rv.setInt(R.id.w_btn, "setBackgroundResource", pillRes);
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

    /**
     * 未就绪按钮的引导去处：没登录就回应用登录（否则门列表只会显示「未登录」死路）；
     * 已登录但没选门才进门列表。四种样式的按钮文案（打开应用 / 去登录 / 登录）
     * 与这里的选择一致。
     */
    private static PendingIntent guideIntent(Context c, State s, PendingIntent config) {
        return s.logged ? config : appIntent(c);
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

    /** 点组件名：打开小组件设置界面，并自动定位到这个 widget 的标签。 */
    private static PendingIntent settingsIntent(Context c, int appWidgetId) {
        int req = REQ_CONFIG + 100000 + Math.max(0, appWidgetId);
        return PendingIntent.getActivity(c, req,
                new Intent(c, WidgetConfigActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(WidgetConfigActivity.EXTRA_FOCUS_ID, appWidgetId),
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
                // 用三态结果而不是 boolean：弱网下探活失败只说明「暂时不知道」，
                // 不能当成凭证失效去提示重新登录（旧实现就是这个误报）。
                // 探活走短超时预算：广播窗口只有约 10 秒，默认的 5s+6s 会把开门挤出去。
                int pr = token.length() > 0 ? SessionGuard.probeNowQuick(c) : SessionGuard.EXPIRED;
                if (pr == SessionGuard.OK) {
                    AppLog.ok(AppLog.LOGIN, trace, "小部件开门前探活成功 → 凭证恢复，继续开门");
                    token = Prefs.session(c);
                    refreshAll(c);
                } else if (pr == SessionGuard.NET_FAIL) {
                    AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：探活网络异常，登录态未改动");
                    noteResult(c, false, "网络异常", 0, FROM_WIDGET, boundDoorName, appWidgetId);
                    toast(c, "网络异常，暂时无法确认登录状态，请稍后重试");
                    return;
                } else if (pr == SessionGuard.UNKNOWN || pr == SessionGuard.BIZ_REJECT) {
                    AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：探活给不出结论 [" + pr
                            + "]（已有探活在跑或网关暂时不可用），登录态未改动");
                    noteResult(c, false, "登录状态待确认", 0, FROM_WIDGET, boundDoorName, appWidgetId);
                    toast(c, "正在检查登录状态，请稍后重试");
                    return;
                } else {
                    AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：未登录或凭证超时且探活确认失效");
                    // 被拦下也算一次尝试：写一条失败记录，App 里能看到「为什么没开」
                    noteResult(c, false, token.length() > 0 ? "凭证超时" : "未登录", 0, FROM_WIDGET, boundDoorName, appWidgetId);
                    toast(c, token.length() > 0
                            ? "登录已失效，请打开应用点「重试连接」"
                            : "请先在应用里登录");
                    return;
                }
            }
            if (cid <= 0 || did <= 0) {
                AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：未选择门 cid=" + cid + " did=" + did);
                noteResult(c, false, "未选门", 0, FROM_WIDGET, boundDoorName, appWidgetId);
                toast(c, "请先在应用里选择要控制的门");
                return;
            }
            if (!Net.online(c)) {
                AppLog.w(AppLog.OPEN, trace, "小部件开门被拦下：当前无网络（" + Net.describe(c) + "）");
                noteResult(c, false, "无网络", 0, FROM_WIDGET, boundDoorName, appWidgetId);
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
                // 小部件走短超时（2.5s 连接 + 5s 读）：默认的 5s+15s 会超出 goAsync 窗口，
                // 进程被回收后卡片会永久停在「开门中」。正常 RTT 只有 0.3~0.6 秒，余量足够。
                String raw = QinlinApi.openDoorQuick(token, cid, did);
                long cost = System.currentTimeMillis() - t0;
                AppLog.ok(AppLog.OPEN, "小部件开门：服务端已接受指令（耗时 " + cost
                        + "ms，不等于物理门已开）| 原文：" + raw);
                noteResult(c, true, "已开门", (int) cost, FROM_WIDGET, boundDoorName, appWidgetId);
                toast(c, "已发出开门指令，请确认门是否打开");
            } catch (QinlinApi.ApiException e) {
                int cost = (int) (System.currentTimeMillis() - t0);
                if ("SESSION_EXPIRED".equals(e.code)) {
                    // 二次确认也用短预算：已经花掉一次开门的时间，不能再等 11 秒探活
                    boolean dead = SessionGuard.expiredAfterRetryQuick(c);
                    AppLog.w(AppLog.LOGIN, trace, dead
                            ? "小部件开门：确认登录超时 → 只标记过期，本机凭证保留"
                            : "小部件开门：401 判定为网关误判，登录态保留");
                    noteResult(c, false, "凭证失效", cost, FROM_WIDGET, boundDoorName, appWidgetId);
                    toast(c, dead ? "登录已失效，请打开应用点「重试连接」"
                                 : "刚才是网关临时异常，请再点一次");
                    if (dead) refreshAll(c);
                } else {
                    AppLog.e(AppLog.ERR, trace, "小部件开门失败 [" + e.code + "] " + e.getMessage());
                    noteResult(c, false, "失败 " + e.code, cost, FROM_WIDGET, boundDoorName, appWidgetId);
                    toast(c, "开门失败：" + e.code);
                }
            } catch (Exception e) {
                AppLog.e(AppLog.ERR, trace, "小部件开门异常 " + AppLog.stack(e));
                noteResult(c, false, "网络异常",
                        (int) (System.currentTimeMillis() - t0), FROM_WIDGET, boundDoorName, appWidgetId);
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
