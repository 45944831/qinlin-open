package com.wb.qinlin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 门列表「悬浮二级界面」：以圆角浮层卡片盖在主界面之上，主界面仍可见（变暗）。
 * 点一扇门 → 写回本机选择 + setResult → finish，主界面立刻把该门显示出来。
 * 关闭只保留标题行「✕ 关闭」这一个显式入口（点浮层外 / 系统返回键等价），
 * 不再单列「返回主界面」按钮，避免两个按钮做同一件事。收起浮层不改变已选的门。
 */
public class DoorListActivity extends Activity {

    /** true = 首次登录引导：请用户先确认要控制的门，选完才进主界面。 */
    public static final String EXTRA_ONBOARDING = "onboarding";

    private TextView subtitleTv;
    private TextView statusTv;
    private LinearLayout doorsBox;
    /** 引导模式下若读取失败 / 没有门，给一条「先进入主界面」的退路，避免把用户困在浮层里。 */
    private Button skipBtn;
    /** 标题行关闭入口：引导模式默认隐藏，无门可选时才显示。 */
    private TextView closeTv;
    private boolean onboarding;
    private long lastBackAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        QinlinApi.setOpenid(Prefs.openid(this));
        QinlinApi.PREFERRED_COMM_MODE = Prefs.commMode(this);   // 沿用上次成功的小区请求姿势
        onboarding = getIntent().getBooleanExtra(EXTRA_ONBOARDING, false);
        AppLog.i(AppLog.DOORS, onboarding
                ? "打开悬浮门列表（首次登录引导：先选门再进主界面）"
                : "打开悬浮门列表二级界面");
        setupFloatingWindow();
        buildUi();
        loadDoors();
    }

    /** 把 Activity 窗口做成中央悬浮卡片：宽度约屏宽 92%，高度约屏高 76%。 */
    private void setupFloatingWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        w.setLayout((int) (dm.widthPixels * 0.92),
                (int) (dm.heightPixels * (onboarding ? 0.82 : 0.76)));
        w.setGravity(Gravity.CENTER);
        w.setDimAmount(onboarding ? 0.62f : 0.55f);
        // 引导模式下不许点外面关掉：这一步就是让用户确认门，点外面等于「跳过」
        setFinishOnTouchOutside(!onboarding);
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
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void buildUi() {
        // 悬浮卡片外壳：圆角白底 + 描边 + 阴影，四周留出透明区域透出下层主界面
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#D6E2F5"));
        card.setBackground(bg);
        card.setElevation(dp(14));
        card.setPadding(dp(16), dp(14), dp(16), dp(12));

        // 标题行：左标题，右「✕ 关闭」——浮层唯一的显式关闭入口。
        // 首次登录引导时先不显示关闭（这一步要求先选门），
        // 只有在「读取失败 / 一扇门都没有、根本没法选」时才把它放出来，让用户能进主界面。
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(label(onboarding ? "选择要控制的门" : "门列表", 21,
                        Color.parseColor("#16233B"), true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        closeTv = label("✕ 关闭", 14, Color.parseColor("#1B3A6B"), true);
        closeTv.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.parseColor("#E3EAF6"));
        closeBg.setCornerRadius(dp(10));
        closeBg.setStroke(dp(1), Color.parseColor("#C9D6EA"));
        closeTv.setBackground(closeBg);
        closeTv.setContentDescription("关闭门列表，返回主界面");
        closeTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDoorList(); }
        });
        // 引导模式先藏起来：有门可选时必须是「选完才进主界面」
        closeTv.setVisibility(onboarding ? View.GONE : View.VISIBLE);
        titleRow.addView(closeTv);
        card.addView(titleRow);

        TextView tip = label(onboarding
                        ? "首次登录 · 选一扇门作为默认开门，选好后直接进入主界面"
                        : "点一扇门即显示到主界面 · 点卡片外可收起",
                12, Color.parseColor("#607089"), false);
        tip.setPadding(0, dp(2), 0, 0);
        card.addView(tip);

        subtitleTv = label("小区：读取中…", 13, Color.parseColor("#3C587F"), false);
        subtitleTv.setPadding(0, dp(8), 0, dp(6));
        card.addView(subtitleTv);

        // 固定操作行：只保留「刷新门列表」，占满整行更好按。
        // 关闭统一交给标题行「✕ 关闭」/ 点卡片外 / 系统返回键。
        Button refreshBtn = button("↻ 刷新门列表", Color.parseColor("#E3EAF6"),
                Color.parseColor("#1B3A6B"));
        GradientDrawable refreshBg = new GradientDrawable();
        refreshBg.setColor(Color.parseColor("#E3EAF6"));
        refreshBg.setCornerRadius(dp(10));
        refreshBg.setStroke(dp(1), Color.parseColor("#C9D6EA"));
        refreshBtn.setBackground(refreshBg);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(0, dp(6), 0, dp(2));
        refreshBtn.setLayoutParams(hp);
        card.addView(refreshBtn);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadDoors(false); }   // 主动刷新跳过缓存
        });

        // 引导模式的退路：只有「读取失败 / 一扇门都没有」时才显示，
        // 否则用户会因为门列表拉不下来而永远回不到主界面。
        skipBtn = button("稍后再说，先进入主界面", Color.parseColor("#F1F3F7"),
                Color.parseColor("#6B7688"));
        skipBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams skipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        skipLp.setMargins(0, dp(6), 0, dp(2));
        skipBtn.setLayoutParams(skipLp);
        skipBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AppLog.w(AppLog.DOORS, "首次选门引导：用户选择稍后再说，先进入主界面");
                toast("可随时点「门列表 ▸」来选择门");
                finish();
            }
        });
        card.addView(skipBtn);

        // 可滚动的内容区：状态 + 分组门列表
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(16));

        statusTv = label("正在读取门列表…", 14, Color.parseColor("#22304B"), false);
        statusTv.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.parseColor("#E9EEF7"));
        box.setCornerRadius(dp(10));
        box.setStroke(dp(1), Color.parseColor("#9DB4DC"));
        statusTv.setBackground(box);
        body.addView(statusTv);

        doorsBox = new LinearLayout(this);
        doorsBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(doorsBox);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 外层透明容器：卡片四周留边，才能看到圆角和背后的主界面
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.TRANSPARENT);
        outer.setPadding(dp(8), dp(12), dp(8), dp(12));
        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(outer);
    }

    /** 唯一的显式关闭入口：只收起浮层，不改动已选的门。 */
    private void closeDoorList() {
        String name = Prefs.doorName(this);
        AppLog.i(AppLog.DOORS, (onboarding
                        ? "关闭悬浮门列表（引导模式：当前无门可选，直接进主界面）| 当前开门："
                        : "关闭悬浮门列表（未改动已选门）| 当前开门：")
                + (name.length() > 0 ? name : "尚未选择"));
        finish();
    }

    /** 系统返回键与「✕ 关闭」走同一条路径，行为与日志一致。 */
    @Override
    public void onBackPressed() {
        if (!onboarding) { closeDoorList(); return; }
        // 引导模式没有关闭按钮：先提示，再按一次才放行，
        // 既不误触退出，也不会把用户困在浮层里（例如门列表一直拉不下来）。
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2500) {
            AppLog.w(AppLog.DOORS, "首次选门引导：连按返回，先进入主界面");
            finish();
            return;
        }
        lastBackAt = now;
        toast("请先选择一扇门；再按一次返回可先进入主界面");
    }

    /** 引导模式下也没门可选：放出「✕ 关闭」与「稍后再说」，保证用户能回到主界面。 */
    private void showSkip() {
        if (!onboarding) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (skipBtn != null) skipBtn.setVisibility(View.VISIBLE);
                if (closeTv != null) closeTv.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 有门可选时把退路收回去：引导模式必须先选门，选完才进主界面。 */
    private void hideSkip() {
        if (!onboarding) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (skipBtn != null) skipBtn.setVisibility(View.GONE);
                if (closeTv != null) closeTv.setVisibility(View.GONE);
            }
        });
    }

    private void status(final String s) {
        // 浮层保留单条状态提示，同时写入统一日志，主界面日志流能看到门列表全过程
        AppLog.i(AppLog.UI, "门列表 · " + s);
        runOnUiThread(new Runnable() {
            @Override public void run() { statusTv.setText(s); }
        });
    }

    private void subtitle(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { subtitleTv.setText(s); }
        });
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // 与主界面一致：独立排队，不 cancel 上一条（ColorOS 会报 Toast already killed）
                Toast.makeText(DoorListActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 首次打开：缓存优先渲染，再后台刷新。 */
    private void loadDoors() { loadDoors(true); }

    /**
     * 读取门列表。allowCache=false 表示用户主动点「刷新」，跳过缓存直接联网。
     * 缓存命中也照样后台刷新，保证看到的是最新门禁权限。
     */
    private void loadDoors(final boolean allowCache) {
        final String token = Prefs.session(this);
        if (!Prefs.loggedIn(this)) {
            AppLog.w(AppLog.DOORS, "门列表打开但未登录 / 凭证已被标记超时");
            status("未登录或凭证已超时，请回主界面点「重试连接」，或重新短信登录。");
            showSkip();
            return;
        }
        final String trace = AppLog.newTrace();

        // ① 先秒出本机缓存：门列表是用来选门的，先让用户看到、能点，再默默刷新。
        final DoorCache.Data cached = allowCache ? DoorCache.load(this) : null;
        if (cached != null && !cached.doors.isEmpty()) {
            AppLog.i(AppLog.DOORS, trace, "命中门列表缓存 " + cached.doors.size()
                    + " 扇门 | 读取于 " + cached.age());
            subtitle("小区：" + (cached.communityName.length() > 0
                            ? cached.communityName : "读取中…")
                    + "\n缓存 · " + cached.age() + " · " + currentLine());
            renderDoors(cached.doors);
            status("已显示上次读取的 " + cached.doors.size() + " 扇门（" + cached.age()
                    + "），正在后台刷新…");
        } else {
            subtitle(currentLine());
            status("正在读取小区与门列表…");
        }

        // ② 断网就立刻给结论：不必等 5 秒连接超时 + 15 秒读超时。
        if (!Net.online(this)) {
            AppLog.w(AppLog.DOORS, trace, "跳过门列表刷新：当前无网络（"
                    + Net.describe(this) + "）");
            status(cached != null
                    ? "当前无网络，显示的是上次缓存（" + cached.age()
                            + "）。联网后点「↻ 刷新门列表」。"
                    : "当前无网络，无法读取门列表。请连接网络后点「↻ 刷新门列表」。");
            if (cached == null) showSkip();   // 引导模式没有缓存可选时，必须留一条退路
            return;
        }
        if (cached == null) toast("正在读取门列表…");

        new Thread(new Runnable() {
            @Override public void run() {
                AppLog.bind(trace);
                AppLog.i(AppLog.DOORS, "开始联网刷新门列表 | 网络=" + Net.describe(DoorListActivity.this)
                        + " | session=" + AppLog.fp(token)
                        + " | openid=" + AppLog.fp(Prefs.openid(DoorListActivity.this)));
                try {
                    DoorCache.Data d = DoorCache.fetch(DoorListActivity.this, token);
                    subtitle("小区：" + d.communityName + "\n" + currentLine());
                    renderDoors(d.doors);
                    status(d.doors.isEmpty()
                            ? "未读取到门列表。请确认账号有门禁权限，或重新登录。\n" + d.note
                            : "读取到 " + d.doors.size() + " 个门，点一扇即设为当前开门。\n" + d.note);
                    if (d.doors.isEmpty()) showSkip();
                } catch (QinlinApi.ApiException e) {
                    if ("SESSION_EXPIRED".equals(e.code)) {
                        boolean dead = SessionGuard.expiredAfterRetry(DoorListActivity.this);
                        AppLog.w(AppLog.LOGIN, dead
                                ? "门列表读取确认登录超时 → 只标记过期，本机凭证保留"
                                : "门列表读取的 401 判定为误判，登录态保留");
                        status(dead
                                ? "服务端判定登录已超时，本机凭证已保留。回主界面点「重试连接」，或重新短信登录。"
                                : "刚才是网关临时异常，登录状态仍有效，请点「↻ 刷新门列表」重试。");
                        showSkip();
                    } else {
                        AppLog.e(AppLog.ERR, "门列表读取失败 [" + e.code + "] " + e.getMessage());
                        status("[" + e.code + "] " + e.getMessage()
                                + (cached != null ? "\n仍在显示上次缓存（" + cached.age() + "）。" : ""));
                        if (cached == null) showSkip();
                    }
                } catch (Exception e) {
                    AppLog.e(AppLog.ERR, "门列表读取异常 " + AppLog.stack(e));
                    status("读取失败：" + e.getClass().getSimpleName() + " " + e.getMessage()
                            + (cached != null ? "\n仍在显示上次缓存（" + cached.age() + "）。" : ""));
                    if (cached == null) showSkip();
                } finally {
                    AppLog.unbind();
                }
            }
        }).start();
    }

    private String currentLine() {
        String name = Prefs.doorName(this);
        return "当前开门：" + (name.length() > 0 ? name : "尚未选择");
    }

    /** 按上游区域分组展示；当前已选的门用蓝底 ✓ 标出。 */
    private void renderDoors(final List<QinlinApi.Door> doors) {
        if (doors != null && !doors.isEmpty()) hideSkip();   // 有门可选，关闭入口收回
        runOnUiThread(new Runnable() {
            @Override public void run() {
                doorsBox.removeAllViews();
                int selected = Prefs.doorId(DoorListActivity.this);
                String lastKind = "";
                for (final QinlinApi.Door d : doors) {
                    if (!d.kind.equals(lastKind)) {
                        TextView group = label("▸ " + d.kind + " 区域", 13,
                                Color.parseColor("#1B3A6B"), true);
                        group.setPadding(0, dp(12), 0, dp(2));
                        doorsBox.addView(group);
                        lastKind = d.kind;
                    }
                    Button b = button(d.listLabel(), Color.parseColor("#FFFFFF"),
                            Color.parseColor("#1B3A6B"));
                    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    b.setPadding(dp(12), dp(8), dp(12), dp(8));
                    style(b, d.listLabel(), d.id == selected);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            String plain = d.plainLabel();
                            Prefs.setDoor(DoorListActivity.this,
                                    d.communityId, d.id, plain);
                            Prefs.setDoorMeta(DoorListActivity.this, d.kind, d.online);
                            AppLog.ok(AppLog.DOORS, "选中门 id=" + d.id
                                    + " cid=" + d.communityId + " | 名称=" + plain
                                    + " | 区域=" + d.kind + " | 在线=" + d.online
                                    + " | 已写回本机并回传主界面");
                            // 把选择结果直接回传主界面，不依赖生命周期时序
                            Intent data = new Intent();
                            data.putExtra("door_name", plain);
                            data.putExtra("door_id", d.id);
                            data.putExtra("community_id", d.communityId);
                            setResult(RESULT_OK, data);
                            toast("已选择：" + plain);
                            finish();   // 收起悬浮层，主界面立即显示这扇门
                        }
                    });
                    doorsBox.addView(b);
                }
            }
        });
    }

    private void style(Button b, String text, boolean on) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(8));
        gd.setColor(Color.parseColor(on ? "#2563EB" : "#FFFFFF"));
        gd.setStroke(on ? dp(2) : dp(1),
                Color.parseColor(on ? "#1B3A6B" : "#C9D6EA"));
        b.setBackground(gd);
        b.setTextColor(Color.parseColor(on ? "#FFFFFF" : "#1B3A6B"));
        b.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setText((on ? "✓  " : "     ") + text);
    }
}
