package com.wb.qinlin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 主界面：只显示「当前选中的那一扇门 + 一键开门」。
 * 点右上角「门列表 ▸」以悬浮二级界面选门，选中的门立刻回填到主界面卡片。
 * 全部网络操作在后台线程执行；界面完全用代码构建，无第三方 UI 库。
 */
public class MainActivity extends Activity {

    private static final int REQ_DOOR = 1001;
    /** 日志二级弹窗：只用来在返回时刷新入口上的条数。 */
    private static final int REQ_LOG = 1002;

    /** 主界面配色（与效果图同一套色值，集中在这里避免逐个 Color.parseColor）。 */
    private static final int C_BG = 0xFFF5F7FB;
    private static final int C_INK = 0xFF16233B;
    private static final int C_SUB = 0xFF5A6B85;
    private static final int C_MUTED = 0xFF8A94A6;
    private static final int C_FAINT = 0xFF9AA7BC;
    private static final int C_BLUE = 0xFF2563EB;
    private static final int C_RED = 0xFFD93025;
    private static final int C_GREEN = 0xFF137333;

    /** 记录卡默认展示行数；点「全部」展开到 10 行。 */
    private static final int DEFAULT_RECORDS = 3;

    /** 启动 / 开门耗时打点：真机可直接从日志读出首帧与端到端耗时，验证优化效果。 */
    private long createT0;
    private boolean firstFrameLogged;

    /** 只在登录态真正变化时留痕，避免 onResume 反复刷日志。 */
    private Boolean lastLoginState;

    private EditText phoneEt;
    private EditText codeEt;

    /**
     * 日志入口按钮：主界面不再内嵌日志区，点它打开二级弹窗（与门列表同款浮层）。
     * 按钮上带条数，收起状态下也能看出后台确实在记录。
     */
    private Button logToggle;
    private int logCount;
    /** 弹窗是否已打开：避免连点入口叠出多个窗口。 */
    private boolean logPanelOpen;
    /**
     * 本次运行是否已经弹过「首次选门」引导。
     * 只在本 Activity 实例内生效：避免 onResume 反复拉起门列表，
     * 又能在重新登录（onboardingShown 复位）后照常再引导一次。
     */
    private boolean onboardingShown;
    /** 正在打开的门列表是不是「首次选门」引导：决定选完后的提示文案。 */
    private boolean doorListOnboarding;

    /** 开门按钮，以及把它推到单手舒适区的两块弹性留白（上：推开按钮，下：吸收剩余高度）。 */
    private Button openBtn;
    private View centerSpacer;
    private View bottomSpacer;
    private boolean centering;
    /** 单手操作：开门请求进行中禁止连点，避免拇指重复触发多条开门指令。 */
    private boolean opening;
    private Runnable resetOpenBtnTask;

    /** 开门成功的顶部横幅：替代屏幕下方的 Toast，点按钮时不会被手指挡住。 */
    private View openBanner;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideBannerTask = new Runnable() {
        @Override public void run() { dismissOpenedBanner(); }
    };
    /** 合并刷新入口上的条数：短时间内多条日志只更新一次文本。 */
    private final Runnable countTask = new Runnable() {
        @Override public void run() { updateLogToggleText(); }
    };

    /** 订阅 AppLog：主界面只更新入口条数，日志正文由二级弹窗自己渲染。 */
    private final AppLog.Listener logListener = new AppLog.Listener() {
        @Override public void onLog(final AppLog.Entry e) {
            uiHandler.removeCallbacks(countTask);
            uiHandler.postDelayed(countTask, 120);
        }
    };

    /** 「选中的门」卡片：门形图示 + 名称 + 区域/在线 + id。 */
    private TextView doorTagTv;
    private TextView selectedDoorTv;
    private TextView doorStateTv;
    private TextView doorMetaTv;
    private GradientDrawable doorCardBg;
    private GradientDrawable doorPlateBg;
    private GradientDrawable doorHandleBg;

    /** 登录表单整体（未登录时显示）；已登录时整块隐藏。 */
    private LinearLayout loginBox;
    /** 品牌标题区：仅未登录时显示，已登录时账号卡片已表明身份。 */
    private LinearLayout brandBox;
    /** 顶部常驻的登录态卡片（在 ScrollView 之外，状态框自动滚动不会把它推走）。 */
    private LinearLayout accountBox;
    private TextView accountTv;
    private TextView communityTv;
    /** 开门区（门卡片 + 主按钮下方的记录卡）：整体随登录态显示/隐藏。 */
    private LinearLayout openArea;

    /** 门卡内的三格指标：今日开门 / 上次响应 / 网关直连。 */
    private TextView statTodayTv;
    private TextView statRespTv;
    private TextView statGwTv;
    /** 「上次开门」摘要条：左为时间与结果，右为耗时。 */
    private TextView lastOpenTv;
    private TextView lastMsTv;
    /** 在线胶囊的底色：在线/离线两种状态换色。 */
    private GradientDrawable onlinePillBg;

    /** 最近开门记录卡与其行容器。 */
    private LinearLayout recordsCard;
    private LinearLayout recordsBox;
    private TextView recordsMoreTv;
    private int recordsLimit = DEFAULT_RECORDS;
    /** 底部版本行：未登录时页面被登录表单占满，这行会被挤出屏幕，索性只对已登录显示。 */
    private TextView versionFooter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        createT0 = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        QinlinApi.setOpenid(Prefs.openid(this));
        QinlinApi.PREFERRED_COMM_MODE = Prefs.commMode(this);   // 沿用上次成功的小区请求姿势
        buildUi();

        // 订阅新日志：只用来刷新入口按钮上的条数（日志正文由弹窗渲染），
        // 主线程不再为每条日志重建一次长文本。
        AppLog.addListener(logListener);
        AppLog.startup(this);       // 识别覆盖安装 / 版本变化 + 记录本机状态快照
        AppLog.i(AppLog.SYS, "注入设备标识 openid=" + AppLog.fp(Prefs.openid(this)));

        String savedPhone = Prefs.phone(this);
        if (savedPhone.length() > 0) phoneEt.setText(savedPhone);
        refreshDoorLabel();
        applyLoginState();          // 重进 App 时按本机凭证恢复登录态 / 未登录态

        if (Prefs.session(this).length() == 0) {
            AppLog.i(AppLog.SYS, "首次使用：输入手机号 → 获取验证码 → 登录。凭证仅保存在本机。");
        } else {
            AppLog.ok(AppLog.LOGIN, "检测到本机凭证 " + AppLog.fp(Prefs.session(this))
                    + "，可直接开门；若提示凭证失效请重新登录");
        }

        // 已登录但本机还没存过小区名（例如刚升级到登录态版本）：后台静默补齐，
        // 不弹 busy、不滚动状态框，避免一打开 App 就跳到页面底部。
        String savedSession = Prefs.session(this);
        if (savedSession.length() > 0 && Prefs.communityName(this).length() == 0) {
            loadCommunityName(savedSession);
        }

