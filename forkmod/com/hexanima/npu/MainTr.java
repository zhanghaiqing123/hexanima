package com.hexanima.npu;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 翻译卡片：夹在提示词框和反向提示词框之间，可折叠。
 *
 *  输入中文 → 自动翻成英文并**整段替换**提示词框（即时模式下每改一个字都会重填，所以必须先清空旧的）。
 *
 *  第一行是三档：关 / 即时 / 仅词典
 *    · 关     ：只有点「翻译」才动；
 *    · 即时   ：500 ms 防抖后走当前引擎（云端禁用这一档，每打一个字就是一次付费请求）；
 *    · 仅词典 ：防抖后只做词典替换，不碰任何模型，未命中的段原样留中文。
 *
 *  词典开关打开时，所有引擎都先经过同一条流水线（TrEngine.plan）：能整段被词条覆盖的段直接出标签，
 *  剩下的段才交给当前引擎 —— ML Kit / Hy-MT / 云端都一样。 */
final class MainTr {
    static final int REQ_DICT = 41, REQ_PACK = 42, REQ_CSV = 43;

    private final Main m;
    EditText in;
    private TextView status;
    private Switch dictSw;
    private Button mOff, mLive, mDict;
    private Button engBtn;   // 引擎切换：ML Kit / 本地 Hy-MT / 云端
    View box;
    private MainLora.Fold fold;

    // 输入联想：边打中文边列出词典里包含这个词的条目，点一下就换成那个词（中文，不换成英文）。
    // 用 PopupWindow 而不是塞在卡片里：它是独立窗口，列表的滑动不会传给主界面的 ScrollView
    private PopupWindow assocPopup;
    private ListView assocListView;
    private BaseAdapter assocAdapter;
    private final ArrayList<String[]> assocRows = new ArrayList<>();
    private boolean assocSuppress;                 // 点选后 setText 会再触发一次搜索，跳过它
    private final Runnable assocTick = () -> doAssoc();

    private int seq;                 // 过期的翻译结果直接丢掉
    private boolean autoSilent;      // 程序性改动开关/档位时，不要把值写回设置
    static final String ENG_MLKIT = "mlkit", ENG_LOCAL = "local", ENG_CLOUD = "cloud";
    static final String MODE_OFF = "off", MODE_LIVE = "live", MODE_DICT = "dict";
    String engine() { return m.prefs().getString("tr_engine", ENG_MLKIT); }
    void setEngine(String e) { m.prefs().edit().putString("tr_engine", e).apply(); }
    private final Runnable tick = () -> doTranslate(false);

    MainTr(Main m) { this.m = m; }

    private boolean pref(String k, boolean d) { return m.prefs().getBoolean("tr_" + k, d); }
    private void setPref(String k, boolean v) { m.prefs().edit().putBoolean("tr_" + k, v).apply(); }

    /** 三档；老版本只有一个「即时」开关，第一次读时迁移过来 */
    private String mode() {
        String md = m.prefs().getString("tr_mode", "");
        if (md.isEmpty()) {
            md = pref("auto", true) ? MODE_LIVE : MODE_OFF;
            m.prefs().edit().putString("tr_mode", md).apply();
        }
        return md;
    }
    private void setMode(String md) {
        if (md.equals(mode())) return;
        m.prefs().edit().putString("tr_mode", md).putBoolean("tr_auto", !MODE_OFF.equals(md)).apply();
        paintMode();
        statusLine();
    }

    View build() {
        final int TXT = Main.TXT, ACC = Main.ACC, ON_ACC = Main.ON_ACC, FIELD = Main.FIELD, DIM = Main.DIM;
        LinearLayout card = new LinearLayout(m);
        card.setOrientation(LinearLayout.VERTICAL);
        if (!m.prefs().contains("fold_trbox")) m.prefs().edit().putBoolean("fold_trbox", true).apply();   // 第一次装着：先展开给人看见
        MainLora.Fold f = m.fold(card, Tr.s(m, "TRANSLATE · ZH → EN", "翻译 · 中 → 英"), "trbox");
        fold = f;

        in = m.field(Tr.s(m, "Type Chinese here…", "在这里输入中文…"));
        in.setMinLines(2);
        in.setGravity(Gravity.TOP);
        in.setTextSize(16);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        f.body.addView(in);

        String keep = m.prefs().getString("tr_in", "");
        if (!keep.isEmpty()) in.setText(keep);

        // 第一行：三档（关 / 即时 / 仅词典）+ 词典开关
        LinearLayout r1 = new LinearLayout(m);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.setPadding(0, m.dp(6), 0, 0);
        mOff = m.button(Tr.s(m, "Off", "关"), FIELD, TXT);
        mLive = m.button(Tr.s(m, "Live", "即时"), FIELD, TXT);
        mDict = m.button(Tr.s(m, "Dict only", "仅词典"), FIELD, TXT);
        Button[] seg = {mOff, mLive, mDict};
        for (int k = 0; k < seg.length; k++) {
            Button b = seg[k];
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, m.dp(34), 1);
            if (k > 0) lp.leftMargin = m.dp(4);
            r1.addView(b, lp);
        }
        mOff.setOnClickListener(v -> setMode(MODE_OFF));
        mLive.setOnClickListener(v -> setMode(MODE_LIVE));
        mDict.setOnClickListener(v -> setMode(MODE_DICT));
        dictSw = new Switch(m);
        dictSw.setChecked(pref("dict", true));
        dictSw.setOnCheckedChangeListener((v, on) -> { if (!autoSilent) setPref("dict", on); statusLine(); });
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.leftMargin = m.dp(10);
        r1.addView(dictSw, dl);
        r1.addView(m.text(Tr.s(m, "Dict", "词典"), 12, TXT, false));
        f.body.addView(r1);

