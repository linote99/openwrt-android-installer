package top.linote.owrtinstaller;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Widgets styled after the desktop OpenWrt Installer (teal accent, white cards, thin borders). */
final class Ui {
    final Context c;
    final boolean dark;
    final int bg, surface, border, text, muted, primary, onPrimary, primarySoft, danger, dangerSoft, dangerBorder,
            infoSoft, infoBorder, logBg, disabled;

    Ui(Context c) {
        this.c = c;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            bg = 0xFF16191D; surface = 0xFF1E2228; border = 0xFF30363D; text = 0xFFE8ECEB; muted = 0xFF9AA6A3;
            primary = 0xFF5FD3B8; onPrimary = 0xFF0E1A17; primarySoft = 0xFF1F3A34; danger = 0xFFE36A61;
            dangerSoft = 0xFF3A2422; dangerBorder = 0xFF6B3530; infoSoft = 0xFF1B302B; infoBorder = 0xFF2F5A50;
            logBg = 0xFF23282E; disabled = 0xFF4A5157;
        } else {
            bg = 0xFFF6F7F7; surface = 0xFFFFFFFF; border = 0xFFE2E6E5; text = 0xFF1C2321; muted = 0xFF5F6B68;
            primary = 0xFF17695A; onPrimary = 0xFFFFFFFF; primarySoft = 0xFFE5F0ED; danger = 0xFFD2544B;
            dangerSoft = 0xFFFCF2F1; dangerBorder = 0xFFF0C9C4; infoSoft = 0xFFEAF4F1; infoBorder = 0xFFBFDCD4;
            logBg = 0xFFF0F2F2; disabled = 0xFFC9CFCD;
        }
    }

    int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    GradientDrawable circle(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    android.graphics.drawable.Drawable ripple(android.graphics.drawable.Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1A17695A), content, null);
    }

    TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    TextView overline(String s) {
        TextView t = text(s, 11.5f, primary, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    TextView h1(String s) {
        TextView t = text(s, 24, text, true);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    TextView sub(String s) { return text(s, 13.5f, muted, false); }

    LinearLayout col() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout card() {
        LinearLayout l = col();
        l.setBackground(round(surface, border, 12));
        l.setPadding(dp(16), dp(6), dp(16), dp(6));
        return l;
    }

    LinearLayout.LayoutParams mw(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    View divider() {
        View v = new View(c);
        v.setBackgroundColor(border);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f))));
        return v;
    }

    /** Key / value row as in the plan and completion cards. */
    View kv(String k, CharSequence v) {
        LinearLayout r = row();
        r.setPadding(0, dp(12), 0, dp(12));
        TextView kt = text(k, 13.5f, muted, false);
        TextView vt = text(v, 13.5f, text, true);
        vt.setGravity(Gravity.END);
        vt.setTextIsSelectable(true);
        r.addView(kt, weight(1));
        LinearLayout.LayoutParams vp = weight(1.4f);
        vp.leftMargin = dp(12);
        r.addView(vt, vp);
        return r;
    }

    void addKvs(LinearLayout card, String[][] rows) {
        boolean first = true;
        for (String[] kv : rows) {
            if (kv[1] == null || kv[1].isEmpty()) continue;
            if (!first) card.addView(divider());
            card.addView(kv(kv[0], kv[1]));
            first = false;
        }
    }

    /** Icon tile + bold title + grey description + arrow, as in the home list. */
    View actionRow(String glyph, String title, String desc, boolean enabled, View.OnClickListener l) {
        LinearLayout r = row();
        r.setPadding(dp(10), dp(14), dp(10), dp(14));
        TextView tile = text(glyph, 18, enabled ? primary : muted, true);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(round(enabled ? primarySoft : (dark ? 0xFF262B31 : 0xFFEFF1F1), 0, 10));
        r.addView(tile, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout tx = col();
        tx.setPadding(dp(14), 0, dp(8), 0);
        tx.addView(text(title, 15, enabled ? text : muted, true));
        TextView d = text(desc, 12.5f, muted, false);
        d.setPadding(0, dp(3), 0, 0);
        tx.addView(d);
        r.addView(tx, weight(1));
        TextView arrow = text("→", 18, muted, false);
        r.addView(arrow);
        if (enabled && l != null) {
            r.setBackground(ripple(round(0x00000000, 0, 10)));
            r.setOnClickListener(l);
        } else {
            r.setAlpha(0.6f);
        }
        return r;
    }

    TextView button(String label, int kind, View.OnClickListener l) {
        // kind: 0 outline, 1 primary (teal), 2 danger (red), 3 text-only
        TextView b = text(label, 14, text, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setSingleLine(false);
        int fill, stroke, fg;
        switch (kind) {
            case 1: fill = primary; stroke = 0; fg = onPrimary; break;
            case 2: fill = danger; stroke = 0; fg = 0xFFFFFFFF; break;
            case 3: fill = 0x00000000; stroke = 0; fg = text; break;
            default: fill = surface; stroke = border; fg = text; break;
        }
        StateListDrawable sl = new StateListDrawable();
        int dis = kind == 2 ? (dark ? 0xFF6B3D3A : 0xFFE9B3AE) : kind == 1 ? disabled : surface;
        sl.addState(new int[]{-android.R.attr.state_enabled}, round(dis, stroke, 10));
        sl.addState(new int[]{}, round(fill, stroke, 10));
        b.setBackground(ripple(sl));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{kind == 0 || kind == 3 ? disabled : (dark ? 0xFFBFC6C4 : 0xFFFFFFFF), fg}));
        b.setOnClickListener(l);
        return b;
    }

    /** Coloured notice box: kind 0 = info (teal), 1 = danger (red), 2 = neutral. */
    View notice(String glyph, String title, String body, int kind) {
        LinearLayout r = row();
        r.setGravity(Gravity.TOP);
        int fill = kind == 1 ? dangerSoft : kind == 0 ? infoSoft : surface;
        int stroke = kind == 1 ? dangerBorder : kind == 0 ? infoBorder : border;
        int ic = kind == 1 ? danger : primary;
        r.setBackground(round(fill, stroke, 10));
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView g = text(glyph, 16, ic, true);
        g.setPadding(0, 0, dp(10), 0);
        r.addView(g);
        LinearLayout tx = col();
        if (title != null) tx.addView(text(title, 14, text, true));
        if (body != null) {
            TextView b = text(body, 13, text, false);
            if (title != null) b.setPadding(0, dp(3), 0, 0);
            tx.addView(b);
        }
        r.addView(tx, weight(1));
        return r;
    }

    /** Small status line "● 正在执行". */
    TextView dot(String s, int color) {
        TextView t = text("●  " + s, 13.5f, muted, false);
        android.text.SpannableString sp = new android.text.SpannableString(t.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(sp);
        return t;
    }

    TextView mono(String s) {
        TextView t = text(s, 11.5f, text, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}
