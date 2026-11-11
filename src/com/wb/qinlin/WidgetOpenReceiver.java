package com.wb.qinlin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 小部件「开门」点击的落点。
 *
 * 安全性：本接收器 exported="false"，外部应用无法直接发广播触发开门。
 * 桌面上的点击走 PendingIntent，发送时以本应用的身份执行，因此不导出也能被送达——
 * 这既满足了「点得到」，又不给第三方留一个「远程开门」的入口。
 *
 * 为什么不用 Service：targetSdk 33 下从后台启动前台服务要处理通知权限与前台服务类型，
 * 为了几秒钟的网络请求引入一整套前台服务并不划算；这里用 goAsync() 把广播的生命周期
 * 延长到请求结束，是官方支持的最小做法。
 */
public class WidgetOpenReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !OpenWidget.ACTION_OPEN.equals(intent.getAction())) return;

        if (OpenWidget.inFlight()) {
            AppLog.i(AppLog.OPEN, "小部件重复点击已忽略：上一次开门请求尚未结束");
            return;
        }

        final PendingResult pr = goAsync();
        // 主线程先把按钮切成「开门中…」，用户点完立刻看到反馈
        OpenWidget.markBusy(context);

        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    OpenWidget.openNow(app);
                } catch (Throwable t) {
                    AppLog.e(AppLog.ERR, "小部件开门未捕获异常 " + AppLog.stack(t));
                } finally {
                    try {
                        pr.finish();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "widget-open").start();
    }
}