        // 第二行：翻译 / 清空 / LLM（预留）/ ⚙（模型与词典的管理，导出模型包也在这里）
        LinearLayout r2 = new LinearLayout(m);
        r2.setPadding(0, m.dp(8), 0, 0);
        Button tr = m.button(Tr.s(m, "Translate", "翻译"), ACC, ON_ACC);
        tr.setTextSize(14);
        tr.setOnClickListener(v -> doTranslate(true));
        r2.addView(tr, new LinearLayout.LayoutParams(0, m.dp(40), 1));
        Button clr = m.button(Tr.s(m, "Clear", "清空"), FIELD, TXT);
        clr.setTextSize(14);
        clr.setOnClickListener(v -> { in.setText(""); seq++; statusLine(); });
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, m.dp(40), 1);
        c2.leftMargin = m.dp(6);
        r2.addView(clr, c2);
        engBtn = m.button("", FIELD, TXT);
        engBtn.setTextSize(13);
        engBtn.setAllCaps(false);
        engBtn.setOnClickListener(v -> toggleEngine());
        LinearLayout.LayoutParams c3 = new LinearLayout.LayoutParams(0, m.dp(40), 1);
        c3.leftMargin = m.dp(6);
        r2.addView(engBtn, c3);
        Button gear = m.button("⚙", FIELD, TXT);
        gear.setTextSize(14);
        gear.setOnClickListener(v -> manageDialog());
        LinearLayout.LayoutParams c4 = new LinearLayout.LayoutParams(m.dp(48), m.dp(40));
        c4.leftMargin = m.dp(6);
        r2.addView(gear, c4);
        f.body.addView(r2);

        status = m.text("", 12, DIM, false);
        status.setPadding(m.dp(4), m.dp(8), m.dp(4), 0);
        f.body.addView(status);

        // 输入防抖：停手 700ms 才翻一次，翻的过程中再来字就作废旧的
        in.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                m.prefs().edit().putString("tr_in", e.toString()).apply();
                m.ui.removeCallbacks(assocTick);
                String word = currentWord();
                if (!assocOn() || dictSw == null || !dictSw.isChecked() || assocSuppress || word.isEmpty()) hideAssoc();
                else m.ui.postDelayed(assocTick, 300);
                String md = mode();
                if (MODE_OFF.equals(md)) return;
                if (MODE_LIVE.equals(md) && ENG_CLOUD.equals(engine())) return;   // 云端即时 = 每打一个字一次付费请求
                m.ui.removeCallbacks(tick);
                if (e.toString().trim().isEmpty()) return;
                m.ui.postDelayed(tick, 500);
            }
        });

        paintMode();
        in.setOnFocusChangeListener((v, has) -> {                    // 点输入框才把本地引擎挂上（用户要求：不点不占内存）
            if (has && ENG_LOCAL.equals(engine()) && TrLlm.installed(m) && !TrLlm.ready() && !TrLlm.loading())
                new Thread(() -> { TrLlm.start(m); m.ui.post(this::statusLine); }, "hymt-load").start();
        });
        if (in.getText().toString().trim().length() > 0 && !MODE_OFF.equals(mode())) m.ui.postDelayed(tick, 900);
        if (m.mainScroll != null)
            m.mainScroll.getViewTreeObserver().addOnScrollChangedListener(this::hideAssoc);   // 列表是独立窗口，页面一滚就收起
        new Thread(() -> { TrDict.ensure(m); m.ui.post(this::statusLine); }).start();   // 词典 1.4 MB：先读好，打字时就不卡
        TrMlKit.refresh(m, st -> statusLine());
        box = card;
        return card;
    }

    private void statusLine() {
        if (status == null) return;
        int st = TrMlKit.state();
        String eng;
        if (st == TrMlKit.NOSDK) eng = Tr.s(m, "engine missing", "引擎缺失");
        else if (st == TrMlKit.READY) eng = Tr.s(m, "model ready", "模型已就绪");
        else if (st == TrMlKit.MISSING) eng = Tr.s(m, "model not downloaded (⚙)", "模型未下载（⚙）");
        else eng = Tr.s(m, "model state unknown", "模型状态未知");
        int n = TrDict.size();
        String d = dictSw != null && dictSw.isChecked()
                ? Tr.f(m, "dictionary %d entries", "词典 %d 条", n) : Tr.s(m, "dictionary off", "词典关闭");
        String engName = ENG_CLOUD.equals(engine()) ? Tr.s(m, "Cloud LLM", "云端 LLM")
                : ENG_LOCAL.equals(engine()) ? ("Hy-MT " + TrLlm.cur(m).id.replace("125", "1.25bit")) : "ML Kit";
        String engState;
        if (ENG_LOCAL.equals(engine())) engState = llmStateText();
        else if (ENG_CLOUD.equals(engine())) engState = TrCloud.configured(m)
                ? Tr.s(m, "API ready", "接口已配置") : Tr.s(m, "API not set", "未配置 API");
        else engState = eng;
        if (MODE_DICT.equals(mode())) engState = Tr.s(m, "dictionary only, no model", "仅词典，不调用模型");
        String line = engName + " · " + engState + " · " + d;
        status.setText(line);
        if (engBtn != null) engBtn.setText(ENG_CLOUD.equals(engine()) ? Tr.s(m, "Cloud", "云端")
                : ENG_LOCAL.equals(engine()) ? "Hy-MT" : "ML Kit");
        if (fold != null) fold.sum.setText(Tr.s(m, "zh", "中") + " → EN");
    }

    /** 翻译一次：manual = 点了按钮（忽略档位），否则是输入防抖触发 */
    private void doTranslate(boolean manual) {
        if (m.prompt == null) return;
        final String src = TrEngine.normalize(in.getText().toString());
        if (src.isEmpty()) {
            if (manual) Toast.makeText(m, Tr.s(m, "Type something first", "先输入中文"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String md = mode();
        if (!manual && MODE_OFF.equals(md)) return;
        final int my = ++seq;
        final boolean only = MODE_DICT.equals(md);
        final String before = status.getText().toString();

        final TrEngine.Plan p = TrEngine.plan(m, src, dictSw.isChecked());
        if (!p.any() || only) {                    // 全部被词典吃掉，或者就是「仅词典」档
            if (only && p.dictSegs == 0)
                status.setText(Tr.s(m, "dict only · nothing matched", "仅词典 · 没有命中词条"));
            finish(p.merge(null), p, 0, my, before);
            return;
        }
        if (ENG_CLOUD.equals(engine())) cloudTranslate(p, my, before);
        else if (ENG_LOCAL.equals(engine())) localTranslate(p, my, before);
        else mlkitTranslate(p, my, before);
    }

    /** 写回提示词框，并说明这次是「词典几段 / 模型几段」 */
    private void finish(String en, TrEngine.Plan p, int modelSegs, int my, String before) {
        if (my != seq) return;
        if (en == null || en.trim().isEmpty()) { status.setText(before); return; }
        applyToPrompt(en.trim());
        if (modelSegs > 0)
            status.setText(Tr.f(m, "done · dict %1$d · model %2$d", "完成 · 词典 %1$d 段 · 模型 %2$d 段", p.dictSegs, modelSegs));
        else if (p.dictSegs > 0)
            status.setText(Tr.f(m, "done · dict %d", "完成 · 词典 %d 段", p.dictSegs));
        m.ui.postDelayed(this::statusLine, 1300);
    }

    private void autoSiLent(Runnable r) {
        autoSilent = true;
        try { r.run(); } finally { autoSilent = false; }
    }

    /** 云端：只把词典没吃完的段发过去；失败回退本地引擎，再回退 ML Kit */
    private void cloudTranslate(TrEngine.Plan p, int my, String before) {
        if (!TrCloud.configured(m)) {
            Toast.makeText(m, Tr.s(m, "Configure the cloud API first (⚙ → Cloud LLM)", "先配置云端 API（⚙ → 云端 LLM）"), Toast.LENGTH_SHORT).show();
            fallback(p, my, before, true);
            return;
        }
        final TrTpl tpl = TrTpl.current(m);
        final String text = p.pending;
        status.setText(Tr.s(m, "translating (cloud)…", "云端翻译中…"));
        new Thread(() -> TrCloud.translate(m, tpl, text, (out, err) -> m.ui.post(() -> {
            if (my != seq) return;
            if (out != null && !out.trim().isEmpty()) {
                finish(p.merge(out), p, p.pend.size(), my, before);
            } else {
                status.setText(Tr.s(m, "cloud failed: ", "云端失败：") + err);
                fallback(p, my, before, true);
            }
        })), "cloud").start();
    }

    private void fallback(TrEngine.Plan p, int my, String before, boolean tryLocal) {
        if (tryLocal && TrLlm.installed(m)) { localTranslate(p, my, before); return; }
        mlkitTranslate(p, my, before);
    }

    private void localTranslate(TrEngine.Plan p, int my, String before) {
        if (!TrLlm.installed(m)) {
            Toast.makeText(m, Tr.s(m, "Local model not downloaded (⚙)", "本地模型未下载（⚙）"), Toast.LENGTH_SHORT).show();
            mlkitTranslate(p, my, before);
            return;
        }
        final String text = p.pending;
        status.setText(Tr.s(m, "translating (Hy-MT)…", "本地引擎翻译中…"));
        new Thread(() -> {
            if (!TrLlm.ready()) TrLlm.start(m);
            final String en = TrLlm.ready() ? TrLlm.translate(text) : null;
            m.ui.post(() -> {
                if (my != seq) return;
                if (en != null && !en.trim().isEmpty()) {
                    finish(p.merge(en), p, p.pend.size(), my, before);
                } else {
                    status.setText(Tr.s(m, "local engine failed → ML Kit", "本地引擎不可用 → 改用 ML Kit"));
                    mlkitTranslate(p, my, before);
                }
            });
        }, "hymt").start();
    }

    /** ML Kit：整串一次翻（它不支持批量），没有模型时就保留中文，用户能看见哪些词没翻出来 */
    private void mlkitTranslate(TrEngine.Plan p, int my, String before) {
        if (!TrMlKit.ready()) { finish(p.merge(null), p, 0, my, before); return; }
        final String text = p.pending;
        TrMlKit.translateOne(m, text, en -> m.ui.post(() -> {
            if (my != seq) return;
            finish(p.merge(en), p, p.pend.size(), my, before);
        }));
    }

    private String llmStateText() {
        if (!TrLlm.installed(m)) return Tr.s(m, "not downloaded", "未下载");
        if (TrLlm.ready()) return Tr.s(m, "loaded", "已加载");
        if (TrLlm.loading()) return Tr.s(m, "loading…", "加载中…");
        String e = TrLlm.lastError();
        return e.isEmpty() ? Tr.s(m, "idle", "待机") : (Tr.s(m, "error: ", "错误：") + e);
    }

    /** 选择引擎：ML Kit / Hy-MT 本地 / 云端 LLM */
    private void toggleEngine() {
        final String[] names = {
                Tr.s(m, "ML Kit (35 MB, 0.1 s)", "ML Kit（35 MB，0.1 秒）"),
                Tr.s(m, "Hy-MT local (440 MB, ~1.5 s)", "Hy-MT 本地（440 MB，约 1.5 秒）"),
                Tr.s(m, "Cloud LLM (your API key)", "云端 LLM（你自己的 API）")
        };
        m.builder().setTitle(Tr.s(m, "Translation engine", "翻译引擎"))
                .setSingleChoiceItems(names, engineIndex(), (d, i) -> {
                    d.dismiss();
                    if (i == 0) { setEngine(ENG_MLKIT); new Thread(TrLlm::stop).start(); }
                    else if (i == 1) {
                        if (!TrLlm.installed(m)) {
                            m.builder().setTitle(Tr.s(m, "Local translation model", "本地翻译模型"))
                                    .setMessage(Tr.s(m, "Hy-MT1.5-1.8B (1.25bit, 440 MB) runs offline on the CPU and is only loaded while you type. Download it now?",
                                            "Hy-MT1.5-1.8B（1.25bit，440 MB）在手机上离线运行，只在你打字的这段时间占用内存。现在下载吗？"))
                                    .setPositiveButton(Tr.s(m, "Download", "下载"), (w, q) -> downloadLlm())
                                    .setNegativeButton(Tr.s(m, "Cancel", "取消"), null).show();
                            return;
                        }
                        setEngine(ENG_LOCAL);
                        new Thread(() -> { TrLlm.start(m); m.ui.post(this::statusLine); }, "hymt-load").start();
                    } else {
                        if (!TrCloud.configured(m)) {
                            m.builder().setTitle(Tr.s(m, "Cloud LLM", "云端 LLM"))
                                    .setMessage(Tr.s(m, "Fill in the API base URL, key and model first.", "先填好 API 地址、Key 和模型。"))
                                    .setPositiveButton(Tr.s(m, "Open settings", "去设置"), (w, q) -> { nav = new TrNav(m); nav.push(cloudPage()); nav.show(); })
                                    .setNegativeButton(Tr.s(m, "Cancel", "取消"), null).show();
                            return;
                        }
                        setEngine(ENG_CLOUD);
                        new Thread(TrLlm::stop).start();
                    }
                    paintMode();
                    statusLine();
                }).show();
    }

    private int engineIndex() {
        return ENG_CLOUD.equals(engine()) ? 2 : ENG_LOCAL.equals(engine()) ? 1 : 0;
    }

    /** 刷新三档按钮：云端不能选「即时」（每打一个字就是一次付费请求），「仅词典」不受影响 */
    private void paintMode() {
        if (mOff == null) return;
        String md = mode();
        if (ENG_CLOUD.equals(engine()) && MODE_LIVE.equals(md)) {
            m.prefs().edit().putString("tr_mode", MODE_OFF).putBoolean("tr_auto", false).apply();
            md = MODE_OFF;
        }
        tint(mOff, MODE_OFF.equals(md));
        tint(mLive, MODE_LIVE.equals(md));
        tint(mDict, MODE_DICT.equals(md));
        boolean cloud = ENG_CLOUD.equals(engine());
        mLive.setEnabled(!cloud);
        mLive.setAlpha(cloud ? .4f : 1f);
        final boolean only = MODE_DICT.equals(md);
        if (dictSw != null)
            autoSiLent(() -> {
                if (only) dictSw.setChecked(true);
                else dictSw.setChecked(pref("dict", true));   // 还原用户自己的开关，不把「仅词典」的强制值写进设置
                dictSw.setEnabled(!only);
            });
        if (MODE_OFF.equals(md)) m.ui.removeCallbacks(tick);
    }

    /** 和 m.button() 一样的圆角，只是选中态单独画（不能 setBackgroundColor，会把圆角削掉） */
    private void tint(Button b, boolean on) {
        b.setBackground(on ? m.grad(14) : m.bg(Main.FIELD, 14));
        b.setTextColor(on ? Main.ON_ACC : Main.TXT);
    }

    private void downloadLlm() {
        if (TrLlm.installed(m)) {
            Toast.makeText(m, Tr.s(m, "Already downloaded", "已经下载好了"), Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText(Tr.s(m, "downloading the model…", "正在下载模型…"));
        new Thread(() -> {
            TrLlm.download(m,
                    got -> m.ui.post(() -> status.setText(Tr.f(m, "downloading %.0f / %.0f MB", "下载中 %.0f / %.0f MB", got / 1048576.0, TrLlm.cur(m).size / 1048576.0))),
                    res -> m.ui.post(() -> {
                        statusLine();
                        if ("ok".equals(res)) {
                            if (!TrLlm.installed(m)) { showText(Tr.s(m, "Download failed", "下载失败"), Tr.s(m, "The file size is still wrong after the download.", "下载完成后文件大小仍不正确。")); return; }
                            setEngine(ENG_LOCAL);
                            statusLine();
                            Toast.makeText(m, Tr.s(m, "Downloaded — the local engine is selected", "下载完成，已切换到本地引擎"), Toast.LENGTH_LONG).show();
                            new Thread(() -> {
                                boolean ok = TrLlm.start(m);
                                m.ui.post(() -> {
                                    statusLine();
                                    if (!ok) showLlmLog();   // 起不来就把日志直接摆出来
                                });
                            }, "hymt-load").start();
                            nav.refresh();
                        } else if ("busy".equals(res)) {
                            Toast.makeText(m, Tr.s(m, "A download is already running", "已经在下载了"), Toast.LENGTH_SHORT).show();
                        } else {
                            showText(Tr.s(m, "Download failed", "下载失败"), res);
                        }
                    }));
        }, "hymt-dl").start();
    }

    private void deleteLlm() {
        new Thread(() -> {
            TrLlm.stop();
            File f = TrLlm.model(m);
            long n = f.length();
            f.delete();
            new File(f.getPath() + ".part").delete();
            m.ui.post(() -> { statusLine(); Toast.makeText(m, Tr.f(m, "Deleted (%.0f MB)", "已删除（%.0f MB）", n / 1048576.0), Toast.LENGTH_SHORT).show(); });
        }, "hymt-del").start();
    }

    private void showLlmLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("bin: ").append(new File(m.getApplicationInfo().nativeLibraryDir, "libhymt.so").getAbsolutePath())
          .append("  exists=").append(new File(m.getApplicationInfo().nativeLibraryDir, "libhymt.so").isFile()).append('\n');
        sb.append("model: ").append(TrLlm.model(m).getAbsolutePath()).append("  ").append(TrLlm.model(m).length()).append(" B\n");
        sb.append("state: ").append(TrLlm.state(m)).append('\n');
        sb.append("last: ").append(TrLlm.lastError()).append("\n\n== log ==\n");
        tail(TrLlm.logFile(m), sb, 60);
        showText("Hy-MT", sb.toString());
    }

    /** 生图前调用：把本地翻译进程杀掉，内存全部释放 */
    void stopEngineForGeneration() {
        if (TrLlm.ready() || TrLlm.loading()) new Thread(TrLlm::stop, "hymt-stop").start();
    }

    /** 整段替换提示词框：实时模式下每输入一点都会重填，先清空旧的，避免越拼越长 */
    private void applyToPrompt(String en) {
        try {
            m.prompt.setText(en);
            m.prompt.setSelection(m.prompt.getText().length());
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── 输入联想 ─────────────────────────

    private boolean assocOn() { return m.prefs().getBoolean("tr_assoc", true); }

    /** 光标前那一段连续中文（遇到逗号、空格、括号、英文就断），没有就返回空串 */
    private String currentWord() {
        if (in == null) return "";
        String s = in.getText().toString();
        int end = in.getSelectionEnd();
        if (end < 0 || end > s.length()) end = s.length();
        int i = end;
        while (i > 0 && TrDict.cjk(s.charAt(i - 1))) i--;
        return s.substring(i, end);
    }

    /** 后台查词表，回来时确认这个词还是当前词（用户可能已经又改了） */
    private void doAssoc() {
        if (in == null) return;
        final String word = currentWord();
        if (word.isEmpty()) { hideAssoc(); return; }
        new Thread(() -> {
            final ArrayList<String[]> got = TrDict.search(word, 60);
            m.ui.post(() -> {
                if (!word.equals(currentWord())) return;
                fillAssoc(got);
            });
        }, "tr-assoc").start();
    }

    private void fillAssoc(ArrayList<String[]> rows) {
        if (rows.isEmpty()) { hideAssoc(); return; }
        ensureAssocPopup();
        assocRows.clear();
        assocRows.addAll(rows);
        assocAdapter.notifyDataSetChanged();
        assocListView.setSelection(0);
        int h = Math.min(rows.size(), 10) * m.dp(24) + m.dp(2);
        int w = Math.max(m.dp(160), in.getWidth());
        assocPopup.setWidth(w);
        assocPopup.setHeight(h);
        if (assocPopup.isShowing()) assocPopup.update(in, 0, m.dp(4), w, h);
        else assocPopup.showAsDropDown(in, 0, m.dp(4));
    }

    private void ensureAssocPopup() {
        if (assocPopup != null) return;
        assocPopup = new PopupWindow(m);
        assocListView = new ListView(m);
        assocListView.setDivider(null);
        assocListView.setDividerHeight(0);
        assocListView.setVerticalScrollBarEnabled(true);
        assocAdapter = new BaseAdapter() {
            public int getCount() { return assocRows.size(); }
            public Object getItem(int i) { return assocRows.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View c, ViewGroup p) {
                TextView t = c instanceof TextView ? (TextView) c : null;
                if (t == null) {
                    t = new TextView(m);
                    t.setTextSize(12);
                    t.setTextColor(Main.DIM);
                    t.setSingleLine(true);
                    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    t.setPadding(m.dp(12), m.dp(4), m.dp(12), m.dp(4));
                    t.setGravity(Gravity.CENTER_VERTICAL);
                }
                t.setText(assocRows.get(i)[0] + "  ·  " + assocRows.get(i)[1]);
                return t;
            }
        };
        assocListView.setAdapter(assocAdapter);
        assocListView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= assocRows.size()) return;
            String[] row = assocRows.get(pos);
            hideAssoc();
            pickAssoc(row[0]);          // 输入框里的词补全成这个中文
            appendPromptTag(row[1]);    // 同时把英文标签追加到提示词框末尾
        });
        assocListView.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= assocRows.size()) return false;
            copyTag(assocRows.get(pos)[1]);
            return true;                // 长按只复制，不再触发单击
        });
        assocPopup.setContentView(assocListView);
        assocPopup.setBackgroundDrawable(m.bg(Main.FIELD, 10));
        assocPopup.setFocusable(false);            // 不抢输入框焦点，键盘不收
        assocPopup.setTouchable(true);
        assocPopup.setOutsideTouchable(true);      // 点外面消失
        assocPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    }

    /** 点联想项：把光标前那个中文词换成它，光标放到词尾（继续用中文，不换成英文标签） */
    private void pickAssoc(String zh) {
        if (in == null) return;
        android.text.Editable e = in.getText();
        String s = e.toString();
        int end = in.getSelectionEnd();
        if (end < 0 || end > s.length()) end = s.length();
        int start = end;
        while (start > 0 && TrDict.cjk(s.charAt(start - 1))) start--;
        assocSuppress = true;
        try {
            e.replace(start, end, zh);
            in.setSelection(Math.min(start + zh.length(), e.length()));
        } finally { assocSuppress = false; }
        hideAssoc();
        in.requestFocus();                      // 选完继续打字，键盘别收
        try {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    m.getSystemService(Main.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(in, 0);
        } catch (Throwable ignored) { }
    }

    /** 点候选词时把英文标签追加到提示词框末尾（已有同一个标签就不重复加） */
    private void appendPromptTag(String en) {
        if (m.prompt == null) return;
        String add = en == null ? "" : en.trim();
        if (add.isEmpty()) return;
        String cur = m.prompt.getText().toString().trim();
        while (cur.endsWith(",")) cur = cur.substring(0, cur.length() - 1).trim();
        for (String p : cur.split(",")) if (p.trim().equalsIgnoreCase(add)) return;
        String out = cur.isEmpty() ? add : cur + ", " + add;
        m.prompt.setText(out);
        m.prompt.setSelection(m.prompt.getText().length());
    }

    /** 长按候选词：只把英文放进剪贴板 */
    private void copyTag(String en) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) m.getSystemService(Main.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("tag", en));
            Toast.makeText(m, Tr.s(m, "Copied: ", "已复制：") + en, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    private void hideAssoc() {
        if (assocPopup != null && assocPopup.isShowing()) assocPopup.dismiss();
    }

    // ───────────────────────── 管理对话框：模型 / 词典 / 导出导入 ─────────────────────────

    /** ⚙ 菜单：按功能分组，每组一个子菜单 */
    private TrNav nav;

    /** ⚙ 翻译设置：一个多级窗，点外面不关、有返回、不跳页 */
    private void manageDialog() {
        nav = new TrNav(m);
        nav.push(rootPage());
        nav.show();
    }

    private TrNav.Page rootPage() {
        String[] items = {
                Tr.s(m, "ML Kit offline model (35 MB)", "ML Kit 离线模型（35 MB）"),
                Tr.s(m, "Hy-MT local engine (440 MB)", "Hy-MT 本地引擎（440 MB）"),
                Tr.s(m, "Cloud LLM (OpenAI compatible)", "云端 LLM（OpenAI 兼容）"),
                Tr.s(m, "Prompt templates", "提示词模板"),
                Tr.s(m, "Built-in dictionary", "内置词典"),
                Tr.s(m, "My dictionary (edit entries)", "个人词典（增删改）"),
                Tr.s(m, "Repair & self-check", "修复与自检")
        };
        return nav.menu(Tr.s(m, "Translation", "翻译"), items, i -> {
            switch (i) {
                case 0: nav.push(mlkitPage()); break;
                case 1: nav.push(hymtPage()); break;
                case 2: nav.push(cloudPage()); break;
                case 3: nav.push(templatePage()); break;
                case 4: nav.push(dictPage()); break;
                case 5: nav.push(personalPage()); break;
                case 6: nav.push(repairPage()); break;
            }
        });
    }

    private TrNav.Page mlkitPage() {
        String[] items = {
                Tr.s(m, "Download the zh→en model (≈35 MB)", "下载中英模型（约 35 MB）"),
                Tr.s(m, "Delete the downloaded model", "删除已下载的模型"),
                Tr.s(m, "Export the model pack", "导出模型包"),
                Tr.s(m, "Import a model pack", "导入模型包")
        };
        return nav.menu("ML Kit", items, i -> {
            switch (i) {
                case 0: downloadModel(); break;
                case 1: deleteModel(); break;
                case 2: exportPack(); break;
                case 3: pickFile(REQ_PACK, "application/zip"); break;
            }
        });
    }

    private TrNav.Page hymtPage() {
        return nav.menu("Hy-MT", () -> {
            boolean has = TrLlm.installed(m);
            long partial = has ? 0 : TrLlm.partial(m);
            return new String[]{
                    Tr.f(m, "Model: Hy-MT1.5 1.25bit · %s", "模型：Hy-MT1.5 1.25bit · %s", has ? Tr.s(m, "downloaded", "已下载") : Tr.s(m, "not downloaded", "未下载")),
                    has ? Tr.s(m, "Downloaded ✓ (no need to download again)", "已下载 ✓（无需重复下载）")
                        : (partial > 0 ? Tr.f(m, "Download (partial %.0f MB, resumes)", "下载（已有 %.0f MB，会续传）", partial / 1048576.0)
                                       : Tr.s(m, "Download the model (440 MB)", "下载模型（440 MB）")),
                    Tr.s(m, "Delete the model", "删除模型"),
                    Tr.s(m, "System prompt (custom instruction)", "系统提示词（自定义指令）"),
                    Tr.s(m, "Show the engine log", "查看引擎日志")
            };
        }, i -> {
            switch (i) {
                case 0: Toast.makeText(m, Tr.s(m, "Only the 1.25bit build is supported", "当前只支持 1.25bit 版本"), Toast.LENGTH_SHORT).show(); break;
                case 1:
                    if (TrLlm.installed(m)) { Toast.makeText(m, Tr.s(m, "Already downloaded", "已经下载好了"), Toast.LENGTH_SHORT).show(); return; }
                    downloadLlm(); break;
                case 2: deleteLlm(); nav.refresh(); break;
                case 3: nav.push(sysPromptPage()); break;
                case 4: nav.push(llmLogPage()); break;
            }
        });
    }

    private TrNav.Page sysPromptPage() {
        final android.widget.EditText[] e = new android.widget.EditText[1];
        return formPage(Tr.s(m, "System prompt", "系统提示词"), Tr.s(m, "e.g. Translate faithfully, keep names unchanged", "例如：忠实翻译，专有名词保持原样"),
                m.prefs().getString("tr_sys", ""), true, e, v -> {
                    m.prefs().edit().putString("tr_sys", v.trim()).apply();
                    new Thread(() -> { if (TrLlm.ready()) TrLlm.setSystem(v.trim()); }).start();
                    statusLine();
                },
                Tr.s(m, "Leave empty for the official behaviour. This only changes style — it cannot turn sentences into Danbooru tags.",
                        "留空 = 官方默认行为。只能改风格和要求，没法把句子变成 Danbooru 标签。"), null);
    }

    private TrNav.Page llmLogPage() {
        File bin = new File(m.getApplicationInfo().nativeLibraryDir, "libhymt.so");
        StringBuilder sb = new StringBuilder();
        sb.append("bin: ").append(bin.isFile()).append(' ').append(bin.getAbsolutePath()).append('\n');
        sb.append("model: ").append(TrLlm.model(m).length()).append(" B  ").append(TrLlm.model(m).getAbsolutePath()).append('\n');
        sb.append("state: ").append(TrLlm.state(m)).append('\n');
        sb.append("last: ").append(TrLlm.lastError()).append("\n\n== log ==\n");
        tail(TrLlm.logFile(m), sb, 60);
        return nav.text("Hy-MT", sb.toString());
    }

    private TrNav.Page dictPage() {
        return nav.menu(Tr.s(m, "Dictionary", "词典"), () -> new String[]{
                Tr.f(m, "Entries: %d total", "词条：%d 条", TrDict.size()),
                Tr.f(m, "built-in %1$d · CSV %2$d · mine %3$d",
                        "内置 %1$d · CSV %2$d · 个人 %3$d",
                        TrDict.builtinSize(), TrDict.csvSize(), TrDict.mineSize()),
                Tr.s(m, "Import a danbooru CSV (tag,中文)", "导入 danbooru CSV（标签,中文）"),
                (TrDict.csvFirst(m) ? "✓ " : "✗ ") + Tr.s(m, "CSV overrides built-in", "CSV 优先于内置"),
                (assocOn() ? "✓ " : "✗ ") + Tr.s(m, "Suggestions while typing", "输入联想（边打边列词）"),
                Tr.s(m, "Clear the imported CSV", "清空导入的 CSV"),
                Tr.s(m, "Clear the personal dictionary", "清空个人词典"),
                Tr.s(m, "Format examples", "格式示例")
        }, i -> {
            switch (i) {
                case 0: case 1: nav.refresh(); break;
                case 2: pickFile(REQ_CSV, "*/*"); break;
                case 3:
                    final boolean to = !TrDict.csvFirst(m);
                    new Thread(() -> {
                        TrDict.setCsvFirst(m, to);
                        TrEngine.clearCache();
                        m.ui.post(() -> { statusLine(); nav.refresh(); });
                    }, "tr-dict-order").start();
                    break;
                case 4:
                    m.prefs().edit().putBoolean("tr_assoc", !assocOn()).apply();
                    if (!assocOn()) hideAssoc();
                    nav.refresh();
                    break;
                case 5: clearDictLayer(true); nav.refresh(); break;
                case 6: clearDictLayer(false); nav.refresh(); break;
                case 7: nav.push(examplePage()); break;
            }
        });
    }

    /** 词典格式说明：两种文件、方向、多标签、注释、csv 直接导入 */
    private TrNav.Page examplePage() {
        StringBuilder b = new StringBuilder();
        if (Tr.zh(m)) {
            b.append("App 认两种文件，都是 UTF-8 纯文本，一行一条。\n\n");
            b.append("一、个人词典（最高优先级，覆盖一切；只放你手动维护的那几十条）\n");
            b.append("    中文<TAB>english_tag\n");
            b.append("    银发\tsilver_hair\n");
            b.append("    红瞳\tred_eyes\n");
            b.append("    便当\tbento, lunchbox\n\n");
            b.append("二、danbooru CSV（社区大表，默认只补内置没有的词；不要导入个人词典）\n");
            b.append("    english_tag,中文\n");
            b.append("    1girl,1个女孩\n");
            b.append("    long_hair,长发\n\n");
            b.append("说明：\n");
            b.append("· 分隔符用 Tab 或英文逗号都行，哪边是中文会自动判断，方向不用管；\n");
            b.append("· 右边可以写多个标签，用逗号隔开；\n");
            b.append("· # 开头的行是注释；\n");
            b.append("· 中文超过 24 个字会跳过（作品名、角色全名，做匹配没意义）；\n");
            b.append("· 传统 danbooru 中文对照 CSV（10 万行）可以直接导入，App 自动转换；\n");
            b.append("· CSV 里有些机器翻译把角色名当成了通用词（猫女→catwoman、幽灵→gespenst），\n");
            b.append("  所以默认不让它覆盖内置；需要完全按 CSV 走时把「CSV 优先于内置」打开。");
        } else {
            b.append("Two kinds of files, both plain UTF-8, one entry per line.\n\n");
            b.append("1) My dictionary (highest priority, overrides everything)\n");
            b.append("    chinese<TAB>english_tag\n");
            b.append("    银发\tsilver_hair\n");
            b.append("    便当\tbento, lunchbox\n\n");
            b.append("2) danbooru CSV (only fills gaps in the built-in table by default)\n");
            b.append("    english_tag,chinese\n");
            b.append("    1girl,1个女孩\n\n");
            b.append("Notes:\n");
            b.append("· Tab or comma both work; the Chinese column is detected automatically.\n");
            b.append("· Several tags on the right, separated by commas.\n");
            b.append("· Lines starting with # are comments.\n");
            b.append("· Chinese keys longer than 24 characters are skipped.\n");
            b.append("· A traditional danbooru CSV (~100k lines) imports as-is.\n");
            b.append("· Some CSV rows map character names to wrong generic tags (猫女→catwoman),\n");
            b.append("  so the built-in table wins by default. Toggle 「CSV overrides built-in」 to flip it.");
        }
        return nav.text(Tr.s(m, "Dictionary format", "词典格式"), b.toString());
    }

    // ───────────────────────── 个人词典（软件内增删改，优先级最高） ─────────────────────────

    /** tr_user.tsv 的内容，保持插入顺序 */
    private java.util.LinkedHashMap<String, String> loadUserDict() {
        java.util.LinkedHashMap<String, String> d = new java.util.LinkedHashMap<>();
        File f = TrDict.mineFile(m);
        if (!f.isFile()) return d;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isEmpty() || l.charAt(0) == '#') continue;
                int t = l.indexOf('\t');
                if (t <= 0) continue;
                String k = l.substring(0, t).trim(), v = l.substring(t + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) d.put(k, v);
            }
        } catch (Throwable ignored) { }
        return d;
    }

    private boolean saveUserDict(java.util.LinkedHashMap<String, String> d) {
        try (OutputStream os = new FileOutputStream(TrDict.mineFile(m))) {
            StringBuilder b = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : d.entrySet())
                b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            os.write(b.toString().getBytes("UTF-8"));
            return true;
        } catch (Throwable t) { return false; }
    }

    /** 个人词典页：头部导入/导出/新增，下面**分页**列出（每页 50，几万条也不会把界面撑爆），点一条编辑，✕ 删除 */
    private TrNav.Page personalPage() {
        final int[] page = {0};
        return new TrNav.Page() {
            public String title() { return Tr.s(m, "My dictionary", "个人词典"); }
            public View build(final TrNav n) {
                LinearLayout box = new LinearLayout(m);
                box.setOrientation(LinearLayout.VERTICAL);
                final java.util.LinkedHashMap<String, String> d = loadUserDict();
                box.addView(m.text(Tr.f(m, "%d entries · these win over the built-in and CSV tables",
                        "%d 条 · 优先级最高，覆盖内置词典和导入的 CSV", d.size()), 12, Main.DIM, false));

                LinearLayout head = new LinearLayout(m);
                head.setGravity(Gravity.CENTER_VERTICAL);
                Button imp = m.button(Tr.s(m, "Import", "导入"), Main.FIELD, Main.TXT);
                imp.setTextSize(14);
                imp.setOnClickListener(v -> pickFile(REQ_DICT, "*/*"));
                Button exp = m.button(Tr.s(m, "Export", "导出"), Main.FIELD, Main.TXT);
                exp.setTextSize(14);
                exp.setOnClickListener(v -> exportUserDict());
                Button add = m.button(Tr.s(m, "+ Add", "＋ 新增"), Main.ACC, Main.ON_ACC);
                add.setTextSize(14);
                add.setOnClickListener(v -> n.push(dictEditPage(null, null)));
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, m.dp(40), 1);
                head.addView(imp, lp1);
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, m.dp(40), 1);
                lp2.leftMargin = m.dp(6);
                head.addView(exp, lp2);
                LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, m.dp(40), 1);
                lp3.leftMargin = m.dp(6);
                head.addView(add, lp3);
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
                hl.topMargin = m.dp(8);
                hl.bottomMargin = m.dp(10);
                box.addView(head, hl);

                if (d.isEmpty()) {
                    box.addView(m.text(Tr.s(m, "Nothing yet. Tap 「+ Add」; you can also import a .tsv/.csv with 「chinese<TAB>english_tag」 lines and it is merged into this list.",
                            "还没有词条。点「＋ 新增」加一条；也可以导入「中文<TAB>english_tag」格式的 .tsv/.csv，会合并进这个列表。"), 12, Main.DIM, false));
                    return box;
                }

                // 一次只建一页的 View：有人把 10 万条的 CSV 导进过「我的词典」，整表渲染会直接把 App 撑爆
                final int PER = 50;
                final java.util.ArrayList<java.util.Map.Entry<String, String>> all =
                        new java.util.ArrayList<>(d.entrySet());
                final int pages = (all.size() + PER - 1) / PER;
                if (page[0] >= pages) page[0] = pages - 1;
                if (page[0] < 0) page[0] = 0;
                if (pages > 1)
                    box.addView(m.text(Tr.f(m, "page %1$d/%2$d · tap a row to edit", "第 %1$d/%2$d 页 · 点一行编辑", page[0] + 1, pages), 12, Main.DIM, false));
                int from = page[0] * PER, to = Math.min(from + PER, all.size());
                for (int i = from; i < to; i++) {
                    final java.util.Map.Entry<String, String> e = all.get(i);
                    LinearLayout row = new LinearLayout(m);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackground(m.bg(Main.FIELD, 10));
                    TextView t = m.text(e.getKey() + "   →   " + e.getValue(), 14, Main.TXT, false);
                    t.setSingleLine(true);
                    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    t.setPadding(m.dp(12), m.dp(10), m.dp(6), m.dp(10));
                    t.setOnClickListener(v -> n.push(dictEditPage(e.getKey(), e.getValue())));
                    row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView x = m.text("✕", 15, Main.DIM, true);
                    x.setPadding(m.dp(12), m.dp(10), m.dp(14), m.dp(10));
                    x.setOnClickListener(v -> confirmDelete(e.getKey(), n));
                    row.addView(x);
                    LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                    rl.bottomMargin = m.dp(6);
                    box.addView(row, rl);
                }
                if (pages > 1) {
                    LinearLayout pager = new LinearLayout(m);
                    pager.setGravity(Gravity.CENTER_VERTICAL);
                    Button prev = m.button("‹", Main.FIELD, Main.TXT);
                    prev.setEnabled(page[0] > 0);
                    prev.setOnClickListener(v -> { if (page[0] > 0) { page[0]--; n.refresh(); } });
                    pager.addView(prev, new LinearLayout.LayoutParams(0, m.dp(40), 1));
                    TextView mid = m.text(page[0] + 1 + " / " + pages, 12, Main.DIM, false);
                    mid.setGravity(Gravity.CENTER);
                    pager.addView(mid, new LinearLayout.LayoutParams(0, m.dp(40), 1));
                    Button next = m.button("›", Main.FIELD, Main.TXT);
                    next.setEnabled(page[0] < pages - 1);
                    next.setOnClickListener(v -> { if (page[0] < pages - 1) { page[0]++; n.refresh(); } });
                    pager.addView(next, new LinearLayout.LayoutParams(0, m.dp(40), 1));
                    box.addView(pager);
                }
                return box;
            }
        };
    }

    /** 新增 / 编辑一条词条 */
    private TrNav.Page dictEditPage(final String zh0, final String en0) {
        final boolean editing = zh0 != null;
        return new TrNav.Page() {
            public String title() { return editing ? Tr.s(m, "Edit entry", "编辑词条") : Tr.s(m, "New entry", "新增词条"); }
            public View build(final TrNav n) {
                LinearLayout box = new LinearLayout(m);
                box.setOrientation(LinearLayout.VERTICAL);
                box.addView(m.text(Tr.s(m, "Chinese", "中文"), 12, Main.DIM, false));
                final EditText zh = m.field(Tr.s(m, "e.g. 银发", "例如：银发"));
                zh.setText(zh0 == null ? "" : zh0);
                box.addView(zh);
                box.addView(m.text(Tr.s(m, "English tag(s)", "英文标签（多个用逗号隔开）"), 12, Main.DIM, false));
                final EditText en = m.field(Tr.s(m, "e.g. silver_hair", "例如：silver_hair"));
                en.setText(en0 == null ? "" : en0);
                box.addView(en);
                Button save = m.button(Tr.s(m, "Save", "保存"), Main.ACC, Main.ON_ACC);
                save.setOnClickListener(v -> {
                    String k = zh.getText().toString().trim();
                    String val = en.getText().toString().trim();
                    if (k.isEmpty() || val.isEmpty()) { Toast.makeText(m, Tr.s(m, "Fill both fields", "两项都要填"), Toast.LENGTH_SHORT).show(); return; }
                    if (!TrEngine.hasCjk(k)) { Toast.makeText(m, Tr.s(m, "The Chinese column needs Chinese characters", "中文那一栏必须含中文"), Toast.LENGTH_SHORT).show(); return; }
                    if (k.length() > 24) { Toast.makeText(m, Tr.s(m, "Keep it under 24 characters", "中文别超过 24 个字"), Toast.LENGTH_SHORT).show(); return; }
                    java.util.LinkedHashMap<String, String> d = loadUserDict();
                    if (editing && !k.equals(zh0)) d.remove(zh0);   // 改了中文键：旧键删掉，避免留一条重复的
                    d.put(k, val);
                    final boolean ok = saveUserDict(d);
                    n.pop();
                    refreshDictAsync(ok, null, Tr.s(m, "Saved", "已保存"));
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, m.dp(44));
                lp.topMargin = m.dp(12);
                box.addView(save, lp);
                return box;
            }
        };
    }

    private void confirmDelete(final String zh, final TrNav n) {
        m.builder().setTitle(Tr.s(m, "Delete this entry?", "删除这条词条？"))
                .setMessage(zh)
                .setPositiveButton(Tr.s(m, "Delete", "删除"), (d, w) -> {
                    java.util.LinkedHashMap<String, String> map = loadUserDict();
                    if (map.remove(zh) != null) refreshDictAsync(saveUserDict(map), n, Tr.s(m, "Deleted", "已删除"));
                })
                .setNegativeButton(Tr.s(m, "Cancel", "取消"), null).show();
    }

    /** 写盘之后重建内存里的词表（11 万条，放后台），好了再刷新页面 */
    private void refreshDictAsync(final boolean ok, final TrNav n, final String msg) {
        if (!ok) { Toast.makeText(m, Tr.s(m, "Write failed", "写入失败"), Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            TrDict.reset();
            TrDict.ensure(m);
            TrEngine.clearCache();
            m.ui.post(() -> {
                if (n != null) n.refresh();
                statusLine();
                if (msg != null) Toast.makeText(m, msg, Toast.LENGTH_SHORT).show();
            });
        }, "tr-userdict").start();
    }

    /** 导出个人词典到 Download（MediaStore，不需要存储权限） */
    private void exportUserDict() {
        final File f = TrDict.mineFile(m);
        if (!f.isFile() || f.length() == 0) {
            Toast.makeText(m, Tr.s(m, "No entries yet", "还没有词条"), Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String err = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, "hexanima_user_dict.tsv");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/tab-separated-values");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri out = m.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (out == null) throw new IOException("no Download entry");
                try (InputStream is = new FileInputStream(f); OutputStream os = m.getContentResolver().openOutputStream(out)) {
                    byte[] b = new byte[65536];
                    for (int r; (r = is.read(b)) > 0; ) os.write(b, 0, r);
                }
            } catch (Throwable t) { err = String.valueOf(t); }
            final String e = err;
            m.ui.post(() -> Toast.makeText(m, e == null
                    ? Tr.s(m, "Exported → Download/hexanima_user_dict.tsv", "已导出 → Download/hexanima_user_dict.tsv")
                    : e, Toast.LENGTH_LONG).show());
        }, "tr-dict-export").start();
    }

    private TrNav.Page repairPage() {
        String[] items = {
                Tr.s(m, "Network self-test (report → Download)", "网络自检（报告存到 Download）"),
                Tr.s(m, "Storage self-check (report → Download)", "存储自检（报告存到 Download）"),
                Tr.s(m, "Clear derived caches (LoRA cache, temp)", "清理派生缓存（LoRA 缓存 / 临时文件）"),
                Tr.s(m, "Repair the shared model files (vae.bin etc.)", "修复共用模型文件（vae.bin 等）")
        };
        return nav.menu(Tr.s(m, "Repair & self-check", "修复与自检"), items, i -> {
            switch (i) {
                case 0: diag(); break;
                case 1: storageDiag(); break;
                case 2: clearCaches(); break;
                case 3: repairCommon(); break;
            }
        });
    }

    // ───────────────────────── 云端 LLM ─────────────────────────

    private TrNav.Page cloudPage() {
        return nav.menu(Tr.s(m, "Cloud LLM", "云端 LLM"), () -> new String[]{
                Tr.s(m, "Provider preset", "服务商预设"),
                Tr.f(m, "API base URL: %s", "API 地址：%s", shortUrl(TrCloud.base(m))),
                Tr.f(m, "API key: %s", "API Key：%s", maskKey(TrCloud.key(m))),
                Tr.f(m, "Model: %s", "模型：%s", TrCloud.model(m)),
                Tr.f(m, "Temperature: %.2f", "温度：%.2f", TrCloud.temp(m)),
                Tr.f(m, "Max output tokens: %d", "最大输出 tokens：%d", TrCloud.maxTokens(m)),
                Tr.f(m, "Reasoning effort: %s", "思考强度：%s", effortName(TrCloud.effort(m))),
                Tr.s(m, "Extra JSON (advanced)", "附加 JSON（高级）"),
                Tr.s(m, "Test connection", "测试连接")
        }, i -> {
            switch (i) {
                case 0: nav.push(presetPage()); break;
                case 1: nav.push(formPage(Tr.s(m, "API base URL", "API 地址"), "https://api.deepseek.com/v1", TrCloud.base(m), false, null, v -> {
                    TrCloud.save(m, v, TrCloud.key(m), TrCloud.model(m), TrCloud.temp(m), TrCloud.topP(m), TrCloud.maxTokens(m), TrCloud.extra(m));
                    statusLine(); }, null, null)); break;
                case 2: nav.push(formPage(Tr.s(m, "API key", "API Key"), "sk-…", TrCloud.key(m), false, null, v -> {
                    TrCloud.save(m, TrCloud.base(m), v, TrCloud.model(m), TrCloud.temp(m), TrCloud.topP(m), TrCloud.maxTokens(m), TrCloud.extra(m));
                }, null, null)); break;
                case 3: nav.push(modelPage()); break;
                case 4: nav.push(formPage(Tr.s(m, "Temperature 0–2", "温度 0–2"), "0.3", String.valueOf(TrCloud.temp(m)), false, null, v -> {
                    try { float f = Float.parseFloat(v.trim().replace(',', '.')); f = Math.max(0f, Math.min(2f, f));
                        TrCloud.save(m, TrCloud.base(m), TrCloud.key(m), TrCloud.model(m), f, TrCloud.topP(m), TrCloud.maxTokens(m), TrCloud.extra(m)); } catch (Exception ignored) { }
                }, null, null)); break;
                case 5: nav.push(formPage(Tr.s(m, "Max output tokens", "最大输出 tokens"), "1024", String.valueOf(TrCloud.maxTokens(m)), false, null, v -> {
                    try { int n = Integer.parseInt(v.trim()); n = Math.max(64, Math.min(8192, n));
                        TrCloud.save(m, TrCloud.base(m), TrCloud.key(m), TrCloud.model(m), TrCloud.temp(m), TrCloud.topP(m), n, TrCloud.extra(m)); } catch (Exception ignored) { }
                }, null, null)); break;
                case 6: nav.push(effortPage()); break;
                case 7: nav.push(formPage(Tr.s(m, "Extra JSON", "附加 JSON"), "{\"repetition_penalty\":1.05}", TrCloud.extra(m), true, null, v -> {
                    String t = v.trim();
                    if (!t.isEmpty()) { try { new org.json.JSONObject(t); } catch (Exception e) { Toast.makeText(m, Tr.s(m, "Not valid JSON", "不是合法 JSON"), Toast.LENGTH_SHORT).show(); return; } }
                    TrCloud.save(m, TrCloud.base(m), TrCloud.key(m), TrCloud.model(m), TrCloud.temp(m), TrCloud.topP(m), TrCloud.maxTokens(m), t);
                }, null, null)); break;
                case 8: testCloud(); break;
            }
        });
    }

    private static String shortUrl(String u) {
        return u.length() > 42 ? u.substring(0, 39) + "…" : u;
    }

    private String maskKey(String k) {
        if (k.isEmpty()) return Tr.s(m, "(not set)", "（未填）");
        return k.length() <= 8 ? "…" : ("…" + k.substring(k.length() - 4));
    }

    private String effortName(String e) {
        switch (e) {
            case "off": return Tr.s(m, "not sent", "不发送");
            case "medium": return Tr.s(m, "medium", "中");
            case "high": return Tr.s(m, "high", "高");
            default: return Tr.s(m, "low (default)", "低（默认）");
        }
    }

    private TrNav.Page effortPage() {
        final String[] ids = {"off", "low", "medium", "high"};
        final String[] names = {Tr.s(m, "Not sent (server default)", "不发送（用服务商默认）"), Tr.s(m, "Low (default)", "低（默认）"),
                Tr.s(m, "Medium", "中"), Tr.s(m, "High", "高")};
        int cur = 1;
        String e = TrCloud.effort(m);
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(e)) cur = i;
        final int c = cur;
        return new TrNav.Page() {
            public String title() { return Tr.s(m, "Reasoning effort", "思考强度"); }
            public View build(TrNav n) {
                LinearLayout box = new LinearLayout(m);
                box.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < names.length; i++) {
                    final int idx = i;
                    box.addView(n.row((i == c ? "✓ " : "   ") + names[i], v -> {
                        TrCloud.setEffort(m, ids[idx]);
                        Toast.makeText(m, Tr.f(m, "Reasoning effort: %s", "思考强度：%s", effortName(ids[idx])), Toast.LENGTH_SHORT).show();
                        n.pop();
                    }));
                }
                TextView note = m.text(Tr.s(m, "DeepSeek: thinking on with this effort; Qwen/GLM use their own switch. Extra JSON overrides it.",
                        "DeepSeek：按这个强度开启思考；通义/GLM 用各自的开关。附加 JSON 会覆盖这里的设置。"), 11, Main.DIM, false);
                note.setPadding(m.dp(8), m.dp(6), m.dp(8), 0);
                box.addView(note);
                return box;
            }
        };
    }

    private TrNav.Page presetPage() {
        String[] names = new String[TrCloud.PRESETS.length];
        for (int i = 0; i < names.length; i++) names[i] = TrCloud.PRESETS[i][0];
        final String[] cur = new String[1];
        cur[0] = TrCloud.base(m);
        return nav.menu(Tr.s(m, "Provider", "服务商"), names, i -> {
            String[] pr = TrCloud.PRESETS[i];
            if (!pr[1].isEmpty()) {
                TrCloud.setProvider(m, pr[3]);
                TrCloud.save(m, pr[1], TrCloud.key(m), pr[2], TrCloud.temp(m), TrCloud.topP(m), TrCloud.maxTokens(m), TrCloud.extra(m));
                Toast.makeText(m, Tr.f(m, "Filled the %s defaults", "已填入 %s 的默认值", pr[0]), Toast.LENGTH_SHORT).show();
            } else {
                TrCloud.setProvider(m, "custom");
            }
            nav.pop();
            statusLine();
        });
    }

    private TrNav.Page modelPage() {
        final java.util.List<String> got = new java.util.ArrayList<>();
        final String[] err = new String[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] loaded = {false};
        final int[] page = {0};
        final android.widget.EditText manual = m.field(Tr.s(m, "Model id", "模型名"));
        manual.setText(TrCloud.model(m));
        manual.setSingleLine(true);
        new Thread(() -> { got.addAll(TrCloud.models(m, err)); latch.countDown(); }, "models").start();
        return new TrNav.Page() {
            public String title() { return Tr.s(m, "Model", "模型"); }
            public View build(TrNav n) {
                LinearLayout box = new LinearLayout(m);
                box.setOrientation(LinearLayout.VERTICAL);
                if (!loaded[0]) {                     // 还没拉到：显示提示，拉完刷新整个页面
                    box.addView(m.text(Tr.s(m, "Fetching the model list…", "正在拉取模型列表…"), 12, Main.DIM, false));
                    new Thread(() -> {
                        try { latch.await(); } catch (InterruptedException ignored) { }
                        m.ui.post(() -> { loaded[0] = true; n.refresh(); });
                    }, "models-ui").start();
                } else if (got.isEmpty()) {
                    box.addView(m.text(Tr.s(m, "No list from the server", "服务器没有返回列表")
                            + (err[0] == null ? "" : "：" + err[0]), 12, Main.DIM, false));
                } else {
                    // 中转站/集合站的模型列表能有几百个，一页 20 个，底部分页
                    final int PER = 20;
                    final int pages = (got.size() + PER - 1) / PER;
                    if (page[0] >= pages) page[0] = pages - 1;
                    if (page[0] < 0) page[0] = 0;
                    box.addView(m.text(Tr.f(m, "%1$d models · page %2$d/%3$d", "共 %1$d 个模型 · 第 %2$d/%3$d 页",
                            got.size(), page[0] + 1, pages), 12, Main.DIM, false));
                    int from = page[0] * PER, to = Math.min(from + PER, got.size());
                    for (int i = from; i < to; i++) {
                        final String id = got.get(i);
                        box.addView(n.row(id, v -> {
                            TrCloud.save(m, TrCloud.base(m), TrCloud.key(m), id, TrCloud.temp(m), TrCloud.topP(m), TrCloud.maxTokens(m), TrCloud.extra(m));
                            manual.setText(id);                       // 点列表也要填进输入框，不然看着像没反应
                            Toast.makeText(m, id, Toast.LENGTH_SHORT).show();
                        }));
                    }
                    if (pages > 1) {
                        LinearLayout pager = new LinearLayout(m);
                        pager.setGravity(Gravity.CENTER_VERTICAL);
                        Button prev = m.button("‹", Main.FIELD, Main.TXT);
                        prev.setEnabled(page[0] > 0);
                        prev.setOnClickListener(v -> { if (page[0] > 0) { page[0]--; n.refresh(); } });
                        pager.addView(prev, new LinearLayout.LayoutParams(0, m.dp(40), 1));
                        TextView mid = m.text(page[0] + 1 + " / " + pages, 12, Main.DIM, false);
                        mid.setGravity(Gravity.CENTER);
                        pager.addView(mid, new LinearLayout.LayoutParams(0, m.dp(40), 1));
                        Button next = m.button("›", Main.FIELD, Main.TXT);
                        next.setEnabled(page[0] < pages - 1);
                        next.setOnClickListener(v -> { if (page[0] < pages - 1) { page[0]++; n.refresh(); } });
                        pager.addView(next, new LinearLayout.LayoutParams(0, m.dp(40), 1));
                        box.addView(pager);
                    }
                }
                box.addView(m.text(Tr.s(m, "Or type it", "或手动填写"), 12, Main.DIM, false));
                // manual 是跨次复用的同一个 EditText（要保留用户输入）：翻页/刷新重建页面时
                // 它可能还挂在上一份容器上，addView 前必须先摘下来，否则直接 IllegalStateException 闪退
                if (manual.getParent() instanceof ViewGroup)
                    ((ViewGroup) manual.getParent()).removeView(manual);
                box.addView(manual);
                TextView save = m.button(Tr.s(m, "Save", "保存"), Main.ACC, Main.ON_ACC);
                save.setOnClickListener(v -> {
                    String id = manual.getText().toString().trim();
                    if (id.isEmpty()) { Toast.makeText(m, Tr.s(m, "Empty", "不能为空"), Toast.LENGTH_SHORT).show(); return; }
                    TrCloud.save(m, TrCloud.base(m), TrCloud.key(m), id, TrCloud.temp(m), TrCloud.topP(m), TrCloud.maxTokens(m), TrCloud.extra(m));
                    n.pop();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, m.dp(44));
                lp.topMargin = m.dp(10);
                box.addView(save, lp);
                return box;
            }
        };
    }


    private void testCloud() {
        status.setText(Tr.s(m, "testing…", "测试中…"));
        final TrTpl tpl = TrTpl.current(m);
        new Thread(() -> TrCloud.translate(m, tpl, "银发红瞳的少女在樱花树下微笑", (text, err) -> m.ui.post(() -> {
            statusLine();
            nav.push(nav.text(Tr.s(m, "Cloud test", "云端测试"),
                    text != null ? ("✓ " + text + "\n\nmodel: " + TrCloud.model(m) + "\neffort: " + TrCloud.effort(m))
                                 : ("✗ " + err + "\n\nbase: " + TrCloud.base(m) + "\nmodel: " + TrCloud.model(m))));
        })), "cloud-test").start();
    }

    // ───────────────────────── 模板 ─────────────────────────

    private TrNav.Page templatePage() {
        return nav.menu(Tr.s(m, "Prompt templates", "提示词模板"), () -> {
            TrTpl cur = TrTpl.current(m);
            return new String[]{
                    Tr.f(m, "Current: %s %s", "当前模板：%s %s", cur.name, cur.tag(m)),
                    Tr.s(m, "Choose another template", "选择其他模板"),
                    Tr.s(m, "Edit the current template", "编辑当前模板"),
                    Tr.s(m, "New template", "新建模板"),
                    Tr.s(m, "Rename the current template", "重命名当前模板"),
                    Tr.s(m, "Delete the current template", "删除当前模板")
            };
        }, i -> {
            switch (i) {
                case 0: nav.refresh(); break;
                case 1: nav.push(chooseTemplatePage()); break;
                case 2: nav.push(editTemplatePage(TrTpl.current(m))); break;
                case 3: nav.push(newTemplatePage()); break;
                case 4: {
                    TrTpl cur = TrTpl.current(m);
                    nav.push(formPage(Tr.s(m, "Name", "名称"), "", cur.name, false, null, v -> {
                        if (!v.trim().isEmpty()) { cur.name = v.trim(); TrTpl.update(m, cur); }
                    }, null, null));
                    break;
                }
                case 5: confirmDeleteTemplate(TrTpl.current(m)); break;
            }
        });
    }

    private TrNav.Page chooseTemplatePage() {
        final java.util.List<TrTpl> list = TrTpl.all(m);
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).name + "  " + list.get(i).tag(m);
        return nav.menu(Tr.s(m, "Templates", "模板"), labels, i -> {
            TrTpl.select(m, list.get(i));
            nav.pop();
            statusLine();
        });
    }

    private TrNav.Page newTemplatePage() {
        String[] modes = {Tr.s(m, "Simple (system + user)", "极简（系统 + 用户）"), Tr.s(m, "Advanced (messages JSON)", "高级（messages JSON）")};
        return nav.menu(Tr.s(m, "New template", "新建模板"), modes, i -> {
            TrTpl t = new TrTpl(TrTpl.newId(), Tr.s(m, "New template", "新模板"), i);
            if (i == TrTpl.ADVANCED) t.adv = "[\n  {\"role\":\"user\",\"content\":\"$1\"}\n]";
            else t.user = "$1";
            TrTpl.add(m, t);
            TrTpl.select(m, t);
            nav.pop();
            nav.push(editTemplatePage(t));
        });
    }

    private void confirmDeleteTemplate(final TrTpl t) {
        m.builder().setTitle(Tr.s(m, "Delete template", "删除模板"))
                .setMessage(Tr.f(m, "Delete \"%s\"?", "删除「%s」？", t.name))
                .setPositiveButton(Tr.s(m, "Delete", "删除"), (d, w) -> { TrTpl.remove(m, t); nav.pop(); statusLine(); })
                .setNegativeButton(Tr.s(m, "Cancel", "取消"), null).show();
    }

    private TrNav.Page editTemplatePage(final TrTpl t) {
        return new TrNav.Page() {
            public String title() { return t.mode == TrTpl.SIMPLE ? Tr.s(m, "Simple template", "极简模板") : Tr.s(m, "Advanced template", "高级模板"); }
            public View build(TrNav n) {
                LinearLayout box = new LinearLayout(m);
                box.setOrientation(LinearLayout.VERTICAL);

                final android.widget.EditText name = m.field(Tr.s(m, "Name", "名称"));
                name.setSingleLine(true); name.setText(t.name);
                box.addView(m.text(Tr.s(m, "Name", "名称"), 13, Main.TXT, false));
                box.addView(name);

                if (t.mode == TrTpl.SIMPLE) {
                    final android.widget.EditText sys = m.field(Tr.s(m, "System prompt (optional)", "系统提示词（可空）"));
                    sys.setMinLines(3); sys.setGravity(Gravity.TOP); sys.setText(t.system);
                    box.addView(m.text(Tr.s(m, "System", "系统提示词"), 13, Main.TXT, false));
                    box.addView(sys);
                    final android.widget.EditText usr = m.field(Tr.s(m, "User prompt with $1", "用户提示词（用 $1 表示原文）"));
                    usr.setMinLines(4); usr.setGravity(Gravity.TOP); usr.setText(t.user);
                    box.addView(m.text(Tr.s(m, "User ($1 = the text from the box)", "用户提示词（$1 = 翻译框内容）"), 13, Main.TXT, false));
                    box.addView(usr);
                    TextView save = m.button(Tr.s(m, "Save", "保存"), Main.ACC, Main.ON_ACC);
                    save.setOnClickListener(v -> {
                        if (!name.getText().toString().trim().isEmpty()) t.name = name.getText().toString().trim();
                        t.system = sys.getText().toString();
                        t.user = usr.getText().toString();
                        TrTpl.update(m, t);
                        n.pop();
                        statusLine();
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, m.dp(44));
                    lp.topMargin = m.dp(10);
                    box.addView(save, lp);
                } else {
                    final android.widget.EditText adv = m.field(Tr.s(m, "messages JSON", "messages JSON"));
                    adv.setMinLines(8); adv.setGravity(Gravity.TOP); adv.setText(t.adv);
                    LinearLayout head = new LinearLayout(m);
                    head.setGravity(Gravity.CENTER_VERTICAL);
                    head.addView(m.text(Tr.s(m, "messages JSON ($1 supported)", "messages JSON（支持 $1）"), 13, Main.TXT, false), new LinearLayout.LayoutParams(0, -2, 1));
                    TextView q = m.text("❔", 16, Main.ACC, true);
                    q.setPadding(m.dp(8), m.dp(4), 0, m.dp(4));
                    q.setOnClickListener(v -> nav.push(nav.text(Tr.s(m, "How to write it", "写法说明"), ADV_HELP(m))));
                    head.addView(q);
                    box.addView(head);
                    box.addView(adv);
                    TextView save = m.button(Tr.s(m, "Save", "保存"), Main.ACC, Main.ON_ACC);
                    save.setOnClickListener(v -> {
                        final String a = adv.getText().toString();
                        if (!name.getText().toString().trim().isEmpty()) t.name = name.getText().toString().trim();
                        try {
                            new org.json.JSONArray(a);
                            t.adv = a; TrTpl.update(m, t); n.pop(); statusLine();
                        } catch (Exception e) {
                            m.builder().setTitle(Tr.s(m, "JSON error", "JSON 有误"))
                                    .setMessage(String.valueOf(e.getMessage()) + "\n\n" + Tr.s(m, "Save anyway?", "仍然保存吗？"))
                                    .setPositiveButton(Tr.s(m, "Save anyway", "仍然保存"), (d2, w2) -> {
                                        t.adv = a; TrTpl.update(m, t); n.pop(); statusLine();
                                    })
                                    .setNegativeButton(Tr.s(m, "Back", "返回修改"), null).show();
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, m.dp(44));
                    lp.topMargin = m.dp(10);
                    box.addView(save, lp);
                }
                return box;
            }
        };
    }

    /** 通用表单页：一个输入框 + 保存 */
    private TrNav.Page formPage(String title, String hint, String value, boolean multiline,
                                android.widget.EditText[] out, java.util.function.Consumer<String> save,
                                String note, Runnable onReset) {
        return new TrNav.Page() {
            public String title() { return title; }
            public View build(TrNav n) {
                LinearLayout box = new LinearLayout(m);
                box.setOrientation(LinearLayout.VERTICAL);
                final android.widget.EditText e = m.field(hint);
                e.setText(value);
                if (multiline) { e.setMinLines(5); e.setGravity(Gravity.TOP); } else e.setSingleLine(true);
                box.addView(e);
                if (note != null) {
                    TextView t = m.text(note, 11, Main.DIM, false);
                    t.setPadding(0, m.dp(6), 0, 0);
                    box.addView(t);
                }
                TextView b = m.button(Tr.s(m, "Save", "保存"), Main.ACC, Main.ON_ACC);
                b.setOnClickListener(v -> { save.accept(e.getText().toString()); n.pop(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, m.dp(44));
                lp.topMargin = m.dp(10);
                box.addView(b, lp);
                if (onReset != null) {
                    TextView r = m.text(Tr.s(m, "Reset", "恢复默认"), 13, Main.ACC, true);
                    r.setPadding(m.dp(12), m.dp(10), m.dp(12), 0);
                    r.setOnClickListener(v -> onReset.run());
                    box.addView(r);
                }
                return box;
            }
        };
    }

    private static String ADV_HELP(Main m) {
        return Tr.s(m,
                "Each message is one object: {\"role\":\"...\",\"content\":\"...\"}\n"
                        + "role must be system / user / assistant; system usually goes first.\n"
                        + "$1 = the text from the translation box; it may appear in any content, any number of times.\n"
                        + "Without $1 the text is appended to the last user message.\n"
                        + "Newlines and double quotes inside content must be escaped.\n\n"
                        + "Minimal:\n[{\"role\":\"user\",\"content\":\"$1\"}]\n\n"
                        + "System + user:\n[{\"role\":\"system\",\"content\":\"You are a translation engine.\"},{\"role\":\"user\",\"content\":\"$1\"}]\n\n"
                        + "Few-shot (assistant turns are examples):\n[{\"role\":\"system\",\"content\":\"You are a translation engine.\"},{\"role\":\"user\",\"content\":\"银发\"},{\"role\":\"assistant\",\"content\":\"silver_hair\"},{\"role\":\"user\",\"content\":\"$1\"}]",
                "每条消息是一个对象：{\"role\":\"...\",\"content\":\"...\"}\n"
                        + "role 只能是 system / user / assistant；system 一般放最前面。\n"
                        + "$1 = 翻译框里的内容，可以出现在任何 content 里、可以出现多次。\n"
                        + "没写 $1 时，原文会被追加到最后一条 user 消息。\n"
                        + "content 里的换行和双引号需要转义（反斜杠）。\n\n"
                        + "最小：\n[{\"role\":\"user\",\"content\":\"$1\"}]\n\n"
                        + "系统 + 用户：\n[{\"role\":\"system\",\"content\":\"你是翻译引擎……\"},{\"role\":\"user\",\"content\":\"$1\"}]\n\n"
                        + "少样本（assistant 是示范）：\n[{\"role\":\"system\",\"content\":\"你是翻译引擎……\"},{\"role\":\"user\",\"content\":\"银发\"},{\"role\":\"assistant\",\"content\":\"silver_hair\"},{\"role\":\"user\",\"content\":\"$1\"}]");
    }

    private void downloadModel() {
        status.setText(Tr.s(m, "downloading the model…", "正在下载模型…"));
        TrMlKit.download(m, r -> {
            statusLine();
            if (r.equals("ok")) { Toast.makeText(m, Tr.s(m, "Model downloaded, offline from now on", "模型下载完成，之后完全离线"), Toast.LENGTH_LONG).show(); if (nav != null) nav.refresh(); return; }
            showText(Tr.s(m, "Download failed", "下载失败"),
                    r + "\n\n" + Tr.s(m, "init: ", "初始化：") + TrMlKit.initDetail
                            + "\n\n" + Tr.s(m, "Run the network self-test in the same menu to see whether the app can reach Google.",
                            "在同一个菜单里点「网络自检」，就能看出是这个 App 上不了网，还是 SDK 自己的问题。"));
        });
    }

    private void showText(String title, String body) {
        TextView tv = m.text(body, 12, Main.TXT, false);
        tv.setTextIsSelectable(true);
        tv.setPadding(m.dp(16), m.dp(8), m.dp(16), m.dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(m);
        sv.addView(tv);
        m.builder().setTitle(title).setView(sv).setPositiveButton("OK", null).show();
    }

    /** 从 App 自己发几个请求：能分清「这个 App 没走代理」和「SDK 内部出错」 */
    private void diag() {
        final StringBuilder head = new StringBuilder();
        head.append("init: ").append(TrMlKit.initDetail).append('\n');
        head.append("state: ").append(TrMlKit.state()).append("  (0=missing 1=ready 2=unknown -1=nosdk)\n");
        head.append("dict: ").append(TrDict.size()).append('\n');
        head.append("proxy: ").append(System.getProperty("http.proxyHost", "-")).append('\n');
        head.append('\n');
        showText(Tr.s(m, "Diagnostics", "诊断"), head + Tr.s(m, "testing the network…", "正在测网络…"));
        new Thread(() -> {
            String[] urls = {
                    "https://dl.google.com/translate/offline/v5/high/r29/en_zh.zip",
                    "https://dl.google.com/",
                    "https://www.google.com/generate_204",
                    "https://redirector.gvt1.com/"
            };
            StringBuilder out = new StringBuilder(head);
            for (String u : urls) out.append(probe(u)).append('\n');
            final String text = out.toString();
            saveDiag(text);
            m.ui.post(() -> showText(Tr.s(m, "Diagnostics", "诊断"), text));
        }).start();
    }

    private static String probe(String url) {
        long t0 = System.currentTimeMillis();
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            String len = c.getHeaderField("content-length");
            String loc = c.getHeaderField("location");
            return url + "\n  → HTTP " + code + (len != null ? "  len=" + len : "") + (loc != null ? "  → " + loc : "")
                    + "  (" + (System.currentTimeMillis() - t0) + " ms)";
        } catch (Throwable t) {
            return url + "\n  → " + TrMlKit.chain(t) + "  (" + (System.currentTimeMillis() - t0) + " ms)";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 共用文件（vae.bin / te*.bin，所有模型都用）被写坏时：删掉「已安装」标记和坏的 vae.bin，
     *  再去模型页下载任意一个模型，installCommon 就会按 manifest 的 size/sha 把缺的补回来（其它文件大小对得上会跳过） */
    private void repairCommon() {
        final File anima = new File(m.getFilesDir(), "anima");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            // 先把可能卡在“半加载”状态的 animad 停掉：不然修完文件，下一次「加载」仍会被
            // pidof 误判成已加载，看起来就是“修复了也没用”
            m.su("sh " + new File(anima, "anima.sh") + " stop < /dev/null >/dev/null 2>&1", false);
            for (String d : new String[]{"common", "common_v69", "common_v73", "common_v75", "common_v79", "common_v81"}) {
                File f = new File(anima, d);
                if (f.isDirectory()) { del(f); sb.append("removed ").append(d).append("/\n"); }
            }
            File vae = new File(anima, "vae.bin");
            if (vae.isFile()) { long n = vae.length(); sb.append("vae.bin was ").append(n).append(" B, deleted\n"); vae.delete(); }
            else sb.append("vae.bin: not present\n");
            // 派生缓存和上次运行留下的中间文件一起清：它们都会自动重建，但残留的坏文件
            // 会让「重新下载模型」看起来也没用
            int cached = 0;
            for (String d : new String[]{"lcache", "tecache", "req", "sdq"}) {
                File f = new File(anima, d);
                if (f.isDirectory()) { del(f); cached++; }
            }
            for (String f : new String[]{"chain_x0.raw", "chain_x0.x32", "chain_x1.x32", "chain_x2.raw",
                    "in_x.raw", "st_ctx.raw", "te_out.raw", "ad_in.png", "cancel", "load_error", "te_llm"}) {
                File x = new File(anima, f);
                if (x.isFile()) { x.delete(); cached++; }
            }
            sb.append("derived caches: ").append(cached).append(" item(s) cleared\n");
            sb.append("\n== animad.log (tail) ==\n");
            tail(new File(anima, "animad.log"), sb, 15);
            sb.append('\n').append(Tr.s(m, "Now open the model page and download a model again: only the missing shared files are fetched (the model weights are skipped by size).",
                    "现在去模型页重新下载任意一个模型：只会补下缺失的共用文件，模型权重因为大小对得上会被跳过。"));
            final String out = sb.toString();
            m.ui.post(() -> showText(Tr.s(m, "Repair shared files", "修复共用文件"), out));
        }, "repair").start();
    }

    /** 只删可再生的东西：LoRA 折叠缓存、文本编码器缓存、上一次运行的中间文件。模型本体（m_ 目录、sd 目录、te*.bin）一律不动 */
    private void clearCaches() {
        File A = new File(m.getFilesDir(), "anima");
        String[] dirs = {"lcache", "tecache", "req", "sdq"};
        String[] files = {"chain_x0.raw", "chain_x0.x32", "chain_x1.x32", "chain_x2.raw", "in_x.raw", "st_ctx.raw", "te_out.raw", "ad_in.png", "cancel", "quit", "te_llm", "model"};
        long freed = 0;
        int n = 0;
        for (String d : dirs) {
            File f = new File(A, d);
            if (f.isDirectory()) { freed += dirSize(f); del(f); n++; }
        }
        for (String f : files) {
            File x = new File(A, f);
            if (x.isFile()) { freed += x.length(); if (x.delete()) n++; }
        }
        final String msg = Tr.f(m, "%d items cleared, %.1f MB freed", "已清理 %d 项，释放 %.1f MB", n, freed / 1048576.0);
        showText(Tr.s(m, "Caches", "清理缓存"), msg + "\n\n" + Tr.s(m, "Now try loading the model again.", "现在再点一次加载模型。"));
    }

    private static long dirSize(File d) {
        long[] a = new long[1];
        count(d, a);
        return a[0];
    }

    private static void del(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) del(x);
        f.delete();
    }

    /** 列出 files/ 和 no_backup/ 的目录构成 + 大文件（用来判断模型文件是不是被写坏了） */
    private void storageDiag() {
        StringBuilder sb = new StringBuilder();
        File files = m.getFilesDir();
        sb.append("filesDir: ").append(files.getAbsolutePath()).append('\n');
        sb.append("usable: ").append(files.getUsableSpace() / 1048576).append(" MB\n\n");
        sb.append("== files/ ==\n");
        tree(files, sb, 0, 2);
        sb.append("\n== no_backup/ ==\n");
        tree(m.getNoBackupFilesDir(), sb, 0, 2);
        File A = new File(files, "anima");
        sb.append("\n== common files (every Anima model needs these) ==\n");
        for (String n : new String[]{"vae.bin", "te0.bin", "te1.bin", "te2.bin", "te3.bin", "te_embed.f16", "taew2_1.safetensors"}) {
            File f = new File(n.startsWith("taew2") ? new File(files, "sd") : A, n);
            sb.append(n).append(": ").append(f.isFile() ? (f.length() + " B") : "MISSING").append('\n');
        }
        sb.append("\n== load_error ==\n");
        tail(new File(A, "load_error"), sb, 40);
        sb.append("\n== animad.log ==\n");
        tail(new File(A, "animad.log"), sb, 200);
        sb.append("\n== model dirs ==\n");
        File[] ms = A.listFiles();
        if (ms != null) for (File d : ms) {
            if (!d.isDirectory() || !d.getName().startsWith("m_")) continue;
            sb.append(d.getName()).append("/\n");
            File[] fs = d.listFiles();
            if (fs == null) continue;
            java.util.Arrays.sort(fs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            for (File f : fs) {
                if (f.isDirectory()) sb.append("   ").append(f.getName()).append("/  ").append(dirSize(f) / 1048576).append(" MB\n");
                else sb.append("   ").append(f.getName()).append("  ").append(f.length() / 1048576).append(" MB  (").append(f.length()).append(" B)\n");
            }
        }
        sb.append("\n== sdcli.log (tail) ==\n");
        tail(new File(A, "sdcli.log"), sb, 30);
        final String text = sb.toString();
        saveAs(text, "hexanima_storage.txt");
        showText(Tr.s(m, "Storage", "存储自检"), text);
    }

    /** 读日志/错误文件的最后几行（总长限制在 12 KB，免得报告太大） */
    private static void tail(File f, StringBuilder sb, int lines) {
        if (f == null || !f.isFile()) { sb.append("(none)\n"); return; }
        sb.append(f.getName()).append("  ").append(f.length()).append(" B  ")
                .append(new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(f.lastModified()))).append('\n');
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            for (String l; (l = r.readLine()) != null; ) { q.addLast(l); if (q.size() > lines) q.removeFirst(); }
        } catch (Throwable t) { sb.append("read failed: ").append(TrMlKit.chain(t)).append('\n'); return; }
        int total = 0;
        for (String l : q) {
            if (total > 12000) { sb.append("…(truncated)\n"); break; }
            sb.append(l).append('\n');
            total += l.length() + 1;
        }
    }

    private static void tree(File d, StringBuilder sb, int depth, int maxDepth) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : fs) {
            for (int i = 0; i < depth; i++) sb.append("  ");
            if (f.isDirectory()) {
                long[] agg = new long[1];
                count(f, agg);
                sb.append(f.getName()).append("/  ").append(agg[0] / 1048576).append(" MB\n");
                if (depth + 1 < maxDepth) tree(f, sb, depth + 1, maxDepth);
            } else {
                if (f.length() > 1048576) sb.append(f.getName()).append("  ").append(f.length() / 1048576).append(" MB\n");
            }
        }
    }

    private static void count(File d, long[] agg) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) { if (f.isDirectory()) count(f, agg); else agg[0] += f.length(); }
    }

    private void saveAs(String text, String name) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri out = m.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (out == null) return;
            try (OutputStream os = m.getContentResolver().openOutputStream(out)) { os.write(text.getBytes("UTF-8")); }
        } catch (Throwable ignored) { }
    }

    private void saveDiag(String text) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "hexanima_tr_diag.txt");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri out = m.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (out == null) return;
            try (OutputStream os = m.getContentResolver().openOutputStream(out)) {
                os.write(text.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) { }
    }

    private void deleteModel() {
        TrMlKit.delete(m, r -> { statusLine(); if (nav != null) nav.refresh(); Toast.makeText(m, r.equals("ok") ? Tr.s(m, "Deleted", "已删除") : r, Toast.LENGTH_SHORT).show(); });
    }

    /** 清掉一层词典：csv=true 清「导入的 CSV」，false 清「个人词典」 */
    private void clearDictLayer(boolean csv) {
        File u = csv ? TrDict.csvFile(m) : TrDict.mineFile(m);
        if (u.isFile() && u.delete()) {
            TrDict.reset();
            TrDict.ensure(m);
            TrEngine.clearCache();
            statusLine();
            Toast.makeText(m, Tr.s(m, "Cleared", "已清空"), Toast.LENGTH_SHORT).show();
        }
    }

    private void pickFile(int req, String type) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        try { m.startActivityForResult(i, req); } catch (Throwable t) { Toast.makeText(m, String.valueOf(t), Toast.LENGTH_SHORT).show(); }
    }

    /** 导入词典文件。两种格式都能认（中文在前 / 标签在前），转换交给 TrCsv；
     *  csvLayer=true 写 files/tr_csv.tsv（默认只补内置没有的键），false 写 files/tr_user.tsv（覆盖一切）。 */
    void importDict(final Uri u, final boolean csvLayer) {
        new Thread(() -> {
            final File dst = csvLayer ? TrDict.csvFile(m) : TrDict.mineFile(m);
            final File tmp = new File(m.getCacheDir(), csvLayer ? "tr_csv.tmp" : "tr_user.tmp");
            TrCsv.Stat st;
            try (InputStream is = m.getContentResolver().openInputStream(u);
                 OutputStream os = new FileOutputStream(tmp)) {
                st = TrCsv.convert(is, os);
            } catch (Throwable t) {
                final String msg = String.valueOf(t);
                m.ui.post(() -> Toast.makeText(m, msg, Toast.LENGTH_LONG).show());
                tmp.delete();
                return;
            }
            if (st.err != null) {
                final String e = st.err;
                m.ui.post(() -> showText(Tr.s(m, "Import failed", "导入失败"), msgFor(e)));
                tmp.delete();
                return;
            }
            if (!csvLayer && st.keys > 2000) {
                final int n = st.keys;
                m.ui.post(() -> showText(Tr.s(m, "Too big for the personal dictionary", "这个文件不适合放进个人词典"),
                        Tr.f(m, "This file has %1$d entries — that looks like a community CSV. The personal dictionary is only for the few entries you correct by hand.\n\nUse 「Built-in dictionary → Import a danbooru CSV」 for tables this size.",
                                "这个文件有 %1$d 条，看起来是社区 CSV。个人词典只放你手动纠正的那几十条。\n\n这种规模请用「内置词典 → 导入 danbooru CSV」。", n)));
                tmp.delete();
                return;
            }
            try {
                if (csvLayer) {
                    if (dst.exists()) dst.delete();
                    if (!tmp.renameTo(dst)) {
                        try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(dst)) {
                            byte[] b = new byte[65536];
                            for (int r; (r = in.read(b)) > 0; ) out.write(b, 0, r);
                        }
                        tmp.delete();
                    }
                } else {
                    // 个人词典：合并进现有列表（同名的以新导入的为准），不是整表替换
                    java.util.LinkedHashMap<String, String> cur = loadUserDict();
                    int added = 0, replaced = 0;
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new FileInputStream(tmp), "UTF-8"))) {
                        for (String l; (l = r.readLine()) != null; ) {
                            int t = l.indexOf('\t');
                            if (t <= 0) continue;
                            String k = l.substring(0, t).trim(), v = l.substring(t + 1).trim();
                            if (k.isEmpty() || v.isEmpty()) continue;
                            if (cur.put(k, v) == null) added++; else replaced++;
                        }
                    }
                    if (!saveUserDict(cur)) throw new IOException("write failed");
                    tmp.delete();
                }
            } catch (Throwable t) {
                final String msg = String.valueOf(t);
                m.ui.post(() -> Toast.makeText(m, msg, Toast.LENGTH_LONG).show());
                return;
            }
            TrDict.reset();
            TrDict.ensure(m);
            TrEngine.clearCache();
            final TrCsv.Stat ok = st;
            m.ui.post(() -> {
                statusLine();
                if (nav != null) nav.refresh();
                Toast.makeText(m, Tr.f(m, "%1$d entries (skipped %2$d)", "已导入 %1$d 条（跳过 %2$d）",
                        ok.keys, ok.overlength + ok.bad), Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** TrCsv 的错误码 → 给人看的话 */
    private String msgFor(String e) {
        if ("no-cjk".equals(e)) return Tr.s(m, "No Chinese in either column — is this a zh/en dictionary?", "两列里都没有中文，这不像中英对照表。");
        if ("nothing-usable".equals(e)) return Tr.s(m, "Nothing usable after filtering.", "过滤之后没有可用的条目。");
        if ("empty".equals(e)) return Tr.s(m, "The file is empty.", "文件是空的。");
        return e;
    }

    /** 只把 ML Kit 自己那一份模型文件打包导出（不再用「大于 512 KB 就收」的粗暴规则：
     *  那会把 files/anima 里的图像模型/LoRA 一起卷进来，包越导越大）。 */
    private void exportPack() {
        final File nb = m.getNoBackupFilesDir();
        final File modelRoot = new File(nb, "com.google.mlkit.translate.models");
        File[] kids = modelRoot.listFiles();
        if (kids == null || kids.length == 0) {
            Toast.makeText(m, Tr.s(m, "No model on this phone yet — download it first", "本机还没有模型，先下载"), Toast.LENGTH_LONG).show();
            return;
        }
        status.setText(Tr.s(m, "packing…", "正在打包…"));
        new Thread(() -> {
            File tmp = new File(m.getCacheDir(), "tr_pack.zip");
            int n = 0;
            String err = null;
            long bytes = 0;
            try {
                try (ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp)))) {
                    n += addTree(z, modelRoot, "no_backup/com.google.mlkit.translate.models");
                    File pref = new File(nb.getParentFile(), "shared_prefs/com.google.mlkit.internal.xml");
                    if (pref.isFile()) { addFile(z, pref, "shared_prefs/com.google.mlkit.internal.xml"); n++; }
                }
                bytes = tmp.length();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, "hexanima_tr_model_pack.zip");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri out = m.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (out == null) throw new IOException("no Download entry");
                try (OutputStream os = m.getContentResolver().openOutputStream(out);
                     InputStream is = new FileInputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    for (int r; (r = is.read(buf)) > 0; ) os.write(buf, 0, r);
                }
            } catch (Throwable t) { err = TrMlKit.chain(t); }
            tmp.delete();
            final String e = err; final int cnt = n; final long sz = bytes;
            m.ui.post(() -> {
                statusLine();
                if (e != null) { showText(Tr.s(m, "Export failed", "导出失败"), e); return; }
                showText(Tr.s(m, "Model pack", "模型包"),
                        Tr.f(m, "%d files, %.1f MB → Download/hexanima_tr_model_pack.zip", "%d 个文件，%.1f MB → Download/hexanima_tr_model_pack.zip", cnt, sz / 1048576.0));
            });
        }).start();
    }

    private static int addTree(ZipOutputStream z, File dir, String prefix) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) {
                if (f.getName().equals("temp")) continue;   // 下载中的临时目录不要
                n += addTree(z, f, name);
            } else {
                addFile(z, f, name);
                n++;
            }
        }
        return n;
    }

    private static void addFile(ZipOutputStream z, File f, String name) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int r; (r = is.read(buf)) > 0; ) z.write(buf, 0, r);
        }
        z.closeEntry();
    }

    /** 导入模型包：只接受 ML Kit 的模型目录和它自己的 prefs，其它条目一律忽略；
     *  还原后 ML Kit 需要重启进程才会重新读 prefs，所以提示用户完全退出再打开。 */
    void importPack(final Uri u) {
        new Thread(() -> {
            final String base = m.getFilesDir().getParent();
            final String baseCanon;
            try { baseCanon = new File(base).getCanonicalPath(); }
            catch (Throwable t) { m.ui.post(() -> showText(Tr.s(m, "Import failed", "导入失败"), TrMlKit.chain(t))); return; }
            final String okPrefix = "no_backup/com.google.mlkit.translate.models/";
            final String prefName = "shared_prefs/com.google.mlkit.internal.xml";
            int n = 0, skipped = 0;
            String err = null;
            try (InputStream is = m.getContentResolver().openInputStream(u); ZipInputStream z = new ZipInputStream(new java.io.BufferedInputStream(is))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    String name = e.getName();
                    if (name.endsWith("/")) continue;
                    boolean ok = name.startsWith(okPrefix) || name.equals(prefName);
                    if (!ok || name.contains("..")) { skipped++; continue; }
                    File out = new File(base, name);
                    // /data/user/0 是 /data/data 的软链：两边都取 canonical，否则会把所有条目都判成越界
                    if (!out.getCanonicalPath().startsWith(baseCanon + File.separator)) { skipped++; continue; }
                    File p = out.getParentFile();
                    if (p != null) p.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        for (int r; (r = z.read(buf)) > 0; ) os.write(buf, 0, r);
                    }
                    n++;
                }
            } catch (Throwable t) { err = TrMlKit.chain(t); }
            final String e = err; final int cnt = n, sk = skipped;
            m.ui.post(() -> {
                statusLine();
                if (e != null) { showText(Tr.s(m, "Import failed", "导入失败"), e); return; }
                if (cnt == 0) {
                    showText(Tr.s(m, "Import failed", "导入失败"),
                            Tr.s(m, "This zip has no ML Kit model files in it (wrong file?).", "这个 zip 里没有 ML Kit 的模型文件（是不是拿错文件了？）")
                                    + "\n\n" + Tr.s(m, "skipped entries: ", "跳过条目：") + sk);
                    return;
                }
                showText(Tr.s(m, "Imported", "已导入"),
                        Tr.f(m, "%d files restored. Fully close the app and open it again, then check the status line.",
                                "已还原 %d 个文件。请完全退出 App 再打开，然后看状态行是否变成「模型已就绪」。", cnt)
                                + (sk > 0 ? "\n" + Tr.s(m, "skipped: ", "跳过：") + sk : ""));
            });
        }).start();
    }
}
