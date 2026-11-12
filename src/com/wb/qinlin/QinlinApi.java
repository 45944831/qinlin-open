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
 * 亲邻云端网关协议的独立实现（仅使用 Android / JDK 标准库，无第三方依赖）。
 *
 * 开门指令必须经亲邻服务端实时校验，因此本类必然要联网；
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

    private static final SecureRandom RND = new SecureRandom();

    private QinlinApi() {}

    public static class ApiException extends Exception {
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
        h.put("qversioncode", "3113");
        h.put("qversionname", "4.9.3");
        h.put("qchannel", "xiaomi");
        h.put("qplatform", "0");
        h.put("user-agent", "Dart/2.18 (dart:io)");
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

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String request(String base, LinkedHashMap<String, String> headers,
                                  TreeMap<String, String> params, String body,
                                  String contentType) throws Exception {
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
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
                AppLog.e(AppLog.NET, "HTTP 非 200，本次请求判定失败");
                throw new ApiException("HTTP_" + status,
                        "上游 HTTP " + status + "：" + redact(text));
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
        String code = o.optString("code", o.optString("status", ""));
        boolean ok = code.equals("0") || code.equals("200") || o.optBoolean("success", false);
        AppLog.i(AppLog.NET, "业务解析 code=" + code
                + " | success=" + o.optBoolean("success", false)
                + " | 摘要=" + shorten(redact(text), 160));
        // 官方客户端有 _updateCacheSessionId，疑似服务端会轮换/下发新票据。
        // 先只留痕不自动替换：真机导出日志确认字段名后再决定是否跟随，避免误换凭证。
        if (authenticated && o.has("sessionId")) {
            AppLog.w(AppLog.LOGIN, "⚠ 业务响应顶层出现 sessionId 字段（疑似服务端轮换票据），本版暂不自动替换");
        }
        if (!ok) {
            String msg = redact(o.optString("message",
                    o.optString("msg", "亲邻未接受请求")));
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
                throw new ApiException("SESSION_EXPIRED", "亲邻凭证已失效，请重新短信登录");
            }
            AppLog.e(AppLog.NET, "业务拒绝 → QINLIN_REJECTED | msg=" + msg);
            throw new ApiException("QINLIN_REJECTED", "亲邻未接受请求：" + msg);
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
        AppLog.i(AppLog.NET, "组装请求 " + path + " | mode=" + mode
                + " | session=" + AppLog.fp(token)
                + " | 字段=" + fields.keySet());

        TreeMap<String, String> body = new TreeMap<String, String>(fields);
        body.put("timestamp", String.valueOf(System.currentTimeMillis()));
        body.put("nonce", String.valueOf(RND.nextInt(90000) + 10000));
        body.put("version", "4.9.3");

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
        String text = request(BASE + path, headers, params, payload, contentType);
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
        String token = loginDetailed(phone, code).optString("sessionId", "").trim();
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
     * ① 定期让服务端会话保持活跃，避免长时间不请求被判空闲超时；
     * ② 收到 401 后再单独确认一次，区分「真超时」与「网关偶发误判 / 网络抖动」。
     */
    public static void checkLogin(String token) throws Exception {
        TreeMap<String, String> f = new TreeMap<String, String>();
        post("/api/app/v1/checkLogin", f, token, "json");
    }

    /**
     * 按线程隔离的响应原文：启动时后台补小区名与开门可能并发，
     * 只读全局字段会把别人的响应记进本次开门日志。
     */
    private static final ThreadLocal<String> RAW = new ThreadLocal<String>();

    /** 只保留结构，抹掉凭证和手机号，避免诊断输出泄露敏感信息。 */
    private static String redact(String s) {
        if (s == null) return "";
        s = s.replaceAll("\"sessionId\"\\s*:\\s*\"[^\"]*\"", "\"sessionId\":\"***\"");
        // 数字边界：避免把 13 位毫秒时间戳当成手机号切掉
        s = s.replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "***PHONE***");
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 3000 ? s.substring(0, 3000) + "…(截断)" : s;
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
        Object data = null;
        StringBuilder log = new StringBuilder();
        for (int k = 0; k < order.length; k++) {
            final int i = order[k];
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
                AppLog.w(AppLog.DOORS, names[i] + " 失败 [" + e.code + "] "
                        + shorten(e.getMessage(), 120));
                if (!e.code.startsWith("HTTP_")) break; // 业务明确拒绝，换姿势无意义
            } catch (Exception e) {
                log.append(names[i]).append(" → ").append(e.getClass().getSimpleName())
                        .append(": ").append(shorten(e.getMessage(), 120)).append("\n");
                AppLog.e(AppLog.DOORS, names[i] + " 异常 " + AppLog.stack(e));
            }
        }
        if (!succeeded) {
            DIAG.set(log.toString());
            AppLog.e(AppLog.DOORS, "小区列表全部尝试失败，凭证或网关可能有问题");
            throw new ApiException("COMMUNITY_FAILED",
                    "小区列表请求失败，逐项结果：\n" + log);
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
            out.add(new Community(id, o.optString("communityName", "小区 " + id)));
        }
        AppLog.i(AppLog.DOORS, "解析到 " + out.size() + " 个小区");
        return out;
    }

    public static List<Door> doors(String token, int communityId) throws Exception {
        AppLog.i(AppLog.DOORS, "读取门列表 communityId=" + communityId
                + " | session=" + AppLog.fp(token));
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("communityId", String.valueOf(communityId));
        Object data = post("/api/app/user/v2/queryAllUserDoorByCache", f, token, "form");
        List<Door> out = new ArrayList<Door>();
        if (!(data instanceof JSONObject)) {
            AppLog.w(AppLog.DOORS, "门列表响应 data 非对象，返回空列表 communityId=" + communityId);
            return out;
        }
        JSONObject o = (JSONObject) data;
        String[] keys = {"buildingDoorList", "gateDoorList", "customDoorList", "userDoorDTOS"};
        for (String key : keys) {
            JSONArray arr = o.optJSONArray(key);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                int id = d.optInt("doorControlId", -1);
                if (id <= 0) continue;
                String name = d.optString("doorControlName", "门禁 " + id);
                // 上游按「区域」分组返回，保留分组语义供 UI 分区展示
                String kind = "gateDoorList".equals(key) ? "小区门"
                        : "buildingDoorList".equals(key) ? "楼栋门"
                        : "customDoorList".equals(key) ? "自定义门" : "其他门";
                boolean dup = false;
                for (Door existing : out) if (existing.id == id) { dup = true; break; }
                if (!dup) {
                    String online = d.optString("online", "");
                    out.add(new Door(communityId, id, name, kind, online));
                }
            }
        }
        AppLog.i(AppLog.DOORS, "门列表返回 " + out.size() + " 扇门 communityId=" + communityId);
        return out;
    }

    /**
     * 发送开门指令。成功仅代表「平台已接受指令」，不等于物理门已打开。
     */
    public static String openDoor(String token, int communityId, int doorId) throws Exception {
        AppLog.i(AppLog.OPEN, "发送开门指令 | session=" + AppLog.fp(token)
                + " | cid=" + communityId + " did=" + doorId
                + " | openid=" + AppLog.fp(openid())
                + " | 参数位置=URL query");
        TreeMap<String, String> f = new TreeMap<String, String>();
        f.put("appChannel", "1");
        f.put("doorControlId", String.valueOf(doorId));
        f.put("communityId", String.valueOf(communityId));
        try {
            post("/api/open/doorcontrol/v2/open", f, token, "query");
            AppLog.ok(AppLog.OPEN, "开门指令已被服务端接受（不等于物理门已开）");
            String raw = RAW.get();       // 只取本线程的响应，不受并发请求干扰
            return raw == null ? "" : raw;
        } finally {
            RAW.remove();
        }
    }
}
