package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.function.IntConsumer;

final class LabStyle {
    static int BG, PANEL, LINE, INK, MUTED, BLUE, CORAL, ACTIVE;
    private static final int[][] PALETTES = {
        {0xff101827,0xff182f35,0xff536e7a,0xffeff5fa,0xffb6c8d4,0xff9fdfc5,0xffffb292,0xff243d50},
        {0xff000000,0xff0c0c0c,0xff474747,0xfffafafa,0xffbdbdbd,0xffa9d6ff,0xffffb59d,0xff192c3c},
        {0xfff8fafc,0xffffffff,0xffa2aaba,0xff172139,0xff4b586e,0xff254fa3,0xff973b32,0xffdfebff},
        {0xff151b15,0xff23342b,0xff687657,0xfff8f3df,0xffc2cfad,0xffefd189,0xfff6b298,0xff374b37},
        // Website shell, selected surface, typography and brand accents.
        {0xff000000,0xff102329,0xff405859,0xffedf3f7,0xff97aabd,0xff58d8c9,0xfff4795a,0xff183438}
    };
    static { palette(0); }
    static void palette(int id) {
        int[] p=PALETTES[id];
        BG=p[0]; PANEL=p[1]; LINE=p[2]; INK=p[3]; MUTED=p[4]; BLUE=p[5]; CORAL=p[6]; ACTIVE=p[7];
    }
    static void toggle(Switch s) {
        Context c=s.getContext();
        StateListDrawable track=new StateListDrawable(), thumb=new StateListDrawable();
        for(boolean on:new boolean[]{true,false}) {
            int[] state=on ? new int[]{android.R.attr.state_checked} : new int[]{};
            GradientDrawable rail=box(c,on?BLUE:PANEL,on?BLUE:MUTED,16);
            rail.setSize(dp(c,52),dp(c,26));
            GradientDrawable knob=box(c,on?0xff182637:MUTED,on?0xfffafcff:PANEL,14);
            knob.setStroke(dp(c,2),on?0xfffafcff:PANEL);
            knob.setSize(dp(c,24),dp(c,24));
            track.addState(state,rail); thumb.addState(state,knob);
        }
        s.setTrackTintList(null); s.setThumbTintList(null);
        s.setThumbDrawable(thumb); s.setTrackDrawable(track); s.setSplitTrack(false);
        s.setTextColor(INK); s.setSwitchPadding(dp(c,12)); s.setSwitchMinWidth(dp(c,54));
        s.setShowText(false);
        s.setMinHeight(dp(s.getContext(),48));
    }
    static final int PRESETS = 0, EQ = 1, AUDIO = 2, LAB = 3, DIAGNOSTICS = 4, BATTERY = 5, HEADPHONES = 6,
                     TUNING = 7, IMPORT = 8, EXPORT = 9, BLIND = 10, WEBSITE = 11;
    private LabStyle() {}
    static int dp(Context c, float n) {
        return Math.round(c.getResources().getDisplayMetrics().density * n);
    }
    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold)
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
    static GradientDrawable box(Context c, int fill, int edge, int radius) {
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(dp(c, radius));
        b.setColor(fill);
        if (edge != 0)
            b.setStroke(dp(c, 1), edge);
        return b;
    }
    static Drawable surface(Context c, int fill, int edge, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},box(c,edge==0?BG:PANEL,edge==0?0:LINE,radius));
        states.addState(new int[] {android.R.attr.state_focused}, box(c, ACTIVE, BLUE, radius));
        states.addState(new int[] {}, box(c, fill, edge, radius));
        return new RippleDrawable(ColorStateList.valueOf(0x449ab5ff), states, null);
    }
    static void toggleLabel(Switch s,String label,boolean checked) {
        String value=label+"    "+(checked?"On":"Off");
        android.text.SpannableString caption=new android.text.SpannableString(value);
        caption.setSpan(new android.text.style.ForegroundColorSpan(checked?BLUE:MUTED),label.length(),value.length(),0);
        caption.setSpan(new android.text.style.RelativeSizeSpan(.86f),label.length(),value.length(),0);
        s.setText(caption);s.setContentDescription(label);
    }
    static void button(Button b, boolean primary) {
        b.setStateListAnimator(null);
        b.setAllCaps(false);
        b.setMinHeight(dp(b.getContext(), 48));
        b.setMinWidth(0);
        b.setBackgroundTintList(null);
        b.setBackground(CompanionActions.controlSurface(b.getContext(),
                primary ? ACTIVE : CompanionActions.mix(PANEL, INK, .06f),
                primary ? BLUE : CompanionActions.controlEdge(), primary ? BLUE : INK));
        b.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {MUTED, primary ? BLUE : INK}));
        b.setPadding(
                dp(b.getContext(), 10), dp(b.getContext(), 6), dp(b.getContext(), 10), dp(b.getContext(), 6));
        b.setTextSize(15);
    }
    static void quiet(Button b) {
        button(b, false);
        b.setTextSize(14);
        b.setBackground(CompanionActions.controlSurface(b.getContext(), BG, 0, BLUE));
        b.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {MUTED, BLUE}));
    }
    static Button iconButton(Context c, String label, int symbol, boolean enabled, Runnable action) {
        Button b = new Button(c){
            @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
                super.onSizeChanged(width,height,oldWidth,oldHeight);
                int group=dp(c,28)+Math.round(getPaint().measureText(label));
                int pad=Math.max(dp(c,4),(width-group)/2);
                setPaddingRelative(pad,0,pad,0);
            }
        };
        quiet(b);
        b.setText(label);
        int size = dp(c,20);
        Bitmap bitmap = Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        Icon icon = new Icon(c,symbol,enabled ? BLUE : MUTED);
        icon.layout(0,0,size,size);
        icon.draw(new Canvas(bitmap));
        BitmapDrawable image = new BitmapDrawable(c.getResources(),bitmap);
        image.setBounds(0,0,size,size);
        b.setCompoundDrawablesRelative(image,null,null,null);
        b.setGravity(Gravity.CENTER); b.setPaddingRelative(dp(c,12),0,dp(c,12),0);
        b.setCompoundDrawablePadding(dp(c,8));
        b.setEnabled(enabled);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    static void tab(Button b, boolean on) {
        quiet(b);
        b.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled},{}},new int[]{MUTED,on?BLUE:INK}));
        b.setBackground(CompanionActions.controlSurface(b.getContext(), on ? ACTIVE : BG,
                on ? CompanionActions.mix(ACTIVE, BLUE, .60f) : 0, on ? BLUE : INK));
    }
    static void markTab(Button b, boolean selected) {
        tab(b, selected);
        b.setSelected(selected);
        b.setMinHeight(dp(b.getContext(), 36));
        b.setMinimumHeight(dp(b.getContext(), 36));
    }

    static LinearLayout switcher(Context context, Button left, Button right, int selected) {
        LinearLayout row = new LinearLayout(context);
        row.setBackground(box(context, PANEL, LINE, 9));
        row.setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2));
        for (Button button : new Button[]{left, right}) {
            markTab(button, selected >= 0 && selected <= 1 && button == (selected == 0 ? left : right));
            LinearLayout.LayoutParams part = new LinearLayout.LayoutParams(0, -2, 1);
            if (button == right) part.setMarginStart(dp(context, 4));
            row.addView(button, part);
        }
        return row;
    }

    static void divider(Context c, LinearLayout parent) {
        View line = new View(c);
        line.setBackgroundColor(LINE);
        parent.addView(line, -1, dp(c, 1));
    }
    static void header(Activity a, LinearLayout parent, Runnable showDebug) {
        header(a, parent, showDebug == null ? null : () -> ((AppearanceActivity)a).chooseTheme(), showDebug);
    }
    static void header(Activity a, LinearLayout parent, Runnable themeAction, Runnable showDebug) {
        header(a,parent,themeAction,showDebug,"YinYangEQ Qudelix 5K Companion",false);
    }
    static void modelHeader(Activity a,LinearLayout parent,boolean plus,Runnable theme,Runnable debug){
        header(a,parent,theme,debug,plus?"Yin Yang EQ · 5K Plus":"Yin Yang EQ · 5K",false);
    }
    static void standaloneHeader(AppearanceActivity a,LinearLayout parent){
        header(a,parent,a::chooseTheme,null,"Yin Yang EQ",true);
    }
    private static String buildLabel;
    static void addBuildLabel(Activity a,LinearLayout parent,int endPadding){
        if(buildLabel==null){
            try{
                PackageInfo info=a.getPackageManager().getPackageInfo(a.getPackageName(),0);
                long code=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
                buildLabel="v"+info.versionName+" · Build "+code;
            }catch(PackageManager.NameNotFoundException e){return;}
        }
        TextView version=text(a,buildLabel,12,MUTED,false);
        version.setGravity(Gravity.END);
        version.setPaddingRelative(0,dp(a,4),dp(a,endPadding),0);
        parent.addView(version,-1,-2);
    }
    private static void header(Activity a,LinearLayout parent,Runnable themeAction,Runnable showDebug,String title,boolean standalone){
        addBuildLabel(a,parent,12);
        LinearLayout h = new LinearLayout(a);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(a, 18), dp(a, 4), dp(a, 12), dp(a, 4));
        boolean landscape = a.getResources().getConfiguration().orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean scenicHeader = AppearanceActivity.hasScenery(AppearanceActivity.theme(a)) && !landscape;
        h.setMinimumHeight(dp(a,scenicHeader ? 88 : 54));parent.addView(h,-1,-2);
        ImageView mark = new ImageView(a);
        mark.setImageResource(R.drawable.brand_header);
        h.addView(mark, dp(a, 52), dp(a, 34));
        TextView brand = text(a, title, 19, INK, true);
        brand.setPadding(dp(a, 10), 0, 0, 0);
        h.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        if (themeAction != null) {
            Button appearance = new Button(a);
            quiet(appearance);
            appearance.setText("Theme");
            appearance.setOnClickListener(v -> themeAction.run());
            h.addView(appearance,-2,dp(a,48));
        }
        if (showDebug != null || standalone) {
            Icon bug = new Icon(a,DIAGNOSTICS,BLUE);
            bug.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            bug.setContentDescription("Debug");
            bug.setEnabled(!standalone);
            bug.setFocusable(!standalone);
            bug.setBackground(surface(a,BG,0,8));
            if(standalone)bug.setAlpha(.55f);
            else bug.setOnClickListener(v -> showDebug.run());
            if(android.os.Build.VERSION.SDK_INT>=26)bug.setTooltipText("Debug");
            h.addView(bug,dp(a,48),dp(a,48));
        }
    }
    static LinearLayout eqMenu(Activity a,LinearLayout parent,int selected,IntConsumer choose){
        return eqMenu(a,parent,selected,false,choose);
    }
    static LinearLayout eqMenu(Activity a,LinearLayout parent,int selected,boolean standalone,IntConsumer choose){
        LinearLayout row=new LinearLayout(a);parent.addView(row);
        int[] dests={EQ,TUNING,BLIND};String[] labels={"Filters","Auto EQ","Blind A/B"};
        for(int i=0;i<dests.length;i++){
            int dest=dests[i];
            Button b=CompanionActions.buttonForSize(a,labels[i],false,()->{
                if(!Integer.valueOf(dest).equals(row.getTag()))choose.accept(dest);
            });
            b.setTag(dest);
            b.setEnabled(!standalone || dest==TUNING);
            row.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        }
        selectEqMenu(row,selected);
        return row;
    }

    static void selectEqMenu(LinearLayout row,int selected) {
        if (Integer.valueOf(selected).equals(row.getTag())) return;
        row.setTag(selected);
        for(int i=0;i<row.getChildCount();i++){
            Button button=(Button)row.getChildAt(i);
            boolean active=Integer.valueOf(selected).equals(button.getTag());
            tab(button,active);
            button.setSelected(active);
        }
    }
    static LinearLayout nav(Activity a, LinearLayout root, int active, IntConsumer select) {
        return nav(a,root,active,false,select);
    }
    static LinearLayout nav(Activity a, LinearLayout root, int active, boolean standalone, IntConsumer select) {
        return nav(a, root, active==PRESETS || active==TUNING ? EQ : active, new String[]{"Device","Audio","EQ","Tuning","Sync"},
                new int[]{HEADPHONES,AUDIO,EQ,WEBSITE,LAB}, standalone,select);
    }
    static LinearLayout nav(Activity a, LinearLayout root, int active, String[] names, int[] destinations, IntConsumer select) {
        return nav(a,root,active,names,destinations,false,select);
    }
    private static LinearLayout nav(Activity a, LinearLayout root, int active, String[] names, int[] destinations,
                                    boolean standalone, IntConsumer select) {
        View edge = new View(a);
        edge.setBackgroundColor(LINE);
        root.addView(edge, -1, dp(a, 1));
        LinearLayout nav = new LinearLayout(a);
        nav.setBackgroundColor(BG);
        nav.setPadding(dp(a, 5), dp(a, 4), dp(a, 5), dp(a, 3));
        root.addView(nav, -1, -2);
        for (int i = 0; i < names.length; i++) {
            final int dest = destinations[i];
            boolean on = dest == active;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setBackground(surface(a, on ? ACTIVE : BG, 0, 12));
            item.setSelected(on);
            item.setContentDescription(names[i]);
            boolean enabled=!standalone || dest!=AUDIO;
            item.setEnabled(enabled);
            item.setFocusable(enabled);
            if(enabled)item.setOnClickListener(v -> {if(!on)select.accept(dest);});
            else item.setAlpha(.55f);
            item.setForeground(new RippleDrawable(ColorStateList.valueOf((BLUE & 0xffffff)|0x30000000),null,box(a,0xffffffff,0,12)));
            item.addView(new Icon(a, dest, on ? BLUE : MUTED), dp(a, 28), dp(a, 26));
            TextView label = text(a, names[i], 12, on ? BLUE : MUTED, on);
            label.setGravity(Gravity.CENTER);
            item.addView(label);
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, dp(a, 54), 1);
            placement.setMarginStart(dp(a, 2));
            placement.setMarginEnd(dp(a, 2));
            nav.addView(item, placement);
        }
        return nav;
    }
    static LinearLayout card(Context c, LinearLayout p) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 10));
        card.setBackground(box(c, PANEL, LINE, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 10);
        p.addView(card, lp);
        return card;
    }
    static void slider(SeekBar s, int accent) {
        Context c = s.getContext();
        ClipDrawable fill = new ClipDrawable(box(c, accent, 0, 5), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable track = new LayerDrawable(new Drawable[] {box(c, LINE, 0, 5), fill});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            track.setLayerHeight(i, dp(c, 7));
            track.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        s.setProgressDrawable(track);
        GradientDrawable thumb = box(c, accent, INK, 10);
        thumb.setSize(dp(c, 18), dp(c, 18));
        s.setThumb(thumb);
        s.setProgressTintList(ColorStateList.valueOf(accent));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        s.setThumbTintList(new ColorStateList(
                new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {0xff737989, accent}));
        s.setSplitTrack(false);
        s.setPadding(dp(s.getContext(), 12), 0, dp(s.getContext(), 12), 0);
    }
    static final class VolumeSlider extends SeekBar {
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int divisions;
        VolumeSlider(Context c, int accent, int divisions) {
            super(c);
            this.divisions = divisions;
            slider(this,accent);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeWidth(dp(c,1));
        }
        @Override protected synchronized void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int start = getPaddingLeft(), end = getWidth()-getPaddingRight();
            int center = getHeight()/2;
            int count = divisions == 0 ? Math.max(1,Math.min(20,getMax()-getMin())) : divisions;
            tick.setColor(isEnabled() ? MUTED : LINE);
            for (int n = 0; n <= count; n++) {
                float x = start + (end-start)*n/(float)count;
                int height = n == 0 || n == count || n * 2 == count ? 5 : 2;
                canvas.drawLine(x,center+dp(getContext(),9),x,center+dp(getContext(),9+height),tick);
            }
        }
    }
    static final class Icon extends View {
        private final int id;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c, int id, int color) {
            super(c);
            this.id = id;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(1.7f);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private void line(Canvas c, float... xy) {
            Path path = new Path();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            c.drawPath(path, p);
        }
        @Override
        protected void onDraw(Canvas c) {
            float size = Math.min(Math.min(getWidth(), getHeight()), dp(getContext(), 23));
            c.save();
            c.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
            c.scale(size / 24, size / 24);
            switch (id) {
                case IMPORT:
                case EXPORT:
                    line(c,4,17,4,21,20,21,20,17);
                    if(id==IMPORT) { line(c,12,3,12,15); line(c,7,10,12,15,17,10); }
                    else { line(c,12,15,12,3); line(c,7,8,12,3,17,8); }
                    break;
                case PRESETS:
                    c.drawRoundRect(3, 4, 21, 11, 2, 2, p);
                    c.drawRoundRect(3, 14, 21, 21, 2, 2, p);
                    line(c, 7, 7.5f, 10, 7.5f);
                    line(c, 7, 17.5f, 10, 17.5f);
                    break;
                case EQ:
                case TUNING:
                    for (int i = 0; i < 3; i++) {
                        int x = 5 + 7 * i, y = i == 1 ? 15 : 8;
                        line(c, x, 3, x, y - 2);
                        line(c, x, y + 2, x, 21);
                        c.drawCircle(x, y, 2, p);
                    }
                    break;
                case WEBSITE:
                    line(c,2,15,5,15,8,7,12,19,16,10,19,13,22,13);
                    break;
                case AUDIO:
                    line(c, 3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15, 3, 9);
                    c.drawArc(7, 6, 17, 18, -48, 96, false, p);
                    c.drawArc(5, 2, 21, 22, -42, 84, false, p);
                    break;
                case LAB:
                    c.drawRoundRect(3, 4, 8, 20, 1, 1, p);
                    line(c, 12, 4, 12, 20);
                    line(c, 16, 5, 21, 19);
                    break;
                case DIAGNOSTICS:
                    c.drawRoundRect(7,8,17,20,5,5,p);
                    c.drawArc(9,4,15,10,180,180,false,p);
                    line(c,9,5,7,3); line(c,15,5,17,3);
                    for (int y : new int[]{10,14,18}) {
                        line(c,4,y,7,y); line(c,17,y,20,y);
                    }
                    line(c,12,11,12,17);
                    break;
                case HEADPHONES:
                    c.drawArc(3, 3, 21, 21, 180, 180, false, p);
                    c.drawRoundRect(3, 12, 7, 20, 2, 2, p);
                    c.drawRoundRect(17, 12, 21, 20, 2, 2, p);
                    break;
                case BATTERY:
                    c.drawRoundRect(2, 7, 19, 17, 2, 2, p);
                    line(c, 22, 10, 22, 14);
                    line(c, 6, 12, 14, 12);
                    break;
            }
            c.restore();
        }
    }
}
