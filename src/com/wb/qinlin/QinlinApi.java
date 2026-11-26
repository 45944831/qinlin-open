package com.wb.qinlin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * qinlin云端网关协议的独立实现（仅使用 Android / JDK 标准库，无第三方依赖）。
 *
 * 开门指令必须经qinlin服务端实时校验，因此本类必然要联网；
 * 但不需要任何自建服务器、域名、HTTPS 证书或 Docker。
 */
public final class QinlinApi {

    public static final String BASE = "https://mobileapi3.qinlinkeji.com";
    private static final String SMS_URL =
            "https://gateway2.qinlinkeji.com/member/sms/sendSecurityCode";

    private static final String APPID = "gbDjIZQSOpCMX49P";
    private static final String APP_SECRET = "OKFoQ9MmNXQtcyXROo4PnaFkfDPTHuDg";
    private static final byte[] PHONE_KEY = hexToBytes("FBC213C4C7BEEBD2AA4EDBF0F681C41B");
    private static final String SIGN_SUFFIX = "key=qiAnlPinP";

    // 协议版本头：deviceHeaders() 与签名 body 必须取同一份值，否则签名不匹配（code 121）。
    // 对齐 QinlinOpen 已验证的 5.2.6/3146；官方 5.3.x 无开源验证，不追。
    private static final String VERSION_NAME = "5.2.6";
    private static final String VERSION_CODE = "3146";
    private static final String USER_AGENT = "Dart/3.8 (dart:io)";

    private static final SecureRandom RND = new SecureRandom();

    /**
     * 探活类只读请求的超时预算（checkLogin / 单次 communityInfo 兜底）。
     * 探活只是确认凭证死活，等太久没有意义——尤其是小部件路径：
     * goAsync() 的广播前台时限约 10 秒，探活必须留足余量给真正的开门请求。
     * 登录 / 开门仍用各自的默认超时（见 request()）。
     */
    static final int PROBE_CONNECT_MS = 5000;
    static final int PROBE_READ_MS = 6000;

    /**
     * 桌面小部件路径的开门预算：{@code goAsync()} 的广播窗口约 10 秒。
     *
     * <p>默认的 5s 连接 + 15s 读 = 20 秒，单是开门一项就超过窗口；进程在窗口结束后
     * 可能被回收，{@code finishRequest} 不执行，卡片就会永久停在「开门中…」。
     * 这里压到 2.5s + 5s（最坏 7.5 秒）——正常 RTT 只有 0.3~0.6 秒，余量仍然充足。
     * 应用内开门不受影响，继续用「宁可多等」的默认超时。</p>
     */
    static final int WIDGET_CONNECT_MS = 2500;
    static final int WIDGET_READ_MS = 5000;
    /** 小部件路径的探活预算：最多再花 2.5s + 3s，给后面的开门留出窗口。 */
    static final int WIDGET_PROBE_CONNECT_MS = 2500;
    static final int WIDGET_PROBE_READ_MS = 3000;

    /**
     * 小区列表的总时间预算。4 种请求姿势串行、每种 5s 连接 + 15s 读，最坏 80 秒，
     * 那时用户早就退出门列表页了，线程还在后台空转。超过预算就收手并给出可重试的结论。
     */
    private static final long COMM_TOTAL_BUDGET_MS = 25000L;

    private QinlinApi() {}

    public static class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        public ApiException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static class Community {
        public final int id;
        public final String name;
        Community(int id, String name) { this.id = id; this.name = name; }
    }

    public static class Door {
        public final int communityId;
        public final int id;
        public final String name;
        public final String kind;
        public final String online;
        Door(int communityId, int id, String name, String kind, String online) {
            this.communityId = communityId;
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.online = online;
        }
        /** 简洁名称，用于保存默认门与状态提示（不带在线状态，避免文字折行）。 */
        public String plainLabel() { return kind + " · " + name + " (#" + id + ")"; }

        public String label() {
            // 在线状态用短文案，避免长按钮文字折行（旧写法 [在线:online] 太占宽度）
            String s = "online".equalsIgnoreCase(online) ? "在线" : online;
            return plainLabel() + (s.length() > 0 ? "  · " + s : "");
        }

        /**
         * 悬浮列表内展示用：分组标题已经写明「xx 区域」，
         * 行内不再重复区域前缀，长门名也能单行显示。
         */
        public String listLabel() {
            String s = name + "  (#" + id + ")";
            String o = "online".equalsIgnoreCase(online) ? "在线" : online;
            return o.length() > 0 ? s + "  · " + o : s;
        }
    }

    /**
     * 一次门列表读取的结果：门 + 上游的「授权到期」标记。
     * 以前只返回 List&lt;Door&gt;，把 data 顶层的 authorizeExpired 直接丢掉了——
     * 而官方 FAQ 明确写着开门权限有期限（业主自动续期、租客需申请），
     * 到期前官方 App 会在首页提醒，我们却只能等开门失败才知道。
     */
    public static class DoorsResult {
        public final List<Door> doors;
        /** 上游 authorizeExpired 原值：-1 = 未返回该字段；0 = 无到期限制；≥1 = 有到期限制。 */
        public final int authorizeExpired;

        DoorsResult(List<Door> doors, int authorizeExpired) {
            this.doors = doors;
            this.authorizeExpired = authorizeExpired;
        }
    }

    // ---------- 编码工具 ----------

