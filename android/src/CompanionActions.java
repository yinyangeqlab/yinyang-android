package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Reusable action surfaces for the PEQ editor: tonal buttons, wrapping rows,
 * the Channel Match routing panel, the EQ + Channel Match pair panel and the
 * firmware entry. Views only: callers supply callbacks and enabled states, and
 * nothing here touches the protocol, guards or saved keys.
 */
public final class CompanionActions {
    private CompanionActions() {}

    public static Button primary(Context c, String label, Runnable action) {
        return button(c, label, true, action);
    }

    public static Button secondary(Context c, String label, Runnable action) {
        return button(c, label, false, action);
    }

    private static Button button(Context c, String label, boolean primary, Runnable action) {
        Button b = style(new Button(c), primary);
        b.setText(label);
        bind(b, action);
        return b;
    }

    static Button buttonForSize(Context context, String label, boolean primary, Runnable action) {
        Button b = button(context, label, primary, action);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(px(context, 36));
        b.setMinimumHeight(px(context, 36));
        b.setTextSize(14);
        b.setPadding(px(context, 6), px(context, 3), px(context, 6), px(context, 4));
        return b;
    }

    /** Applies the same tonal action style when the button changes roles. */
    public static Button style(Button b, boolean primary) {
        Context c = b.getContext();
        int fill = primary ? LabStyle.BLUE : mix(LabStyle.PANEL, LabStyle.INK, .06f);
        int edge = primary ? LabStyle.BLUE : controlEdge();
        LabStyle.button(b, primary);
        b.setBackgroundTintList(null);
        b.setStateListAnimator(null);
        b.setBackground(controlSurface(c, fill, edge, readable(fill)));
        b.setTextColor(new ColorStateList(
                new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {LabStyle.MUTED, readable(fill)}));
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(px(c, 48));
        b.setMinimumHeight(px(c, 48));
        b.setMinWidth(px(c, 88));
        b.setMinimumWidth(px(c, 88));
        b.setPadding(px(c, 14), px(c, 8), px(c, 14), px(c, 10));
        return b;
    }

