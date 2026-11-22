package com.hexanima.npu;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;

/** 翻译设置的多级"就地"面板（不再用对话框）。
 *
 *  对话框里的 EditText 在这台机器上叫不出输入法，所以改成直接挂在 Activity 的 content 上：
 *  - 半透明遮罩，点遮罩不关闭（只消费触摸）
 *  - 顶部固定栏：[⬅] 标题 [✕]
 *  - 返回键只退一层
 *  - 换页只换内容容器 + 短促的滑入动画 */
final class TrNav {
    interface Page { String title(); View build(TrNav nav); }

    private final Main m;
    private final FrameLayout overlay;
    private final LinearLayout card;
    private final LinearLayout content;
    private final TextView title, back, close;
    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    private boolean forward = true;

    TrNav(Main m) {
        this.m = m;
        overlay = new FrameLayout(m) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) pop();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        overlay.setFocusableInTouchMode(true);
        overlay.setBackgroundColor(0xC8000000);
        overlay.setClickable(true);   // 吞掉点击：点外面什么都不做

        card = new LinearLayout(m);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(m.bg(Main.CARD, 20));

        LinearLayout head = new LinearLayout(m);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(m.dp(6), m.dp(6), m.dp(6), m.dp(2));
        back = m.text("⬅", 20, Main.ACC, true);
        back.setPadding(m.dp(10), m.dp(8), m.dp(10), m.dp(8));
        back.setOnClickListener(v -> pop());
        head.addView(back);
        title = m.text("", 16, Main.TXT, true);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        close = m.text("✕", 18, Main.DIM, true);
        close.setPadding(m.dp(10), m.dp(8), m.dp(10), m.dp(8));
        close.setOnClickListener(v -> closeAll());
        head.addView(close);
        card.addView(head);

        ScrollView sv = new ScrollView(m);
        content = new LinearLayout(m);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(m.dp(10), 0, m.dp(10), m.dp(10));
        sv.addView(content);
        card.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(m.dp(10), m.dp(48), m.dp(10), m.dp(48));
        overlay.addView(card, lp);
    }

    void push(Page p) { forward = true; stack.addLast(p); render(); }

    void pop() {
        if (stack.size() <= 1) { closeAll(); return; }
        stack.removeLast();
        forward = false;
        render();
    }

    void closeAll() {
        try { hideIme(); } catch (Throwable ignored) { }
        ViewGroup parent = (ViewGroup) overlay.getParent();
        if (parent != null) {
            overlay.setFocusable(false);
            overlay.animate().alpha(0f).setDuration(140).withEndAction(() -> parent.removeView(overlay)).start();
        }
    }

    void show() {
        ViewGroup root = m.findViewById(android.R.id.content);
        if (root == null || overlay.getParent() != null) return;
        overlay.setAlpha(0f);
        root.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        overlay.animate().alpha(1f).setDuration(160).start();
        overlay.requestFocus();
        card.setScaleX(.96f);
        card.setScaleY(.96f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hideIme() {
        try {
            InputMethodManager imm = (InputMethodManager) m.getSystemService(Main.INPUT_METHOD_SERVICE);
            View f = overlay.findFocus();
            if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    // ── 渲染 ───────────────────────────────────────────────────────────────────────

    private void render() {
        Page p = stack.peekLast();
        if (p == null) return;
        title.setText(p.title());
        back.setVisibility(stack.size() > 1 ? View.VISIBLE : View.INVISIBLE);
        content.removeAllViews();
        View v = p.build(this);
        if (v == null) return;
        content.addView(v);
        v.setAlpha(0f);
        v.setTranslationX(m.dp(forward ? 26 : -26));
        v.animate().alpha(1f).translationX(0f).setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
        wireInput(v);
    }

    private void wireInput(View v) {
        if (v instanceof EditText) {
            final EditText e = (EditText) v;
            e.setFocusableInTouchMode(true);
            e.setOnClickListener(x -> { e.requestFocus(); showIme(e); });
            e.setOnFocusChangeListener((x, has) -> { if (has) showIme(e); });
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) wireInput(g.getChildAt(i));
        }
    }

    private void showIme(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) m.getSystemService(Main.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) { }
    }

    // ── 常用控件/页面 ───────────────────────────────────────────────────────────────

    TextView row(String label, View.OnClickListener click) {
        TextView t = new TextView(m);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(Main.TXT);
        t.setPadding(m.dp(12), m.dp(14), m.dp(12), m.dp(14));
        t.setOnClickListener(click);
        t.setBackground(m.bg(Main.FIELD, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = m.dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 菜单页：标题每次 build 时现算，所以改完设置返回时数值是新的 */
    Page menu(final String name, final java.util.function.Supplier<String[]> items, final java.util.function.IntConsumer pick) {
        return new Page() {
            public String title() { return name; }
            public View build(TrNav nav) {
                LinearLayout box = new LinearLayout(m);
                box.setOrientation(LinearLayout.VERTICAL);
                String[] a = items.get();
                for (int i = 0; i < a.length; i++) {
                    final int idx = i;
                    box.addView(row(a[i], v -> pick.accept(idx)));
                }
                return box;
            }
        };
    }

    Page menu(final String name, final String[] items, final java.util.function.IntConsumer pick) {
        final String[] copy = items.clone();
        return menu(name, () -> copy, pick);
    }

    /** 让当前页重新生成一次（改完设置、下载完模型后调用） */
    void refresh() { render(); }

    Page text(final String name, final String body) {
        return new Page() {
            public String title() { return name; }
            public View build(TrNav nav) {
                TextView t = m.text(body, 13, Main.TXT, false);
                t.setTextIsSelectable(true);
                t.setPadding(m.dp(8), m.dp(8), m.dp(8), m.dp(8));
                return t;
            }
        };
    }
}
