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
 * 时间预算：goAsync() 的广播前台时限约 10 秒。开门链路已改成小部件专用的短超时
 * （探活 2.5s+3s、开门 2.5s+5s，见 QinlinApi.WIDGET_*），正常路径 2~4 秒内完成；
 * 仍设 10 秒看门狗兜底——到点先 finish() 释放广播义务，网络线程继续跑。
 * 万一进程随后被回收（finish 之后没有任何豁免），重绘时会靠落盘的「尝试标记」
 * 把卡片改成「上次没收到结果，可重试」，不会永久停在「开门中…」。
 * finish() 用 AtomicBoolean 保证只调一次：二次调用会抛 IllegalStateException
 * （Google issuetracker #257513022）。
 */
public class WidgetOpenReceiver extends BroadcastReceiver {

    /** 看门狗：略大于「探活 + 开门」的小部件预算上限，只为兜「线程卡住」。 */
    private static final long WATCHDOG_MS = 10000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !OpenWidget.ACTION_OPEN.equals(intent.getAction())) return;

        final int appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // 无效 id 会走「默认门」兜底，而 noteResult 的有效 id 分支才负责收尾，
            // 结果在途锁卡满兜底时间、期间所有组件点击被拦。这里直接拒绝。
            AppLog.w(AppLog.OPEN, "小部件开门广播缺少有效 appWidgetId，已忽略");
            return;
        }

        // 防连点按组件隔离：一屏可以放多扇门，点组件 A 不应把组件 B 的点击吞掉
        if (OpenWidget.inFlight(appWidgetId)) {
            AppLog.i(AppLog.OPEN, "组件 " + appWidgetId + " 重复点击已忽略：上一次开门请求尚未结束");
            return;
        }

        final PendingResult pr = goAsync();
        // 主线程先把被点的那个按钮切成「开门中…」，用户点完立刻看到反馈；其它组件不变
        final long seq = OpenWidget.markBusy(context, appWidgetId);

        final Context app = context.getApplicationContext();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final long startAt = System.currentTimeMillis();
        final Runnable finishOnce = new Runnable() {
            @Override public void run() {
                if (finished.compareAndSet(false, true)) {
                    try { pr.finish(); } catch (Throwable ignored) {}
                }
            }
        };
        // 10 秒看门狗：到点先释放广播义务，线程尽力完成记录（重绘由 finally 负责）
        final Handler wd = new Handler(Looper.getMainLooper());
        wd.postDelayed(new Runnable() {
            @Override public void run() {
                AppLog.w(AppLog.OPEN, "组件 " + appWidgetId + " 开门超过 " + WATCHDOG_MS
                        + "ms 仍未结束 → 释放广播义务，网络线程继续跑完");
                finishOnce.run();
            }
        }, WATCHDOG_MS);

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    OpenWidget.openNow(app, appWidgetId);
                } catch (Throwable t) {
                    AppLog.e(AppLog.ERR, "小部件开门未捕获异常 " + AppLog.stack(t));
                } finally {
                    // 无论成功 / 业务失败 / 前置校验抛异常，都在这里收尾在途锁与卡片，
                    // 避免异常路径漏复位导致组件卡在不可点状态。
                    AppLog.i(AppLog.OPEN, "组件 " + appWidgetId + " 本次点击处理结束，耗时 "
                            + (System.currentTimeMillis() - startAt) + "ms");
                    OpenWidget.finishRequest(app, appWidgetId, seq);
                    wd.removeCallbacksAndMessages(null);
                    finishOnce.run();
                }
            }
        }, "widget-open").start();
    }
}
