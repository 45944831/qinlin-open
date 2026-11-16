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
 * 密文格式：{@code enc:v1:<base64(iv)>:<base64(ciphertext)>}；
 * 非此前缀的值一律视为历史明文，原样返回（迁移期兼容）。
 */
public final class SecureStore {
    private static final String KEY_ALIAS = "qinlin_creds_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final Object LOCK = new Object();
    private static volatile boolean keyBroken = false;

    private SecureStore() {}

    /** 读取：密文则解密，否则原样返回（历史明文 / 加密不可用时的降级）。 */
    public static String get(Context c, SharedPreferences sp, String key, String def) {
        String raw;
        try {
            raw = sp.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
        if (raw == null || !raw.startsWith(PREFIX)) return raw;
        String plain = decrypt(raw);
        return plain == null ? def : plain;
    }

    /**
     * 写入：优先加密；失败则回退明文（保证不丢数据）。
     * 返回实际写入的值，便于调用方确认是否已升级为密文。
     */
    public static String put(Context c, SharedPreferences.Editor ed, String key, String value) {
        String toStore = value;
        if (value != null && value.length() > 0) {
            String enc = encrypt(value);
            if (enc != null) toStore = enc;
        }
        ed.putString(key, toStore);
        return toStore;
    }

    private static String encrypt(String plain) {
        synchronized (LOCK) {
            if (keyBroken) return null;
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, key(), new SecureRandom());
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

    private static String decrypt(String payload) {
        synchronized (LOCK) {
            if (keyBroken) return null;
            try {
                String body = payload.substring(PREFIX.length());
                int sep = body.indexOf(':');
                if (sep <= 0) return null;
                byte[] iv = Base64.decode(body.substring(0, sep), Base64.NO_WRAP);
                byte[] ct = Base64.decode(body.substring(sep + 1), Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] pt = cipher.doFinal(ct);
                return new String(pt, "UTF-8");
            } catch (Throwable t) {
                // 密文损坏：不在这里标记 keyBroken（可能只是这一条数据坏），
                // 让调用方走 def；其余字段仍可继续尝试解密。
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
     * 一次性迁移：把历史明文敏感字段升级为密文。
     * 在 App 启动时调用；幂等——已是密文或空值直接跳过。
     * 迁移失败不影响原值（明文仍可读），下次写入会重试。
     */
    public static void migrate(Context c, SharedPreferences sp, String... keys) {
        if (keyBroken || keys == null) return;
        for (String key : keys) {
            try {
                String raw = sp.getString(key, null);
                if (raw == null || raw.length() == 0 || raw.startsWith(PREFIX)) continue;
                String enc = encrypt(raw);
                if (enc == null) continue;
                sp.edit().putString(key, enc).apply();
            } catch (Throwable t) {
                // 单条迁移失败不影响其它字段，也不影响原明文可读性
            }
        }
    }

    /** 诊断用：当前加密是否可用。 */
    public static boolean available() {
        return !keyBroken;
    }
}
