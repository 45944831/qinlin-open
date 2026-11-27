package com.wb.qinlin;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
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
import android.text.InputFilter;
import android.text.Spanned;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 小组件设置「悬浮二级界面」：把桌面上所有已放置的小组件实例列在顶部横排标签里，
 * 选中一个即可改它的显示名、主题色与绑定的门。所有修改只写本机 SharedPreferences，
 * 改完立刻 {@link OpenWidget#refreshAll} 刷新桌面卡片，不触发任何网络请求。
 *
 * 入口有两处：主界面的「小组件设置」按钮；点桌面卡片的组件名行也可直接进入并定位。
 * 桌面上没有任何组件时给出明确提示——本界面只管理「已放置」的实例，不负责添加。
 */
public class WidgetConfigActivity extends Activity {

    /** 从桌面卡片组件名进入时带的目标 widgetId，用于自动选中对应标签。 */
    public static final String EXTRA_FOCUS_ID = "focus_widget_id";
    private static final int REQ_PICK_DOOR = 2101;

    /**
     * 配色：与另外三个界面共用 res/values/colors.xml 的语义令牌。
     * 六个主题色圆点也走资源（深色下提亮），不再在代码里写死 ARGB。
     */
    private int C_INK, C_NAVY, C_TIP, C_STROKE, C_BLUE_SOFT, C_BLUE_FILL;
    private int C_BG_SOFT, C_MUTED, C_SURFACE, C_FIELD, C_SKIP_BG, C_STEEL;
    private int[] SWATCH;

    private void loadPalette() {
        C_INK = getColor(R.color.q_ink);
        C_NAVY = getColor(R.color.q_navy);
        C_TIP = getColor(R.color.q_sub);
        C_STROKE = getColor(R.color.q_edge_strong);
        C_BLUE_SOFT = getColor(R.color.q_blue_soft);
        C_BLUE_FILL = getColor(R.color.q_blue_fill);
        C_BG_SOFT = getColor(R.color.q_bg);
        C_MUTED = getColor(R.color.q_muted);
        C_SURFACE = getColor(R.color.q_surface);
        C_FIELD = getColor(R.color.q_field);
        C_SKIP_BG = getColor(R.color.q_skip_bg);
        C_STEEL = getColor(R.color.q_steel);
        SWATCH = new int[] {
                getColor(R.color.q_swatch0), getColor(R.color.q_swatch1),
                getColor(R.color.q_swatch2), getColor(R.color.q_swatch3),
                getColor(R.color.q_swatch4), getColor(R.color.q_swatch5)
        };
    }

    /** 组件名输入过滤器：最多 5 个 Unicode 码点（汉字/emoji 都算 1 个），与 Prefs 存储层一致。 */
    private static final InputFilter NAME_FILTER = new InputFilter() {
        @Override
        public CharSequence filter(CharSequence src, int start, int end,
                                   Spanned dest, int dstart, int dend) {
            // dest 中删掉选中区间后剩下的码点数
            String before = dest.subSequence(0, dstart).toString();
            String after = dest.subSequence(dend, dest.length()).toString();
            int kept = before.codePointCount(0, before.length())
                    + after.codePointCount(0, after.length());
            String incoming = src.subSequence(start, end).toString();
            int add = incoming.codePointCount(0, incoming.length());
            if (kept + add <= Prefs.WIDGET_NAME_MAX) return null; // 接受全部
            int room = Prefs.WIDGET_NAME_MAX - kept;
            if (room <= 0) return ""; // 已满，拒绝新输入
            // 只保留放得下的前 room 个码点，避免把一个 emoji 代理对截成半截
            int cut = incoming.offsetByCodePoints(0, room);
            return incoming.subSequence(0, cut);
        }
    };

    private List<OpenWidget.Installed> items;
    private int focusId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int selectedIndex = -1;
    /**
     * 组件名未保存的草稿，按 widgetId 保存。
     * 编辑区在 onResume / 切标签 / 换色 / 选门返回时都会整块重建，
     * 以前重建会直接用已保存值覆盖输入框，用户刚敲的名字就静默丢了。
     */
    private final java.util.HashMap<Integer, String> nameDrafts =
            new java.util.HashMap<Integer, String>();

    private LinearLayout tabRow;
    private LinearLayout editorBox;
    private TextView emptyTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPalette();
        AppLog.init(this);
        focusId = getIntent().getIntExtra(EXTRA_FOCUS_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        setupFloatingWindow();
        buildUi();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从门列表选门返回后，门名可能已变，重画当前编辑区
        if (selectedIndex >= 0) renderEditor(selectedIndex);
    }

    /**
     * 声明了 configChanges，旋转不会重建 Activity；窗口尺寸是按 onCreate 时的
     * 屏幕像素算的，不重算的话横屏后卡片可能高出屏幕，底部按钮被裁掉。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setupFloatingWindow();
    }

    /** 悬浮卡片：宽 94%、高 82%，居中。 */
    private void setupFloatingWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        w.setLayout((int) (dm.widthPixels * 0.94), (int) (dm.heightPixels * 0.82));
        w.setGravity(Gravity.CENTER);
        w.setDimAmount(0.55f);
        setFinishOnTouchOutside(true);
    }

    private int dp(int v) { return Ui.dp(this, v); }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        return Ui.label(this, text, sizeSp, color, bold);
    }

    /** 重新枚举桌面组件并渲染标签；优先定位 focusId。 */
    private void reload() {
        items = OpenWidget.listInstalled(this);
        tabRow.removeAllViews();
        editorBox.removeAllViews();

        if (items.isEmpty()) {
            emptyTv.setVisibility(View.VISIBLE);
            tabRow.setVisibility(View.GONE);
            editorBox.setVisibility(View.GONE);
            return;
        }
        emptyTv.setVisibility(View.GONE);
        tabRow.setVisibility(View.VISIBLE);
        editorBox.setVisibility(View.VISIBLE);

        for (int i = 0; i < items.size(); i++) {
            tabRow.addView(makeTab(i));
        }
        int target = 0;
        boolean focusMissed = false;
        if (focusId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // 先假设找不到：找不到时不再「静默改到第一个」，否则用户以为看到的是刚点的那个组件
            target = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id == focusId) { target = i; break; }
            }
            focusId = AppWidgetManager.INVALID_APPWIDGET_ID; // 只定位一次
            if (target < 0) {
                target = 0;
                focusMissed = true;
            }
        }
        select(target);
        if (focusMissed) {
            AppLog.w(AppLog.UI, "小组件设置：要定位的实例已不在桌面，改为显示第一个组件");
            Ui.snack(this, "该小组件已不在桌面，已显示第一个");
        }
    }

    private void select(int index) {
        selectedIndex = index;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            styleTab((LinearLayout) tabRow.getChildAt(i), i == index);
        }
        renderEditor(index);
    }

    /** 顶部横排标签：显示组件名 + 尺寸，选中高亮。 */
    private LinearLayout makeTab(final int index) {
        OpenWidget.Installed it = items.get(index);
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView name = label(it.name(this), Ui.T_BODY_S, C_INK, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTag("tab_name");
        tab.addView(name);
        TextView size = label(it.size(), Ui.T_CAPTION, C_MUTED, false);
        size.setSingleLine(true);
        tab.addView(size);

        final LinearLayout t = tab;
        tab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { select(index); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tab.setLayoutParams(lp);
        return tab;
    }

    /** 标签选中态：蓝底白字；未选：浅蓝底墨字。两者都保留系统涟漪。 */
    private void styleTab(LinearLayout tab, boolean on) {
        tab.setBackground(Ui.rippled(this, on ? C_BLUE_FILL : C_BLUE_SOFT, Ui.R_MD,
                on ? C_NAVY : C_STROKE));
        View name = tab.findViewWithTag("tab_name");
        if (name instanceof TextView) ((TextView) name).setTextColor(on ? Color.WHITE : C_INK);
    }

    /** 改名保存后同步刷新顶部标签文字：标签只在 makeTab 时写过一次，不刷会一直显示旧名。 */
    private void updateTabLabel(int index) {
        if (index < 0 || index >= items.size() || index >= tabRow.getChildCount()) return;
        View name = tabRow.getChildAt(index).findViewWithTag("tab_name");
        if (name instanceof TextView) {
            ((TextView) name).setText(items.get(index).name(this));
        }
    }

    /** 选中某个组件后的编辑区：改名 / 换色 / 选门。 */
    private void renderEditor(int index) {
        editorBox.removeAllViews();
        final OpenWidget.Installed it = items.get(index);
        final int widgetId = it.id;

        TextView hint = label("正在设置：" + it.size() + "（#" + widgetId + "）", Ui.T_BODY_S, C_TIP, false);
        hint.setPadding(0, 0, 0, dp(10));
        editorBox.addView(hint);

        // ---- 组件名 ----
        editorBox.addView(label("组件名称（最多 5 个字，显示在桌面卡片上）", Ui.T_BODY, C_INK, true));
        final EditText nameEt = new EditText(this);
        nameEt.setHint(it.name(this));
        // 有未保存的草稿就优先恢复：编辑区重建（onResume / 切 Tab / 换色）不能吃掉用户输入
        String draft = nameDrafts.get(widgetId);
        nameEt.setText(draft != null ? draft : Prefs.widgetName(widgetId, this));
        nameEt.setSingleLine(true);
        nameEt.setTextSize(Ui.T_BODY);
        nameEt.setFilters(new InputFilter[]{NAME_FILTER}); // 输入时就限制 5 个字符（按码点）
        // 监听放在 setText 之后：初始化那一次不算用户输入，不该生成草稿
        nameEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                nameDrafts.put(widgetId, s.toString());
            }
        });
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(dp(Ui.R_MD));
        etBg.setColor(C_FIELD);
        etBg.setStroke(dp(1), C_STROKE);
        nameEt.setBackground(etBg);
        nameEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        editorBox.addView(nameEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button saveNameBtn = makeButton("保存名称", C_BLUE_SOFT, C_NAVY);
        saveNameBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String n = nameEt.getText().toString().trim();
                nameDrafts.remove(widgetId);   // 已保存：草稿使命结束，之后重建读持久化值
                Prefs.setWidgetName(widgetId, WidgetConfigActivity.this, n);
                OpenWidget.refreshAll(WidgetConfigActivity.this);
                updateTabLabel(selectedIndex);   // 顶部标签同步成新名字
                select(selectedIndex);   // 重画编辑区（选中态 / 提示）
                Ui.snack(WidgetConfigActivity.this, "名称已更新");
            }
        });
        editorBox.addView(saveNameBtn);

        // ---- 主题色 ----
        TextView colorTitle = label("主题色（按钮 + 卡片底色）", Ui.T_BODY, C_INK, true);
        colorTitle.setPadding(0, dp(14), 0, dp(8));
        editorBox.addView(colorTitle);
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        final int cur = Prefs.widgetColor(widgetId, this);
        for (int i = 0; i < OpenWidget.COLORS.length; i++) {
            colorRow.addView(makeSwatch(widgetId, i, i == cur));
        }
        editorBox.addView(colorRow);

        // ---- 绑定的门 ----
        TextView doorTitle = label("控制哪扇门", Ui.T_BODY, C_INK, true);
        doorTitle.setPadding(0, dp(14), 0, dp(8));
        editorBox.addView(doorTitle);
        OpenWidget.BoundDoor bound = OpenWidget.boundDoor(this, widgetId);
        String doorText = bound.bound
                ? (bound.name.length() > 0 ? bound.name : ("门 #" + bound.did))
                : "未单独绑定（跟随主界面默认门）";
        TextView doorTv = label(doorText, Ui.T_BODY, C_NAVY, true);
        doorTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable doorBg = new GradientDrawable();
        doorBg.setCornerRadius(dp(Ui.R_MD));
        doorBg.setColor(C_BG_SOFT);
        doorBg.setStroke(dp(1), C_STROKE);
        doorTv.setBackground(doorBg);
        editorBox.addView(doorTv);

        Button pickBtn = makeButton("请选择需要控制的门", C_BLUE_SOFT, C_NAVY);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(WidgetConfigActivity.this, DoorListActivity.class);
                i.putExtra(DoorListActivity.EXTRA_WIDGET_ID, widgetId);
                startActivityForResult(i, REQ_PICK_DOOR);
            }
        });
        editorBox.addView(pickBtn);

        Button resetBtn = makeButton("恢复跟随默认门", C_SKIP_BG, C_STEEL);
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setWidgetDoor(widgetId, WidgetConfigActivity.this, "");
                OpenWidget.refreshAll(WidgetConfigActivity.this);
                renderEditor(selectedIndex);
                Ui.snack(WidgetConfigActivity.this, "已恢复跟随默认门");
            }
        });
        editorBox.addView(resetBtn);
    }

    /** 单个主题色圆点；点击即应用并刷新桌面。6 格等分父宽，圆点与名称严格居中对齐。 */
    private View makeSwatch(final int widgetId, final int idx, boolean selected) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(0, 0, 0, 0);
        // 等分父容器宽度：不受颜色名长短影响，窄屏也不会因为总宽超出而被裁切
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int size = dp(34);
        View dot = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(SWATCH[idx]);
        gd.setStroke(selected ? dp(3) : dp(1), selected ? C_INK : C_STROKE);
        dot.setBackground(gd);
        wrap.addView(dot, new LinearLayout.LayoutParams(size, size,
                Gravity.CENTER_HORIZONTAL));

        TextView cap = label(OpenWidget.COLORS[idx], Ui.T_CAPTION, selected ? C_INK : C_MUTED, selected);
        cap.setGravity(Gravity.CENTER);           // 名称文字在格内居中，与圆点同轴
        cap.setPadding(0, dp(3), 0, 0);
        wrap.addView(cap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setWidgetColor(widgetId, WidgetConfigActivity.this, idx);
                OpenWidget.refreshAll(WidgetConfigActivity.this);
                renderEditor(selectedIndex);   // 更新选中态圆点
            }
        });
        return wrap;
    }

    private Button makeButton(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(Ui.T_BODY);
        b.setTextColor(fg);
        b.setBackground(Ui.rippled(this, bg, Ui.R_MD, C_STROKE));
        b.setMinHeight(dp(46));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_DOOR) {
            // 选门在 DoorListActivity 里已写好绑定；回来刷新桌面 + 编辑区门名
            OpenWidget.refreshAll(this);
            if (resultCode == RESULT_OK && selectedIndex >= 0) {
                String name = data != null ? data.getStringExtra("door_name") : null;
                AppLog.ok(AppLog.DOORS, "小组件设置：已换门" + (name != null ? " → " + name : ""));
            }
            if (selectedIndex >= 0) renderEditor(selectedIndex);
        }
    }

    /** 整体界面：标题 + 提示 + 横排标签 + 编辑区（可滚动）。 */
    private void buildUi() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_SURFACE);
        bg.setCornerRadius(dp(Ui.R_LG));
        bg.setStroke(dp(1), C_STROKE);
        card.setBackground(bg);
        card.setElevation(dp(14));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 标题行
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("小组件设置", Ui.T_HEADLINE, C_INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView closeTv = label("✕ 关闭", Ui.T_BODY, C_NAVY, true);
        closeTv.setPadding(dp(12), dp(8), dp(4), dp(8));
        closeTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(closeTv);
        card.addView(head);

        TextView tip = label("桌面上每一个小组件都可单独改名、换色、指定控制的门。",
                Ui.T_BODY_S, C_TIP, false);
        tip.setPadding(0, dp(4), 0, dp(8));
        card.addView(tip);

        // 横排标签
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 空状态
        emptyTv = label("桌面上还没有小组件。\n\n请长按桌面空白处 → 小部件 → 找到「qinlin 快捷开门」\n选择一种尺寸添加到桌面后，再来这里设置。",
                Ui.T_BODY, C_TIP, false);
        emptyTv.setPadding(dp(16), dp(28), dp(16), dp(28));
        emptyTv.setVisibility(View.GONE);
        card.addView(emptyTv);

        // 编辑区（可滚动，内容多时上下滑）
        ScrollView editorScroll = new ScrollView(this);
        editorBox = new LinearLayout(this);
        editorBox.setOrientation(LinearLayout.VERTICAL);
        editorBox.setPadding(0, dp(12), 0, dp(4));
        editorScroll.addView(editorBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(editorScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(card);
    }
}
