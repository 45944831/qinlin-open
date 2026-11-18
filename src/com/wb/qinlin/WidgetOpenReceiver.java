package com.wb.qinlin;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * 时间预算：goAsync() 的广播前台时限约 10 秒。开门链路里的探活已是「单次 + 短超时」，
 * 正常路径 2~4 秒内完成；仍设 8 秒看门狗兜底——到点先 finish() 释放广播义务，
 * 网络线程继续跑，开门记录与桌面卡片反馈完成后照常落盘（仅内存压力下进程可能被提前回收）。
 * finish() 用 AtomicBoolean 保证只调一次：二次调用会抛 IllegalStateException
 * （Google issuetracker #257513022）。
 */
public class WidgetOpenReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !OpenWidget.ACTION_OPEN.equals(intent.getAction())) return;

        if (OpenWidget.inFlight()) {
            AppLog.i(AppLog.OPEN, "小部件重复点击已忽略：上一次开门请求尚未结束");
            return;
        }

        final int appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);

        final PendingResult pr = goAsync();
        // 主线程先把被点的那个按钮切成「开门中…」，用户点完立刻看到反馈；其它组件不变
        OpenWidget.markBusy(context, appWidgetId);

        final Context app = context.getApplicationContext();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = new Runnable() {
            @Override public void run() {
                if (finished.compareAndSet(false, true)) {
                    try { pr.finish(); } catch (Throwable ignored) {}
                }
            }
        };
        // 8 秒看门狗：给广播窗口留 2 秒余量；弱网超预算时先释放广播义务，线程尽力完成记录
        final Handler wd = new Handler(Looper.getMainLooper());
        wd.postDelayed(finishOnce, 8000L);

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    OpenWidget.openNow(app, appWidgetId);
                } catch (Throwable t) {
                    AppLog.e(AppLog.ERR, "小部件开门未捕获异常 " + AppLog.stack(t));
                } finally {
                    wd.removeCallbacks(finishOnce);
                    finishOnce.run();
                }
            }
        }, "widget-open").start();
    }
}
