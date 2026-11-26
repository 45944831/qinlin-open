package com.wb.qinlin.core;

/** 纯文本工具：同样不依赖 Android，可被桌面测试直接覆盖。 */
public final class Text {

    private Text() {}

    /**
     * 按 Unicode 码点截断，emoji / 生僻字不会被截成半个代理对。
     * @param max 最大码点数；≤0 时返回空串
     */
    public static String limitCodepoints(String v, int max) {
        if (v == null || max <= 0) return "";
        if (v.codePointCount(0, v.length()) <= max) return v;
        return v.substring(0, v.offsetByCodePoints(0, max));
    }

    /** 超长截断并加省略号；不截断时原样返回（null → 空串）。 */
    public static String shorten(String s, int max) {
        if (s == null) return "";
        if (max <= 0) return s.length() == 0 ? "" : "…";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
