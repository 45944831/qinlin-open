package com.wb.qinlin;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 本机敏感字段加密层（Android Keystore + AES-GCM）。
 *
 * 设计原则——绝不能因为加密故障让用户丢登录态：
 * 1. 密钥保存在 AndroidKeyStore，正常设备不可导出；
 * 2. 任何环节失败（Keystore 不可用 / 密文损坏 / 系统降级）都安全降级为「按明文处理」，
 *    即原样返回存储值；这样旧版本的明文凭证照常可读，覆盖安装后登录态不丢；
 * 3. 写入时尽量加密；加密失败则写明文并落日志，保证功能不中断。
 *
 * <p>密文格式：</p>
 * <ul>
 *   <li>{@code enc:v2:<iv>:<ct>} —— 当前格式，<b>把字段名作为 AAD 一起校验</b>。
 *       密文被从 {@code session} 搬到 {@code phone}（或用同一把密钥解另一字段）会解密失败，
 *       不会再出现「把 A 字段的密文贴到 B 字段还能解开」的静默错位。</li>
 *   <li>{@code enc:v1:<iv>:<ct>} —— 早期格式（无 AAD），仍可正常解密；
 *       下次写入 / 启动迁移时自动升级为 v2。</li>
 *   <li>其它值 —— 视为历史明文，原样返回（迁移期兼容）。</li>
 * </ul>
 */
public final class SecureStore {
    private static final String KEY_ALIAS = "qinlin_creds_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** 当前写入格式（带 AAD）。 */
    private static final String PREFIX = "enc:v2:";
    /** 历史格式（无 AAD），只读兼容，不再写入。 */
    private static final String PREFIX_V1 = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final Object LOCK = new Object();
    private static volatile boolean keyBroken = false;
    /** 已经报过「解密失败」的字段名：同一字段只记一次，避免每次读偏好都刷一条日志。 */
    private static final java.util.HashSet<String> BROKEN_KEYS = new java.util.HashSet<String>();
    /** 已经报过「仍是历史明文」的字段名，同样只记一次。 */
    private static final java.util.HashSet<String> PLAIN_KEYS = new java.util.HashSet<String>();

    private SecureStore() {}

    private static boolean isEncrypted(String raw) {
        return raw != null && (raw.startsWith(PREFIX) || raw.startsWith(PREFIX_V1));
    }