    private static byte[] hexToBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
        return sb.toString();
    }

    private static String randomHex(int byteCount) {
        byte[] b = new byte[byteCount];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        return sb.toString();
    }

    private static String md5Upper(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return bytesToHexUpper(md.digest(s.getBytes("UTF-8"))).toUpperCase(Locale.US);
    }

    /** 按 key 字典序拼接 k=v&...，追加后缀后取 MD5 大写。null 值跳过。 */
    private static String sign(TreeMap<String, String> fields, String suffix) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String v = e.getValue();
            if (v == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(v);
        }
        sb.append('&').append(suffix);
        return md5Upper(sb.toString());
    }

    /** 手机号 AES-128-ECB + PKCS7(等价 PKCS5Padding)，输出大写十六进制。 */
    private static String encryptPhone(String phone) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(PHONE_KEY, "AES"));
        return bytesToHexUpper(c.doFinal(phone.getBytes("UTF-8")));
    }

    // ---------- HTTP ----------

    /** openid 必须跨请求、跨进程保持一致：服务端可能按设备标识索引会话。 */
    private static volatile String DEVICE_OPENID = null;

    /** 由 Activity 在启动时注入持久化后的设备标识。 */
    public static void setOpenid(String v) {
        if (v != null && v.length() > 0) {
            String old = DEVICE_OPENID;
            DEVICE_OPENID = v;
            if (old != null && !old.equals(v)) {
                AppLog.w(AppLog.SYS, "进程内 openid 变更 " + AppLog.fp(old)
                        + " → " + AppLog.fp(v) + "（会话可能与登录时不一致）");
            }
        }
    }

    private static String openid() {
        String v = DEVICE_OPENID;
        if (v == null) {
            v = randomHex(8);
            DEVICE_OPENID = v;
            // 这类情况最容易造成「登录态还在但服务端拒绝」：必须留痕
            AppLog.w(AppLog.SYS, "⚠ openid 未从本机注入，临时生成随机值；与登录时不一致可能导致会话被拒");
        }
        return v;
    }

    /** 网关对签名头大小写敏感：所有头名一律小写。 */
    private static LinkedHashMap<String, String> deviceHeaders() {
        LinkedHashMap<String, String> h = new LinkedHashMap<String, String>();
        h.put("qvendor", "Xiaomi");
        h.put("openid", openid());
        // 版本头取自顶部常量（与签名 body.version 同一份 VERSION_NAME），改一处即全生效。
        // 对齐 QinlinOpen 已验证的 5.2.6/3146；qchannel/qvendor 涉及风控指纹，保持历史验证值不动。
        h.put("qversioncode", VERSION_CODE);
        h.put("qversionname", VERSION_NAME);
        h.put("qchannel", "xiaomi");
        h.put("qplatform", "0");
        h.put("user-agent", USER_AGENT);
        return h;
    }

    private static String urlEncode(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String buildUrl(String base, TreeMap<String, String> params) throws Exception {
        if (params == null || params.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&');
            first = false;
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String formBody(TreeMap<String, String> fields) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&');
            first = false;
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    /** 部分网关接口会把这些字段强转成数值；传字符串会触发服务端 ClassCastException。 */
    private static final String[] NUMERIC_KEYS = {
            "timestamp", "appChannel", "communityId", "doorControlId"
    };

    private static boolean isNumericKey(String k) {
        for (String n : NUMERIC_KEYS) if (n.equals(k)) return true;
        return false;
    }

    private static String jsonBody(TreeMap<String, String> fields, boolean numeric)
            throws Exception {
        JSONObject j = new JSONObject();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String v = e.getValue();
            if (v == null) continue;
            if (numeric && isNumericKey(e.getKey())) {
                try { j.put(e.getKey(), Long.parseLong(v)); }
                catch (NumberFormatException ignore) { j.put(e.getKey(), v); }
            } else {
                j.put(e.getKey(), v);
            }
        }
        return j.toString();
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    /**
     * 取字符串字段，把 JSON 的 null 当成「字段不存在」。
     *
     * <p>org.json 的 {@code optString(key, fallback)} 在字段值是 {@code JSONObject.NULL}
     * 时返回字符串 {@code "null"} 而不是 fallback。上游只要下发一个 {@code "sessionId": null}，
     * 旧代码就会把 "null" 当成真凭证写进本机；门名/小区名也会显示成 "null"。</p>
     */
    public static String str(JSONObject o, String key, String fallback) {
        if (o == null || key == null || !o.has(key)) return fallback;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return fallback;
        String s = String.valueOf(v);
        return "null".equals(s) ? fallback : s;
    }

    /**
     * 宽松布尔：上游有的接口给 {@code true}，有的给 {@code 1} 或 {@code "true"}。
     * {@code JSONObject.optBoolean()} 只认真正的 JSON 布尔，数字/字符串一律返回 false，
     * 会把「成功」误判成「失败」。这里三种写法都认。
     */
    private static boolean lenientTrue(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "ok".equalsIgnoreCase(s);
    }

    /** 单次响应体上限：正常业务响应只有几 KB，超过这个量级说明上游异常，继续读只会 OOM。 */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        boolean truncated = false;
        try {
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_BODY_BYTES) {
                    out.write(buf, 0, MAX_BODY_BYTES - out.size());
                    truncated = true;
                    break;
                }
                out.write(buf, 0, n);
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        if (truncated) {
            // 截断后的 JSON 必然解析失败，留着半截文本没有意义；直接判定失败并断开连接，
            // 避免把「还有未读数据」的连接交还连接池。
            AppLog.w(AppLog.NET, "⚠ 响应体超过 " + MAX_BODY_BYTES + " 字节上限，放弃解析（上游可能异常）");
            throw new ApiException("BODY_TOO_LARGE",
                    "上游响应超过 " + (MAX_BODY_BYTES / 1024 / 1024) + "MB 上限，已放弃解析");
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String request(String base, LinkedHashMap<String, String> headers,
                                  TreeMap<String, String> params, String body,
                                  String contentType) throws Exception {
        return request(base, headers, params, body, contentType, 5000, 15000);
    }

    /** 带超时预算的通用请求：探活走短读超时，登录 / 开门保持「宁可多等」的默认值。 */
    private static String request(String base, LinkedHashMap<String, String> headers,
                                  TreeMap<String, String> params, String body,
                                  String contentType, int connectMs, int readMs) throws Exception {
        final long t0 = System.currentTimeMillis();
        String path = base;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);   // URL 里带 sessionId，日志只记路径
        AppLog.i(AppLog.NET, "请求 → POST " + path
                + " | body=" + (body == null ? "无" : body.length() + "字节")
                + " | 参数键=" + (params == null ? "无" : params.keySet())
                + " | session=" + AppLog.fp(params == null ? null : params.get("sessionId"))
                + " | openid=" + AppLog.fp(headers == null ? null : headers.get("openid")));

        HttpURLConnection conn = null;
        // 响应体完整读完并关闭后，连接可交还底层连接池；开门是同域名的后续请求，
        // 复用后能省掉一次 DNS + TCP + TLS 握手，这是点击到反馈之间最重的一段。
        boolean reusable = false;
        try {
            conn = (HttpURLConnection) new URL(buildUrl(base, params)).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (contentType != null) conn.setRequestProperty("content-type", contentType);
            if (body != null) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);       // readAll 内部会 close 输入流
            reusable = (in != null);        // 流已读完 → 允许 keep-alive 复用
            String red = redact(text);
            RAW.set(red);               // 只给本线程的调用方读取，避免并发请求互相串响应
            AppLog.i(AppLog.NET, "响应 ← HTTP " + status
                    + " | 耗时 " + (System.currentTimeMillis() - t0) + "ms"
                    + " | 长度 " + text.length() + "字节"
                    + " | 摘要 " + shorten(red, 220));
            if (status != 200) {
                // 5xx 的真实原因常在 exception / error 里，别只报一句 HTTP 500
                String detail = upstreamDetail(text);
                AppLog.e(AppLog.NET, "HTTP 非 200，本次请求判定失败 | 原因=" + shorten(detail, 240));
                // 带凭证的请求被回 401，等价于「会话已失效」：以前只抛 HTTP_401，
                // unwrap() 里 code==401 的分支永远走不到，界面也就不会识别成掉登录，
                // 表现为「登录态还在但什么都读不出来」。这里统一成业务错误码，
                // 后续仍由 SessionGuard.expiredAfterRetry() 做二次确认，不会误清凭证。
                if (status == 401 && params != null) {
                    String sid = params.get("sessionId");
                    if (sid != null && sid.length() > 0) {
                        AppLog.w(AppLog.LOGIN, "HTTP 401 + 已带凭证 → SESSION_EXPIRED（等待二次确认）");
                        throw new ApiException("SESSION_EXPIRED",
                                "qinlin网关返回 HTTP 401：凭证已失效，请重新短信登录");
                    }
                }
                throw new ApiException("HTTP_" + status,
                        "上游 HTTP " + status + "：" + detail);
            }
            return text;
        } catch (ApiException ae) {
            throw ae;   // 上面已记录，避免重复
        } catch (Exception ex) {
            AppLog.e(AppLog.NET, "网络异常 耗时 " + (System.currentTimeMillis() - t0)
                    + "ms | " + AppLog.stack(ex));
            throw ex;
        } finally {
            // 只有异常 / 响应流没读完才断开；正常响应保留连接给下一次请求复用。
            // 不缩短 readTimeout：开门请求宁可多等，也不能因过早判失败让用户再点一次。
            if (!reusable && conn != null) conn.disconnect();
        }
    }

    /** 解析业务响应；返回 data 节点（可能是 JSONObject / JSONArray / null）。 */
    private static Object unwrap(String text, boolean authenticated) throws Exception {
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Exception e) {
            AppLog.e(AppLog.NET, "响应格式异常 BAD_FORMAT | " + shorten(redact(text), 160));
            throw new ApiException("BAD_FORMAT", "上游返回格式异常：" + redact(text));
        }
        // 上游可能下发 code/status: null —— optString 会返回字符串 "null"，这里按缺省处理
        String code = str(o, "code", str(o, "status", ""));
        boolean hasCode = code.length() > 0;
        // code 与 success 必须是「主从」而不是「或」：
        // 旧写法 code==0 || code==200 || optBoolean("success") 会让 success:true 盖掉
        // code=401/121，直接跳过会话失效识别——凭证明明被判超时，界面却报成普通业务失败。
        // 现在：给了 code 就只认 code；没给 code 才看 success（并兼容 1 / "true"）。
        boolean ok = hasCode
                ? (code.equals("0") || code.equals("200"))
                : lenientTrue(o.opt("success"));
        AppLog.i(AppLog.NET, "业务解析 code=" + code
                + " | success=" + String.valueOf(o.opt("success"))
                + " | 摘要=" + shorten(redact(text), 160));
        // 官方客户端有 _updateCacheSessionId，疑似服务端会轮换/下发新票据。
        // 先只留痕不自动替换：真机导出日志确认字段名后再决定是否跟随，避免误换凭证。
        if (authenticated && o.has("sessionId")) {
            AppLog.w(AppLog.LOGIN, "⚠ 业务响应顶层出现 sessionId 字段（疑似服务端轮换票据），本版暂不自动替换");
        }
        if (!ok) {
            String msg = errorText(o);
            boolean expired = code.equals("401")
                    || msg.contains("请重新登录") || msg.contains("请先登录")
                    || msg.contains("未登录") || msg.contains("登录已过期")
                    || msg.contains("登录过期") || msg.contains("登录失效")
                    || msg.contains("登录状态失效") || msg.contains("登录状态已失效")
                    || msg.toLowerCase(Locale.US).contains("session expired")
                    || msg.toLowerCase(Locale.US).contains("invalid session")
                    || msg.toLowerCase(Locale.US).contains("token expired");
            if (authenticated && expired) {
                AppLog.w(AppLog.LOGIN, "服务端判定凭证失效 → SESSION_EXPIRED（需重新短信登录）");
                throw new ApiException("SESSION_EXPIRED", "qinlin凭证已失效，请重新短信登录");
            }
            // 121 = 签名或签名头不被接受（缺头 / 时间戳越界 / 签名不匹配）。
            // 单独成码：以前混在 QINLIN_REJECTED 里，和「门离线 / 无权限」的提示一模一样，
            // 上游一旦改签名规则，日志和界面都看不出真正原因。
            if (code.equals("121")) {
                AppLog.e(AppLog.NET, "业务拒绝 → SIGN_REJECTED（code 121 = 签名/签名头不被接受）| msg=" + msg);
                throw new ApiException("SIGN_REJECTED",
                        "qinlin网关拒绝了请求签名（code 121），多为协议版本头过期；请更新 App 后重试，"
                                + "持续出现请把日志发给开发者。上游原因：" + msg);
            }
            AppLog.e(AppLog.NET, "业务拒绝 → QINLIN_REJECTED | msg=" + msg);
            throw new ApiException("QINLIN_REJECTED", "qinlin未接受请求：" + msg);
        }
        return o.opt("data");
    }

    /**
     * 通用网关 POST。
     * mode: json（JSON body）/ form（表单 body）/ query（参数全部放 URL，无 body）
     */
    private static Object post(String path, TreeMap<String, String> fields,
                               String token, String mode) throws Exception {
        return post(path, fields, token, mode, true);
    }

    private static Object post(String path, TreeMap<String, String> fields,
                               String token, String mode, boolean numericJson) throws Exception {
        return post(path, fields, token, mode, numericJson, 5000, 15000);
    }

    private static Object post(String path, TreeMap<String, String> fields,
                               String token, String mode, boolean numericJson,
                               int connectMs, int readMs) throws Exception {
        AppLog.i(AppLog.NET, "组装请求 " + path + " | mode=" + mode
                + " | session=" + AppLog.fp(token)
                + " | 字段=" + fields.keySet());

        TreeMap<String, String> body = new TreeMap<String, String>(fields);
        body.put("timestamp", String.valueOf(System.currentTimeMillis()));
        body.put("nonce", String.valueOf(RND.nextInt(90000) + 10000));
        body.put("version", VERSION_NAME);   // 与 deviceHeaders().qversionname 同一常量，绝不脱节

        TreeMap<String, String> signed = new TreeMap<String, String>();
        signed.put("token", token == null ? "" : token);
        signed.putAll(body);
        body.put("sign", sign(signed, SIGN_SUFFIX));

        TreeMap<String, String> params = new TreeMap<String, String>();
        if (token != null && token.length() > 0) params.put("sessionId", token);

        LinkedHashMap<String, String> headers = deviceHeaders();
        String payload;
        String contentType;
        if ("query".equals(mode)) {
            params.putAll(body);
            payload = null;
            contentType = "application/json; charset=utf-8";
        } else if ("form".equals(mode)) {
            payload = formBody(body);
            contentType = "application/x-www-form-urlencoded";
        } else {
            payload = jsonBody(body, numericJson);
            contentType = "application/json; charset=utf-8";
        }
        String text = request(BASE + path, headers, params, payload, contentType, connectMs, readMs);
        return unwrap(text, token != null && token.length() > 0);
    }

    // ---------- 对外接口 ----------

    /** 发送短信验证码。注意：短信签名用 appsecret 后缀，且 mobile 是明文。 */
    public static void sendSms(String phone) throws Exception {
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("appid", APPID);
        f.put("mobile", phone);
        f.put("nonce", String.valueOf(RND.nextInt(9000) + 1000));
        f.put("timestamp", String.valueOf(System.currentTimeMillis()));
        f.put("version", "v2");

        LinkedHashMap<String, String> headers = deviceHeaders();
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (!"mobile".equals(e.getKey())) headers.put(e.getKey(), e.getValue());
        }
        headers.put("sign", sign(f, "appsecret=" + APP_SECRET));
        headers.put("content-type", "application/json; charset=utf-8");

        TreeMap<String, String> smsBody = new TreeMap<String, String>();
        smsBody.put("mobile", phone);
        String text = request(SMS_URL, headers, null, jsonBody(smsBody, false),
                "application/json; charset=utf-8");
        unwrap(text, false);
    }

    /**
     * 验证码登录，返回 data 的完整对象。
     * 日志只记字段名（不记字段值），用于确认上游是否还下发长效凭证 / 续期票据；
     * 完整原文由调用方存到本机，为「超时后静默续期」留材料。
     */
    public static JSONObject loginDetailed(String phone, String code) throws Exception {
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("mobile", encryptPhone(phone));
        f.put("smsCode", code);
        f.put("appChannel", "1");
        Object data = post("/api/app/v1/login", f, "", "json");
        if (!(data instanceof JSONObject)) {
            throw new ApiException("BAD_FORMAT", "登录响应缺少凭证");
        }
        JSONObject d = (JSONObject) data;
        JSONArray names = d.names();
        AppLog.i(AppLog.LOGIN, "登录响应字段=" + (names == null ? "空" : names.toString()));
        return d;
    }

    /** 验证码登录，返回 sessionId。 */
    public static String login(String phone, String code) throws Exception {
        String token = str(loginDetailed(phone, code), "sessionId", "").trim();
        if (token.length() == 0) {
            throw new ApiException("BAD_FORMAT", "登录响应缺少 sessionId");
        }
        return token;
    }

    /**
     * 会话探活：官方 APK 静态提取到 app/v1/checkLogin，且无凭证探测确认
     * /api/app/v1/checkLogin 路由存在（返回 401「登录已超时」）。
     *
     * 只读、无业务副作用。用途有二：
     * ① 前台按需检查凭证是否已被服务端作废（防抖窗口见 SessionGuard）；
     * ② 收到 401 后再单独确认一次，区分「真超时」与「网关偶发误判 / 网络抖动」。
     *
     * 用探活短超时（PROBE_*）：探活本身很轻，等太久没有意义，
     * 且小部件路径的广播前台时限只有约 10 秒，预算必须留给开门请求。
     */
    public static int checkLogin(String token) throws Exception {
        return checkLogin(token, PROBE_CONNECT_MS, PROBE_READ_MS);
    }

    /** 带超时预算的探活：小部件路径用更短的预算（见 {@link #WIDGET_PROBE_READ_MS}）。 */
    static int checkLogin(String token, int connectMs, int readMs) throws Exception {
        TreeMap<String, String> f = new TreeMap<String, String>();
        Object data = post("/api/app/v1/checkLogin", f, token, "json", true,
                connectMs, readMs);
        // 2026-11-16 真机实测：data = 7776000，正好 90 天整 → 会话滑动过期窗口（秒）。
        // 以前返回 void 把它丢了；现在解析出来，界面与日志都能看到「凭证还剩多久」。
        int ttl = -1;
        if (data instanceof Number) {
            ttl = ((Number) data).intValue();
        } else if (data instanceof String) {
            try {
                ttl = Integer.parseInt(((String) data).trim());
            } catch (Exception ignored) {
            }
        }
        if (ttl > 0) {
            AppLog.i(AppLog.LOGIN, "checkLogin 通过 | 会话有效期剩余 " + ttl + " 秒 ≈ "
                    + (ttl / 86400) + " 天（滑动过期：任意一次带凭证的请求都会续上）");
        } else {
            AppLog.i(AppLog.LOGIN, "checkLogin 通过 | 有效期字段未识别（data 类型="
                    + (data == null ? "null" : data.getClass().getSimpleName()) + "），不影响登录态判定");
        }
        return ttl;
    }

    /**
     * 按线程隔离的响应原文：启动时后台补小区名与开门可能并发，
     * 只读全局字段会把别人的响应记进本次开门日志。
     */
    private static final ThreadLocal<String> RAW = new ThreadLocal<String>();

    /**
     * 只保留结构，抹掉凭证和手机号，避免诊断输出泄露敏感信息。
     * 直接复用日志层的同一套规则：界面显示（status）与日志落盘必须一样干净。
     */
    private static String redact(String s) {
        if (s == null) return "";
        return AppLog.scrub(s);
    }

    /**
     * 从上游 JSON 里拼出「可读原因」：message 之外还要看 exception / error。
     * 2026-11-16 调研（吾爱破解实测报文）：服务端 5xx 时 message 往往只有一句
     * {@code No message available}，真实异常栈在 {@code exception} 字段里，
     * 只读 message 等于把唯一线索丢掉。
     */
    private static String reason(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        String msg = str(o, "message", str(o, "msg", ""));
        if (msg.length() > 0 && !"No message available".equalsIgnoreCase(msg)) sb.append(msg);
        appendField(sb, "exception", str(o, "exception", ""));
        appendField(sb, "error", str(o, "error", ""));
        return sb.toString().trim();
    }

    private static void appendField(StringBuilder sb, String key, String v) {
        if (v == null || v.length() == 0) return;
        if (sb.length() > 0) sb.append(" | ");
        sb.append(key).append('=').append(v.length() > 200 ? v.substring(0, 200) + "…" : v);
    }

    /** HTTP 非 200 时的原因文本：优先解析 JSON 的 message/exception/error，解析不了就退回原文。 */
    private static String upstreamDetail(String text) {
        try {
            String r = reason(new JSONObject(text));
            if (r.length() > 0) return redact(r);
        } catch (Exception ignored) {
        }
        return redact(text);
    }

    /** 业务 code != 0 时的原因文本（解析不出就保持旧的兜底文案）。 */
    private static String errorText(JSONObject o) {
        String r = reason(o);
        return redact(r.length() > 0 ? r : "qinlin未接受请求");
    }

    /**
     * 本线程最近一次小区列表请求的诊断摘要（不含任何凭证）。
     * 用 ThreadLocal 而非全局字段：门列表页与主界面可能同时补小区名，
     * 全局值会把另一个请求的结论显示给用户。
     */
    private static final ThreadLocal<String> DIAG = new ThreadLocal<String>();

    public static String diag() {
        String d = DIAG.get();
        return d == null ? "" : d;
    }

    /** 上次成功的请求姿势下标；由调用方在启动时从本机设置注入，成功时写回。 */
    public static volatile int PREFERRED_COMM_MODE = -1;

    /** 把「上次成功姿势」提到最前，其余按原顺序跟进；下标非法时保持原顺序。 */
    private static int[] attemptOrder(int n, int preferred) {
        int[] order = new int[n];
        int p = 0;
        if (preferred >= 0 && preferred < n) order[p++] = preferred;
        for (int i = 0; i < n; i++) {
            if (i == preferred) continue;
            order[p++] = i;
        }
        return order;
    }

    /** 网络层失败（超时 / 断网 / TLS）：换请求格式没有任何帮助，必须立刻停手。 */
    private static boolean isNetworkFailure(Throwable e) {
        // SocketTimeoutException / ConnectException / SSLException 都是 IOException 的子类
        return e instanceof java.io.IOException;
    }

    /**
     * 这次失败是否「像请求姿势不对」，值得换一种 body 形式再试。
     *
     * <p>判据：网关明确回了 4xx（400/404/405/415 —— 路径、方法或 body 类型不被接受），
     * 或者业务层回了一个「未接受请求 / 格式异常」。其余业务码（签名被拒 121、
     * 无权限、门不存在…）换 body 形式不会变好，继续串行只是白等。</p>
     */
    private static boolean shapeRetryable(String code) {
        if (code == null) return false;
        if ("QINLIN_REJECTED".equals(code) || "BAD_FORMAT".equals(code)) return true;
        if (!code.startsWith("HTTP_")) return false;
        int st = 0;
        try {
            st = Integer.parseInt(code.substring(5));
        } catch (Exception ignored) {
        }
        // 4xx 才可能是姿势不对；5xx 是上游自身故障，换格式不会让它恢复
        return st == 400 || st == 404 || st == 405 || st == 415;
    }

    public static List<Community> communities(String token) throws Exception {
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("method", "communityInfo");

        // 网关对请求体的类型/位置较敏感：按「最贴近上游」优先，依次兜底。
        final String[] modes = {"json", "form", "query", "json"};
        final boolean[] nums = {true, false, false, false};
        final String[] names = {"JSON/数值字段", "表单", "URL参数", "JSON/全字符串"};

        // 上次成功过的那一种优先：命中就只发 1 个请求，不再串行试 4 种。
        int[] order = attemptOrder(modes.length, PREFERRED_COMM_MODE);

        boolean succeeded = false;
        /** 是否因网络层原因（超时 / 断网 / TLS）放弃：决定抛 NETWORK 还是 COMMUNITY_FAILED。 */
        boolean netFail = false;
        /** 是否因总预算耗尽而放弃：同样按「可重试的网络问题」处理。 */
        boolean budgetHit = false;
        /** 最后一次业务拒绝的 code，用于给界面一句可读的原因。 */
        String lastBizCode = "";
        Object data = null;
        StringBuilder log = new StringBuilder();
        final long deadline = System.currentTimeMillis() + COMM_TOTAL_BUDGET_MS;
        for (int k = 0; k < order.length; k++) {
            final int i = order[k];
            if (k > 0 && System.currentTimeMillis() > deadline) {
                AppLog.w(AppLog.DOORS, "小区列表已用尽 " + COMM_TOTAL_BUDGET_MS
                        + "ms 总预算 → 停止换姿势重试");
                budgetHit = true;
                break;
            }
            AppLog.i(AppLog.DOORS, "小区列表尝试 " + (k + 1) + "/" + order.length + " → " + names[i]
                    + (k == 0 && PREFERRED_COMM_MODE == i ? "（上次成功姿势）" : ""));
            try {
                data = post("/api/app/user/v2/communityInfo", f, token, modes[i], nums[i]);
                succeeded = true;
                PREFERRED_COMM_MODE = i;
                DIAG.set("小区列表：已用「" + names[i] + "」方式成功");
                AppLog.ok(AppLog.DOORS, "小区列表成功 → " + names[i]);
                break;
            } catch (ApiException e) {
                // 凭证失效必须原样抛出：如果被包成 COMMUNITY_FAILED，
                // 界面就识别不出 401，不会清凭证也不会提示重新登录，
                // 表现就是「登录态还在但什么都读不出来、也开不了门」。
                if ("SESSION_EXPIRED".equals(e.code)) {
                    AppLog.w(AppLog.DOORS, "小区列表遇到凭证失效 → 直接上抛，不再换姿势重试");
                    throw e;
                }
                log.append(names[i]).append(" → ").append(e.code).append(": ")
                        .append(shorten(e.getMessage(), 120)).append("\n");
                lastBizCode = e.code;
                AppLog.w(AppLog.DOORS, names[i] + " 失败 [" + e.code + "] "
                        + shorten(e.getMessage(), 120));
                // 只有「像请求姿势不对」的失败才值得换一种 body 形式重试。
                // 旧实现对任何非 HTTP_ 的业务码都直接 break，而网关对格式问题很可能
                // 是以业务码拒绝的（不是 4xx）—— 于是 2~4 号兜底姿势实际上从没被用到过。
                if (!shapeRetryable(e.code)) {
                    AppLog.w(AppLog.DOORS, "「" + e.code + "」不是请求姿势问题（业务拒绝 / 上游故障）"
                            + "→ 停止换姿势重试");
                    break;
                }
            } catch (Exception e) {
                log.append(names[i]).append(" → ").append(e.getClass().getSimpleName())
                        .append(": ").append(shorten(e.getMessage(), 120)).append("\n");
                AppLog.e(AppLog.DOORS, names[i] + " 异常 " + AppLog.stack(e));
                // 网络层失败（超时 / 断网）与「请求姿势不对」没有因果关系：
                // 换一种 body 格式照样连不上，继续串行 4 次只会把等待放大到 4×20 秒。
                if (isNetworkFailure(e)) {
                    AppLog.w(AppLog.DOORS, "网络层失败 → 停止换姿势重试（换请求格式不解决网络问题）");
                    netFail = true;
                    break;
                }
            }
        }
        if (!succeeded) {
            DIAG.set(log.toString());
            if (netFail || budgetHit) {
                // 断网 / 超时不是「网关拒绝」，上层要按网络问题提示（可以重试），
                // 而不是显示一段带 SocketTimeoutException 类名的技术文本。
                AppLog.e(AppLog.DOORS, "小区列表因" + (netFail ? "网络层失败" : "总预算耗尽") + "而中止");
                throw new ApiException("NETWORK",
                        netFail ? "网络异常，读不到小区列表。请检查网络后点「↻ 刷新门列表」重试。"
                                : "读取小区列表超过 " + (COMM_TOTAL_BUDGET_MS / 1000)
                                  + " 秒仍未完成，请检查网络后点「↻ 刷新门列表」重试。");
            }
            AppLog.e(AppLog.DOORS, "小区列表全部尝试失败，凭证或网关可能有问题");
            // 明细只进 DIAG 与日志：旧实现把整段带 JVM 类名的逐项结果拼进 message，
            // 门列表界面直接 status() 给用户看，既看不懂也不像人话。
            StringBuilder why = new StringBuilder("网关拒绝了小区列表请求");
            if (lastBizCode.length() > 0) why.append("（").append(lastBizCode).append("）");
            why.append("。可在「日志」里看逐项明细，或稍后重试。");
            throw new ApiException("COMMUNITY_FAILED", why.toString());
        }

        List<Community> out = new ArrayList<Community>();
        JSONArray arr = null;
        if (data instanceof JSONArray) {
            arr = (JSONArray) data;
        } else if (data instanceof JSONObject) {
            JSONObject d = (JSONObject) data;
            String[] keys = {"communityList", "list", "communities", "data"};
            for (String k : keys) {
                arr = d.optJSONArray(k);
                if (arr != null) break;
            }
        }
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int id = o.optInt("communityId", -1);
            if (id <= 0) continue;
            out.add(new Community(id, str(o, "communityName", "小区 " + id)));
        }
        AppLog.i(AppLog.DOORS, "解析到 " + out.size() + " 个小区");
        return out;
    }

    /**
     * 单次小区列表请求：仅供 {@link SessionGuard} 在 checkLogin 给不出结论时兜底探活。
     * 与 {@link #communities} 的区别：只发一个请求（用上次成功的姿势或默认姿势）、
     * 探活短超时、绝不逐姿势串行重试——communities() 最坏会连试 4 种姿势，
     * 每次 5 秒连接 + 15 秒读，探活路径的总预算会完全失控（小部件广播前台约 10 秒）。
     * 失败直接抛异常，由调用方判定。
     */
    static void communitiesProbe(String token) throws Exception {
        communitiesProbe(token, PROBE_CONNECT_MS, PROBE_READ_MS);
    }

    /** 带超时预算的兜底探活：小部件路径用更短的预算，避免探活吃掉整个广播窗口。 */
    static void communitiesProbe(String token, int connectMs, int readMs) throws Exception {
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("method", "communityInfo");
        final String[] modes = {"json", "form", "query", "json"};
        final boolean[] nums = {true, false, false, false};
        final String[] names = {"JSON/数值字段", "表单", "URL参数", "JSON/全字符串"};
        int mode = (PREFERRED_COMM_MODE >= 0 && PREFERRED_COMM_MODE < modes.length)
                ? PREFERRED_COMM_MODE : 0;
        AppLog.i(AppLog.DOORS, "探活兜底：单次 communityInfo（" + names[mode] + "，短超时，不串行重试）");
        post("/api/app/user/v2/communityInfo", f, token, modes[mode], nums[mode],
                connectMs, readMs);
        PREFERRED_COMM_MODE = mode;
        DIAG.set("小区列表：已用「" + names[mode] + "」方式成功");
    }

    /** 读取某小区的门列表，并带出上游的授权到期标记（见 {@link DoorsResult}）。 */
    public static DoorsResult doorsDetailed(String token, int communityId) throws Exception {
        AppLog.i(AppLog.DOORS, "读取门列表 communityId=" + communityId
                + " | session=" + AppLog.fp(token));
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("communityId", String.valueOf(communityId));
        List<Door> out = new ArrayList<Door>();
        int authorizeExpired = -1;
        try {
            Object data = post("/api/app/user/v2/queryAllUserDoorByCache", f, token, "form");
            authorizeExpired = parseDoors(data, communityId, out);
        } catch (Exception e) {
            // 网络层失败时兜底接口只会再等一轮超时（5s 连接 + 15s 读），
            // 而结果必然一样失败：直接上抛，让界面快点给出「网络异常」结论。
            if (isNetworkFailure(e)) {
                AppLog.w(AppLog.DOORS, "主门列表接口网络异常 → 不再尝试兜底接口 | " + e.getMessage());
                throw e;
            }
            // 凭证失效也不能被兜底吞掉：否则界面只显示「未读取到门列表」，
            // 用户看不到「登录已失效」，也就不会去重新登录（旧实现就是这个症状）。
            if (e instanceof ApiException && "SESSION_EXPIRED".equals(((ApiException) e).code)) {
                AppLog.w(AppLog.DOORS, "主门列表接口判定凭证失效 → 直接上抛，不再尝试兜底接口");
                throw e;
            }
            AppLog.w(AppLog.DOORS, "主门列表接口失败，尝试 New 接口：" + e.getMessage());
        }
        // 主接口返回空或失败：用 QinlinOpen 所用的 *ByCacheNew 接口兜底。
        // 无凭证环境无法 A/B 实测，故保留主接口优先、New 仅兜底，避免引入回归。
        if (out.isEmpty()) {
            try {
                Object data = post("/api/app/user/v2/queryUserDoorByCacheNew", f, token, "form");
                int ax = parseDoors(data, communityId, out);
                if (ax >= 0) authorizeExpired = ax;
                if (!out.isEmpty()) AppLog.ok(AppLog.DOORS, "New 接口兜底成功，返回 " + out.size() + " 扇门");
            } catch (Exception e) {
                // 兜底接口同样可能先撞上凭证失效：不能吞掉，否则界面只显示「未读取到门列表」，
                // 用户看不到「登录已失效」，也就不会去重新登录。
                if (e instanceof ApiException && "SESSION_EXPIRED".equals(((ApiException) e).code)) {
                    AppLog.w(AppLog.DOORS, "New 门列表接口判定凭证失效 → 上抛");
                    throw e;
                }
                AppLog.w(AppLog.DOORS, "New 门列表接口也失败：" + e.getMessage());
            }
        }
        AppLog.i(AppLog.DOORS, "门列表返回 " + out.size() + " 扇门 communityId=" + communityId
                + " | authorizeExpired=" + authorizeExpired);
        return new DoorsResult(out, authorizeExpired);
    }

    /** 解析门列表：兼容新的扁平数组与旧的分组对象两种 data 结构；返回 authorizeExpired。 */
    private static int parseDoors(Object data, int communityId, List<Door> out) {
        if (data instanceof JSONArray) {
            JSONArray arr = (JSONArray) data;
            for (int i = 0; i < arr.length(); i++) {
                addDoor(arr.optJSONObject(i), communityId, "门", out);
            }
            return -1;
        }
        if (!(data instanceof JSONObject)) {
            AppLog.w(AppLog.DOORS, "门列表响应 data 非对象/数组，跳过");
            return -1;
        }
        JSONObject o = (JSONObject) data;
        // 把 data 的字段名打进日志：上游新增/改名时，靠这一行就能判断该补哪个解析分支
        JSONArray names = o.names();
        if (names != null) AppLog.i(AppLog.DOORS, "门列表 data 字段=" + names.toString());

        int authorizeExpired = o.has("authorizeExpired") ? o.optInt("authorizeExpired", -1) : -1;
        if (authorizeExpired >= 0) {
            AppLog.i(AppLog.DOORS, "门禁授权标记 authorizeExpired=" + authorizeExpired
                    + (authorizeExpired == 0 ? "（无到期限制）"
                    : "（上游标记存在到期限制：到期后需在官方 App 续期，否则开门会被拒）"));
        }

        // commonlyUsedDoor（服务端指定的常用门）在上游是**单个对象**而非数组。
        // 旧实现只按 optJSONArray 取值，这个键直接被跳过——若某扇门只出现在这里就会整扇漏掉。
        String[] keys = {"buildingDoorList", "gateDoorList", "customDoorList", "commonlyUsedDoor",
                "userDoorDTOS", "doorList", "doors", "data", "list"};
        for (String key : keys) {
            String kind = kindOf(key);
            JSONArray arr = o.optJSONArray(key);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) addDoor(arr.optJSONObject(i), communityId, kind, out);
                if (arr.length() > 0) AppLog.i(AppLog.DOORS, "门列表分组 " + key + "=" + arr.length());
                continue;
            }
            JSONObject single = o.optJSONObject(key);
            if (single != null) {
                JSONArray nk = single.names();
                AppLog.i(AppLog.DOORS, "「" + key + "」是单对象而非数组 | 字段="
                        + (nk == null ? "空" : nk.toString()));
                int before = out.size();
                addDoor(single, communityId, kind, out);
                if (out.size() > before) {
                    AppLog.ok(AppLog.DOORS, "「" + key + "」已按单门解析并入列表");
                }
            }
        }
        return authorizeExpired;
    }

    private static String kindOf(String key) {
        return "gateDoorList".equals(key) ? "小区门"
                : "buildingDoorList".equals(key) ? "楼栋门"
                : "customDoorList".equals(key) ? "自定义门"
                : "commonlyUsedDoor".equals(key) ? "常用门"
                : "门";
    }

    private static void addDoor(JSONObject d, int communityId, String kind, List<Door> out) {
        if (d == null) return;
        int id = d.optInt("doorControlId", -1);
        if (id <= 0) return;
        String name = str(d, "doorControlName", str(d, "doorName", "门禁 " + id));
        for (Door existing : out) if (existing.id == id) return;
        String online = str(d, "online", "");
        out.add(new Door(communityId, id, name, kind, online));
    }

    /**
     * 发送开门指令。成功仅代表「平台已接受指令」，不等于物理门已打开。
     *
     * <p>默认超时（5s + 15s）适合应用内：用户看得见进度，宁可多等也不要误判失败后让他再点一次。
     * 桌面小部件走 {@link #openDoorQuick}，那里有广播窗口的硬约束。</p>
     */
    public static String openDoor(String token, int communityId, int doorId) throws Exception {
        return openDoor(token, communityId, doorId, 5000, 15000);
    }

    /** 小部件路径专用：短超时，保证在 {@code goAsync()} 窗口内完成或失败。 */
    static String openDoorQuick(String token, int communityId, int doorId) throws Exception {
        return openDoor(token, communityId, doorId, WIDGET_CONNECT_MS, WIDGET_READ_MS);
    }

    static String openDoor(String token, int communityId, int doorId,
                           int connectMs, int readMs) throws Exception {
        AppLog.i(AppLog.OPEN, "发送开门指令 | session=" + AppLog.fp(token)
                + " | cid=" + communityId + " did=" + doorId
                + " | openid=" + AppLog.fp(openid())
                + " | 参数位置=URL query | 超时=" + connectMs + "/" + readMs + "ms");
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("appChannel", "1");
        f.put("doorControlId", String.valueOf(doorId));
        f.put("communityId", String.valueOf(communityId));
        try {
            post("/api/open/doorcontrol/v2/open", f, token, "query", true, connectMs, readMs);
            AppLog.ok(AppLog.OPEN, "开门指令已被服务端接受（不等于物理门已开）");
            String raw = RAW.get();       // 只取本线程的响应，不受并发请求干扰
            return raw == null ? "" : raw;
        } finally {
            RAW.remove();
        }
    }
}
