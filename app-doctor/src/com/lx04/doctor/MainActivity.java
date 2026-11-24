package com.lx04.doctor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * LX04 优化助手（原「设备医生」）—— 界面层。
 *
 * 设备上 app 拿不到 root（su 是 750 root:shell），所以所有采集与修复
 * 都由 root 代理 /data/local/tmp/lx04d.sh 执行，这里只做：
 *   1. 读代理写的 status.json / samples.csv 显示状态
 *   2. 把动作写成命令文件给代理执行，读回 out.txt
 *
 * 屏幕 800x480、密度 240dpi，折合 533x320dp，高度很矮。所以只有两屏：
 *   第一屏：六张带条形环的状态卡
 *   第二屏：操作卡，最重要的「一键优化」单独放大，其余按相关程度成组排
 */
public class MainActivity extends Activity {

    static final String DIR = "/data/data/com.lx04.doctor/files";

    static final int C_BG = 0xFF0B1013;
    static final int C_CARD = 0xFF19222A;
    static final int C_DOWN = 0xFF2B3944;
    static final int C_ACCENT_BG = 0xFF13261F;
    static final int C_TEXT = 0xFFE6EDF3;
    static final int C_MUTED = 0xFF8B9AA8;
    static final int C_OK = 0xFF35C2A0;
    static final int C_WARN = 0xFFE8A33D;
    static final int C_BAD = 0xFFE5544B;
    static final int C_DOT = 0xFF35414C;
    static final int C_TRACK = 0xFF26333D;

    File dir, statusFile, cmdFile, outFile, samplesFile;

    TextView tvProxy, tvHint, tvResult;
    View dotView;
    LinearLayout dotsBar;
    Pager pager;
    Metric mLd, mMem, mSwap, mDisk, mBt, mVpm;
    Card mVoice;

    Handler ui = new Handler(Looper.getMainLooper());
    volatile boolean running = true;
    volatile boolean voiceRunning = true;

    StringBuilder logBuf = new StringBuilder("就绪\n");
    String lastOut = "";
    String lastSent = "";

    // ---- 自启代理（不需要电脑）----
    final Object ensureLock = new Object();
    volatile boolean ensuring = false;      // 正在尝试拉起
    volatile long lastEnsureAt = 0;         // 上次尝试时刻，用于限频
    volatile String ensureMsg = null;       // 上次结果，给界面看
    String lastEnsureLogged = "";

    // ---------------- 条形环 ----------------
    /**
     * 圆环中间空着，在这块 52dp 的直径上只会显得空，看不出读的是哪一个量。
     * 改成圆头进度条：横向铺满卡片，长度即比例，一眼能比出六项谁高谁低。
     */
    static class Bar extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float frac;
        int color = C_OK;

        Bar(Context c) {
            super(c);
        }

