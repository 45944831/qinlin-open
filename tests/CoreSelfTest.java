import com.wb.qinlin.core.Text;
import com.wb.qinlin.core.Verify;

/**
 * core 包的零依赖自检。不依赖 Android、不需要 JUnit：
 *
 * <pre>
 *   python run-tests.py            # 编译 + 运行
 * </pre>
 *
 * 覆盖的是「改一行就可能悄悄坏掉、而真机很难复现」的那批纯逻辑：
 * 手机号 / 验证码字符集、TTL 衰减（含系统时间回拨）、改名截断（含 emoji）。
 */
public final class CoreSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        phoneCases();
        smsCodeCases();
        ttlCases();
        truncateCases();

        System.out.println();
        System.out.println("PASS " + passed + " / FAIL " + failed);
        if (failed > 0) System.exit(1);
    }

    // ---------- 手机号 ----------

    private static void phoneCases() {
        section("Verify.phone");
        check("正常号", Verify.phone("13800138000"));
        check("首位 2 拒绝", !Verify.phone("23800138000"));
        check("10 位拒绝", !Verify.phone("1380013800"));
        check("12 位拒绝", !Verify.phone("138001380000"));
        check("含空格拒绝", !Verify.phone("138 0013800"));
        check("中文数字拒绝", !Verify.phone("1380013800一"));
        check("全角数字拒绝", !Verify.phone("１３８００１３８０００"));
        check("null 拒绝", !Verify.phone(null));
        check("空串拒绝", !Verify.phone(""));

        // 不合格原因要能分清「长度不对」和「11 位但不是 1 开头」，提示才不会答非所问
        section("Verify.phoneIssue");
        check("合格为空", Verify.phoneIssue("13800138000").length() == 0);
        check("null 报长度 0", "长度 0".equals(Verify.phoneIssue(null)));
        check("10 位报长度", "长度 10".equals(Verify.phoneIssue("1380013800")));
        check("首位非 1", "首位非 1".equals(Verify.phoneIssue("23800138000")));
        check("含空格报非数字", "含非数字".equals(Verify.phoneIssue("138 0013800")));
    }

    // ---------- 验证码 ----------

    private static void smsCodeCases() {
        section("Verify.smsCode");
        check("4 位", Verify.smsCode("1234"));
        check("6 位", Verify.smsCode("123456"));
        check("8 位", Verify.smsCode("12345678"));
        check("3 位拒绝", !Verify.smsCode("123"));
        check("9 位拒绝", !Verify.smsCode("123456789"));
        check("含字母拒绝", !Verify.smsCode("12a456"));
        check("含空格拒绝", !Verify.smsCode(" 23456"));
        check("null 拒绝", !Verify.smsCode(null));
    }

    // ---------- 有效期衰减 ----------

    private static void ttlCases() {
        section("Verify.ttlRemain");
        long now = 1_700_000_000L;
        eq("未知 TTL → -1", -1, Verify.ttlRemain(-1, now, now));
        eq("刚探活 → 原值", 7776000, Verify.ttlRemain(7776000, now, now));
        eq("过 10 天 → 减 10 天", 7776000 - 10 * 86400,
                Verify.ttlRemain(7776000, now - 10 * 86400L, now));
        eq("已过期 → 0", 0, Verify.ttlRemain(100, now - 200, now));
        eq("无基准时间 → 原值", 100, Verify.ttlRemain(100, 0, now));
        eq("时间回拨 → 原值", 100, Verify.ttlRemain(100, now + 5000, now));
        eq("恰好到期 → 0", 0, Verify.ttlRemain(100, now - 100, now));
    }

    // ---------- 截断 ----------

    private static void truncateCases() {
        section("Text.limitCodepoints");
        eq("短名原样", "车库闸", Text.limitCodepoints("车库闸", 5));
        eq("5 字原样", "一二三四五", Text.limitCodepoints("一二三四五", 5));
        eq("6 字截断", "一二三四五", Text.limitCodepoints("一二三四五六", 5));
        eq("emoji 不被切半", "🥰🥰", Text.limitCodepoints("🥰🥰🥰", 2));
        eq("null → 空串", "", Text.limitCodepoints(null, 5));
        eq("max=0 → 空串", "", Text.limitCodepoints("abc", 0));

        section("Text.shorten");
        eq("不超长原样", "abc", Text.shorten("abc", 5));
        eq("超长加省略号", "ab…", Text.shorten("abcdef", 2));
        eq("null → 空串", "", Text.shorten(null, 5));
    }

    // ---------- 断言小工具 ----------

    private static void section(String name) {
        System.out.println("-- " + name);
    }

    private static void check(String what, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    private static void eq(String what, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (!ok) {
            failed++;
            System.out.println("  FAIL " + what + "  expect=" + expect + " actual=" + actual);
        } else {
            passed++;
        }
    }

    private static void eq(String what, int expect, int actual) {
        eq(what, Integer.valueOf(expect), Integer.valueOf(actual));
    }
}
