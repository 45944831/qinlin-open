package com.wb.qinlin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全屏日志查看器：主界面底部空间有限，日志框只能露出几行，
 * 想看完整内容 / 顺畅上下滑动时从这里打开。
 * 与主界面共用同一份 AppLog 数据，筛选、自动跟随最新、复制、导出都在这里做。
 */
public class LogActivity extends Activity {

    private static final String[] FILTERS =
            { "全部", "开门", "登录", "门列表", "网络", "系统", "错误" };
    /** 一次最多渲染多少条：再多就用「导出」看全量，避免一次排版过重。 */
    private static final int MAX_LINES = 300;
    private static final int REQ_EXPORT = 2001;

    /** 弹窗配色：编译期常量，不再逐处 Color.parseColor。 */
    private static final int C_INK = 0xFF16233B;        // 标题深墨
    private static final int C_NAVY = 0xFF1B3A6B;       // 按钮文字深蓝
    private static final int C_STEEL = 0xFF6B7688;      // 「✕ 关闭」灰字
    private static final int C_MUTED = 0xFF8A94A6;      // 提示 / 页脚灰
    private static final int C_LOG_TEXT = 0xFF22304B;   // 日志正文
    private static final int C_BG = 0xFFF5F7FB;         // 弹窗卡片底
    private static final int C_BLUE_SOFT = 0xFFE3EAF6;  // 浅蓝按钮底
    private static final int C_FILTER_BG = 0xFFDCE7FA;  // 「筛选」按钮底
    private static final int C_DIAG_BG = 0xFFE8F1FF;    // 「诊断快照」按钮底
    private static final int C_DANGER_BG = 0xFFF3E8E7;  // 「清空日志」按钮底
    private static final int C_FAIL = 0xFFB3261E;       // 「清空日志」文字 / 失败红
    private static final int C_STROKE = 0xFFC9D6EA;     // 常规浅描边
    private static final int C_STATUS_BG = 0xFFE9EEF7;  // 日志框底
    private static final int C_STATUS_EDGE = 0xFF9DB4DC;// 日志框描边
    private static final int C_BLUE = 0xFF2563EB;       // 「回到最新」胶囊底
    private static final int C_BLUE_EDGE = 0xFF1B4FBF;  // 胶囊描边
    // 日志级别着色（渲染时按 Entry.level 取）
    private static final int C_LV_ERR = 0xFFC5221F;
    private static final int C_LV_WARN = 0xFFB06000;
    private static final int C_LV_OK = 0xFF137333;

    private int filterIdx;
    private Button filterBtn;
    private ScrollView logScroll;
    private TextView logTv;
    private TextView footTv;
    private TextView newBtn;

    /** 是否自动跟随最新一条；用户上滑翻历史后自动暂停。 */
    private boolean follow = true;
    /** 手指是否还按在日志上：按住期间不抢滚动位置，避免和手指打架。 */
    private boolean touching;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable renderTask = new Runnable() {
        @Override public void run() { render(); }
    };
    /** 订阅 AppLog：新日志到来后合并成一次渲染；退出时一定要取消订阅。 */
    private final AppLog.Listener logListener = new AppLog.Listener() {
        @Override public void onLog(AppLog.Entry e) {
            ui.removeCallbacks(renderTask);
            ui.postDelayed(renderTask, 60);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        setupWindow();
        buildUi();
        AppLog.addListener(logListener);
        AppLog.i(AppLog.UI, "打开分类日志弹窗");
        render();
    }

    @Override
    protected void onDestroy() {
        AppLog.removeListener(logListener);
        ui.removeCallbacks(renderTask);
        super.onDestroy();
    }

    /** 接近全屏的浮层：宽 96%、高 86%，四周留出主界面轮廓。 */
    private void setupWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        w.setLayout((int) (dm.widthPixels * 0.96), (int) (dm.heightPixels * 0.86));
        w.setGravity(Gravity.CENTER);
        w.setDimAmount(0.5f);
        // 不提供「点外部关闭」：日志是排障现场，误触一下就消失会丢掉上下文，
        // 关闭只走标题行的「✕ 关闭」与系统返回键。
        setFinishOnTouchOutside(false);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        return Ui.label(this, text, sizeSp, color, bold);
    }