        void set(float f, int c) {
            frac = f < 0 ? 0 : (f > 1 ? 1 : f);
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            p.setColor(C_TRACK);
            cv.drawRoundRect(0, 0, w, h, r, r, p);
            if (frac > 0.002f) {
                p.setColor(color);
                cv.drawRoundRect(0, 0, Math.max(h, w * frac), h, r, r, p);
            }
        }
    }

    // ---------------- 状态卡 ----------------
    static class Metric {
        LinearLayout root;
        TextView value, label, sub;
        Bar bar;

        Metric(Activity a, String labelText) {
            root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(a, 12), dp(a, 6), dp(a, 12), dp(a, 6));
            root.setBackground(round(a, C_CARD, 10));

            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            label = tv(a, labelText, 10, C_MUTED);
            top.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            sub = tv(a, "", 9.5f, C_MUTED);
            sub.setGravity(Gravity.END);
            top.addView(sub, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            value = tv(a, "--", 21, C_TEXT);
            value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            value.setPadding(0, dp(a, 2), 0, 0);

            bar = new Bar(a);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 5));
            bLp.topMargin = dp(a, 5);

            root.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(value, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(bar, bLp);
        }

        void set(String v, String s, int color, float frac) {
            value.setText(v);
            value.setTextColor(color);
            sub.setText(s == null ? "" : s);
            sub.setTextColor(color == C_TEXT ? C_MUTED : color);
            bar.set(frac, color == C_TEXT ? C_OK : color);
        }
    }

    // ---------------- 操作卡 ----------------
    static class Card {
        LinearLayout root;
        TextView title, sub;
    }

    // ---------------- 左右翻页容器 ----------------
    /**
     * 用 HorizontalScrollView 自己做分页。
     *
     * 不能在滑动停下后「吸附到最近一页」—— 手指一甩，惯性会直接越过一页，
     * 实测一次滑动从第 1 页跳到了第 3 页。所以改成松手时看拖动方向翻一页：
     * 拖过屏宽 18% 才翻，翻完直接 smoothScrollTo 把惯性顶掉。
     */
    class Pager extends HorizontalScrollView {
        int pageW = 1, current = 0, count = 1;
        float downX;
        int startPage;
        boolean dragging;

        Pager(Context c) {
            super(c);
        }

        void setPages(View[] pages) {
            count = pages.length;
            pageW = getResources().getDisplayMetrics().widthPixels - dp(MainActivity.this, 16);
            LinearLayout strip = new LinearLayout(getContext());
            strip.setOrientation(LinearLayout.HORIZONTAL);
            for (View p : pages) {
                strip.addView(p, new LinearLayout.LayoutParams(
                        pageW, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            addView(strip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setFillViewport(true);
        }

        void goTo(int p) {
            if (p < 0) p = 0;
            if (p > count - 1) p = count - 1;
            current = p;
            smoothScrollTo(p * pageW, 0);
            updateDots();
        }

        /**
         * 起点必须在拦截阶段记。
         * 手指按在卡片上时 DOWN 会被卡片自己吃掉，等父容器拦截到 MOVE 再来记起点，
         * 起点就变成 0，算出来的滑动距离永远是错的（实测按在卡片上滑不动）。
         */
        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                startPage = current;
                dragging = false;
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    startPage = current;
                    dragging = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getX() - downX) > dp(MainActivity.this, 10)) dragging = true;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    boolean handled = super.onTouchEvent(ev);
                    if (dragging) {
                        float dx = downX - ev.getX();
                        int dir = 0;
                        if (Math.abs(dx) > pageW * 0.18f) dir = dx > 0 ? 1 : -1;
                        goTo(startPage + dir);
                    }
                    dragging = false;
                    return handled;
                }
            }
            return super.onTouchEvent(ev);
        }
    }

    static int dp(Context a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }

    int dp(int v) {
        return dp(this, v);
    }

    static TextView tv(Context a, String text, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(Context a, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(a, radiusDp));
        return g;
    }

    static StateListDrawable pressable(Context a, int normal, int down, int radiusDp) {
        StateListDrawable sd = new StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_pressed}, round(a, down, radiusDp));
        sd.addState(new int[]{}, round(a, normal, radiusDp));
        return sd;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

        dir = new File(DIR);
        if (!dir.exists()) dir.mkdirs();
        statusFile = new File(dir, "status.json");
        cmdFile = new File(dir, "cmd");
        outFile = new File(dir, "out.txt");
        samplesFile = new File(dir, "samples.csv");

        // 上次跑完留下的 out.txt 不要回显：那是上一轮的结果，直接摆到底部会让人以为是刚跑的
        String stale = readSmall(outFile, 8192);
        if (stale != null) lastOut = stale;

        setContentView(buildUi());
        startPolling();
        kickEnsure();      // 打开就自己把 root 代理拉起来，不用电脑
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ---------------- UI ----------------
    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(8), dp(6), dp(8), dp(2));

        // ==== 顶部：标题 + 代理状态 + 日志入口 ====
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), 0, dp(2), dp(4));

        TextView title = tv(this, "优化助手", 16, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);

        dotView = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.leftMargin = dp(10);
        dotView.setBackground(round(this, C_MUTED, 3));
        head.addView(dotView, dotLp);

        tvProxy = tv(this, "检测中", 10, C_MUTED);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.leftMargin = dp(5);
        head.addView(tvProxy, stLp);

        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView btnLog = tv(this, "日志", 10, C_TEXT);
        btnLog.setPadding(dp(10), dp(3), dp(10), dp(3));
        btnLog.setBackground(pressable(this, C_CARD, C_DOWN, 6));
        btnLog.setClickable(true);
        btnLog.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLog();
            }
        });
        head.addView(btnLog);

        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ==== 第一屏：指标 + 仪表盘 ====
        mLd = new Metric(this, "CPU 负载");
        mMem = new Metric(this, "可用内存");
        mSwap = new Metric(this, "虚拟内存");
        mDisk = new Metric(this, "磁盘剩余");
        mBt = new Metric(this, "蓝牙占用");
        mVpm = new Metric(this, "语音引擎");

        LinearLayout p1 = page();
        p1.addView(row(mLd.root, mMem.root), rowLp(3, 1f));
        p1.addView(row(mSwap.root, mDisk.root), rowLp(3, 1f));
        p1.addView(row(mBt.root, mVpm.root), rowLp(3, 1f));

        tvHint = tv(this, "等待后台代理", 10, C_MUTED);
        tvHint.setMaxLines(2);
        tvHint.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.leftMargin = dp(4);
        hintLp.rightMargin = dp(4);
        hintLp.topMargin = dp(4);
        p1.addView(tvHint, hintLp);

        // ==== 第二屏：操作 ====
        LinearLayout p2 = page();

        // 主力动作单独放大，占满一整行
        Card opt = card("一键优化", "按当前状态自动处理", C_OK, 20, 10, true);
        opt.root.setBackground(pressable(this, C_ACCENT_BG, 0xFF1B3A31, 12));
        opt.root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ask("optimize", true);
            }
        });
        p2.addView(opt.root, rowLp(3, 1.55f));

        // 日常两项
        Card check = card("体检", "检查状态，不做改动", C_TEXT, 15, 9.5f, false);
        check.root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                perform("health");
            }
        });
        Card space = card("空间清理", "清理可自动重建的缓存", C_TEXT, 15, 9.5f, false);
        space.root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ask("space", true);
            }
        });
        p2.addView(rowLp2(check.root, space.root, 3, 1f));

        // 清理类
        Card cast = card("清投屏残留", "清理第三方投屏应用", C_TEXT, 15, 9.5f, false);
        cast.root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ask("fix_yst", true);
            }
        });
        Card bt = card("蓝牙重启", "清理蓝牙的 CPU 泄漏", C_TEXT, 15, 9.5f, false);
        bt.root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ask("fix_bt", true);
            }
        });
        p2.addView(rowLp2(cast.root, bt.root, 3, 1f));

        // 开关与兜底
        mVoice = card("暂停语音引擎", "释放约 70% 单核算力", C_TEXT, 15, 9.5f, false);
        mVoice.root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 两个方向都要确认：暂停会让小爱同学失效，恢复会重新吃掉几十个点的算力
                ask(voiceRunning ? "vpm_stop" : "vpm_start", true);
            }
        });
        Card proxy = card("代理重启", "代理无响应时使用", C_TEXT, 15, 9.5f, false);
        proxy.root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ask("proxy", true);
            }
        });
        p2.addView(rowLp2(mVoice.root, proxy.root, 3, 1f));

        pager = new Pager(this);
        pager.setPages(new View[]{p1, p2});
        root.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ==== 底部：最新结果 + 页码圆点 ====
        View divider = new View(this);
        divider.setBackgroundColor(0xFF1B242B);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(3);
        root.addView(divider, divLp);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(3), dp(3), dp(3), 0);

        tvResult = tv(this, "就绪", 10, 0xFFA6B6C4);
        tvResult.setSingleLine(true);
        tvResult.setEllipsize(TextUtils.TruncateAt.END);
        tvResult.setClickable(true);
        tvResult.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLog();
            }
        });
        foot.addView(tvResult, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dotsBar = new LinearLayout(this);
        dotsBar.setOrientation(LinearLayout.HORIZONTAL);
        dotsBar.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 2; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
            dlp.leftMargin = dp(4);
            d.setBackground(round(this, C_DOT, 3));
            dotsBar.addView(d, dlp);
        }
        foot.addView(dotsBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateDots();
        return root;
    }

    LinearLayout page() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(2), 0, dp(2), 0);
        return p;
    }

    LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a, cell());
        r.addView(b, cell());
        return r;
    }

    /** 一行两格的容器，高度按权重分 */
    LinearLayout rowLp2(View a, View b, int topDp, float weight) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a, cell());
        r.addView(b, cell());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
        p.topMargin = dp(topDp);
        r.setLayoutParams(p);
        return r;
    }

    LinearLayout.LayoutParams rowLp(int topDp, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
        p.topMargin = dp(topDp);
        return p;
    }

    LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        return p;
    }

    /** 操作卡片：标题 + 一句说明，按下变亮 */
    Card card(String label, String sub, int titleColor, float titleSp, float subSp,
              boolean accent) {
        Card c = new Card();
        c.root = new LinearLayout(this);
        c.root.setOrientation(LinearLayout.VERTICAL);
        c.root.setGravity(Gravity.CENTER);
        c.root.setPadding(dp(10), dp(4), dp(10), dp(4));
        c.root.setBackground(pressable(this, accent ? C_ACCENT_BG : C_CARD, C_DOWN, 12));
        c.root.setClickable(true);
        c.root.setFocusable(true);

        c.title = tv(this, label, titleSp, titleColor);
        c.title.setTypeface(Typeface.DEFAULT_BOLD);
        c.title.setGravity(Gravity.CENTER);
        c.title.setSingleLine(true);
        c.title.setEllipsize(TextUtils.TruncateAt.END);

        c.sub = tv(this, sub, subSp, C_MUTED);
        c.sub.setGravity(Gravity.CENTER);
        c.sub.setSingleLine(true);
        c.sub.setEllipsize(TextUtils.TruncateAt.END);
        c.sub.setPadding(0, dp(4), 0, 0);

        c.root.addView(c.title);
        c.root.addView(c.sub);
        return c;
    }

    void updateDots() {
        if (dotsBar == null || pager == null) return;
        for (int i = 0; i < dotsBar.getChildCount(); i++) {
            dotsBar.getChildAt(i).setBackground(
                    round(this, i == pager.current ? C_OK : C_DOT, 3));
        }
    }

    String cn(String a) {
        if ("health".equals(a)) return "体检";
        if ("optimize".equals(a)) return "一键优化";
        if ("space".equals(a)) return "空间清理";
        if ("fix_bt".equals(a)) return "蓝牙重启";
        if ("fix_yst".equals(a)) return "清投屏残留";
        if ("vpm_stop".equals(a)) return "暂停语音引擎";
        if ("vpm_start".equals(a)) return "恢复语音引擎";
        if ("proxy".equals(a)) return "代理重启";
        return a;
    }

    void ask(final String act, boolean confirm) {
        if (!confirm) {
            perform(act);
            return;
        }
        panel(cn(act), confirmText(act), "确定执行", new Runnable() {
            public void run() {
                perform(act);
            }
        });
    }

    /**
     * 小屏专用弹层。
     * 系统 AlertDialog 在这块 320dp 高的屏幕上会把长文字排到按钮底下去（实测被裁掉半段），
     * 所以自己造一个：正文字号小一点，超出部分在框内滚动，按钮固定在底部。
     */
    void panel(String title, String body, String okText, final Runnable onOk) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_CARD);
        box.setPadding(dp(18), dp(13), dp(18), dp(10));

        TextView t = tv(this, title, 15, C_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);

        TextView b = tv(this, body, 11, 0xFFC9D6DF);
        b.setLineSpacing(dp(3), 1f);

        ScrollView sc = new ScrollView(this) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(dp(182), MeasureSpec.AT_MOST));
            }
        };
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = dp(8);
        box.addView(sc, scLp);

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, 0);

        if (onOk != null) {
            TextView cancel = pill("取消", C_MUTED, 0xFF243039, 0xFF32404B);
            cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                }
            });
            bar.addView(cancel, pillLp());

            TextView ok = pill(okText, 0xFF06120F, C_OK, 0xFF2AA98A);
            ok.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    onOk.run();
                }
            });
            bar.addView(ok, pillLp());
        } else {
            TextView close = pill(okText, C_MUTED, 0xFF243039, 0xFF32404B);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                }
            });
            bar.addView(close, pillLp());
        }
        box.addView(bar);

        d.setContentView(box);
        d.getWindow().setBackgroundDrawable(round(this, C_CARD, 12));
        d.getWindow().setLayout(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT);
        d.show();
    }

    TextView pill(String text, int color, int bgNormal, int bgDown) {
        TextView v = tv(this, text, 12, color);
        v.setPadding(dp(18), dp(7), dp(18), dp(7));
        v.setBackground(pressable(this, bgNormal, bgDown, 8));
        v.setClickable(true);
        return v;
    }

    LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(10);
        return p;
    }

    String confirmText(String a) {
        if ("fix_bt".equals(a)) {
            return "重启蓝牙服务，不涉及硬件，也不需要操作设备。\n\n"
                    + "蓝牙进程会被强制结束，系统随后自动重建，中断大约十到十五秒。"
                    + "配对信息和设备名称不会丢失。\n\n"
                    + "建议仅在蓝牙占用明显偏高时执行，作用是清除长期累积的 CPU 泄漏。";
        }
        if ("vpm_stop".equals(a)) {
            return "语音引擎会被停止，期间无法通过小爱同学唤醒设备。\n\n"
                    + "可在本应用内恢复，重启设备也会自动恢复。\n\n"
                    + "停止后大约可以释放 70% 的单核算力。";
        }
        if ("vpm_start".equals(a)) {
            return "语音引擎会重新启动，小爱同学可以再次唤醒设备。\n\n"
                    + "启动后大约会占用 40% 到 70% 的单核算力，负载与耗电都会上升。";
        }
        if ("fix_yst".equals(a)) {
            return "会清除第三方投屏与镜像类应用，包括乐播、小电视、AirScreen 等。\n\n"
                    + "两种情况会自动跳过：当前停留在该应用界面，或者该应用正被局域网设备连接投屏。\n\n"
                    + "系统自带的投屏组件不做处理。";
        }
        if ("space".equals(a)) {
            return "只清理删除后会自动重建的内容：各应用缓存、崩溃日志、系统日志、"
                    + "基带日志、相册缩略图，以及临时目录中残留的安装包。\n\n"
                    + "不会处理 dalvik 缓存、已安装应用、下载目录文件、相册与音乐，"
                    + "以上项目只统计占用，不做删除。\n\n"
                    + "清理后首次打开部分应用会略慢。";
        }
        if ("optimize".equals(a)) {
            return "根据当前状态判断需要执行的项目，执行与跳过都会记录在日志中。\n\n"
                    + "处理范围包括投屏残留、蓝牙 CPU 泄漏、应用缓存、页缓存与语音引擎占用，"
                    + "只有超过阈值才动手。\n\n"
                    + "所有操作均可逆，耗时约半分钟到一分钟。";
        }
        if ("proxy".equals(a)) {
            return "重新连接本机 adb，启动后台 root 代理。\n\n"
                    + "代理已在运行时，本次不会做任何改动。";
        }
        return "确定执行";
    }

    // ---------------- 轮询 ----------------
    void startPolling() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        poll();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    void poll() {
        String st = readSmall(statusFile, 4096);
        final String out = readSmall(outFile, 8192);
        final String samples = readTail(samplesFile, 30);

        if (st == null) {
            kickEnsure();
            ui.post(new Runnable() {
                public void run() {
                    renderOffline();
                }
            });
            return;
        }

        final JSONObject j;
        try {
            j = new JSONObject(st);
        } catch (Exception e) {
            return;   // 读到半截就先跳过这一轮
        }

        final long now = System.currentTimeMillis() / 1000;
        final boolean ok = now - j.optLong("ts", 0) <= 25;
        if (!ok) kickEnsure();

        ui.post(new Runnable() {
            public void run() {
                if (ok) {
                    render(j, out, samples);
                } else {
                    renderOffline();
                }
            }
        });
    }

    /** 代理没在跑时的界面：区分「正在拉」「拉失败了（附原因）」 */
    void renderOffline() {
        if (ensuring) {
            setProxy("正在启动代理", C_WARN);
            tvHint.setText("正在重启后台代理，大约需要五秒");
            tvHint.setTextColor(C_WARN);
        } else {
            setProxy("代理未运行", C_BAD);
            tvHint.setText(ensureMsg == null
                    ? "当前无法执行操作，重新打开本应用会再次尝试"
                    : ensureMsg);
            tvHint.setTextColor(C_WARN);
        }
    }

    void setProxy(String text, int color) {
        tvProxy.setText(text);
        tvProxy.setTextColor(color);
        dotView.setBackground(round(this, color, 3));
    }

    /**
     * 后台尝试把 root 代理拉起来。不会阻塞界面，也不会重复并发。
     * 15 秒内只试一次，避免代理起不来时疯狂连 adbd。
     */
    void kickEnsure() {
        synchronized (ensureLock) {
            if (ensuring) return;
            long now = System.currentTimeMillis();
            if (now - lastEnsureAt < 15000) return;
            lastEnsureAt = now;
            ensuring = true;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                String m;
                try {
                    m = Daemon.ensure();
                } catch (Throwable e) {
                    m = "代理启动出错，" + e;
                }
                ensureMsg = m;
                ensuring = false;
                final String fm = m;
                ui.post(new Runnable() {
                    public void run() {
                        if (!fm.equals(lastEnsureLogged)) {
                            lastEnsureLogged = fm;
                            addLog(fm);
                        }
                    }
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    void render(JSONObject j, String out, String samples) {
        setProxy("代理在线", C_OK);

        double load = j.optDouble("load1", 0);
        long memKb = j.optLong("mem_avail", 0);
        long swap = j.optLong("swap_used", 0);
        long freeKb = j.optLong("data_free", 0);
        int btPct = j.optInt("bt_pct", 0);
        int vpmPct = j.optInt("vpm_pct", 0);
        int btAge = j.optInt("bt_age_min", 0);

        long memM = memKb / 1024;
        long freeM = freeKb / 1024;

        // 条形环长度跟标签同向：可用内存、磁盘剩余是越多越长，占用类是越高越长；
        // 颜色单独表示健康度。混着用会出现快满了还是绿色这种自相矛盾的显示。
        mLd.set(String.format("%.2f", load), load > 4 ? "过载" : "正常",
                load > 4 ? C_BAD : C_OK, (float) (load / 8.0));
        mMem.set(memM + "M", "共 996M",
                memM < 120 ? C_WARN : C_TEXT, memM / 996f);
        mSwap.set(swap + "M", swap > 300 ? "偏高" : "正常",
                swap > 300 ? C_WARN : C_TEXT, (float) (swap / 730.0));
        mDisk.set(freeM + "M", freeM < 150 ? "偏紧" : "够用",
                freeM < 150 ? C_WARN : C_TEXT, freeM / 1000f);
        mBt.set(btPct + "%", "已运行 " + btAge + " 分",
                btPct > 10 ? C_BAD : (btPct > 5 ? C_WARN : C_OK), btPct / 30f);
        mVpm.set(vpmPct + "%", "单核占比",
                vpmPct > 50 ? C_BAD : (vpmPct > 25 ? C_WARN : C_OK), vpmPct / 100f);

        String vpmPid = j.optString("vpm_pid", "");
        boolean vr = vpmPid != null && vpmPid.length() > 0 && !"0".equals(vpmPid);
        if (vr != voiceRunning) {
            voiceRunning = vr;
            if (mVoice != null) {
                mVoice.title.setText(vr ? "暂停语音引擎" : "恢复语音引擎");
                mVoice.sub.setText(vr ? "释放约 70% 单核算力" : "恢复小爱同学唤醒");
                mVoice.title.setTextColor(vr ? C_TEXT : C_MUTED);
            }
        }

        tvHint.setText(trendLine(samples, j.optString("bt_pid", ""), btPct));
        tvHint.setTextColor(btPct > 10 || vpmPct > 50 ? C_WARN : C_MUTED);

        if (out != null && !out.equals(lastOut)) {
            lastOut = out;
            String clean = cleanOut(out);
            if (!clean.isEmpty()) {
                addLog(clean);
                // 体检输出的最后一行是前台应用，底部那一行放这个没意义，改放第一行的总览
                setResult("health".equals(lastSent) ? firstLine(clean) : lastLine(clean));
            }
        }
    }

    /** 用采样历史估算蓝牙泄漏速率 */
    String trendLine(String samples, String pid, int curPct) {
        if (samples == null || samples.isEmpty()) {
            return "采样中，当前蓝牙占用 " + curPct + "% 单核";
        }
        String[] lines = samples.split("\n");
        long firstU = 0, firstT = 0, lastU = 0, lastT = 0;
        int n = 0;
        for (String l : lines) {
            String[] p = l.trim().split(",");
            if (p.length < 4) continue;
            if (!p[2].equals(pid)) continue;
            try {
                long u = Long.parseLong(p[1]);
                long t = Long.parseLong(p[3]);
                if (n == 0) {
                    firstU = u;
                    firstT = t;
                }
                lastU = u;
                lastT = t;
                n++;
            } catch (Exception ignored) {
            }
        }
        if (n < 3 || lastU <= firstU) {
            return "当前蓝牙占用 " + curPct + "% 单核，累积几小时后可估算泄漏速度";
        }
        double hours = (lastU - firstU) / 3600.0;
        double avg = (lastT - firstT) / 100.0 / (lastU - firstU) * 100.0;
        String s = String.format("蓝牙均值 %.1f%% 单核，已采样 %.1f 小时", avg, hours);
        if (avg > 0.3) {
            double etaH = hours * (30.0 / avg) - hours;
            if (etaH > 0 && etaH < 500) {
                s += String.format("，约 %.0f 小时后达到 30%%", etaH);
            }
        } else {
            s += "，未发现泄漏";
        }
        return s;
    }

    void addLog(String text) {
        if (logBuf.length() > 4000) {
            logBuf.delete(0, logBuf.length() - 2500);
        }
        logBuf.append(text).append('\n');
    }

    void setResult(String text) {
        if (text == null || text.isEmpty()) return;
        tvResult.setText(text);
        tvResult.setTextColor(0xFFA6B6C4);
    }

    static String lastLine(String s) {
        String[] ls = s.split("\n");
        for (int i = ls.length - 1; i >= 0; i--) {
            if (!ls[i].trim().isEmpty()) return ls[i].trim();
        }
        return s.trim();
    }

    static String firstLine(String s) {
        for (String l : s.split("\n")) {
            if (!l.trim().isEmpty()) return l.trim();
        }
        return s.trim();
    }

    /** 代理写回的内容带「=== 时间 执行 xx ===」表头和英文动作名，界面上不要这些 */
    static String cleanOut(String out) {
        StringBuilder sb = new StringBuilder();
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("===")) continue;
            sb.append(s).append('\n');
        }
        return sb.toString().trim();
    }

    void showLog() {
        panel("运行日志", logBuf.toString(), "关闭", null);
    }

    // ---------------- 下发命令 ----------------
    void perform(String action) {
        if ("proxy".equals(action)) {
            lastEnsureAt = 0;          // 绕过 15 秒限频，手动点就立刻试
            kickEnsure();
            setResult("正在启动后台代理");
            return;
        }
        send(action);
    }

    void send(String action) {
        try {
            lastSent = action;
            File tmp = new File(dir, ".cmd.tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            // 带 unix 时间戳：代理重启时若残留上一条命令，靠它判定是否过期后丢弃
            fo.write((action + "@" + (System.currentTimeMillis() / 1000)).getBytes("UTF-8"));
            fo.flush();
            fo.close();
            if (cmdFile.exists()) cmdFile.delete();
            tmp.renameTo(cmdFile);
            addLog("执行 " + cn(action));
            setResult("开始执行：" + cn(action));
        } catch (Exception e) {
            addLog("执行失败，" + e);
            setResult("执行失败，" + e);
        }
    }

    // ---------------- 文件读取 ----------------
    static String readSmall(File f, int max) {
        if (f == null || !f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int r;
            while ((r = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, r);
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static String readTail(File f, int lines) {
        if (f == null || !f.exists()) return null;
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 4096);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, "UTF-8");
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl > 0) s = s.substring(nl + 1);
            }
            String[] all = s.split("\n");
            if (all.length <= lines) return s;
            StringBuilder sb = new StringBuilder();
            for (int i = all.length - lines; i < all.length; i++) sb.append(all[i]).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