    /** Section heading, also suitable for Debug and other plain sections. */
    public static TextView heading(Context c, String title) {
        TextView t = LabStyle.text(c, title, 14, LabStyle.INK, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    /** Plain section break in long settings pages. */
    static void sectionTitle(LinearLayout parent, String title) {
        Context c = parent.getContext();
        TextView heading = heading(c, title);
        heading.setTextSize(19);
        heading.setPadding(0, px(c, 18), 0, px(c, 6));
        parent.addView(heading);
    }

    static final class UsbModes extends LinearLayout {
        private final Button[] options=new Button[UsbMode.values().length];
        private int selected=-1;
        UsbModes(Context c,java.util.function.BiConsumer<Integer,Integer> choose){
            super(c);setOrientation(VERTICAL);
            addView(heading(c,"USB mode"));
            LinearLayout choices=new LinearLayout(c);addView(choices);
            for(UsbMode mode:UsbMode.values()){
                int index=mode.ordinal();
                Button option=secondary(c,mode.label,()->choose.accept(selected,index));
                options[index]=option;
                option.setContentDescription("USB mode · "+mode.label);
                option.setMinHeight(px(c,48));
                choices.addView(option,new LinearLayout.LayoutParams(0,-2,1));
            }
            update(-1,false);
        }
        void update(int mode,boolean writable){
            selected=mode;
            for(int i=0;i<options.length;i++){
                Button option=options[i];boolean active=i==mode;
                if(option.getTag()==null || active!=option.isSelected()){
                    LabStyle.tab(option,active);option.setSelected(active);option.setTag(true);
                }
                option.setEnabled(writable && UsbMode.supported(mode));
            }
        }
    }

    /** Wrapping row whose items share each line's width; use for File actions and Device options. */
    public static Flow row(Context c, View... items) {
        Flow f = new Flow(c, px(c, 8), true);
        for (View v : items) f.addView(v);
        return f;
    }

    /** Unboxed heading plus children, for dense groups that do not need a panel. */
    public static LinearLayout section(Context c, String title, View... children) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        if (title != null) box.addView(heading(c, title), below(c, 0));
        for (View v : children) box.addView(v, below(c, 6));
        return box;
    }

    /** Left-to-right wrapping layout; with stretch, each line's items grow to fill the width. */
    public static class Flow extends ViewGroup {
        private static final int FREE = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        private final int gap;
        private final boolean stretch;
        private int[] xs = new int[0];
        private int[] ys = new int[0];

        public Flow(Context c, int gapPx, boolean stretch) {
            super(c);
            this.gap = gapPx;
            this.stretch = stretch;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int n = getChildCount();
            if (xs.length != n) {
                xs = new int[n];
                ys = new int[n];
            }
            boolean bounded = MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED;
            int pad = getPaddingLeft() + getPaddingRight();
            int avail = bounded ? Math.max(0, MeasureSpec.getSize(wSpec) - pad) : Integer.MAX_VALUE;
            int childW = bounded ? MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST) : FREE;
            int y = getPaddingTop();
            int widest = 0;
            int from = 0;
            int rowW = 0;
            int count = 0;
            for (int i = 0; i <= n; i++) {
                View v = i < n ? getChildAt(i) : null;
                if (v != null && v.getVisibility() == GONE) continue;
                int w = 0;
                if (v != null) {
                    v.measure(childW, FREE);
                    w = v.getMeasuredWidth();
                }
                if (count > 0 && (v == null || rowW + gap + w > avail)) {
                    y += closeRow(from, i, y, count, bounded ? avail - rowW : 0) + gap;
                    widest = Math.max(widest, rowW);
                    from = i;
                    rowW = 0;
                    count = 0;
                }
                if (v != null) {
                    rowW += (count > 0 ? gap : 0) + w;
                    count++;
                }
            }
            int h = y - (y > getPaddingTop() ? gap : 0) + getPaddingBottom();
            int w = stretch && bounded ? MeasureSpec.getSize(wSpec) : resolveSize(widest + pad, wSpec);
            setMeasuredDimension(w, resolveSize(Math.max(h, getSuggestedMinimumHeight()), hSpec));
        }

        private int closeRow(int from, int to, int y, int count, int spare) {
            int extra = stretch ? Math.max(0, spare) / count : 0;
            int x = getPaddingLeft();
            int h = 0;
            for (int i = from; i < to; i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                if (extra > 0) v.measure(exactly(v.getMeasuredWidth() + extra), FREE);
                xs[i] = x;
                ys[i] = y;
                x += v.getMeasuredWidth() + gap;
                h = Math.max(h, v.getMeasuredHeight());
            }
            for (int i = from; i < to; i++) {
                View v = getChildAt(i);
                if (v.getVisibility() != GONE && v.getMeasuredHeight() != h) {
                    v.measure(exactly(v.getMeasuredWidth()), exactly(h));
                }
            }
            return h;
        }

        private static int exactly(int size) {
            return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount() && i < xs.length; i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int w = v.getMeasuredWidth();
                int x = rtl ? r - l - xs[i] - w : xs[i];
                v.layout(x, ys[i], x + w, ys[i] + v.getMeasuredHeight());
            }
        }
    }

    /** Two-channel routing with an explicit state, the swap action and interrupted recovery. */
    public static final class RoutingPanel extends LinearLayout {
        public static final int NORMAL = 0;
        public static final int SWAPPED = 1;
        public static final int UNAVAILABLE = 2;
        public static final int INTERRUPTED = 3;

        private final TextView badge;
        private final TextView leftTo;
        private final TextView rightTo;
        private final TextView note;
        private final TextView reason;
        private final Button swap;
        private final Button recover;

        public RoutingPanel(Context c) {
            super(c);
            setOrientation(VERTICAL);
            panel(this);
            badge = chip(c, "");
            leftTo = chip(c, "");
            rightTo = chip(c, "");
            note = LabStyle.text(c, "", 12, LabStyle.MUTED, false);
            note.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            reason = LabStyle.text(c, "", 12, LabStyle.INK, false);
            swap = secondary(c, "", null);
            recover = secondary(c, "Use current 5K settings\u2026", null);
            Flow head = new Flow(c, px(c, 8), false);
            head.addView(heading(c, "Channel routing"));
            head.addView(badge);
            addView(head, below(c, 0));
            addView(row(c, lane(c, "Left in", leftTo), lane(c, "Right in", rightTo)), below(c, 8));
            addView(note, below(c, 6));
            addView(swap, below(c, 8));
            addView(reason, below(c, 4));
            addView(recover, below(c, 8));
            setState(UNAVAILABLE, false, null);
        }

        /** Wires the swap/restore/finish button and the interrupted-only recovery button. */
        public RoutingPanel setActions(Runnable onSwap, Runnable onUseCurrent) {
            bind(swap, onSwap);
            bind(recover, onUseCurrent);
            return this;
        }

        /** blockedReason is shown only while the swap is disabled; null uses the offline text. */
        public RoutingPanel setState(int state, boolean swapEnabled, String blockedReason) {
            boolean swapped = state == SWAPPED;
            boolean known = swapped || state == NORMAL;
            boolean broken = state == INTERRUPTED;
            int edge = broken ? LabStyle.CORAL : swapped ? LabStyle.BLUE : LabStyle.LINE;
            int ink = known ? LabStyle.INK : broken ? LabStyle.CORAL : LabStyle.MUTED;
            int fill = swapped ? LabStyle.ACTIVE : LabStyle.BG;
            String unknown = broken ? "Unconfirmed" : "Unknown";
            badge.setText(swapped ? "Swapped" : known ? "Normal" : broken ? "Interrupted" : "Unavailable");
            tone(badge, fill, edge, swapped ? readable(fill) : ink);
            leftTo.setText(swapped ? "Right out" : known ? "Left out" : unknown);
            rightTo.setText(swapped ? "Left out" : known ? "Right out" : unknown);
            tone(leftTo, LabStyle.BG, edge, ink);
            tone(rightTo, LabStyle.BG, edge, ink);
            note.setText(broken ? "Finish the swap to verify routing and both Channel Match filters."
                    : "Swapping the DAC outputs also exchanges CH0 and CH1 in Channel Match, including both preamps and all ten filters per ear. Main EQ stays in place.");
            note.setVisibility(VISIBLE);
            style(swap, true);
            swap.setText(broken ? "Finish channel swap"
                    : swapped ? "Restore normal channels" : "Swap left and right");
            swap.setEnabled(swapEnabled);
            reason.setText(or(blockedReason, "Connect to the 5K to change channel routing."));
            reason.setVisibility(swapEnabled ? GONE : VISIBLE);
            recover.setVisibility(broken ? VISIBLE : GONE);
            return this;
        }

        public RoutingPanel setRecoveryEnabled(boolean enabled) {
            recover.setEnabled(enabled);
            return this;
        }

    }

    /** "EQ + Channel Match preset": a named pair of the two stage drafts, saved on this phone. */
    public static final class PairPanel extends LinearLayout {
        private final TextView eqName;
        private final TextView eqDraft;
        private final TextView matchName;
        private final TextView matchDraft;
        private final TextView pair;
        private final Button save;
        private final Button open;

        public PairPanel(Context c) {
            super(c);
            setOrientation(VERTICAL);
            panel(this);
            eqName = LabStyle.text(c, "", 12, LabStyle.MUTED, false);
            eqDraft = LabStyle.text(c, "", 14, LabStyle.INK, true);
            matchName = LabStyle.text(c, "", 12, LabStyle.MUTED, false);
            matchDraft = LabStyle.text(c, "", 14, LabStyle.INK, true);
            pair = LabStyle.text(c, "", 14, LabStyle.INK, true);
            save = secondary(c, "Save pair\u2026", null);
            open = secondary(c, "Open pair\u2026", null);
            addView(heading(c, "EQ + Channel Match preset"), below(c, 0));
            addView(LabStyle.text(c, "Save both editor drafts together on this phone.",
                    12, LabStyle.MUTED, false), below(c, 2));
            addView(row(c, cell(c, eqName, eqDraft), cell(c, matchName, matchDraft)), below(c, 8));
            addView(pair, below(c, 8));
            addView(row(c, save, open), below(c, 8));
            setStageNames(null, null);
            setDrafts(null, null);
            setPair(null, false, true);
        }

        /** Configured stage names; null or empty falls back to the Qudelix defaults. */
        public PairPanel setStageNames(String eq, String match) {
            eqName.setText(or(eq, "Main EQ · USR"));
            matchName.setText(or(match, "Channel Match (SPK)"));
            return this;
        }

        public PairPanel setDrafts(String eq, String match) {
            eqDraft.setText(or(eq, "Unnamed draft"));
            matchDraft.setText(or(match, "Unnamed draft"));
            return this;
        }

        /** savedName is the saved pair matching both drafts, or null. */
        public PairPanel setPair(String savedName, boolean hasSavedPairs, boolean idle) {
            boolean named = savedName != null && !savedName.isEmpty();
            pair.setText(named ? "Saved pair · " + savedName
                    : hasSavedPairs ? "Current drafts are unsaved as a pair" : "No pairs saved yet");
            pair.setTextColor(named ? LabStyle.INK : LabStyle.MUTED);
            open.setEnabled(hasSavedPairs && idle);
            return this;
        }

        public PairPanel setActions(Runnable onSave, Runnable onOpen) {
            bind(save, onSave);
            bind(open, onOpen);
            return this;
        }

        public PairPanel setSavePairEnabled(boolean enabled) {
            save.setEnabled(enabled);
            return this;
        }
    }

    /** Compact bordered entry that shows the firmware version and opens the existing details dialog. */
    public static final class FirmwareEntry extends Flow {
        private final TextView version;

        public FirmwareEntry(Context c, Runnable onDetails) {
            super(c, px(c, 8), true);
            version = LabStyle.text(c, "", 15, LabStyle.INK, true);
            LinearLayout id = new LinearLayout(c);
            id.setOrientation(LinearLayout.VERTICAL);
            id.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            id.addView(LabStyle.text(c, "Firmware", 12, LabStyle.MUTED, false));
            id.addView(version);
            TextView go = LabStyle.text(c, "Firmware details \u203A", 14, LabStyle.INK, true);
            go.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            go.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(id);
            addView(go);
            setBackground(controlSurface(c, mix(LabStyle.PANEL, LabStyle.INK, .06f), controlEdge(), LabStyle.INK));
            setPadding(px(c, 12), px(c, 8), px(c, 12), px(c, 10));
            setMinimumHeight(px(c, 48));
            setFocusable(true);
            bind(this, onDetails);
            setVersion(null);
        }

        public FirmwareEntry setVersion(String v) {
            String shown = or(v, "Not read");
            version.setText(shown);
            setContentDescription("Firmware " + shown + ". Firmware details");
            return this;
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }
    }

    static int controlEdge() {
        return mix(LabStyle.LINE, LabStyle.MUTED, .44f);
    }

    static Drawable controlSurface(Context c, int fill, int edge, int ink) {
        StateListDrawable states = new StateListDrawable();
        int disabledEdge = edge == 0 ? 0 : mix(LabStyle.PANEL, LabStyle.LINE, .72f);
        states.addState(new int[]{-android.R.attr.state_enabled},
                LabStyle.box(c, mix(LabStyle.PANEL, LabStyle.INK, .025f), disabledEdge, 8));
        states.addState(new int[]{android.R.attr.state_pressed},
                LabStyle.box(c, mix(fill, ink, .12f), edge, 8));
        android.graphics.drawable.GradientDrawable ring = LabStyle.box(c, Color.TRANSPARENT, LabStyle.BLUE, 8);
        ring.setStroke(px(c, 2), LabStyle.BLUE);
        LayerDrawable focused = new LayerDrawable(new Drawable[]{ring, LabStyle.box(c, fill, edge, 5)});
        int inset = px(c, 4);
        focused.setLayerInset(1, inset, inset, inset, inset);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], LabStyle.box(c, fill, edge, 8));
        return states;
    }

    private static TextView chip(Context c, String s) {
        TextView t = LabStyle.text(c, s, 13, LabStyle.INK, true);
        t.setPadding(px(c, 8), px(c, 3), px(c, 8), px(c, 3));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return tone(t, LabStyle.BG, LabStyle.LINE, LabStyle.INK);
    }

    private static TextView tone(TextView t, int fill, int edge, int ink) {
        int[] p = {t.getPaddingLeft(), t.getPaddingTop(), t.getPaddingRight(), t.getPaddingBottom()};
        t.setBackground(LabStyle.box(t.getContext(), fill, edge, 6));
        t.setPadding(p[0], p[1], p[2], p[3]);
        t.setTextColor(ink);
        return t;
    }

    private static Flow lane(Context c, String from, TextView to) {
        Flow f = new Flow(c, px(c, 6), false);
        TextView arrow = LabStyle.text(c, "\u2192", 14, LabStyle.MUTED, true);
        arrow.setGravity(Gravity.CENTER_VERTICAL);
        f.addView(chip(c, from));
        f.addView(arrow);
        f.addView(to);
        return f;
    }

    private static LinearLayout cell(Context c, TextView name, TextView value) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(LabStyle.box(c, LabStyle.BG, LabStyle.LINE, 6));
        box.setPadding(px(c, 8), px(c, 5), px(c, 8), px(c, 6));
        box.addView(name);
        box.addView(value);
        return box;
    }

    private static void panel(LinearLayout v) {
        Context c = v.getContext();
        v.setBackground(LabStyle.box(c, LabStyle.PANEL, LabStyle.LINE, 10));
        v.setPadding(px(c, 12), px(c, 10), px(c, 12), px(c, 12));
    }

    private static LinearLayout.LayoutParams below(Context c, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = px(c, topDp);
        return p;
    }

    private static void bind(View v, final Runnable r) {
        if (r == null) v.setOnClickListener(null);
        else v.setOnClickListener(x -> r.run());
    }

    private static String or(String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    private static int px(Context c, float dp) {
        return LabStyle.dp(c, dp);
    }

    static int readable(int fill) {
        return contrast(fill, LabStyle.INK) >= contrast(fill, LabStyle.BG) ? LabStyle.INK : LabStyle.BG;
    }

    private static float contrast(int a, int b) {
        float la = Color.luminance(a);
        float lb = Color.luminance(b);
        return (Math.max(la, lb) + 0.05f) / (Math.min(la, lb) + 0.05f);
    }

    static int mix(int a, int b, float t) {
        float u = 1f - t;
        return Color.rgb(
                Math.round(Color.red(a) * u + Color.red(b) * t),
                Math.round(Color.green(a) * u + Color.green(b) * t),
                Math.round(Color.blue(a) * u + Color.blue(b) * t));
    }
}
