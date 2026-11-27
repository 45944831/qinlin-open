package com.wb.qinlin;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 三个 Activity 与桌面小部件共用的设计基元：dp 换算、字号阶梯、圆角阶梯、
 * 带涟漪的圆角背景、统一 TextView 构造、线程安全 Toast 与应用内提示条（snack）。
 *
 * <p>之前 MainActivity / DoorListActivity / LogActivity 各自维护一份 dp() 与 label()，
 * OpenWidget 又有自己的主线程 Toast——同一件事四套实现，改样式要改四处。
 * 收敛到这里后各处只保留一行委托。</p>
 */
final class Ui {

    // ---- 字号阶梯（sp）：只允许这六档 ----
    /** 说明文字 / 胶囊 / 页脚。11sp 是可读下限，再小在户外就读不出了。 */
    static final int T_CAPTION = 11;
    /** 正文小号：提示句、记录行、次级信息。 */
    static final int T_BODY_S = 12;
    /** 正文：按钮、输入框、段落。 */
    static final int T_BODY = 14;
    /** 小标题：小区名、区块标题、指标数值。 */
    static final int T_TITLE = 17;
    /** 大标题：门名、页面主标题。 */
    static final int T_HEADLINE = 22;
    /** 展示级：唯一的开门按钮。 */
    static final int T_DISPLAY = 26;

    // ---- 圆角阶梯（dp）：只允许这四档 ----
    /** 小控件：门板、色块、小按钮。 */
    static final int R_SM = 8;
    /** 中控件：按钮、标签、状态框。 */
    static final int R_MD = 12;
    /** 大容器：卡片、浮层、横幅。 */
    static final int R_LG = 16;
    /** 胶囊：取一个足够大的值，系统会按高度裁成半圆。 */
    static final int R_PILL = 99;

    /** 提示条的标记 tag：同一时刻只留一条，新提示直接顶掉旧的。 */
    private static final String TAG_SNACK = "qinlin_snack";
    private static final long SNACK_MS = 2600L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {}

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * 系统是否允许播放动画（读「动画时长缩放」，无障碍/开发者选项里的「移除动画」会把它设为 0）。
     *
     * <p>为什么必须主动问：{@code ViewPropertyAnimator} 在缩放为 0 时不是「不动」，而是
     * 「立刻跳到终值」——按钮照样缩到 97% 停在按下态、横幅照样先瞬移到屏幕外再瞬移回来。
     * 对关掉动画的人来说，这是静止变形和闪烁，比不做动画更难受。所以装饰性动效一律先过这一关，
     * 关了就一步到位：按钮保持原尺寸、横幅直接显示、提示条直接出现。</p>
     *
     * <p>minSdk 26 正好是 {@code areAnimatorsEnabled()} 的起始版本；真机上的设置变更由框架
     * 自己的 ContentObserver 同步，这里每次调用都读最新值，不需要自己注册监听。</p>
     */
    static boolean motionOn(Context c) {
        try {
            return ValueAnimator.areAnimatorsEnabled();
        } catch (Throwable t) {
            // 老固件没有这个方法、个别 ROM 也裁剪过动画框架：退回直接读系统设置。
            try {
                return Settings.Global.getFloat(c.getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
            } catch (Throwable ignored) {
                // 连设置都读不到时按「允许」处理，宁可多一段动画也不要误伤。
                return true;
            }
        }
    }

    static TextView label(Context c, String text, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 纯色圆角矩形；{@code strokeDp <= 0} 表示不要描边。 */
    static GradientDrawable roundRect(Context c, int color, int radiusDp,
                                      int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    /**
     * 带系统涟漪的圆角背景。
     *
     * <p>旧实现用 {@code setBackgroundColor(bg)} 直接把 Button 的 StateList（含 ripple）
     * 换成单色 ColorDrawable：于是最重要的两个按钮是方角、无涟漪、无按下态的，
     * 和全站 8–16dp 圆角的容器割裂。这里用 RippleDrawable 包一层圆角内容，
     * 涟漪被 mask 裁在圆角内——涟漪与圆角同时拿到，且不引任何三方库。</p>
     */
    static RippleDrawable rippled(Context c, int bg, int radiusDp, int strokeColor) {
        GradientDrawable content = roundRect(c, bg, radiusDp, strokeColor == 0 ? 0 : 1, strokeColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(c, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleOn(bg)), content, mask);
    }

    /** 涟漪色随底色明暗走：浅底用深色水波，深底（红/蓝按钮）用白色水波。 */
    private static int rippleOn(int bg) {
        return luminance(bg) > 0.55 ? 0x1F101828 : 0x33FFFFFF;
    }

    private static double luminance(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    private static double channel(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * 任意线程可调的 Toast。
     * 真机实测（ColorOS）：「先 cancel 旧 Toast 再 show 新 Toast」会触发
     * NotificationService 的 "Toast already killed / already cancelled"，连续提示反而被丢掉；
     * 因此每条独立排队、由系统自己管理生命周期，绝不 cancel 上一条。
     *
     * <p>只在「没有 Activity 可承载提示条」时使用（桌面组件、导出完成回调等）。
     * Activity 可见时的反馈走 {@link #snack}。</p>
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

    /**
     * 应用内提示条：底部浮出一条深色圆角条，2.6 秒后自动淡出。
     *
     * <p>为什么不用 Toast：Toast 属于系统窗口，位置固定、无法被读屏聚焦、
     * 连续触发会互相覆盖；而这个 App 的关键反馈（已开门 / 网络异常 / 已复制）
     * 都发生在界面底部，用应用内提示条既能贴着内容，也能被无障碍服务读到。</p>
     *
     * <p>任意线程可调；Activity 已销毁或没有内容视图时退回 Toast。</p>
     */
    static void snack(final Activity a, final String msg) {
        if (a == null || msg == null || msg.length() == 0) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override public void run() { snack(a, msg); }
            });
            return;
        }
        if (a.isFinishing() || a.isDestroyed()) {
            toast(a.getApplicationContext(), msg);
            return;
        }
        View content = a.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            toast(a.getApplicationContext(), msg);
            return;
        }
        final ViewGroup root = (ViewGroup) content;
        View old = root.findViewWithTag(TAG_SNACK);
        if (old != null) root.removeView(old);

        final TextView t = new TextView(a);
        t.setTag(TAG_SNACK);
        t.setText(msg);
        t.setTextSize(T_BODY);
        t.setTextColor(a.getColor(R.color.q_snack_ink));
        t.setBackground(roundRect(a, a.getColor(R.color.q_snack_bg), R_MD, 0, 0));
        t.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        t.setElevation(dp(a, 8));
        t.setMaxLines(4);
        t.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(dp(a, 16), 0, dp(a, 16), dp(a, 24));
        root.addView(t, lp);

        // 关掉系统动画时直接出现、直接消失：淡入淡出本身不带信息，不该成为干扰。
        final boolean motion = motionOn(a);
        if (motion) {
            t.setAlpha(0f);
            t.setTranslationY(dp(a, 16));
            t.animate().alpha(1f).translationY(0f).setDuration(180).start();
        }

        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if (t.getParent() != root) return;
                if (!motion) {
                    root.removeView(t);
                    return;
                }
                t.animate().alpha(0f).translationY(dp(a, 16)).setDuration(160)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                if (t.getParent() == root) root.removeView(t);
                            }
                        }).start();
            }
        }, SNACK_MS);
    }
}
