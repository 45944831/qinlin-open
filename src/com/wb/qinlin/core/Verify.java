package com.wb.qinlin.core;

/**
 * 纯校验逻辑：不 import 任何 android.*，因此可以脱离设备在桌面 JVM 上直接跑测试。
 *
 * <p>为什么单独放一个包：这些规则以前散落在 MainActivity / Prefs 里，只能靠真机手输
 * 才能验证，改一次要装一次包。抽出来以后 {@code tests/CoreSelfTest.java} 用普通
 * {@code javac + java} 就能覆盖（见 {@code run-tests.py}），不需要 Gradle / JUnit。</p>
 */
public final class Verify {

    private Verify() {}

    /**
     * 手机号：11 位、以 1 开头、全为数字。
     * 输入法的 inputType 只限制键盘，粘贴可以塞进任意字符，所以必须再校验字符集。
     */
    public static boolean phone(String p) {
        return phoneIssue(p).length() == 0;
    }

    /**
     * 手机号不合格的<b>具体原因</b>（写日志 / 组织提示文案用）；合格返回空串。
     *
     * <p>只对外给一个布尔值时，界面只能一律提示「请输入 11 位手机号」——
     * 用户输入了长度正确的 {@code 23800138001} 也会看到这句话，属于答非所问
     * （2026-11-26 真机测试发现）。把原因拆开以后，提示可以按原因给。</p>
     */
    public static String phoneIssue(String p) {
        if (p == null) return "长度 0";
        if (p.length() != 11) return "长度 " + p.length();
        if (p.charAt(0) != '1') return "首位非 1";
        if (!digitsOnly(p)) return "含非数字";
        return "";
    }

    /** 短信验证码：4~8 位纯数字。放宽上下限是为了兼容上游日后改长度，不是放水。 */
    public static boolean smsCode(String c) {
        if (c == null) return false;
        int n = c.length();
        return n >= 4 && n <= 8 && digitsOnly(c);
    }

    /** 是否「非空且全为 ASCII 数字」。 */
    public static boolean digitsOnly(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /**
     * 会话剩余有效期（秒）：原始 TTL 减去「自上次探活以来流逝的时间」。
     *
     * <p>滑动窗口的准确剩余只有服务端知道；这里给的是保守下界，用来提示「快到期」足够，
     * 至少不会把 80 天前探活拿到的「90 天」一直原样显示成 90 天。</p>
     *
     * @param totalSeconds 上游下发的 TTL；≤0 表示未知
     * @param lastProbeSec 上次探活成功的 Unix 秒；≤0 表示没有基准
     * @param nowSec       当前 Unix 秒
     * @return -1 未知 / 0 已到期 / &gt;0 剩余秒数
     */
    public static int ttlRemain(int totalSeconds, long lastProbeSec, long nowSec) {
        if (totalSeconds <= 0) return -1;
        if (lastProbeSec <= 0) return totalSeconds;
        long used = nowSec - lastProbeSec;
        if (used <= 0) return totalSeconds;     // 系统时间回拨：不给「凭空变长」的结论
        long left = (long) totalSeconds - used;
        if (left <= 0) return 0;
        return left > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) left;
    }
}
