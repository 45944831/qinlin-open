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
 * 判定策略：取不到 ConnectivityManager 或系统抛异常时一律返回 true（放行），
 * 让真实请求去判定——预检只用来提前拦掉「确定没网」的情况，绝不误拦。
 */
public final class Net {

    private Net() {}

    /** 当前是否有可用网络（存在默认网络且具备 INTERNET 能力）。 */
    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null) return true;            // 拿不到能力信息时不误判
            return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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
            Network n = cm.getActiveNetwork();
            if (n == null) return "无网络";
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null) return "未知";
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