    private Button smallButton(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        b.setTextSize(12);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = new GradientDrawable();
        card.setColor(C_BG);
        card.setCornerRadius(dp(16));
        card.setStroke(dp(1), C_STROKE);
        root.setBackground(card);

        // ---- 标题行：标题 + 关闭 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("分类日志", 18, C_INK, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView closeTv = label("✕ 关闭", 14, C_STEEL, true);
        closeTv.setPadding(dp(12), dp(8), dp(2), dp(8));
        closeTv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(closeTv);
        root.addView(head);

        TextView hint = label("上下滑动查看历史 · 新日志自动跟到最新 · 手机号与凭证已脱敏",
                12, C_MUTED, false);
        hint.setPadding(0, dp(2), 0, dp(8));
        root.addView(hint);

        // ---- 工具条 ----
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        filterBtn = smallButton("筛选：全部 ▸", C_FILTER_BG,
                C_NAVY);
        Button copyBtn = smallButton("复制全部", C_BLUE_SOFT,
                C_NAVY);
        Button exportBtn = smallButton("导出文件", C_BLUE_SOFT,
                C_NAVY);
        tools.addView(filterBtn);
        tools.addView(copyBtn);
        tools.addView(exportBtn);
        root.addView(tools);

        // 第二行：排障用的两个重操作，放在弹窗里做，主界面不再摆这些按钮
        LinearLayout tools2 = new LinearLayout(this);
        tools2.setOrientation(LinearLayout.HORIZONTAL);
        Button diagBtn = smallButton("诊断快照", C_DIAG_BG,
                C_NAVY);
        Button clearBtn = smallButton("清空日志", C_DANGER_BG,
                C_FAIL);
        tools2.addView(diagBtn);
        tools2.addView(clearBtn);
        root.addView(tools2);

        filterBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                filterIdx = (filterIdx + 1) % FILTERS.length;
                filterBtn.setText("筛选：" + FILTERS[filterIdx] + " ▸");
                follow = true;
                render();
            }
        });
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 导出的整份文本（含头部快照），比只复制当前筛选更实用；
                // 全文要读 512KB 级磁盘日志 → 后台生成，主线程只写剪贴板
                copyAllLogsAsync();
            }
        });
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportLog(); }
        });
        diagBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String shot = AppLog.snapshot(LogActivity.this);
                AppLog.i(AppLog.SYS, "【诊断快照】" + shot);
                copyText(shot, "诊断快照已复制，可直接发给开发者");
            }
        });
        // 清空是破坏性操作：日志正是排障证据，误触会把「覆盖安装前」的历史一起抹掉
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(LogActivity.this)
                        .setTitle("清空日志？")
                        .setMessage("会同时清空本机保存的历史日志（含覆盖安装前的记录），清空后无法恢复。\n"
                                + "如果是要反馈问题，建议先点「导出文件」。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确定清空", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                AppLog.clear();
                                AppLog.i(AppLog.SYS, "日志已清空（内存与本机文件都已重置）");
                                filterIdx = 0;
                                filterBtn.setText("筛选：全部 ▸");
                                follow = true;
                                render();
                                Ui.toast(LogActivity.this, "日志已清空");
                            }
                        })
                        .show();
            }
        });

        // ---- 日志正文：独立 ScrollView，天然支持惯性滑动，不受页面滚动干扰 ----
        FrameLayout wrap = new FrameLayout(this);
        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setBackground(makeLogBox());
        logScroll.setPadding(dp(2), dp(4), dp(2), dp(4));

        logTv = label("", 12, C_LOG_TEXT, false);
        logTv.setPadding(dp(12), dp(8), dp(12), dp(8));
        logTv.setLineSpacing(dp(2), 1f);
        logScroll.addView(logTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(logScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 上滑离开底部后出现：一键回到最新并恢复跟随
        newBtn = label("↓ 回到最新", 12, Color.WHITE, true);
        newBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable nb = new GradientDrawable();
        nb.setColor(C_BLUE);
        nb.setCornerRadius(dp(15));
        nb.setStroke(dp(1), C_BLUE_EDGE);
        newBtn.setBackground(nb);
        newBtn.setElevation(dp(6));
        newBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        nlp.setMargins(0, 0, dp(12), dp(12));
        wrap.addView(newBtn, nlp);
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jumpToLatest(); }
        });

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wrapLp.topMargin = dp(10);
        root.addView(wrap, wrapLp);

        footTv = label("", 11, C_MUTED, false);
        footTv.setPadding(0, dp(8), 0, 0);
        root.addView(footTv);

        // 手指按下时暂停自动跟底，松手后按实际位置决定是否继续跟随
        logScroll.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    touching = true;
                } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                        || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    touching = false;
                    syncFollowState();
                }
                return false;   // 不消费事件，交给 ScrollView 自己处理
            }
        });
        logScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                if (!touching) syncFollowState();
            }
        });

        // 面板高度必须钉死：Dialog 主题窗口是按内容 wrap 的，
        // 一旦筛选到「0 条日志」，内容变矮窗口就会跟着缩成一小块（真机已复现）。
        root.setMinimumHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.86));
        setContentView(root);
    }

    private void copyText(String s, String tip) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("qinlin-log", s));
            Ui.toast(this, tip);
        } catch (Exception e) {
            AppLog.e(AppLog.ERR, "复制失败 " + AppLog.stack(e));
            Ui.toast(this, "复制失败");
        }
    }

    /**
     * 「复制全部」：exportText() 要同步读轮转 + 当前两份日志文件（最大各 256KB），
     * 不能在主线程做——后台拼好全文，再回主线程写剪贴板。
     */
    private void copyAllLogsAsync() {
        final Context app = getApplicationContext();
        Ui.toast(this, "正在整理全部日志…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String text = AppLog.exportText(app);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        copyText(text, "全部日志已复制");
                    }
                });
            }
        }, "log-export-copy").start();
    }

    /** 用系统文件选择器导出 txt（SAF，不需要任何权限或第三方库）。 */
    private void exportLog() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "qinlin-log-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + ".txt");
            startActivityForResult(i, REQ_EXPORT);
            AppLog.i(AppLog.SYS, "已打开日志导出文件选择器");
        } catch (Exception e) {
            AppLog.e(AppLog.ERR, "无法打开导出界面 " + AppLog.stack(e));
            Ui.toast(this, "导出不可用：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT) return;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            // exportText（读 512KB 级日志）+ SAF 写流都是磁盘 IO：全部移到后台线程，
            // 主线程只负责选完目标文件后立刻提示「正在导出」。
            final android.net.Uri target = data.getData();
            final Context app = getApplicationContext();
            Ui.toast(this, "正在导出日志…");
            new Thread(new Runnable() {
                @Override public void run() {
                    OutputStream os = null;
                    try {
                        os = app.getContentResolver().openOutputStream(target);
                        os.write(AppLog.exportText(app).getBytes("UTF-8"));
                        os.flush();
                        AppLog.ok(AppLog.SYS, "日志已导出到所选位置");
                        Ui.toast(app, "日志已导出");
                    } catch (Exception e) {
                        AppLog.e(AppLog.ERR, "日志导出失败 " + AppLog.stack(e));
                        Ui.toast(app, "导出失败：" + e.getMessage());
                    } finally {
                        if (os != null) try { os.close(); } catch (Exception ignored) {}
                    }
                }
            }, "log-export-write").start();
        } else {
            AppLog.w(AppLog.UI, "日志导出已取消");
        }
    }

    private GradientDrawable makeLogBox() {
        GradientDrawable box = new GradientDrawable();
        box.setColor(C_STATUS_BG);
        box.setCornerRadius(dp(10));
        box.setStroke(dp(1), C_STATUS_EDGE);
        return box;
    }

    /** 渲染当前筛选下的日志（最新在最下面）；只在「跟随最新」时滚到底。 */
    private void render() {
        if (logTv == null) return;
        String f = FILTERS[filterIdx];
        boolean errOnly = "错误".equals(f);
        String tag = ("全部".equals(f) || errOnly) ? null : f;
        List<AppLog.Entry> list = AppLog.recent(tag, errOnly, MAX_LINES);

        final int cErr = C_LV_ERR;
        final int cWarn = C_LV_WARN;
        final int cOk = C_LV_OK;
        final int cInfo = C_LOG_TEXT;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < list.size(); i++) {
            AppLog.Entry e = list.get(i);
            int start = sb.length();
            sb.append(e.line()).append('\n');
            int color;
            if (AppLog.E.equals(e.level)) color = cErr;
            else if (AppLog.W.equals(e.level)) color = cWarn;
            else if (AppLog.OK.equals(e.level)) color = cOk;
            else color = cInfo;
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (sb.length() == 0) sb.append("该筛选下暂无日志，点「筛选」切换分类。");
        logTv.setText(sb);
        footTv.setText("当前显示 " + list.size() + " 条（最多 " + MAX_LINES
                + " 条）· 全部日志已保存在本机，可用上方「导出文件 / 复制全部」取出");

        if (follow) {
            logScroll.post(new Runnable() {
                @Override public void run() { scrollToBottom(); }
            });
        }
    }

    private int maxScroll() {
        if (logScroll == null || logTv == null) return 0;
        int content = logTv.getHeight() + logScroll.getPaddingTop()
                + logScroll.getPaddingBottom();
        return Math.max(0, content - logScroll.getHeight());
    }

    private void scrollToBottom() {
        int max = maxScroll();
        if (max <= 0) return;
        logScroll.scrollTo(0, max);
        syncFollowState();
    }

    private void jumpToLatest() {
        follow = true;
        scrollToBottom();
        logScroll.post(new Runnable() {
            @Override public void run() { scrollToBottom(); }
        });
    }

    /** 根据当前位置判断是否还在「最新」处，控制跟随开关与按钮显隐。 */
    private void syncFollowState() {
        int max = maxScroll();
        boolean atBottom = max <= 0 || logScroll.getScrollY() >= max - dp(20);
        follow = atBottom;
        if (newBtn != null) {
            newBtn.setVisibility(atBottom ? View.GONE : View.VISIBLE);
        }
    }
}
