package com.wb.qinlin;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 本机开门记录：只用来在主界面回答「上次开成没成 / 今天开了几次」。
 *
 * 为什么不放 SharedPreferences：每次开门都要追加一条，而 apply() 会把整份 XML 重写一遍，
 * 长期是白写的 IO 放大。这里单独一个小文件，超限时整体重写一次即可（最多 60 行 ≈ 6KB）。
 *
 * 上限取「条数」与「天数」先到者：60 条且不超过 90 天，磁盘占用恒定在 6KB 以内，
 * 不会随使用年限膨胀。数据不上传，退出登录也不清（属于本机行为痕迹，不是账号凭证）。
 */
public final class OpenRecord {

    private static final String FILE = "open_records.jsonl";
    /** 条数上限：超过就丢最旧的。 */
    public static final int MAX = 60;
    /** 天数上限：超过 90 天的记录不再保留。 */
    private static final long KEEP_DAYS = 90L;

    private static final Object LOCK = new Object();

    private OpenRecord() {}

    /** 开门入口标记：桌面小部件。 */
    public static final String FROM_WIDGET = "widget";
    /** 开门入口标记：应用内主按钮。 */
    public static final String FROM_APP = "app";

    /** 一条开门记录。ms 为端到端耗时，&lt;=0 表示未知。 */
    public static final class Item {
        public final long at;
        public final boolean ok;
        public final int ms;
        public final String door;
        public final String note;
        /** 入口标记；旧数据没有该字段，按空串（应用内）处理。 */
        public final String from;

        Item(long at, boolean ok, int ms, String door, String note) {
            this(at, ok, ms, door, note, "");
        }

        Item(long at, boolean ok, int ms, String door, String note, String from) {
            this.at = at;
            this.ok = ok;
            this.ms = ms;
            this.door = door == null ? "" : door;
            this.note = note == null ? "" : note;
            this.from = from == null ? "" : from;
        }
    }

    /** 追加一条记录；失败不影响开门主流程，异常全部吞掉。 */
    public static void add(Context c, boolean ok, int ms, String door, String note) {
        add(c, ok, ms, door, note, "");
    }

    /**
     * 带入口标记的记录：桌面小部件与应用内按钮共用这一份文件，
     * 主界面的「最近开门记录 / 今日开门 / 上次开门」才能同时看到两个入口。
     */
    public static void add(Context c, boolean ok, int ms, String door, String note, String from) {
        if (c == null) return;
        synchronized (LOCK) {
            try {
                List<Item> items = load(c);
                items.add(new Item(System.currentTimeMillis(), ok, ms, door, note, from));
                save(c, trim(items));
            } catch (Throwable t) {
                AppLog.w(AppLog.OPEN, "本机开门记录写入失败（不影响开门）："
                        + t.getClass().getSimpleName());
            }
        }
    }

    /** 记录行里的入口标签；旧数据无标记时不显示，保持原有排版。 */
    public static String sourceLabel(String from) {
        if (FROM_WIDGET.equals(from)) return "小组件";
        if (FROM_APP.equals(from)) return "应用";
        return "";
    }

    /** 最近的 n 条，新的在前。 */
    public static List<Item> recent(Context c, int n) {
        List<Item> out = new ArrayList<Item>();
        synchronized (LOCK) {
            List<Item> all = load(c);
            // 按时间倒序：文件本身是追加写，正常就是升序；
            // 但设备时间被回拨、或旧文件被外部改过时，保证界面顺序仍然正确。
            java.util.Collections.sort(all, new java.util.Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return a.at == b.at ? 0 : (a.at < b.at ? 1 : -1);
                }
            });
            for (int i = 0; i < all.size() && out.size() < n; i++) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    /** 最新一条；没有记录返回 null。 */
    public static Item last(Context c) {
        List<Item> l = recent(c, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 今天的成功开门次数。 */
    public static int todayCount(Context c) {
        int n = 0;
        Calendar today = Calendar.getInstance();
        synchronized (LOCK) {
            for (Item it : load(c)) {
                if (!it.ok) continue;
                Calendar t = Calendar.getInstance();
                t.setTimeInMillis(it.at);
                if (sameDay(t, today)) n++;
            }
        }
        return n;
    }

    /** 全部条数（用于「全部 ▸ / 收起」是否显示）。 */
    public static int count(Context c) {
        synchronized (LOCK) {
            return load(c).size();
        }
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) f.delete();
        }
    }

    // ---------- 展示用的格式化 ----------

    /** 「今天 09:41 / 昨天 22:10 / 11-07 08:03」。 */
    public static String relative(long at) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(at);
        Calendar now = Calendar.getInstance();
        if (sameDay(t, now)) {
            return "今天 " + hhmm(t);
        }
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(t, y)) {
            return "昨天 " + hhmm(t);
        }
        return two(t.get(Calendar.MONTH) + 1) + "-" + two(t.get(Calendar.DAY_OF_MONTH))
                + " " + hhmm(t);
    }

    /** 320ms → "0.3"，便于后面接「秒」。 */
    public static String seconds(int ms) {
        if (ms <= 0) return "—";
        return String.format(java.util.Locale.US, "%.1f", ms / 1000f);
    }

    private static String hhmm(Calendar c) {
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    // ---------- 存储 ----------

    private static List<Item> load(Context c) {
        List<Item> out = new ArrayList<Item>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    out.add(new Item(o.optLong("at"), o.optInt("ok", 0) == 1,
                            o.optInt("ms", -1), o.optString("door", ""),
                            o.optString("note", ""), o.optString("from", "")));
                } catch (Exception ignore) {
                    // 单行损坏就跳过，不影响其它记录
                }
            }
        } catch (Throwable t) {
            AppLog.w(AppLog.OPEN, "读取本机开门记录失败：" + t.getClass().getSimpleName());
        } finally {
            close(r);
        }
        return out;
    }

    private static void save(Context c, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("at", it.at);
                o.put("ok", it.ok ? 1 : 0);
                o.put("ms", it.ms);
                o.put("door", it.door);
                o.put("note", it.note);
                if (it.from.length() > 0) o.put("from", it.from);
                sb.append(o.toString()).append('\n');
            } catch (Exception ignore) {
                // 序列化失败就跳过这一条
            }
        }
        File dir = c.getFilesDir();
        File tmp = new File(dir, FILE + ".tmp");
        File dst = new File(dir, FILE);
        try {
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                w.write(sb.toString());
                w.flush();
            } finally {
                close(w);
            }
            if (!tmp.renameTo(dst)) {
                // 改名失败（个别机型）：退回直接覆盖写
                OutputStreamWriter w2 = new OutputStreamWriter(new FileOutputStream(dst), "UTF-8");
                try {
                    w2.write(sb.toString());
                    w2.flush();
                } finally {
                    close(w2);
                }
                tmp.delete();
            }
        } catch (Throwable t) {
            AppLog.w(AppLog.OPEN, "本机开门记录落盘失败：" + t.getClass().getSimpleName());
        }
    }

    /** 截断：先按天数丢老的，再按条数丢最早的。 */
    private static List<Item> trim(List<Item> items) {
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L;
        List<Item> kept = new ArrayList<Item>();
        for (Item it : items) {
            if (it.at >= cutoff) kept.add(it);
        }
        while (kept.size() > MAX) kept.remove(0);
        return kept;
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignore) {
        }
    }
}
