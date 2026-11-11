package com.wb.qinlin;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 分类诊断日志中枢。
 *
 * 目标：出现「覆盖安装后登录状态还在、但无法开门」这类问题时，能凭日志还原现场：
 *   ① 每条日志带时间、级别、分类、traceId（一次操作的所有日志串成一条链路）；
 *   ② 断电 / 重启 / 覆盖安装都不丢 —— 追加写入 filesDir/logs/qinlin.log，带轮转；
 *   ③ 每次启动记录安装信息与本机状态快照，能识别「覆盖安装 / 版本变化」；
 *   ④ 记录凭证与设备标识的对应关系（只存长度 + 指纹，绝不存明文），
 *      用于判断「登录时 openid 与现在是否一致」这类服务端拒答的原因；
 *   ⑤ 全部输出脱敏，可直接复制 / 导出外发分析。
 *
 * 纯标准库实现，同时写 logcat（tag=QinlinLog）供 adb 抓取。
 */
public final class AppLog {

    // ---------- 日志分类 ----------
    public static final String SYS   = "系统";
    public static final String LOGIN = "登录";
    public static final String DOORS = "门列表";
    public static final String OPEN  = "开门";
    public static final String NET   = "网络";
    public static final String UI    = "界面";
    public static final String ERR   = "异常";

    // ---------- 日志级别 ----------
    public static final String I  = "I";    // 常规信息
    public static final String W  = "W";    // 警告 / 前置校验未通过 / 需要留意
    public static final String E  = "E";    // 错误 / 请求失败 / 未捕获异常
    public static final String OK = "✓";    // 关键动作成功

    private static final String LOGCAT = "QinlinLog";
    private static final int MAX_MEM = 400;          // 内存保留最近条数
    private static final int MAX_FILE = 256 * 1024;  // 单个日志文件上限，超出轮转
    private static final int MAX_MSG = 1500;         // 单条消息上限

