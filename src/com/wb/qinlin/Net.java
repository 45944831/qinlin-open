package com.wb.qinlin;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 网络可达性预检。
 *
 * 用途：断网 / 飞行模式下不等 connectTimeout（5 秒）和 readTimeout（15 秒），
 * 立刻给出「没有网络」的明确提示，用户不会以为「点了没反应」。
 *
 * 判定策略：宁可放行，绝不误拦。
 * 只有「确认一块可用网络都没有」才返回 false；取不到 ConnectivityManager、
 * 系统抛异常、拿不到能力信息时一律返回 true，让真实请求去判定。
 */
public final class Net {

    private Net() {}

    /**
     * 挑出「实际会被用来联网」的那块网络：默认网络可用就用它，
     * 否则遍历所有网络找第一块具备 INTERNET 能力的（受限 WiFi / VPN 被选为默认时）。
     * online() 与 describe() 共用这一个判断，避免「判定说有网、描述说无网络」的自相矛盾。
     *
     * <p>判定分两轮：先找 {@code VALIDATED}（系统已实际验证过能上网）的网络，
     * 一圈都没有再接受「只有 INTERNET 能力」的网络。旧实现只看 INTERNET：
     * 连上需要网页认证的公共 WiFi、或空壳热点时会判定「有网」，
     * 用户随后吃满 15 秒读超时才看到失败；而 VALIDATED 正好能识别这种「连上了但出不去」。
     * 仍然保持 fail-open —— 系统没给 VALIDATED 信息时不因此拦人。</p>
     */
    @SuppressWarnings("deprecation")   // getAllNetworks 在 API 31 标记过时，但 minSdk 26 仍需它兜底
    private static NetworkCapabilities pick(ConnectivityManager cm) {
        NetworkCapabilities fallback = null;

        Network n = cm.getActiveNetwork();
        NetworkCapabilities cap = n == null ? null : cm.getNetworkCapabilities(n);
        if (cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return cap;
            fallback = cap;
        }

        Network[] all = cm.getAllNetworks();
        if (all == null || all.length == 0) return fallback;
        for (Network x : all) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(x);
            if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                continue;
            }
            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return nc;
            if (fallback == null) fallback = nc;
        }
        return fallback;
    }

    /** 当前是否有可用网络（存在默认网络，或至少一块具备 INTERNET 能力的网络）。 */
    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            // 宁可放行，绝不误拦：取不到能力信息时让真实请求去判定
            return pick(cm) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 一句话网络类型，仅写诊断日志用（不含任何可识别信息）。 */
    public static String describe(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "未知";
            NetworkCapabilities cap = pick(cm);
            if (cap == null) return "无网络";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动数据";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "有线网络";
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
            return "其他";
        } catch (Throwable t) {
            return "未知";
        }
    }
}
