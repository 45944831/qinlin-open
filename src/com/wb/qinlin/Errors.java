package com.wb.qinlin;

/**
 * 统一的「给用户看的错误文案」。
 *
 * <p>以前 {@code MainActivity.friendlyFailure()} 与 {@code DoorListActivity.friendlyCode()}
 * 各写一套：同一类网络异常在登录页和门列表页提示不一样，改文案时也必然漏改一处。
 * 现在收敛到这里，界面只负责决定「展示成 toast 还是状态行」。</p>
 */
final class Errors {

    private Errors() {}

    /**
     * 网络层原始异常 → 一句人能看懂的话。
     *
     * <p>刻意<b>不读</b> {@code getMessage()}：HttpURLConnection 的异常消息常带完整 URL
     * （含 sessionId），直接弹出来既吓人又泄露凭证。完整堆栈只进本机日志（AppLog 已脱敏）。</p>
     */
    static String fromException(Throwable e) {
        if (e == null) return "操作失败，请稍后重试";
        if (e instanceof java.net.SocketTimeoutException) return "网络请求超时，请检查网络后重试";
        if (e instanceof java.net.UnknownHostException) return "无法连接服务器，请检查网络后重试";
        if (e instanceof java.net.ConnectException) return "无法连接服务器，请稍后重试";
        if (e instanceof javax.net.ssl.SSLException) return "安全连接失败，请稍后重试";
        if (e instanceof java.io.IOException) return "网络连接异常，请稍后重试";
        return "操作失败（" + e.getClass().getSimpleName() + "），请稍后重试";
    }

    /**
     * 错误码 → 界面前缀（含末尾的「 · 」分隔符，调用方直接拼上上游原文）。
     *
     * <p>只给「原文本身不像人话」的码加前缀：{@code NETWORK} / {@code COMMUNITY_FAILED}
     * 的 message 已经是一句完整的中文提示，再加前缀会变成
     * 「网络异常 · 网络异常，读不到门列表…」这种重复。</p>
     */
    static String prefixFromCode(String code) {
        if (code == null) return "";
        if ("BAD_FORMAT".equals(code)) return "上游返回异常 · ";
        if ("SIGN_REJECTED".equals(code)) return "签名被拒 · ";
        if (code.startsWith("HTTP_")) return "服务异常 · ";
        return "";
    }
}
