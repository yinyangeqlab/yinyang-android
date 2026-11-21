package com.yinyangeq.qudelixprobe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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
    static void stages(LinearLayout parent, int active, boolean plus, IntConsumer select) {
        Context c=parent.getContext();
        LinearLayout tabs=new LinearLayout(c);
        String[] titles={"Main EQ","Channel Match","30-band"};
        String[] subtitles={"USR","SPK","Main only"};
        String[] labels=AppearanceActivity.stageTabs(c);
        for(int i=0;i<(plus?3:2);i++) {
            final int bank=i;
            LinearLayout col=column(c);
            Button button=CompanionActions.buttonForSize(c,"",false,()->{if(bank!=active)select.accept(bank);});
            quiet(button);
            android.text.SpannableString label=new android.text.SpannableString(titles[i]+"\n"+subtitles[i]);
            label.setSpan(new android.text.style.RelativeSizeSpan(.83f),titles[i].length()+1,label.length(),0);
            button.setText(label);
            button.setContentDescription(i<labels.length?labels[i]:"30-band");
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
    static void responseTitle(LinearLayout parent,String title,boolean stereo){
        Context c=parent.getContext();
        LinearLayout row=new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(c,10),0,0);
        row.addView(text(c,title,14,INK,true),part());
        if(stereo){
            row.addView(text(c,"Left",13,left(c,null),true));
            TextView right=text(c,"Right",13,right(c),true);
            right.setPadding(dp(c,12),0,0,0);row.addView(right);
        }
        parent.addView(row);
    }
    static void preset(LinearLayout parent,String name,boolean open,boolean enabled,Runnable toggleList){
        Context c=parent.getContext();
        LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);parent.addView(row);
        LinearLayout identity=column(c);row.addView(identity,part());
        identity.addView(text(c,"Current preset",13,MUTED,false));
        TextView title=text(c,name,17,INK,true);title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);identity.addView(title);
        Button browse=CompanionActions.secondary(c,open?"Close preset list":"Load preset",toggleList);
        quiet(browse);browse.setEnabled(enabled);row.addView(browse);
    }
    static void filterHeading(LinearLayout parent,String name,String count,boolean canReload,Runnable reload,
                              boolean canClear,Runnable clear){
        Context c=parent.getContext();
        LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(c,10),0,dp(c,4));parent.addView(row);
        LinearLayout labels=column(c);row.addView(labels,part());
        TextView title=text(c,name,19,INK,true);
        if(android.os.Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);
        labels.addView(title);
        if(count!=null)labels.addView(text(c,count,13,MUTED,false));
        Button restore=CompanionActions.secondary(c,"Reload",reload);
        restore.setMinWidth(dp(c,76));restore.setEnabled(canReload);row.addView(restore);
        Button remove=CompanionActions.secondary(c,"Clear filters",clear);
        remove.setEnabled(canClear);
        LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-2,-2);
        spacing.setMarginStart(dp(c,4));
        row.addView(remove,spacing);
    }
    static Button saves(LinearLayout parent,String device,boolean canSave,Runnable saveDevice,String phone,boolean canSavePhone,Runnable savePhone){
        Context c=parent.getContext();
        LinearLayout actions=new LinearLayout(c);parent.addView(actions);
        Button hardware=CompanionActions.buttonForSize(c,device,true,saveDevice);
        hardware.setMinHeight(dp(c,48));hardware.setEnabled(canSave);actions.addView(hardware,part());
        Button local=CompanionActions.buttonForSize(c,phone,false,savePhone);
        local.setMinHeight(dp(c,48));local.setEnabled(canSavePhone);
        LinearLayout.LayoutParams params=part();params.setMarginStart(dp(c,6));actions.addView(local,params);
        return hardware;
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
            for(int i=0;i<views.length;i++){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(i==0?dp(c,36):0,height-2*gap,WEIGHTS[i]);
                lp.setMargins(gap,gap,gap,gap);addView(views[i]==null?new Space(c):views[i],lp);
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