        // 首次使用 / 还没确认过要控制的门：进门列表选门，选完再回主界面。
        // 已经选过门的账号，冷启动一律直接进主界面，不再打扰。
        maybeShowOnboarding();
    }

    @Override
    protected void onDestroy() {
        AppLog.removeListener(logListener);
        uiHandler.removeCallbacks(hideBannerTask);
        uiHandler.removeCallbacks(countTask);
        if (resetOpenBtnTask != null) uiHandler.removeCallbacks(resetOpenBtnTask);
        super.onDestroy();
    }

    /** 从悬浮门列表返回后，立刻把选中的门刷新到主界面。 */
    @Override
    protected void onResume() {
        super.onResume();
        // 兜底复位：日志弹窗无论怎么退出，回到主界面后入口都必须还能点
        logPanelOpen = false;
        updateLogToggleText();
        refreshDoorLabel();
        applyLoginState();
        refreshStats();        // 回到前台补刷「网关直连」与记录（可能在别处开过门）
        // 桌面小部件跟着界面状态走：登录 / 换门 / 退出后，桌面上那张卡片要立刻反映最新的门
        OpenWidget.refreshAll(this);
    }

    /** 键盘收起、窗口尺寸变化、切回前台后重新对齐按钮。 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) centerOpenButton();
    }

    /**
     * 打开分类日志二级弹窗：与门列表一样的悬浮浮层，盖在主界面之上，
     * 主界面保持原样（开门按钮不会被顶走），关掉就回到原状态。
     */
    private void openLogPanel() {
        if (logPanelOpen) return;
        logPanelOpen = true;
        try {
            startActivityForResult(new Intent(this, LogActivity.class), REQ_LOG);
        } catch (Exception e) {
            logPanelOpen = false;
            AppLog.e(AppLog.ERR, "打开日志弹窗失败 " + AppLog.stack(e));
            toast("打开日志失败：" + e.getMessage());
        }
    }

    /** 入口文案：带上条数，收起状态下也能看出后台确实在写日志。 */
    private void updateLogToggleText() {
        if (logToggle == null) return;
        logCount = AppLog.size();
        logToggle.setText(logCount > 0
                ? "▾ 查看分类日志 · 共 " + logCount + " 条"
                : "▾ 查看分类日志");
    }

    /** 悬浮门列表选中一扇门：即时把结果写进主界面卡片 + 持久可见的反馈。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOG) {
            logPanelOpen = false;
            updateLogToggleText();      // 弹窗里可能清空 / 筛选过，回来刷新条数
            return;
        }
        if (requestCode == REQ_DOOR) {
            boolean wasOnboarding = doorListOnboarding;
            doorListOnboarding = false;
            if (resultCode != RESULT_OK || data == null) {
                // 首次引导被跳过：不标记确认，下次冷启动仍会再引导一次
                if (wasOnboarding) {
                    AppLog.w(AppLog.DOORS, "首次选门引导被跳过：本机仍无确认的门，下次启动会再提示");
                }
                return;
            }
            refreshDoorLabel();
            String name = data.getStringExtra("door_name");
            if (name == null) name = Prefs.doorName(this);
            // 确认完成标记按手机号记：以后冷启动 / 重新登录直接进主界面，换账号才重新引导
            Prefs.setDoorConfirmed(this, Prefs.phone(this));
            AppLog.ok(AppLog.DOORS, "选门已回填主界面 | 名称=" + name
                    + " | did=" + Prefs.doorId(this) + " cid=" + Prefs.communityId(this)
                    + " | 已标记该手机号完成首次选门引导");
            toast(wasOnboarding
                    ? "已选好：" + name + "，以后打开 App 直接进入开门界面"
                    : "已选择：" + name);
            if (wasOnboarding) statusOk("首次选门完成：当前开门已设为「" + name
                    + "」。以后打开 App 或重新登录都直接进主界面。");
            return;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 开门主按钮的三态背景：默认红渐变、按压深红、请求中置灰。
     * 大圆角让拇指落点更明确，也替掉系统 Button 默认方角的生硬感。
     */
    private StateListDrawable makeOpenButtonDrawable() {
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.parseColor("#F0463C"), Color.parseColor("#C5221F") });
        normal.setCornerRadius(dp(18));
        normal.setStroke(dp(1), Color.parseColor("#A51B18"));

        GradientDrawable pressed = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.parseColor("#C5221F"), Color.parseColor("#8E1512") });
        pressed.setCornerRadius(dp(18));
        pressed.setStroke(dp(2), Color.parseColor("#7A100E"));

        GradientDrawable disabled = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.parseColor("#9AA7BC"), Color.parseColor("#7C899D") });
        disabled.setCornerRadius(dp(18));
        disabled.setStroke(dp(1), Color.parseColor("#6B7688"));

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, pressed);
        s.addState(new int[] { -android.R.attr.state_enabled }, disabled);
        s.addState(new int[] {}, normal);
        return s;
    }

    /** 开门请求期间把按钮就地置为「开门中…」并禁用（StateListDrawable 自动切灰）。 */
    private void applyOpenButtonState(boolean busy, String label) {
        if (openBtn == null) return;
        openBtn.setEnabled(!busy);
        openBtn.setText(label);
        if (busy) {
            openBtn.animate().cancel();
            openBtn.setScaleX(1f);
            openBtn.setScaleY(1f);
        }
    }

    /** 成功 / 失败都复位按钮；成功后短暂显示「已开门 ✓」，不用低头看日志也能确认。 */
    private void finishOpen(final boolean success) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                opening = false;
                if (resetOpenBtnTask != null) uiHandler.removeCallbacks(resetOpenBtnTask);
                applyOpenButtonState(false, success ? "已开门 ✓" : "开  门");
                if (openBtn != null) {
                    openBtn.animate().cancel();
                    openBtn.setScaleX(1f);
                    openBtn.setScaleY(1f);
                }
                if (success) {
                    resetOpenBtnTask = new Runnable() {
                        @Override public void run() {
                            if (openBtn != null) openBtn.setText("开  门");
                        }
                    };
                    uiHandler.postDelayed(resetOpenBtnTask, 2000);
                }
            }
        });
    }

    /**
     * 把开门按钮的触控热区向外扩展（左右 +12dp、上 +8dp、下 +24dp）。
     * 单手拇指自然落点通常偏下偏右，按在按钮边缘外一点也能命中；
     * 热区按父容器坐标系计算，由 centerOpenButton() 在每次布局完成后重设。
     */
    private void expandOpenButtonHitArea() {
        if (openBtn == null || !(openBtn.getParent() instanceof View)) return;
        View parent = (View) openBtn.getParent();
        Rect r = new Rect();
        openBtn.getHitRect(r);
        if (r.width() <= 0 || r.height() <= 0) return;
        r.left = Math.max(0, r.left - dp(12));
        r.right += dp(12);
        r.top = Math.max(0, r.top - dp(8));
        r.bottom += dp(24);
        parent.setTouchDelegate(new TouchDelegate(r, openBtn));
    }

    /** 日志工具条小按钮：等宽横排，避免挤占开门区。 */
    private Button toolButton(String text, int bg, int fg) {
        Button b = button(text, bg, fg);
        b.setTextSize(11);
        b.setPadding(dp(4), dp(6), dp(4), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(6), dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 门形图示：浅蓝圆角方块 + 门轮廓 + 红色把手。
     * 外框与门板的描边色由 refreshDoorLabel() 按「是否已选门」切换，
     * 未选门时整体转灰，一眼能看出当前没有可开的门。
     */
    private View makeDoorGlyph() {
        FrameLayout icon = new FrameLayout(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));

        doorCardBg = new GradientDrawable();
        doorCardBg.setColor(Color.parseColor("#EAF1FF"));
        doorCardBg.setCornerRadius(dp(15));
        doorCardBg.setStroke(dp(2), C_BLUE);
        icon.setBackground(doorCardBg);

        doorPlateBg = new GradientDrawable();
        doorPlateBg.setColor(Color.TRANSPARENT);
        doorPlateBg.setCornerRadius(dp(3));
        doorPlateBg.setStroke(dp(2), C_BLUE);
        View plate = new View(this);
        plate.setBackground(doorPlateBg);
        icon.addView(plate, new FrameLayout.LayoutParams(dp(28), dp(38), Gravity.CENTER));

        doorHandleBg = new GradientDrawable();
        doorHandleBg.setShape(GradientDrawable.OVAL);
        doorHandleBg.setColor(C_RED);
        View knob = new View(this);
        knob.setBackground(doorHandleBg);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                dp(5), dp(5), Gravity.CENTER);
        klp.leftMargin = dp(18);
        klp.topMargin = dp(6);
        icon.addView(knob, klp);
        return icon;
    }

    private void buildUi() {
        // ---- 页面容器：整页浅底，卡片直接落在页面上 ----
        // 旧版是「红卡里再套白卡」，两层边框把开门区推高、又显得拥挤；
        // 现在红只留给开门按钮，门信息用一张白卡承载，视觉重心落在按钮上。
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);
        page.setPadding(dp(14), dp(6), dp(14), dp(20));   // 底部留出导航条/手势条的高度

        // ---- 1. 顶部：未登录 = 品牌 + 登录表单；已登录 = 一行账号 + 小区名 + 退出 ----
        brandBox = new LinearLayout(this);
        brandBox.setOrientation(LinearLayout.VERTICAL);
        brandBox.addView(label("亲邻开门 · 无广告", 21, C_INK, true));
        TextView sub = label("手机本机直连云端网关\n无服务器 / 无域名 / 无广告 SDK",
                12, C_SUB, false);
        sub.setPadding(0, dp(4), 0, dp(6));
        brandBox.addView(sub);

        loginBox = new LinearLayout(this);
        loginBox.setOrientation(LinearLayout.VERTICAL);
        loginBox.setPadding(0, dp(4), 0, 0);
        loginBox.addView(label("手机号", 14, C_INK, true));
        phoneEt = new EditText(this);
        phoneEt.setHint("11 位手机号");
        phoneEt.setInputType(InputType.TYPE_CLASS_PHONE);
        loginBox.addView(phoneEt);

        codeEt = new EditText(this);
        codeEt.setHint("短信验证码");
        codeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        loginBox.addView(codeEt);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        Button smsBtn = button("发送验证码", Color.parseColor("#E3EAF6"),
                Color.parseColor("#1B3A6B"));
        Button loginBtn = button("登录", C_BLUE, Color.WHITE);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(0, dp(6), dp(4), 0);
        smsBtn.setLayoutParams(half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMargins(dp(4), dp(6), 0, 0);
        loginBtn.setLayoutParams(half2);
        row.addView(smsBtn);
        row.addView(loginBtn);
        loginBox.addView(row);

        smsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSendSms(); }
        });
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doLogin(); }
        });

        accountBox = buildAccountHeader();   // 已登录时替代上面两块

        page.addView(brandBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(loginBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(accountBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 2. 开门卡 ----
        openArea = new LinearLayout(this);
        openArea.setOrientation(LinearLayout.VERTICAL);
        openArea.addView(buildDoorCard());
        LinearLayout.LayoutParams openAreaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openAreaLp.topMargin = dp(12);
        page.addView(openArea, openAreaLp);

        // ---- 3. 顶部弹性留白：把开门按钮推到窗口纵向 57%（单手拇指舒适弧内）----
        centerSpacer = new View(this);
        centerSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        page.addView(centerSpacer);

        // ---- 4. 开门按钮：整屏最大的触控区，高度取屏幕高 13%（96~116dp）----
        openBtn = button("开  门", Color.TRANSPARENT, Color.WHITE);
        openBtn.setTextSize(26);
        openBtn.setTypeface(Typeface.DEFAULT_BOLD);
        openBtn.setBackground(makeOpenButtonDrawable());
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int btnH = Math.max(dp(96), Math.min(dp(116), (int) (screenH * 0.13f)));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, btnH);
        openLp.topMargin = dp(10);
        openBtn.setLayoutParams(openLp);
        page.addView(openBtn);
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 点击瞬间先给一次触觉反馈：不用等网络返回就知道「按到了」
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                doOpen();
            }
        });
        // 按下轻微缩小再回弹，给单手点击一个明确的触觉外反馈；返回 false 不影响点击分发。
        openBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (!v.isEnabled()) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    v.animate().cancel();
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                } else if (e.getAction() == MotionEvent.ACTION_UP
                        || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                }
                return false;
            }
        });

        // ---- 5. 最近开门记录：填掉按钮下方空间，同时是「门到底开没开」的现场凭证 ----
        recordsCard = buildRecordsCard();
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recLp.topMargin = dp(12);
        page.addView(recordsCard, recLp);

        // ---- 6. 底部收口：日志入口 + 版本行，剩余高度由这段弹性空间吸收 ----
        bottomSpacer = new View(this);
        bottomSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(bottomSpacer);

        logToggle = new Button(this);
        logToggle.setAllCaps(false);
        logToggle.setTextSize(13);
        logToggle.setTypeface(Typeface.DEFAULT_BOLD);
        logToggle.setTextColor(Color.parseColor("#1B3A6B"));
        GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setColor(Color.parseColor("#EEF2F9"));
        toggleBg.setCornerRadius(dp(12));
        toggleBg.setStroke(dp(1), Color.parseColor("#9DB4DC"));
        logToggle.setBackground(toggleBg);
        logToggle.setMinHeight(dp(46));
        logToggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.topMargin = dp(8);
        logToggle.setLayoutParams(toggleLp);
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                openLogPanel();
            }
        });
        updateLogToggleText();
        page.addView(logToggle);
        versionFooter = makeVersionFooter();
        page.addView(versionFooter);

        // 顶层叠加：页面 + 屏幕最上方的「已开门」横幅。
        // 横幅盖在顶部而不是出现在屏幕下方，点开门按钮时手指遮不到。
        FrameLayout rootLayer = new FrameLayout(this);
        rootLayer.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        openBanner = makeOpenBanner();
        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bannerLp.setMargins(dp(12), dp(8), dp(12), 0);
        openBanner.setLayoutParams(bannerLp);
        openBanner.setVisibility(View.GONE);
        openBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOpenedBanner(); }
        });
        rootLayer.addView(openBanner);
        setContentView(rootLayer);

        refreshStats();         // 记录 / 指标首帧就位（本机数据，不联网）
        centerOpenButton();     // 首帧布局完成后校正按钮位置
    }

    /**
     * 已登录时的顶部信息行：一行账号 + 小区名当标题 + 退出胶囊。
     * 旧版是一张 96dp 的蓝色大卡，占地方又只说了「账号 138****8000」；
     * 现在把小区名升成标题——用户更需要确认「我在哪个小区」。
     */
    private LinearLayout buildAccountHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(2), 0, 0);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        accountTv = label("● 已登录", 11, C_GREEN, true);
        info.addView(accountTv);
        communityTv = label("小区：—", 18, C_INK, true);
        communityTv.setPadding(0, dp(3), 0, 0);
        communityTv.setSingleLine(true);
        communityTv.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(communityTv);

        TextView logout = label("退出", 11, C_RED, true);
        logout.setTextSize(11.5f);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(99));
        pill.setStroke(dp(1), Color.parseColor("#F4C3BE"));
        logout.setBackground(pill);
        logout.setGravity(Gravity.CENTER);
        logout.setPadding(dp(14), dp(7), dp(14), dp(7));
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doLogout(); }
        });
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.leftMargin = dp(12);
        box.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(logout, pillLp);
        box.setVisibility(View.GONE);   // 由 applyLoginState() 按凭证决定
        return box;
    }

    /** 开门卡：一屏内交代「开哪扇门 / 上次怎样 / 今天开了几次 / 怎么换门」。 */
    private LinearLayout buildDoorCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#E2E8F2"));
        card.setBackground(bg);
        card.setElevation(dp(1));

        // 门形图示 + 门名 / 区域 + 在线胶囊
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.addView(makeDoorGlyph());

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(13), 0, dp(4), 0);
        selectedDoorTv = label("尚未选择门", 22, C_INK, true);
        selectedDoorTv.setMaxLines(2);
        selectedDoorTv.setLineSpacing(0, 1.1f);
        info.addView(selectedDoorTv);
        doorStateTv = label("", 12, C_SUB, false);
        doorStateTv.setTextSize(11.5f);
        doorStateTv.setMaxLines(2);
        doorStateTv.setPadding(0, dp(5), 0, 0);
        info.addView(doorStateTv);
        row1.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        doorTagTv = label("在线", 11, C_GREEN, true);
        doorTagTv.setTextSize(10.5f);
        onlinePillBg = new GradientDrawable();
        onlinePillBg.setColor(Color.parseColor("#E6F4EA"));
        onlinePillBg.setCornerRadius(dp(99));
        doorTagTv.setBackground(onlinePillBg);
        doorTagTv.setPadding(dp(9), dp(3), dp(9), dp(3));
        doorTagTv.setVisibility(View.GONE);
        row1.addView(doorTagTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row1);

        // 「上次开门」摘要条：比翻记录快一眼
        LinearLayout lastRow = new LinearLayout(this);
        lastRow.setOrientation(LinearLayout.HORIZONTAL);
        lastRow.setGravity(Gravity.CENTER_VERTICAL);
        lastRow.setPadding(dp(11), dp(9), dp(11), dp(9));
        GradientDrawable lastBg = new GradientDrawable();
        lastBg.setColor(Color.parseColor("#F4F8FF"));
        lastBg.setCornerRadius(dp(10));
        lastBg.setStroke(dp(1), Color.parseColor("#E3ECFA"));
        lastRow.setBackground(lastBg);
        LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lastLp.topMargin = dp(11);
        card.addView(lastRow, lastLp);

        lastRow.addView(label("上次开门", 11, C_SUB, false));
        lastOpenTv = label("暂无记录", 12, C_MUTED, true);
        lastOpenTv.setSingleLine(true);
        lastOpenTv.setEllipsize(TextUtils.TruncateAt.END);
        lastOpenTv.setPadding(dp(8), 0, 0, 0);
        lastRow.addView(lastOpenTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lastMsTv = label("", 11, C_GREEN, true);
        lastRow.addView(lastMsTv);

        // 三格指标：竖分隔线 + 等分，没有额外一层卡片边框
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(12);
        card.addView(metrics, mLp);
        statTodayTv = addMetric(metrics, "今日开门");
        addMetricDivider(metrics);
        statRespTv = addMetric(metrics, "上次响应");
        addMetricDivider(metrics);
        statGwTv = addMetric(metrics, "网关直连");

        // 注意提示：原来贴在按钮下方、像免责声明；放进卡里位置更合理，也顺带填掉一段空白
        card.addView(divider(dp(9)));
        LinearLayout warn = new LinearLayout(this);
        warn.setOrientation(LinearLayout.HORIZONTAL);
        warn.setPadding(0, dp(8), 0, 0);
        TextView warnKey = label("注意", 10, Color.parseColor("#B3261E"), true);
        warnKey.setTextSize(10.5f);
        warn.addView(warnKey);
        TextView warnTv = label("成功响应仅表示平台已接受指令，请现场确认门是否打开。",
                10, C_MUTED, false);
        warnTv.setTextSize(10.5f);
        warnTv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        warnLp.leftMargin = dp(7);
        warn.addView(warnTv, warnLp);
        card.addView(warn);

        // 卡底：门 ID / 会话写入时间 + 切换门
        card.addView(divider(dp(9)));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(7), 0, 0);
        doorMetaTv = label("", 10, C_FAINT, false);
        doorMetaTv.setTextSize(10.5f);
        doorMetaTv.setSingleLine(true);
        doorMetaTv.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(doorMetaTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView switchTv = label("切换门 ▸", 11, C_BLUE, true);
        switchTv.setTextSize(11.5f);
        switchTv.setPadding(dp(12), dp(6), 0, dp(6));
        switchTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDoorList(); }
        });
        bottom.addView(switchTv);
        card.addView(bottom);
        return card;
    }

    /** 一列指标（小标题 + 数值），返回数值 TextView 供刷新。 */
    private TextView addMetric(LinearLayout parent, String key) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label(key, 10, C_MUTED, false));
        TextView v = label("—", 15, C_INK, true);
        v.setPadding(0, dp(3), 0, 0);
        col.addView(v);
        parent.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private void addMetricDivider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(Color.parseColor("#EDF1F7"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(26));
        lp.setMargins(dp(9), dp(8), dp(9), 0);
        parent.addView(d, lp);
    }

    /** 1px 分隔线，带顶部外边距（用来替代层层嵌套的卡片边框）。 */
    private View divider(int topMarginDp) {
        View d = new View(this);
        d.setBackgroundColor(Color.parseColor("#EEF1F6"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(topMarginDp);
        d.setLayoutParams(lp);
        return d;
    }

    /** 最近开门记录卡：默认 3 行，点「全部」展开到 10 行。 */
    private LinearLayout buildRecordsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#E2E8F2"));
        card.setBackground(bg);
        card.setElevation(dp(1));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("最近开门记录", 12, C_INK, true);
        title.setTextSize(12.5f);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        recordsMoreTv = label("全部 ▸", 11, C_BLUE, true);
        recordsMoreTv.setTextSize(11.5f);
        recordsMoreTv.setPadding(dp(12), dp(6), 0, dp(6));
        recordsMoreTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                recordsLimit = recordsLimit > DEFAULT_RECORDS ? DEFAULT_RECORDS
                        : Math.min(OpenRecord.MAX, 10);
                renderRecords();
            }
        });
        head.addView(recordsMoreTv);
        card.addView(head);

        recordsBox = new LinearLayout(this);
        recordsBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(recordsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** 重画记录行：默认 3 行，展开后最多 10 行；空状态给一句说明而不是留白。 */
    private void renderRecords() {
        if (recordsBox == null) return;
        recordsBox.removeAllViews();
        List<OpenRecord.Item> items = OpenRecord.recent(this, recordsLimit);
        if (items.isEmpty()) {
            TextView empty = label("还没有开门记录，打开一次门就会记在这里", 12, C_MUTED, false);
            empty.setTextSize(11.5f);
            empty.setPadding(0, dp(11), 0, dp(3));
            recordsBox.addView(empty);
            if (recordsMoreTv != null) recordsMoreTv.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            final OpenRecord.Item it = items.get(i);
            final boolean lastRow = i == items.size() - 1;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(it.ok ? C_GREEN : C_RED);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            dotLp.rightMargin = dp(9);
            row.addView(dot, dotLp);

            TextView time = label(OpenRecord.relative(it.at), 11, C_SUB, false);
            time.setSingleLine(true);
            row.addView(time, new LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT));

            // 入口标签：区分「应用内按钮」与「桌面小部件」，旧记录没有该字段就不显示
            String src = OpenRecord.sourceLabel(it.from);
            String tail = src.length() > 0 ? " · " + src : "";
            String text;
            if (it.ok) {
                text = (it.door.length() > 0 ? "已开门 · " + it.door : "已开门") + tail;
            } else {
                text = (it.note.length() > 0 ? "失败 · " + it.note : "失败") + tail;
            }
            TextView result = label(text, 12, it.ok ? C_INK : Color.parseColor("#B3261E"), false);
            result.setSingleLine(true);
            result.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            resultLp.leftMargin = dp(4);
            row.addView(result, resultLp);

            TextView ms = label(it.ok ? OpenRecord.seconds(it.ms) + "s" : "—", 11, C_MUTED, false);
            row.addView(ms);
            recordsBox.addView(row);
            if (!lastRow) recordsBox.addView(divider(0));
        }

        if (recordsMoreTv != null) {
            int total = OpenRecord.count(this);
            if (total > recordsLimit) {
                recordsMoreTv.setText("全部 ▸");
                recordsMoreTv.setVisibility(View.VISIBLE);
            } else if (recordsLimit > DEFAULT_RECORDS) {
                recordsMoreTv.setText("收起 ▴");
                recordsMoreTv.setVisibility(View.VISIBLE);
            } else {
                recordsMoreTv.setVisibility(View.GONE);
            }
        }
    }

    /** 刷新「上次开门 / 今日开门 / 上次响应 / 网关直连」+ 记录列表（全部本机数据）。 */
    private void refreshStats() {
        if (statTodayTv == null) return;
        setMetric(statTodayTv, String.valueOf(OpenRecord.todayCount(this)), "次");

        OpenRecord.Item last = OpenRecord.last(this);
        if (last != null && last.ok && last.ms > 0) {
            setMetric(statRespTv, OpenRecord.seconds(last.ms), "秒");
        } else {
            setMetric(statRespTv, "—", null);
        }

        boolean online = Net.online(this);
        statGwTv.setText(online ? "正常" : "离线");
        statGwTv.setTextColor(online ? C_GREEN : Color.parseColor("#B3261E"));

        if (lastOpenTv != null && lastMsTv != null) {
            if (last == null) {
                lastOpenTv.setText("暂无记录");
                lastOpenTv.setTextColor(C_MUTED);
                lastMsTv.setText("");
            } else {
                lastOpenTv.setText(OpenRecord.relative(last.at)
                        + " · " + (last.ok ? "成功" : "失败"));
                lastOpenTv.setTextColor(last.ok ? C_INK : Color.parseColor("#B3261E"));
                lastMsTv.setText(last.ok && last.ms > 0
                        ? OpenRecord.seconds(last.ms) + "s" : "");
                lastMsTv.setTextColor(last.ok ? C_GREEN : C_MUTED);
            }
        }
        renderRecords();
    }

    /** 数值 + 小号单位：单位用绝对字号缩到 11dp 并压低对比，主数值保持 15sp 粗体。 */
    private void setMetric(TextView tv, String value, String unit) {
        if (unit == null || unit.length() == 0) {
            tv.setText(value);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(value).append(" ").append(unit);
        int start = sb.length() - unit.length();
        sb.setSpan(new AbsoluteSizeSpan(11, true), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(C_SUB), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
    }

    /** 底部版本行：一眼说明「不联网存数据、无广告 SDK」。 */
    private TextView makeVersionFooter() {
        String ver = "v1.0";
        try {
            ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignore) {
            // 取不到版本号就用默认值，不影响界面
        }
        TextView t = label("网关直连 · " + ver + " · 无广告 · 无第三方 SDK",
                10, C_FAINT, false);
        t.setTextSize(10.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(7), 0, dp(2));
        return t;
    }

    /** 顶部「已开门」横幅：绿色渐变卡片 + 白色圆形对勾 + 主副文案。 */
    private View makeOpenBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        bg.setColors(new int[] { Color.parseColor("#16823C"), Color.parseColor("#0E5C29") });
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#0B4A22"));
        bar.setBackground(bg);
        bar.setElevation(dp(12));

        TextView mark = new TextView(this);
        mark.setText("✓");
        mark.setTextSize(20);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(Color.parseColor("#137333"));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        mark.setBackground(circle);
        bar.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        text.addView(label("已开门", 18, Color.WHITE, true));
        TextView sub = label("平台已接受指令 · 请现场确认门是否打开", 12,
                Color.parseColor("#D7F0DE"), false);
        sub.setPadding(0, dp(2), 0, 0);
        text.addView(sub);
        bar.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    /** 从屏幕最上方滑入，3.5 秒后自动收起；连续开门会重置计时。 */
    private void showOpenedBanner() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                uiHandler.removeCallbacks(hideBannerTask);
                if (openBanner == null) return;
                openBanner.setVisibility(View.VISIBLE);
                openBanner.animate().cancel();
                openBanner.setAlpha(0f);
                openBanner.setTranslationY(-dp(90));
                openBanner.animate().alpha(1f).translationY(0f).setDuration(220).start();
                uiHandler.postDelayed(hideBannerTask, 3500);
            }
        });
    }

    /** 点一下横幅可立即收起；自动收起也走这里。 */
    private void dismissOpenedBanner() {
        uiHandler.removeCallbacks(hideBannerTask);
        if (openBanner == null || openBanner.getVisibility() != View.VISIBLE) return;
        openBanner.animate().cancel();
        openBanner.animate().alpha(0f).translationY(-dp(90)).setDuration(200)
                .withEndAction(new Runnable() {
                    @Override public void run() { openBanner.setVisibility(View.GONE); }
                }).start();
    }

    /**
     * 让红色「开门」按钮的中心落在窗口纵向 57% 处（单手拇指舒适弧内）。
     * 在 onPreDraw 里实测按钮与窗口位置，自校正顶部留白高度（加减量 = 目标 - 实测），
     * 因此不同屏幕尺寸、登录态切换、字体缩放都能对齐，而不是写死一个 dp 值。
     *
     * 关键约束：留白只能吃掉下方那段弹性空间（bottomSpacer），不能凭空撑高页面，
     * 否则记录卡会被挤出屏幕。所以上限取「窗口高度 30%」与「下方弹性空间实际高度」的较小值。
     */
    private void centerOpenButton() {
        if (centerSpacer == null || openBtn == null || centering) return;
        centering = true;
        centerSpacer.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver obs = centerSpacer.getViewTreeObserver();
                if (obs.isAlive()) obs.removeOnPreDrawListener(this);
                centering = false;
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    AppLog.i(AppLog.SYS, "启动到首帧耗时 "
                            + (System.currentTimeMillis() - createT0) + "ms"
                            + "（含界面构建 / 状态快照 / 布局测量）");
                }

                View decor = getWindow().getDecorView();
                if (decor.getHeight() <= 0) return true;
                expandOpenButtonHitArea();   // 布局完成后按按钮实际位置重设拇指热区
                int[] b = new int[2];
                openBtn.getLocationOnScreen(b);
                int[] r = new int[2];
                decor.getLocationOnScreen(r);
                int target = r[1] + (int) (decor.getHeight() * 0.57f);   // 目标：纵向 57%
                int delta = target - (b[1] + openBtn.getHeight() / 2);

                ViewGroup.LayoutParams lp = centerSpacer.getLayoutParams();
                boolean logged = Prefs.session(MainActivity.this).length() > 0;
                if (!logged) {
                    // 未登录时顶部是完整登录表单，强行下推会与表单重叠：保持自然排布，
                    // 只把可能残留的弹性留白清零（例如刚退出登录）。
                    if (lp.height != 0) {
                        lp.height = 0;
                        centerSpacer.setLayoutParams(lp);
                        centerOpenButton();
                    }
                    return true;
                }
                int max = (int) (decor.getHeight() * 0.30);
                if (bottomSpacer != null && bottomSpacer.getHeight() > 0) {
                    // 下方还有多少空间可让：让多少都不会把记录卡挤出屏幕
                    max = Math.min(max, lp.height + bottomSpacer.getHeight());
                }
                int next = Math.max(0, Math.min(max, lp.height + delta));
                if (Math.abs(next - lp.height) > 2) {
                    lp.height = next;
                    centerSpacer.setLayoutParams(lp);
                    centerOpenButton();   // 布局变化后再校正一次，直到稳定
                }
                return true;
            }
        });
    }

    /** 打开悬浮门列表（用户主动点「门列表 ▸」）；选中结果由 onActivityResult() 带回主界面。 */
    private void openDoorList() { openDoorList(false); }

    private void openDoorList(boolean onboarding) {
        if (Prefs.session(this).length() == 0) {
            AppLog.w(AppLog.DOORS, "门列表入口前置校验失败：session 为空");
            notifyUser("请先登录，再点门列表选门");
            return;
        }
        doorListOnboarding = onboarding;
        AppLog.i(AppLog.DOORS, (onboarding ? "首次选门引导：自动" : "用户主动")
                + "打开悬浮门列表 | session=" + AppLog.fp(Prefs.session(this)));
        Intent i = new Intent(this, DoorListActivity.class);
        i.putExtra(DoorListActivity.EXTRA_ONBOARDING, onboarding);
        startActivityForResult(i, REQ_DOOR);
    }

    /**
     * 首次使用（或还没确认过要控制的门）时，直接弹出悬浮门列表让用户选门。
     * 选完才回到主界面；已经选过门的账号冷启动不弹，直接进主界面。
     */
    private void maybeShowOnboarding() {
        if (onboardingShown) return;
        if (Prefs.session(this).length() == 0) return;   // 未登录：先走登录表单
        if (!Prefs.needsOnboarding(this)) return;
        onboardingShown = true;
        AppLog.i(AppLog.DOORS, "尚未确认要控制的门（did=" + Prefs.doorId(this)
                + " cid=" + Prefs.communityId(this) + "）→ 先进门列表选门");
        toast("首次使用：请先选择要控制的门");
        // 等主界面首帧画完再弹浮层：否则浮层背后是空白，视觉上像直接进了门列表页
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                openDoorList(true);
            }
        }, 260);
    }

    // ---------- 登录态切换 ----------

    private static String maskPhone(String p) {
        if (p == null || p.length() != 11) return p == null ? "" : p;
        return p.substring(0, 3) + "****" + p.substring(7);
    }

    /**
     * 按本机凭证切换界面：已登录 → 顶部常驻卡片（账号 / 小区名 / 退出按钮），隐藏登录表单；
     * 未登录或凭证失效 → 显示手机号 / 验证码 / 登录按钮。可在后台线程安全调用。
     */
    private void applyLoginState() {
        // onCreate / onResume 本来就在主线程：直接执行，省掉一次消息投递，
        // 让登录态卡片首帧就位；后台线程（凭证失效检查）仍走 runOnUiThread。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyLoginStateUi();
        } else {
            runOnUiThread(new Runnable() {
                @Override public void run() { applyLoginStateUi(); }
            });
        }
    }

    private void applyLoginStateUi() {
        boolean logged = Prefs.session(MainActivity.this).length() > 0;
        if (lastLoginState == null || lastLoginState.booleanValue() != logged) {
            AppLog.i(AppLog.LOGIN, "登录态 → " + (logged
                    ? "已登录，本机凭证 " + AppLog.fp(Prefs.session(MainActivity.this))
                    : "未登录"));
            lastLoginState = Boolean.valueOf(logged);
        }
        loginBox.setVisibility(logged ? View.GONE : View.VISIBLE);
        brandBox.setVisibility(logged ? View.GONE : View.VISIBLE);
        accountBox.setVisibility(logged ? View.VISIBLE : View.GONE);
        // 记录卡只对已登录用户有意义：未登录时开门按钮都按不了，留着只是占位
        if (recordsCard != null) {
            recordsCard.setVisibility(logged ? View.VISIBLE : View.GONE);
        }
        if (versionFooter != null) {
            versionFooter.setVisibility(logged ? View.VISIBLE : View.GONE);
        }
        if (logged) {
            accountTv.setText("● 已登录 · " + maskPhone(Prefs.phone(MainActivity.this)));
            String cname = Prefs.communityName(MainActivity.this);
            communityTv.setText(cname.length() > 0 ? cname : "读取中…");
        }
        refreshStats();        // 记录 / 指标跟着登录态一起刷新（本机数据）
        centerOpenButton();   // 登录态切换会改变上方高度，重新校正按钮位置
    }

    /** 静默补齐小区名：只查 communityInfo，不改状态框、不自动滚动。 */
    private void loadCommunityName(final String token) {
        final String trace = AppLog.newTrace();
        AppLog.i(AppLog.DOORS, trace, "启动后静默补齐小区名 | session=" + AppLog.fp(token));
        new Thread(new Runnable() {
            @Override public void run() {
                AppLog.bind(trace);
                try {
                    List<QinlinApi.Community> cs = QinlinApi.communities(token);
                    Prefs.setCommMode(MainActivity.this, QinlinApi.PREFERRED_COMM_MODE);
                    StringBuilder names = new StringBuilder();
                    for (QinlinApi.Community c : cs) {
                        if (names.length() > 0) names.append("、");
                        names.append(c.name);
                    }
                    Prefs.setCommunityName(MainActivity.this,
                            names.length() > 0 ? names.toString() : "未读取到小区信息");
                    AppLog.ok(AppLog.DOORS, "小区名补齐成功：" + names);
                } catch (QinlinApi.ApiException e) {
                    if ("SESSION_EXPIRED".equals(e.code)) {
                        Prefs.clearSession(MainActivity.this); // 凭证已失效 → 自动切回登录表单
                        AppLog.w(AppLog.LOGIN, "静默补小区名时凭证失效 → 已清除凭证，切回登录表单");
                        applyLoginState();
                        return;
                    }
                    AppLog.e(AppLog.ERR, "小区名补齐失败 [" + e.code + "] " + e.getMessage());
                    Prefs.setCommunityName(MainActivity.this, "未读取到（进「门列表」重试）");
                } catch (Exception e) {
                    AppLog.e(AppLog.ERR, "小区名补齐异常 " + AppLog.stack(e));
                    Prefs.setCommunityName(MainActivity.this, "未读取到（进「门列表」重试）");
                } finally {
                    AppLog.unbind();
                }
                applyLoginState();
            }
        }).start();
    }

    /** 退出：清凭证与小区名，切回登录表单并预填手机号；门与设备标识保留。 */
    private void doLogout() {
        String before = AppLog.fp(Prefs.session(this));
        Prefs.clearSession(this);
        AppLog.w(AppLog.LOGIN, "退出登录：已清除凭证 " + before
                + " 与小区名（保留手机号 / openid / 默认门）");
        applyLoginState();
        codeEt.setText("");
        phoneEt.setText(Prefs.phone(this));
        toast("已退出登录，本机凭证已清除。下次开门需重新短信登录。");
    }

    // ---------- 交互 ----------

    private interface Work { void run() throws Exception; }

    /** 状态框现在是日志流：所有提示都作为一条分类日志写入（同时落盘 + logcat）。 */
    private void status(String s) { AppLog.i(AppLog.UI, s); }
    private void statusError(String s) { AppLog.e(AppLog.ERR, s); }
    private void statusOk(String s) { AppLog.ok(AppLog.UI, s); }

    /** 校验类提示走双通道：Toast + 警告级日志，保证反馈可见且可回溯。 */
    private void notifyUser(final String s) {
        AppLog.w(AppLog.UI, s);
        toast(s);
    }

    private void toast(final String s) { showToast(s, Toast.LENGTH_SHORT); }

    /**
     * 真机实测：ColorOS 上「先 cancel 旧 Toast 再 show 新 Toast」会触发
     * NotificationService 的 "Toast already killed / already cancelled"，连续提示反而被丢掉。
     * 改为每条独立排队、由系统自己管理生命周期；关键反馈另有状态框与顶部横幅兜底。
     */
    private void showToast(final String s, final int duration) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, s, duration).show();
            }
        });
    }

    /**
     * 每次后台操作生成 traceId 并绑定线程：网络层的请求 / 响应 / 业务码 / 异常
     * 会自动带上同一个 id，一次开门的完整链路可一次筛出来。
     */
    private void async(final String busy, final Work work) {
        async(busy, AppLog.newTrace(), work);
    }

    private void async(final String busy, final String trace, final Work work) {
        AppLog.i(AppLog.UI, trace, busy);
        toast(busy);
        new Thread(new Runnable() {
            @Override public void run() {
                AppLog.bind(trace);
                try {
                    work.run();
                } catch (final QinlinApi.ApiException e) {
                    AppLog.e(AppLog.ERR, trace, "操作失败 [" + e.code + "] "
                            + e.getMessage() + " | " + AppLog.stack(e));
                    runOnUiThread(new Runnable() {
                        @Override public void run() { handleError(e, trace); }
                    });
                } catch (final Exception e) {
                    AppLog.e(AppLog.ERR, trace, "未捕获异常 " + AppLog.stack(e));
                    statusError("失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
                } finally {
                    AppLog.unbind();
                }
            }
        }).start();
    }

    private void handleError(QinlinApi.ApiException e, String trace) {
        if ("SESSION_EXPIRED".equals(e.code)) {
            Prefs.clearSession(this);
            applyLoginState();      // 凭证失效后自动切回登录表单
            AppLog.w(AppLog.LOGIN, trace, "凭证已失效 → 已清除本机凭证，请重新获取验证码登录");
            toast("凭证已失效，请重新登录");
        } else {
            AppLog.e(AppLog.ERR, trace, "请求失败 [" + e.code + "] " + e.getMessage());
            toast("请求失败：" + e.code);
        }
    }

    private void doSendSms() {
        final String phone = phoneEt.getText().toString().trim();
        if (phone.length() != 11) {
            AppLog.w(AppLog.LOGIN, "短信前置校验失败：手机号长度 " + phone.length());
            notifyUser("请输入 11 位手机号");
            return;
        }
        Prefs.setPhone(this, phone);
        if (!Net.online(this)) {          // 断网立刻提示，不等连接超时
            AppLog.w(AppLog.LOGIN, "请求短信被拦下：当前无网络（" + Net.describe(this) + "）");
            notifyUser("当前没有网络，请连接网络后重新获取验证码");
            return;
        }
        AppLog.i(AppLog.LOGIN, "请求短信验证码 | 手机号=" + AppLog.maskPhone(phone)
                + " | 网络=" + Net.describe(this));
        async("正在请求短信验证码…", new Work() {
            @Override public void run() throws Exception {
                QinlinApi.sendSms(phone);
                AppLog.ok(AppLog.LOGIN, "短信请求已被接受，请查看验证码后点击「登录」");
            }
        });
    }

    private void doLogin() {
        final String phone = phoneEt.getText().toString().trim();
        final String code = codeEt.getText().toString().trim();
        if (phone.length() != 11) {
            AppLog.w(AppLog.LOGIN, "登录前置校验失败：手机号长度 " + phone.length());
            notifyUser("请输入 11 位手机号");
            return;
        }
        if (code.length() == 0) {
            AppLog.w(AppLog.LOGIN, "登录前置校验失败：验证码为空");
            notifyUser("请输入验证码");
            return;
        }
        final String prevPhone = Prefs.phone(this);   // setPhone 之前抓一次，用来识别换账号
        Prefs.setPhone(this, phone);
        if (!Net.online(this)) {
            AppLog.w(AppLog.LOGIN, "登录被拦下：当前无网络（" + Net.describe(this) + "）");
            notifyUser("当前没有网络，请连接网络后重新登录");
            return;
        }
        AppLog.i(AppLog.LOGIN, "开始登录 | 手机号=" + AppLog.maskPhone(phone)
                + " | 验证码长度=" + code.length() + "（内容不记录）"
                + " | 网络=" + Net.describe(this));
        async("正在登录…", new Work() {
            @Override public void run() throws Exception {
                final String token = QinlinApi.login(phone, code);
                Prefs.setSession(MainActivity.this, token);
                AppLog.ok(AppLog.LOGIN, "登录成功 | session=" + AppLog.fp(token)
                        + " | 登录时openid=" + AppLog.fp(Prefs.openid(MainActivity.this))
                        + " | 写入时间=" + AppLog.time(Prefs.sessionSavedAt(MainActivity.this)));
                Prefs.setCommunityName(MainActivity.this, ""); // 重登时先清掉旧小区名
                DoorCache.clear(MainActivity.this);            // 门列表缓存属于旧账号，一并清掉
                AppLog.i(AppLog.LOGIN, "已清除旧小区名与门列表缓存，避免串号");
                // 换手机号 = 换账号：旧门选择不能沿用，否则会去开别人的门
                if (prevPhone.length() > 0 && !prevPhone.equals(phone)) {
                    Prefs.clearDoor(MainActivity.this);
                    AppLog.w(AppLog.LOGIN, "手机号变化（" + AppLog.maskPhone(prevPhone)
                            + " → " + AppLog.maskPhone(phone) + "）：已清除旧账号的门选择，重新引导选门");
                }
                applyLoginState();  // 立刻把界面切成「已登录」：账号卡片 + 退出按钮
                        toast("登录成功，凭证已更新");
                statusOk("登录成功：凭证已更新并保存在本机。");
                loadCommunityName(token);   // 只补小区名，门列表交给悬浮二级界面
                // 首次登录（本机还没确认过要控制的门）→ 直接弹出选门浮层，选完再回主界面
                onboardingShown = false;
                runOnUiThread(new Runnable() {
                    @Override public void run() { maybeShowOnboarding(); }
                });
            }
        });
    }

    /** 把本机保存的选择显示在主界面的门卡片；门列表本身在悬浮二级界面里。 */
    private void refreshDoorLabel() {
        int cid = Prefs.communityId(this);
        int did = Prefs.doorId(this);
        String name = Prefs.doorName(this);
        int p = name.indexOf("  [在线:");
        if (p > 0) name = name.substring(0, p); // 兼容旧版本已保存的名称
        boolean on = cid > 0 && did > 0;
        if (on) {
            // 门名独占一行做视觉主体：去掉「楼栋门 · 」这类前缀，副标题里再说是什么门
            int sep = name.indexOf(" · ");
            selectedDoorTv.setText(sep > 0 ? name.substring(sep + 3) : name);
            String kind = Prefs.doorKind(this);
            String online = Prefs.doorOnline(this);
            StringBuilder state = new StringBuilder(kind.length() > 0 ? kind : "");
            if (online.length() > 0) {
                String s = "online".equalsIgnoreCase(online) ? "在线" : online;
                if (state.length() > 0) state.append("  ·  ");
                state.append(s);
            }
            doorStateTv.setText(state.length() > 0 ? state.toString() : "已设为默认开门");
            doorStateTv.setTextColor(C_SUB);

            StringBuilder meta = new StringBuilder("门 ID ").append(did);
            long savedAt = Prefs.sessionSavedAt(this);
            if (savedAt > 0) {
                meta.append(" · 会话保存于 ").append(OpenRecord.relative(savedAt));
            }
            doorMetaTv.setText(on ? meta.toString() : "");

            // 在线胶囊：Unknown 时不硬说「离线」，只显示「在线」或原始值
            boolean isOnline = "online".equalsIgnoreCase(online) || online.length() == 0;
            doorTagTv.setText(isOnline ? "在线" : online);
            doorTagTv.setTextColor(isOnline ? C_GREEN : Color.parseColor("#B3261E"));
            if (onlinePillBg != null) {
                onlinePillBg.setColor(Color.parseColor(isOnline ? "#E6F4EA" : "#FCEBEB"));
            }
            doorTagTv.setVisibility(View.VISIBLE);
        } else {
            selectedDoorTv.setText("尚未选择门");
            doorStateTv.setText("点下方「切换门」选一扇，选中的门会显示在这里");
            doorStateTv.setTextColor(Color.parseColor("#6B7688"));
            doorMetaTv.setText("登录后可选择要控制的门");
            doorTagTv.setVisibility(View.GONE);
        }
        // 门形图示随选中态换色（刷新/重启后依旧一致）
        doorCardBg.setColor(Color.parseColor(on ? "#EAF1FF" : "#E7EAF0"));
        doorCardBg.setStroke(dp(2), Color.parseColor(on ? "#2563EB" : "#9AA7BC"));
        doorPlateBg.setStroke(dp(2), Color.parseColor(on ? "#2563EB" : "#9AA7BC"));
        doorHandleBg.setColor(Color.parseColor(on ? "#D93025" : "#AAB3C2"));
    }

    private void doOpen() {
        final long clickAt = System.currentTimeMillis();
        // 单手大按钮容易连点：请求未结束前直接忽略后续点击，避免重复下发开门指令。
        if (opening) {
            AppLog.w(AppLog.OPEN, "忽略重复点击：上一次开门请求尚未结束（单手防连点）");
            return;
        }
        final String token = Prefs.session(this);
        final int cid = Prefs.communityId(this);
        final int did = Prefs.doorId(this);
        final String oid = Prefs.openid(this);
        final String savedFp = Prefs.sessionOpenidFp(this);
        final String trace = AppLog.newTrace();

        // 一次开门的完整现场：凭证 / 设备标识 / 门参数 / 凭证写入时间，全部脱敏
        AppLog.i(AppLog.OPEN, trace, "点击开门 | 参数快照 session=" + AppLog.fp(token)
                + " | openid=" + AppLog.fp(oid)
                + " | 登录时openid=" + (savedFp.length() > 0 ? savedFp : "未记录(旧数据)")
                + " | cid=" + cid + " did=" + did
                + " | 门=" + Prefs.doorName(this)
                + " | 凭证写入=" + AppLog.time(Prefs.sessionSavedAt(this)));

        if (token.length() == 0) {
            AppLog.w(AppLog.OPEN, trace, "前置校验失败：session 为空（界面显示已登录但本机无凭证）");
            notifyUser("请先登录");
            return;
        }
        if (cid <= 0 || did <= 0) {
            AppLog.w(AppLog.OPEN, trace, "前置校验失败：未选择门 cid=" + cid + " did=" + did);
            notifyUser("请先点「门列表 ▸」选一扇门");
            return;
        }
        if (savedFp.length() == 0) {
            AppLog.w(AppLog.OPEN, trace, "本机凭证缺少「登录时 openid」记录（旧版本数据），若被拒请重新登录");
        } else if (!savedFp.equals(AppLog.fp(oid))) {
            AppLog.w(AppLog.OPEN, trace, "⚠ 当前 openid 与登录时不一致，服务端很可能判定会话无效，建议重新登录后再开门");
        }
        // 断网时立刻回话：不开门总比让用户对着按钮干等 15 秒好。
        if (!Net.online(this)) {
            AppLog.w(AppLog.OPEN, trace, "前置校验失败：当前无网络（" + Net.describe(this) + "）");
            notifyUser("当前没有网络，无法开门。请检查网络后重试");
            return;
        }
        AppLog.i(AppLog.OPEN, trace, "网络就绪 → " + Net.describe(this) + "，开始下发开门指令");

        // 校验全部通过后才锁按钮：前置校验失败（未登录 / 未选门）不会让按钮卡在禁用态。
        opening = true;
        if (resetOpenBtnTask != null) uiHandler.removeCallbacks(resetOpenBtnTask);
        applyOpenButtonState(true, "开门中…");
        AppLog.i(AppLog.OPEN, trace, "主按钮已切换为「开门中…」并禁用，防止单手连点重复开门");

        async("正在发送开门指令…", trace, new Work() {
            @Override public void run() throws Exception {
                boolean succeeded = false;
                String failNote = "未成功";
                try {
                    final String raw = QinlinApi.openDoor(token, cid, did);
                    succeeded = true;
                    showOpenedBanner();   // 顶部样式化提示，不再用会被手指挡住的底部 Toast
                    AppLog.ok(AppLog.OPEN, "服务端原文（已脱敏）：" + raw);
                    statusOk("已开门 · 服务端接受指令，但成功响应不等于物理门已开；"
                            + "请现场确认，若门未开请保留官方 App / 实体钥匙兜底。");
                    AppLog.i(AppLog.OPEN, "开门端到端耗时 "
                            + (System.currentTimeMillis() - clickAt) + "ms"
                            + "（点击 → 服务端接受 → 界面反馈）");
                } catch (QinlinApi.ApiException e) {
                    failNote = e.code != null && e.code.length() > 0 ? e.code : "请求失败";
                    throw e;
                } finally {
                    // 两个入口共用同一个写入点：先落一条本机开门记录并同步桌面卡片，
                    // 再投递刷新，保证主线程读到的已经是刚落盘的记录。
                    int cost = (int) (System.currentTimeMillis() - clickAt);
                    OpenWidget.noteResult(MainActivity.this, succeeded,
                            succeeded ? "已开门" : failNote,
                            cost, OpenWidget.FROM_APP);
                    finishOpen(succeeded);   // 成功 / 失败都复位按钮，不留「开门中…」死状态
                    runOnUiThread(new Runnable() {
                        @Override public void run() { refreshStats(); }
                    });
                }
            }
        });
    }
}
