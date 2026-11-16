package com.wb.qinlin;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 三个 Activity 与桌面小部件共用的小工具：dp 换算、统一 TextView 构造、线程安全 Toast。
 *
 * 之前 MainActivity / DoorListActivity / LogActivity 各自维护一份 dp() 与 label()，
 * OpenWidget 又有自己的主线程 Toast——同一件事四套实现，改样式要改四处。
 * 收敛到这里后各处只保留一行委托。
 */
final class Ui {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {}

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    static TextView label(Context c, String text, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /**
     * 任意线程可调的 Toast。
     * 真机实测（ColorOS）：「先 cancel 旧 Toast 再 show 新 Toast」会触发
     * NotificationService 的 "Toast already killed / already cancelled"，连续提示反而被丢掉；
     * 因此每条独立排队、由系统自己管理生命周期，绝不 cancel 上一条。
     */
    static void toast(final Context c, final String s) {
        Runnable show = new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                    // 后台 Toast 在部分 ROM 会被吞，只保证不崩
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) show.run();
        else MAIN.post(show);
    }
}