    /** 冷启动回填：把落盘的单行日志还原成 Entry 的格式（与 Entry.line() 严格对应）。 */
    private static final java.util.regex.Pattern LINE_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})  (\\S+)  (\\S+)  \\[([^\\]]+)\\]  (.*)$");
    /** 每次进程只回填一次，避免 Activity 重建导致历史重复插入。 */
    private static boolean hydrated;

    private static final Object LOCK = new Object();
    private static final List<Entry> MEM = new ArrayList<Entry>();
    private static final ThreadLocal<String> TRACE = new ThreadLocal<String>();
    private static final SimpleDateFormat LINE_TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FULL_TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /**
     * 落盘与 logcat 全部交给单一后台线程，调用线程（含 UI 主线程）零磁盘 IO。
     * 启动 / 开门时日志密集，同步写文件会直接拖慢首帧与点击响应。
     */
    private static final LinkedBlockingQueue<Object> QUEUE =
            new LinkedBlockingQueue<Object>();
    private static final SecureRandom RND = new SecureRandom();

    private static Context app;
    /**
     * 界面订阅者。主界面和全屏日志界面可能同时在栈里，
     * 用单例监听会在后开的界面销毁时把前一个也静音（真机表现为「日志不再刷新」）。
     */
    private static final java.util.List<Listener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<Listener>();
    private static boolean crashHandlerInstalled;
    private static volatile Thread writerThread;

    static {
        Thread writer = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        Object task = QUEUE.take();
                        if (task instanceof Entry) writeEntry((Entry) task);
                        else if (task instanceof Runnable) ((Runnable) task).run();
                    } catch (InterruptedException ignored) {
                        // daemon 线程随进程结束，不因中断退出
                    } catch (Throwable ignored) {
                        // 写日志失败绝不能反过来影响开门主流程
                    }
                }
            }
        }, "QinlinLogWriter");
        writer.setDaemon(true);
        writerThread = writer;
        writer.start();
    }

    private AppLog() {}

    /** 界面订阅新日志，用于实时刷新日志流。 */
    public interface Listener { void onLog(Entry e); }

    public static final class Entry {
        public final long time;
        public final String level;
        public final String tag;
        public final String trace;
        public final String msg;
        /** 时间戳字符串只格式化一次：落盘、logcat、界面渲染共用，省掉重复 SimpleDateFormat。 */
        private final String stampText;

        Entry(long time, String level, String tag, String trace, String msg) {
            this.time = time;
            this.level = level;
            this.tag = tag;
            this.trace = trace;
            this.msg = msg;
            this.stampText = ts(time);
        }

        /** 单行、可解析的日志格式，落盘与界面显示共用。 */
        public String line() {
            return stampText + "  " + level + "  " + tag + "  [" + trace + "]  " + msg;
        }

        /** 冷启动回填：把落盘的一行还原成 Entry；格式不符就跳过，不影响其它行。 */
        static Entry parse(String line) {
            if (line == null) return null;
            java.util.regex.Matcher m = LINE_PATTERN.matcher(line);
            if (!m.matches()) return null;
            long t;
            try {
                // 落盘格式不带年份，补当前年即可；line() 会重新格式化回同样的月日时分秒
                SimpleDateFormat p = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
                t = p.parse(year + "-" + m.group(1)).getTime();
            } catch (Exception e) {
                t = System.currentTimeMillis();
            }
            return new Entry(t, m.group(2), m.group(3), m.group(4), m.group(5));
        }
    }

    // ---------- 初始化与订阅 ----------

    public static void init(Context c) {
        if (c == null) return;
        boolean install = false;
        synchronized (LOCK) {
            if (app == null) app = c.getApplicationContext();
            if (!crashHandlerInstalled) {
                crashHandlerInstalled = true;
                install = true;
            }
        }
        if (install) installCrashHandler();
    }

    /** 未捕获异常也落进日志，避免「闪退了但日志里什么都没有」。 */
    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable ex) {
                try {
                    add(E, ERR, "-", "未捕获异常 / 崩溃 thread=" + t.getName()
                            + " | " + stack(ex));
                    flush();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, ex);
            }
        });
    }

    /** 订阅新日志（可多个界面同时订阅）；界面销毁时务必 remove，避免持有已销毁的 View。 */
    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    /** 日志文件：/data/data/<pkg>/files/logs/qinlin.log，覆盖安装不会清除。 */
    public static File file() {
        Context a;
        synchronized (LOCK) { a = app; }
        if (a == null) return null;
        File dir = new File(a.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "qinlin.log");
    }

    // ---------- 链路追踪 ----------

    /** 一次后台操作生成一个短 id，绑定在当前线程，网络层打点会自动带上。 */
    public static String newTrace() {
        byte[] b = new byte[3];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        return sb.toString();
    }

    public static void bind(String trace) { TRACE.set(trace); }
    public static void unbind() { TRACE.remove(); }
    public static String cur() {
        String t = TRACE.get();
        return t == null ? "-" : t;
    }

    // ---------- 写入 ----------

    public static void i(String tag, String msg) { add(I, tag, cur(), msg); }
    public static void w(String tag, String msg) { add(W, tag, cur(), msg); }
    public static void e(String tag, String msg) { add(E, tag, cur(), msg); }
    public static void ok(String tag, String msg) { add(OK, tag, cur(), msg); }

    public static void i(String tag, String trace, String msg) { add(I, tag, trace, msg); }
    public static void w(String tag, String trace, String msg) { add(W, tag, trace, msg); }
    public static void e(String tag, String trace, String msg) { add(E, tag, trace, msg); }
    public static void ok(String tag, String trace, String msg) { add(OK, tag, trace, msg); }

    public static void e(String tag, String msg, Throwable t) {
        e(tag, msg + " | " + stack(t));
    }

    private static void add(String level, String tag, String trace, String msg) {
        final Entry e = new Entry(System.currentTimeMillis(), level, tag,
                trace == null || trace.length() == 0 ? "-" : trace, sanitize(msg));
        synchronized (LOCK) {
            MEM.add(e);
            while (MEM.size() > MAX_MEM) MEM.remove(0);
        }
        QUEUE.offer(e);   // 落盘 / logcat 交给后台写线程，调用线程立即返回
        for (Listener l : LISTENERS) {
            try { l.onLog(e); } catch (Exception ignored) {}
        }
    }

    /** 只在后台写线程执行：logcat + 追加落盘 + 轮转；与 clear 天然串行，无文件竞态。 */
    private static void writeEntry(Entry e) {
        String line = e.line();
        if (E.equals(e.level)) Log.e(LOGCAT, line);
        else if (W.equals(e.level)) Log.w(LOGCAT, line);
        else Log.i(LOGCAT, line);
        File f = file();
        if (f == null) return;
        try {
            if (f.length() > MAX_FILE) rotate(f);
            FileOutputStream os = new FileOutputStream(f, true);
            os.write((line + "\n").getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {
            // 日志写入失败不能影响开门主流程
        }
    }

    /**
     * 等队列里的写入全部落盘：导出 / 清空 / 崩溃前调用。
     * 最多等 2 秒，绝不因为日志把主线程卡成 ANR。
     */
    public static void flush() {
        if (Thread.currentThread() == writerThread) return;   // 防止写线程自己等自己
        final CountDownLatch latch = new CountDownLatch(1);
        QUEUE.offer(new Runnable() {
            @Override public void run() { latch.countDown(); }
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    private static void rotate(File f) {
        File old = new File(f.getAbsolutePath() + ".1");
        if (old.exists()) old.delete();
        if (!f.renameTo(old)) f.delete();   // 轮转失败就截断，避免无限增长
    }

    /**
     * 兜底脱敏：即便上游原文或异常消息漏进了日志，也不会把凭证明文写进文件。
     * 手机号打码、JSON / KV 形式的凭证字段抹掉、换行拉平（一条日志一行）、超长截断。
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        // 用数字边界限定：13 位毫秒时间戳里也含「1[3-9]+9 位数字」，不限定会被误当手机号切掉
        String v = s.replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "***手机号***");
        v = v.replaceAll("\"(sessionId|token|openid|smsCode|sign)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"***\"");
        v = v.replaceAll("(?i)(sessionId|token|openid|smsCode|sign)\\s*[=:]\\s*\"?[A-Za-z0-9_\\-]{8,}\"?",
                "$1=***");
        v = v.replace("\r", " ").replace("\n", " | ");
        return v.length() > MAX_MSG ? v.substring(0, MAX_MSG) + "…(截断)" : v;
    }

    /** 异常摘要：类型 + 消息 + 前几帧，不打印完整堆栈。 */
    public static String stack(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName())
                .append(": ").append(String.valueOf(t.getMessage()));
        StackTraceElement[] st = t.getStackTrace();
        int n = Math.min(4, st.length);
        for (int i = 0; i < n; i++) {
            sb.append(" | ").append(st[i].getClassName()).append(".")
                    .append(st[i].getMethodName()).append(":").append(st[i].getLineNumber());
        }
        return sb.toString();
    }

    // ---------- 脱敏工具 ----------

    /** 凭证 / 设备标识只留长度与 SHA-256 前 4 字节指纹，可对比是否变化但不泄露原文。 */
    public static String fp(String s) {
        if (s == null || s.length() == 0) return "空";
        String h = "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format(Locale.US, "%02x", b[i]));
            h = "#" + sb;
        } catch (Exception ignored) {}
        return "len" + s.length() + h;
    }

    public static String maskPhone(String p) {
        if (p == null || p.length() == 0) return "未保存";
        if (p.length() != 11) return "异常长度" + p.length();
        return p.substring(0, 3) + "****" + p.substring(7);
    }

    private static String ts(long t) {
        synchronized (LINE_TS) { return LINE_TS.format(new Date(t)); }
    }

    private static String stamp(long t) {
        synchronized (FULL_TS) { return FULL_TS.format(new Date(t)); }
    }

    /** 把毫秒时间戳显示成可读时间；0 视为未记录。日志里比裸数字更不容易看错。 */
    public static String time(long t) {
        return t > 0 ? stamp(t) : "未记录";
    }

    // ---------- 启动 / 状态快照 ----------

    /**
     * 冷启动后把磁盘尾部历史补回内存。
     *
     * 小部件是在后台被点的：广播会拉起进程写日志并落盘，进程随后可能被系统回收。
     * 用户再打开 App 时是全新进程，MEM 为空，日志页就只能看到本次启动之后的几行 ——
     * 那次小部件开门明明写进了文件，却「在 App 里看不到」。这里在写线程里先补历史、
     * 再写本次启动行，保证时间顺序，且磁盘 IO 不落到主线程。
     *
     * 只补 MEM：不重复写盘、不通知订阅者（界面此时还没订阅）。
     */
    private static void hydrate() {
        synchronized (LOCK) {
            if (hydrated) return;
            hydrated = true;
        }
        File f = file();
        if (f == null) return;
        List<String> lines = new ArrayList<String>();
        File old = new File(f.getAbsolutePath() + ".1");
        if (old.exists()) readLines(old, lines);
        if (f.exists()) readLines(f, lines);
        int from = Math.max(0, lines.size() - MAX_MEM);
        List<Entry> hist = new ArrayList<Entry>();
        for (int i = from; i < lines.size(); i++) {
            Entry e = Entry.parse(lines.get(i));
            if (e != null) hist.add(e);
        }
        if (hist.isEmpty()) return;
        synchronized (LOCK) {
            MEM.addAll(0, hist);   // 历史更旧，插在本次启动日志之前
            while (MEM.size() > MAX_MEM) MEM.remove(0);
        }
    }

    private static void readLines(File f, List<String> out) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 每次启动调用：记录包版本、首装/更新时间、本机凭证与默认门状态。
     * 通过对比上次记录的安装戳，可直接识别「覆盖安装 / 版本变化」。
     *
     * 回填历史、拼快照、PackageManager IPC 全部在日志写线程执行 ——
     * 主线程不再为首帧之后的一串日志 IO / IPC 买单。
     */
    public static void startup(Context c) {
        init(c);
        final Context appCtx = c.getApplicationContext() == null ? c : c.getApplicationContext();
        QUEUE.offer(new Runnable() {
            @Override public void run() {
                try {
                    hydrate();                       // 先补历史，顺序在本次启动日志之前
                    i(SYS, "===== App 启动 =====");
                    writeStartupSnapshot(appCtx);
                } catch (Throwable t) {
                    i(SYS, "启动快照生成失败：" + t.getClass().getSimpleName());
                }
            }
        });
    }

    /** 只在日志写线程执行：安装戳对比 + 状态快照。 */
    private static void writeStartupSnapshot(Context c) {
        // PackageManager 是 IPC，只取一次并复用给快照
        PackageInfo pi = null;
        try { pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0); }
        catch (Exception ignored) {}
        String curStamp = pi == null ? "未知" : pi.versionCode + "/" + pi.lastUpdateTime;

        String prev = Prefs.logStamp(c);
        boolean fresh = prev.length() == 0;
        boolean changed = !fresh && !prev.equals(curStamp);
        if (fresh || changed) Prefs.setLogStamp(c, curStamp);

        if (fresh) {
            w(SYS, "首次记录安装信息（新安装，或首次启用诊断日志）");
        } else if (changed) {
            w(SYS, "检测到覆盖安装 / 版本变化：上次=" + prev + " 当前=" + curStamp);
        }
        i(SYS, snapshot(c, pi));

        if ((fresh || changed) && Prefs.session(c).length() > 0) {
            String saved = Prefs.sessionOpenidFp(c);
            if (saved.length() == 0) {
                w(LOGIN, "本机已有凭证，但缺少「登录时设备标识」记录（旧版本数据）；若开门被拒请重新登录");
            } else if (!saved.equals(fp(Prefs.openid(c)))) {
                w(LOGIN, "⚠ 凭证对应的 openid 与当前设备标识不一致，服务端可能判定会话无效，建议重新登录");
            }
        }
    }

    /** 一键诊断快照：把「登录态还在但开不了门」需要的关键事实一次性列全（全脱敏）。 */
    public static String snapshot(Context c) { return snapshot(c, null); }

    /** startup 传入已取到的 PackageInfo，避免启动主线程重复做 PackageManager IPC。 */
    private static String snapshot(Context c, PackageInfo pi) {
        StringBuilder sb = new StringBuilder();
        if (pi == null) {
            try { pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0); }
            catch (Exception ignored) {}
        }
        if (pi != null) {
            sb.append("版本 ").append(pi.versionName).append("(").append(pi.versionCode).append(")")
                    .append(" | 首装 ").append(stamp(pi.firstInstallTime))
                    .append(" | 最近更新 ").append(stamp(pi.lastUpdateTime));
        } else {
            sb.append("包信息读取失败");
        }
        sb.append("\n设备 ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")");

        String sess = Prefs.session(c);
        boolean logged = sess.length() > 0;
        long at = Prefs.sessionSavedAt(c);
        sb.append("\n凭证 session=").append(fp(sess))
                .append(" | 写入时间 ").append(!logged ? "无（未登录）"
                        : (at > 0 ? stamp(at) : "未记录(旧数据)"))
                .append(" | 手机号=").append(maskPhone(Prefs.phone(c)));

        String oid = Prefs.openid(c);
        String saved = Prefs.sessionOpenidFp(c);
        boolean mismatch = logged && saved.length() > 0 && !saved.equals(fp(oid));
        sb.append("\n设备标识 openid=").append(fp(oid))
                .append(" | 登录时=").append(!logged ? "无（未登录）"
                        : (saved.length() > 0 ? saved : "未记录(旧数据)"))
                .append(mismatch ? " | ⚠ 与登录时不一致，服务端可能拒绝会话" : "");

        sb.append("\n默认门 cid=").append(Prefs.communityId(c))
                .append(" | did=").append(Prefs.doorId(c))
                .append(" | 名称=").append(Prefs.doorName(c))
                .append(" | 区域=").append(Prefs.doorKind(c))
                .append(" | 在线=").append(Prefs.doorOnline(c));
        sb.append("\n小区名=").append(Prefs.communityName(c));
        sb.append("\n网络=").append(Net.describe(c));
        DoorCache.Data cache = DoorCache.load(c);
        sb.append("\n门列表缓存=").append(cache == null
                ? "无"
                : cache.doors.size() + " 扇门 · " + cache.age());
        File f = file();
        sb.append("\n日志文件=").append(f == null ? "不可用" : f.getAbsolutePath());
        return sb.toString();
    }

    // ---------- 读取 / 导出 ----------

    /** tag 为 null 表示不按分类过滤；errorsOnly 只看 W / E。 */
    public static List<Entry> recent(String tag, boolean errorsOnly) {
        return recent(tag, errorsOnly, 0);
    }

    /** 内存里当前保留的日志条数：收起日志区时用于在开关上提示「确实有记录在写」。 */
    public static int size() {
        synchronized (LOCK) {
            return MEM.size();
        }
    }

    /**
     * limit > 0 时只返回「过滤后」的最后 limit 条：从新到旧倒序收集，命中即停，
     * 界面渲染不必每次复制并遍历整份 400 条内存日志。
     */
    public static List<Entry> recent(String tag, boolean errorsOnly, int limit) {
        List<Entry> out = new ArrayList<Entry>();
        synchronized (LOCK) {
            for (int i = MEM.size() - 1; i >= 0; i--) {
                Entry e = MEM.get(i);
                if (tag != null && !tag.equals(e.tag)) continue;
                if (errorsOnly && !E.equals(e.level) && !W.equals(e.level)) continue;
                out.add(e);
                if (limit > 0 && out.size() >= limit) break;
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static StringBuilder header(Context c) {
        StringBuilder sb = new StringBuilder("亲邻开门 · 诊断日志\n");
        sb.append("生成时间 ").append(stamp(System.currentTimeMillis())).append('\n');
        sb.append("脱敏说明：手机号 / sessionId / openid 仅保留长度与指纹，可安全外发。\n\n");
        sb.append(snapshot(c)).append('\n');
        return sb;
    }

    /** 复制到剪贴板：头部快照 + 内存里最近 200 条，控制体积。 */
    public static String recentText(Context c) {
        StringBuilder sb = header(c);
        sb.append("\n---- 最近日志 ----\n");
        synchronized (LOCK) {
            int from = Math.max(0, MEM.size() - 200);
            for (int i = from; i < MEM.size(); i++) sb.append(MEM.get(i).line()).append('\n');
        }
        return sb.toString();
    }

    /** 导出 txt：头部快照 + 磁盘上的完整历史（含轮转文件），跨重启 / 跨覆盖安装。 */
    public static String exportText(Context c) {
        flush();   // 导出前确保后台写入全部落盘，txt 内容与界面所见一致
        StringBuilder sb = header(c);
        sb.append("\n---- 完整日志（含磁盘历史） ----\n");
        File f = file();
        boolean wrote = false;
        if (f != null) {
            File old = new File(f.getAbsolutePath() + ".1");
            if (old.exists()) { appendFile(sb, old); wrote = true; }
            if (f.exists()) { appendFile(sb, f); wrote = true; }
        }
        if (!wrote) {
            synchronized (LOCK) {
                for (Entry e : MEM) sb.append(e.line()).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendFile(StringBuilder sb, File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            sb.append("[读取 ").append(f.getName()).append(" 失败] ")
                    .append(e.getClass().getSimpleName()).append('\n');
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /** 清空内存与磁盘日志（用户主动操作；不影响凭证与门设置）。 */
    public static void clear() {
        synchronized (LOCK) { MEM.clear(); }
        // 文件操作也投递到同一个写线程，与 add() 严格串行，避免主线程与后台抢同一文件
        QUEUE.offer(new Runnable() {
            @Override public void run() {
                File f = file();
                if (f == null) return;
                try {
                    FileOutputStream os = new FileOutputStream(f, false);
                    os.close();
                } catch (Exception ignored) {}
                File old = new File(f.getAbsolutePath() + ".1");
                if (old.exists()) old.delete();
            }
        });
        flush();   // 等清空完成再返回，避免随后新写入被误清
    }
}
