package com.yinyangeq.qudelixprobe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.function.IntConsumer;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** Native editor controls shared by both model-specific workbenches. */
final class CompanionEditor {
    /** Apply an editor refresh's offset after focus/layout adjustments, before the frame is drawn. */
    static final class Viewport extends ScrollView {
        private int nextOffset=-1;
        Viewport(Context context){super(context);}
        void preservePosition(){
            if(nextOffset<0 && isLaidOut())nextOffset=getScrollY();
        }
        void restorePosition(int y){nextOffset=y;}
        void top(){nextOffset=-1;scrollTo(0,0);}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            super.onLayout(changed,l,t,r,b);
            if(nextOffset>=0){scrollTo(0,nextOffset);nextOffset=-1;}
        }
    }
    static LinearLayout pageBody(Viewport scroll) {
        Context c=scroll.getContext();
        LinearLayout body=new LinearLayout(c){
            @Override protected void onMeasure(int width,int height){
                int available=Math.min(MeasureSpec.getSize(width),dp(getContext(),720));
                super.onMeasure(MeasureSpec.makeMeasureSpec(available,MeasureSpec.EXACTLY),height);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));
        return body;
    }
    static void stages(LinearLayout parent, int active, IntConsumer select) {
        Context c=parent.getContext();
        LinearLayout tabs=new LinearLayout(c);
        String[] titles={"Main EQ","Channel Match","Paired EQ"};
        for(int i=0;i<titles.length;i++) {
            final int bank=i;
            LinearLayout col=column(c);
            Button button=CompanionActions.buttonForSize(c,"",false,()->{if(bank!=active)select.accept(bank);});
            quiet(button);
            button.setText(titles[i]);
            button.setMinHeight(dp(c,48));
            button.setSelected(i==active);
            button.setTextColor(i==active?INK:MUTED);
            button.setPadding(dp(c,3),0,dp(c,3),0);
            col.addView(button,-1,-2);
            View rule=new View(c);rule.setBackgroundColor(i==active?BLUE:LINE);
            col.addView(rule,-1,dp(c,i==active?2:1));
            tabs.addView(col,part());
        }
        parent.addView(tabs);
    }
    /** The tab bar follows its slot until it reaches the top of the viewport. */
    static final class StickyStages {
        private final Viewport scroll;
        private final View spacer;
        private final LinearLayout bar;
        private int active=-1;

        StickyStages(FrameLayout pane,Viewport viewport,LinearLayout container) {
            scroll=viewport;
            Context c=container.getContext();
            spacer=new View(c);
            container.addView(spacer,-1,0);
            bar=column(c);
            bar.setPadding(dp(c,14),0,dp(c,14),0);
            bar.setBackgroundColor(BG);
            bar.setVisibility(View.GONE);
            pane.addView(bar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
            bar.addOnLayoutChangeListener((view,l,t,r,b,oldL,oldT,oldR,oldB)->{
                sizeSpacer(b-t);
                position();
            });
            spacer.addOnLayoutChangeListener((view,l,t,r,b,oldL,oldT,oldR,oldB)->position());
            container.addOnLayoutChangeListener((view,l,t,r,b,oldL,oldT,oldR,oldB)->{
                if(bar.getLayoutParams().width!=r-l){
                    bar.getLayoutParams().width=r-l;
                    bar.requestLayout();
                }
                position();
            });
            scroll.setOnScrollChangeListener((view,x,y,oldX,oldY)->position());
        }

        void show(int stage,IntConsumer select){
            if(active!=stage){
                active=stage;
                bar.removeAllViews();
                stages(bar,stage,select);
            }
            bar.setVisibility(View.VISIBLE);
            if(bar.getHeight()>0)sizeSpacer(bar.getHeight());
            position();
        }

        void hide(){
            bar.setVisibility(View.GONE);
            sizeSpacer(0);
        }

        private void sizeSpacer(int height){
            if(spacer.getLayoutParams().height==height)return;
            spacer.getLayoutParams().height=height;
            spacer.requestLayout();
        }

        private void position(){
            bar.setTranslationY(Math.max(0,spacer.getTop()-scroll.getScrollY()));
        }
    }

    static CompanionResponse graph(LinearLayout parent,ResponseTraces.Stage stage,EqGraphInputs sources,
                                   PeqResponse main,PeqResponse... ears){
        Context c=parent.getContext();
        boolean match=stage==ResponseTraces.Stage.MATCH;
        LinearLayout title=new LinearLayout(c);
        title.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(title);
        title.addView(text(c,match?(sources.stereo()?"Deviation from reference":"Channel Match correction")
                :sources.measured()?"Estimated response":stage==ResponseTraces.Stage.PAIR?"Combined EQ response":"Main EQ response",
                16,INK,true),part());
        Button inputs=CompanionActions.buttonForSize(c,"Graph inputs",false,sources::choose);
        quiet(inputs);
        inputs.setMinHeight(dp(c,48));
        inputs.setMinimumHeight(dp(c,48));
        title.addView(inputs);
        if(sources.measured() || sources.target!=null)
            parent.addView(text(c,sources.summary(),12,MUTED,false));

        CompanionResponse plot=new CompanionResponse(c,stage,sources,main,ears);
        if(match){
            LinearLayout options=new LinearLayout(c);
            options.setGravity(Gravity.CENTER_VERTICAL);
            boolean wide=c.getResources().getConfiguration().screenWidthDp>=330
                    && c.getResources().getConfiguration().fontScale<=1.3f;
            parent.addView(options);
            if(sources.stereo()){
                Button before=CompanionActions.buttonForSize(c,"Before",false,()->{});
                Button after=CompanionActions.buttonForSize(c,"After",false,()->{});
                before.setContentDescription("Before match");
                after.setContentDescription("After match");
                LinearLayout segments=LabStyle.switcher(c,before,after,sources.after?1:0);
                Runnable update=()->{
                    LabStyle.markTab(before,!sources.after);
                    LabStyle.markTab(after,sources.after);
                    before.setMinHeight(dp(c,48));before.setMinimumHeight(dp(c,48));
                    after.setMinHeight(dp(c,48));after.setMinimumHeight(dp(c,48));
                    plot.refresh();
                };
                before.setOnClickListener(view->{sources.setAfter(false);update.run();});
                after.setOnClickListener(view->{sources.setAfter(true);update.run();});
                update.run();
                options.addView(segments,part());
            }
            CheckBox zero=new CheckBox(c);
            zero.setText("0 dB line");
            zero.setTextColor(INK);
            zero.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
            zero.setMinHeight(dp(c,48));
            zero.setChecked(sources.zeroLine);
            zero.setOnCheckedChangeListener((view,checked)->{sources.setZeroLine(checked);plot.invalidate();});
            if(!sources.stereo() || wide) options.addView(zero);
            else parent.addView(zero);
        }

        LinearLayout labels=new LinearLayout(c);
        parent.addView(labels);
        if(match){
            labels.addView(text(c,"━ Left",13,left(c,null),true),part());
            labels.addView(text(c,"━ Right",13,right(c),true),part());
        }else{
            labels.addView(text(c,stage==ResponseTraces.Stage.PAIR || sources.stereo()?"━ L/R average"
                    :sources.measured()?"━ Estimated":"━ Filter effect",13,BLUE,true),part());
            if(sources.compareTarget())labels.addView(text(c,"━ Target",13,MUTED,true),part());
        }
        labels.addView(text(c,"dB",12,MUTED,false));
        parent.addView(plot,-1,dp(c,124));
        return plot;
    }
    static Button bandAction(Context c,String label,boolean enabled,Runnable action){
        Button button=CompanionActions.buttonForSize(c,label,false,action);
        quiet(button);
        button.setMinHeight(dp(c,48));
        button.setMinimumWidth(dp(c,61));
        button.setPadding(dp(c,8),dp(c,4),dp(c,8),dp(c,4));
        button.setTextSize(13);
        button.setBackground(new android.graphics.drawable.InsetDrawable(surface(c,BG,LINE,6),
                dp(c,4),dp(c,6),dp(c,4),dp(c,6)));
        button.setEnabled(enabled);
        return button;
    }
    static Button historyAction(Context c,String label,boolean clockwise,boolean enabled,Runnable action){
        Button button=bandAction(c,label,enabled,action);
        Drawable arrow=new HistoryArrow(button,clockwise);
        arrow.setBounds(0,0,dp(c,13),dp(c,13));
        button.setCompoundDrawablesRelative(arrow,null,null,null);
        button.setCompoundDrawablePadding(dp(c,4));
        button.setPadding(dp(c,7),dp(c,4),dp(c,7),dp(c,4));
        return button;
    }
    static void bandHeading(LinearLayout parent,String title,String count,Button undo,Button redo,
                            boolean canReload,Runnable reload,boolean canClear,Runnable clear){
        Context c=parent.getContext();
        LinearLayout top=new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(top);
        LinearLayout labels=column(c);
        TextView label=text(c,title,19,INK,true);
        if(android.os.Build.VERSION.SDK_INT>=28)label.setAccessibilityHeading(true);
        labels.addView(label);
        if(count!=null)labels.addView(text(c,count,13,MUTED,false));
        top.addView(labels,part());
        top.addView(undo); top.addView(redo);
        Button reloadButton=bandAction(c,"Reload",canReload,reload);
        reloadButton.setContentDescription("Reload starting filters");
        Button clearButton=bandAction(c,"Clear",canClear,clear);
        clearButton.setContentDescription("Clear filters");

        int unspecified=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);
        int needed=dp(c,8);
        for(View element:new View[]{labels,undo,redo,reloadButton,clearButton}){
            element.measure(unspecified,unspecified);
            needed+=element.getMeasuredWidth();
        }
        int available=dp(c,Math.min(c.getResources().getConfiguration().screenWidthDp,720)-28);
        LinearLayout actions=top;
        if(needed>available){
            actions=new LinearLayout(c);
            actions.setGravity(Gravity.END);
            parent.addView(actions,-1,-2);
        }
        actions.addView(reloadButton); actions.addView(clearButton);
    }

    private static final class HistoryArrow extends Drawable {
        private final Button button;
        private final boolean clockwise;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path head = new Path();

        HistoryArrow(Button button, boolean clockwise) {
            this.button = button;
            this.clockwise = clockwise;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.55f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            head.moveTo(4, 1.6f);
            head.lineTo(4, 5);
            head.lineTo(7.4f, 5);
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 16f, getBounds().height() / 16f);
            if (clockwise) canvas.scale(-1, 1, 8, 8);
            paint.setColor(button.getCurrentTextColor());
            canvas.drawArc(3, 3, 13, 13, 217, 290, false, paint);
            canvas.drawPath(head, paint);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static Button presetActions(LinearLayout parent, boolean open, boolean canLoad, Runnable load,
                                String device, boolean canSaveDevice, Runnable saveDevice,
                                boolean canSavePhone, Runnable savePhone) {
        Context c=parent.getContext();
        LinearLayout actions=new LinearLayout(c);
        parent.addView(actions);
        Button browse=CompanionActions.buttonForSize(c,open?"Close preset list":"Load preset",false,load);
        browse.setMinHeight(dp(c,48));
        browse.setEnabled(canLoad);
        actions.addView(browse,part());
        Button save=CompanionActions.buttonForSize(c,"Save preset…",true,()->{
            android.widget.PopupMenu menu=new android.widget.PopupMenu(c,actions.getChildAt(1));
            boolean hardwareReady=Boolean.TRUE.equals(actions.getChildAt(1).getTag());
            menu.getMenu().add(device).setEnabled(hardwareReady).setOnMenuItemClickListener(item->{saveDevice.run();return true;});
            menu.getMenu().add("Save on this phone").setEnabled(canSavePhone).setOnMenuItemClickListener(item->{savePhone.run();return true;});
            menu.show();
        });
        save.setMinHeight(dp(c,48));
        save.setTag(canSaveDevice);
        save.setEnabled(canSaveDevice || canSavePhone);
        LinearLayout.LayoutParams params=part();
        params.setMarginStart(dp(c,6));
        actions.addView(save,params);
        return save;
    }
    static void presetName(LinearLayout parent,String name) {
        Context c=parent.getContext();
        TextView title=text(c,name,21,INK,true);
        if(android.os.Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        title.setPadding(0,dp(c,8),0,dp(c,6));
        parent.addView(title);
    }
    static void pairStage(LinearLayout parent,String label,String value,Runnable edit){
        Context c=parent.getContext();
        LinearLayout row=new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels=column(c);
        labels.addView(text(c,label,16,INK,true));
        labels.addView(text(c,value,13,MUTED,false));
        row.addView(labels,part());
        Button button=CompanionActions.buttonForSize(c,"Edit",false,edit);
        quiet(button);
        button.setContentDescription("Edit "+label);
        button.setMinHeight(dp(c,48));
        row.addView(button,dp(c,64),-2);
        parent.addView(row);
        divider(c,parent);
    }
    static void files(LinearLayout parent, boolean allowImport, boolean allowExport, Runnable read, Runnable write) {
        Context c=parent.getContext();
        LinearLayout files=new LinearLayout(c);
        files.addView(iconButton(c,"Import",IMPORT,allowImport,read),part());
        files.addView(iconButton(c,"Export",EXPORT,allowExport,write),part());
        parent.addView(files);
    }
    interface Matches {
        int slots(int ear);
        boolean active(int ear,int slot);
        boolean canAdd(int lane);
        void add(int lane);
        void table(LinearLayout into,int ear,int slot);
        boolean sameShape(int slot);
        void paired(MatchRow row,int slot);
    }
    static int left(Context c,SharedPreferences prefs) {
        return AppearanceActivity.theme(c)==2 ? 0xffab2636 : 0xffff8186;
    }
    static int right(Context c) {return AppearanceActivity.theme(c)==2 ? 0xff147548 : 0xff76d69b;}
    static boolean visibleEar(SharedPreferences prefs,int ear){
        int view=prefs.getInt("matchColumnView",1);
        return view==1 || (view==0?0:1)==ear;
    }
    static void matches(LinearLayout body,SharedPreferences prefs,String usage,boolean editable,Matches model,Runnable redraw) {
        Context c=body.getContext();
        body.addView(text(c,usage,13,MUTED,false));
        String[] sides={"Left","Right"};
        String[] views={"Left CM","Both","Right CM"};
        int view=prefs.getInt("matchColumnView",1);
        LinearLayout selector=new LinearLayout(c);body.addView(selector);
        for(int i=0;i<3;i++){
            final int choice=i;
            Button option=CompanionActions.buttonForSize(c,views[i],false,()->{
                if(choice!=view){prefs.edit().putInt("matchColumnView",choice).apply();redraw.run();}
            });
            tab(option,i==view);option.setSelected(i==view);selector.addView(option,part());
        }
        if(view!=1){
            int ear=view==2?1:0;
            FilterTable.headers(body);
            for(int slot=0;slot<model.slots(ear);slot++)
                if(model.active(ear,slot))model.table(body,ear,slot);
            addMatch(body,sides[ear],ear,model,editable);
            return;
        }
        LinearLayout adders=new LinearLayout(c);body.addView(adders);
        for(int ear=0;ear<2;ear++){
            LinearLayout col=column(c);adders.addView(col,part());addMatch(col,sides[ear],ear,model,editable);
        }
        MatchRow header=new MatchRow(c,MatchRow.BOTH);
        String[] names={"","Band","Filter","Hz","L dB","R dB","Q"};
        View[] headings=new View[names.length];
        for(int i=0;i<names.length;i++){
            TextView h=text(c,names[i],12,i==4?left(c,null):i==5?right(c):MUTED,true);
            h.setGravity(Gravity.CENTER);headings[i]=h;
        }
        header.setBackgroundColor(PANEL);header.cells(headings,true);body.addView(header);
        for(int slot=0;slot<Math.max(model.slots(0),model.slots(1));slot++){
            boolean l=slot<model.slots(0) && model.active(0,slot);
            boolean r=slot<model.slots(1) && model.active(1,slot);
            if(!l && !r)continue;
            if(l && r && model.sameShape(slot)){
                MatchRow row=new MatchRow(c,MatchRow.BOTH);body.addView(row);model.paired(row,slot);
            }else{
                for(int ear=0;ear<2;ear++)if(ear==0?l:r){
                    MatchRow row=new MatchRow(c,ear);body.addView(row);model.table(row,ear,slot);
                }
            }
            divider(c,body);
        }
    }
    static final class MatchRow extends LinearLayout {
        static final int BOTH=-1;
        private static final float[] WEIGHTS={0,.62f,1.30f,1.14f,1f,1f,1.1f};
        final int side;
        MatchRow(Context c,int side){super(c);this.side=side;setGravity(Gravity.CENTER_VERTICAL);}
        void cells(View[] views,boolean header){
            Context c=getContext();
            int gap=dp(c,2);
            int height=header?dp(c,32):dp(c,Math.max(48,32*c.getResources().getConfiguration().fontScale));
            for(int offset=1;offset<=views.length;offset++){
                int i=offset%views.length;
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(i==0?dp(c,48):0,
                        header?height-2*gap:height,WEIGHTS[i]);
                lp.setMargins(i==0?0:gap,gap,i==0?0:gap,gap);
                addView(views[i]==null?new Space(c):views[i],lp);
            }
        }
    }
    private static void addMatch(LinearLayout parent,String side,int lane,Matches model,boolean editable){
        Button add=CompanionActions.secondary(parent.getContext(),"Add "+side.toLowerCase(java.util.Locale.ROOT),()->model.add(lane));
        add.setEnabled(editable && model.canAdd(lane));parent.addView(add);
    }
    private static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private static LinearLayout.LayoutParams part(){return new LinearLayout.LayoutParams(0,-2,1);}
}