    /** 读取：密文则解密，否则原样返回（历史明文 / 加密不可用时的降级）。 */
    public static String get(Context c, SharedPreferences sp, String key, String def) {
        String raw;
        try {
            raw = sp.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
        if (raw == null) return raw;
        if (!isEncrypted(raw)) {
            // 非空但不是密文：说明是覆盖安装带来的历史明文（或加密失败降级写的明文）。
            // 以前完全静默，日志里看不出「这个字段其实没加密」，排查安全问题时会被误导。
            if (raw.length() > 0) notePlainKey(key);
            return raw;
        }
        String plain = decrypt(raw, key);
        if (plain == null) {
            // 旧实现在这里静默返回 def：Keystore 失效后门绑定会悄悄退化成空串，
            // 上层再回退默认门，表现为「组件标题写着 A 门、实际开 B 门」且日志里毫无痕迹。
            noteBrokenKey(key);
            return def;
        }
        return plain;
    }

    private static void notePlainKey(String key) {
        synchronized (LOCK) {
            if (!PLAIN_KEYS.add(key)) return;
        }
        try {
            AppLog.i(AppLog.SYS, "敏感字段「" + key + "」当前仍是明文（启动迁移后会自动加密）");
        } catch (Throwable ignored) {
        }
    }

    private static void noteBrokenKey(String key) {
        synchronized (LOCK) {
            if (!BROKEN_KEYS.add(key)) return;
        }
        try {
            AppLog.w(AppLog.SYS, "敏感字段「" + key
                    + "」解密失败（Keystore 失效 / 密文损坏）→ 按未设置处理；"
                    + "相关功能可能需要重新设置或重新登录");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 写入：优先加密；失败则回退明文（保证不丢数据）。
     * 返回实际写入的值，便于调用方确认是否已升级为密文。
     */
    public static String put(Context c, SharedPreferences.Editor ed, String key, String value) {
        String toStore = value;
        if (value != null && value.length() > 0) {
            String enc = encrypt(value, key);
            if (enc != null) toStore = enc;
        }
        ed.putString(key, toStore);
        return toStore;
    }

    /** AAD：把字段名绑进密文完整性校验，防止密文在字段之间搬运。 */
    private static byte[] aad(String key) {
        return ("qinlin:" + key).getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static String encrypt(String plain, String key) {
        synchronized (LOCK) {
            if (keyBroken) return null;
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, key(), new SecureRandom());
                cipher.updateAAD(aad(key));
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length == 0) return null;
                byte[] ct = cipher.doFinal(plain.getBytes("UTF-8"));
                return PREFIX + b64(iv) + ":" + b64(ct);
            } catch (Throwable t) {
                markBroken(t);
                return null;
            }
        }
    }

    private static String decrypt(String payload, String key) {
        synchronized (LOCK) {
            // 刻意不检查 keyBroken：它只表示「写入时加密不可用、已降级明文」，
            // 不能据此否定磁盘上已有的密文。否则一次瞬时加密失败会让本进程内所有健康密文
            // 都读不出来——表现为莫名掉登录、重启 App 后又自动恢复。
            boolean v1 = payload.startsWith(PREFIX_V1);
            String body = payload.substring(v1 ? PREFIX_V1.length() : PREFIX.length());
            try {
                int sep = body.indexOf(':');
                if (sep <= 0) return null;
                byte[] iv = Base64.decode(body.substring(0, sep), Base64.NO_WRAP);
                byte[] ct = Base64.decode(body.substring(sep + 1), Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                if (!v1) cipher.updateAAD(aad(key));   // v1 没有 AAD，加上反而解不开
                byte[] pt = cipher.doFinal(ct);
                return new String(pt, "UTF-8");
            } catch (Throwable t) {
                // 密文损坏 / 字段被搬到别的键（v2 的 AAD 不匹配）：不标记 keyBroken
                // （可能只是这一条数据坏），让调用方走 def；其余字段仍可继续解密。
                return null;
            }
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static void markBroken(Throwable t) {
        keyBroken = true;
        try {
            AppLog.w(AppLog.SYS, "Keystore 加密不可用，敏感字段降级为明文："
                    + (t == null ? "?" : t.getClass().getSimpleName()));
        } catch (Throwable ignored) {
        }
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    /**
     * 一次性迁移：把历史明文敏感字段升级为密文，并把旧的 v1 密文升级为带 AAD 的 v2。
     * 在 App 启动时调用；幂等——已是 v2 或空值直接跳过。
     * 迁移失败不影响原值（明文仍可读 / v1 仍可解），下次写入会重试。
     */
    public static void migrate(Context c, SharedPreferences sp, String... keys) {
        if (keyBroken || keys == null) return;
        for (String key : keys) {
            try {
                String raw = sp.getString(key, null);
                if (raw == null || raw.length() == 0) continue;
                if (raw.startsWith(PREFIX)) continue;          // 已是当前格式
                String plain;
                if (raw.startsWith(PREFIX_V1)) {
                    // v1 → v2：先按老规则解出来；解不开就保持原样（仍可读，别弄丢数据）
                    plain = decrypt(raw, key);
                    if (plain == null) continue;
                } else {
                    plain = raw;                                // 历史明文
                }
                String enc = encrypt(plain, key);
                if (enc == null) continue;
                sp.edit().putString(key, enc).apply();
            } catch (Throwable t) {
                // 单条迁移失败不影响其它字段，也不影响原值可读性
            }
        }
    }

    /** 诊断用：当前加密是否可用。 */
    public static boolean available() {
        return !keyBroken;
    }
}
