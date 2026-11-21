package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

final class CompanionScreen {
    final LinearLayout diagnostics;
    private final Activity activity;
    private final LinearLayout presets, rows, dock;
    private final TextView heading;
    private final View bankSelector, presetList;
    private final TextView feedback, connectionState, activeName;
    private final Button refresh, editor;
    private final Button[] banks = new Button[2];
    private boolean busy, recovery;
    private PresetDirectory directory;
    private IntConsumer select;
    private int group;
    private final List<Integer> occupied = new ArrayList<>();

    CompanionScreen(Activity activity, Runnable showPresets, Runnable showDiagnostics, Runnable read,
                    IntConsumer destination, IntConsumer changeBank, Runnable openEditor) {
        this.activity = activity;
        LinearLayout root = column();
        root.setBackgroundColor(LabStyle.BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setOnApplyWindowInsetsListener((v,i)->{ ProbeActivity.applyInsets(v,i); return i; });
        activity.setContentView(root);
        LinearLayout pane = new LinearLayout(activity) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(w),dp(660)),MeasureSpec.EXACTLY),h);
            }
        };
        pane.setOrientation(LinearLayout.VERTICAL);
        root.addView(pane,-1,-1);
        LabStyle.header(activity,pane,showDiagnostics);
        
        LinearLayout pages = column();
        pages.setPadding(dp(16),dp(4),dp(16),dp(8));
        pane.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        dock = LabStyle.nav(activity,pane,LabStyle.PRESETS,i->{if(i==LabStyle.PRESETS)showPresets.run();else destination.accept(i);});
        presets = column();
        diagnostics = column();
        pages.addView(presets,-1,-1);
        pages.addView(diagnostics,-1,-1);

        editor = CompanionActions.secondary(activity,"PEQ",openEditor);
        heading=text("Presets",24,LabStyle.INK,true);presets.addView(heading);
        LinearLayout device = row();
        spaced(presets,device,8);
        LinearLayout details=column();
        device.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        details.addView(text("Qudelix 5K",16,LabStyle.INK,true));
        connectionState=text("",13,LabStyle.MUTED,false);
        details.addView(connectionState);
        refresh=CompanionActions.secondary(activity,"Connect", read);
        device.addView(refresh,-2,-2);
        feedback=text("",13,LabStyle.INK,false);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        spaced(presets,feedback,4);

        String[] labels = AppearanceActivity.stageTabs(activity);
        for (int i=0;i<banks.length;i++) {
            int bank=i;
            banks[i]=CompanionActions.secondary(activity,labels[i],()->changeBank.accept(bank));
        }
        bankSelector=LabStyle.switcher(activity,banks[0],banks[1],0);spaced(presets,bankSelector,6);
        activeName=text("",14,LabStyle.INK,true);
        activeName.setMaxLines(2);
        activeName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        spaced(presets,activeName,6);

        ScrollView list=new ScrollView(activity);presetList=list;rows=column();list.addView(rows);
        presets.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        tab(false);
    }

    void connectionOnly(){
        heading.setText("Connect your 5K");
        bankSelector.setVisibility(View.GONE);activeName.setVisibility(View.GONE);presetList.setVisibility(View.GONE);
    }
    void tab(boolean debug) {
        diagnostics.setVisibility(debug?View.VISIBLE:View.GONE);
        presets.setVisibility(debug?View.GONE:View.VISIBLE);
        dock.getChildAt(2).setSelected(!debug);
    }

    void render(PresetDirectory list,int group,IntConsumer select,boolean recovery) {
        this.group=group;
        this.select=select;
        this.recovery=recovery;
        directory=list;
        boolean connected=list!=null;
        connectionState.setText(connected?"Control link verified":"Disconnected");
        refresh.setText(connected?"Refresh":"Connect");
        for(int i=0;i<2;i++) LabStyle.markTab(banks[i], i==group);
        occupied.clear();
        if(connected) for(int i=0;i<PresetDirectory.COUNT;i++)
            if(list.name(group,i)!=null) occupied.add(i);
        activeName.setText(!connected ? "Connect your 5K to see its saved presets."
                : "Active · "+list.activeName(group)+(list.enabled(group)?"":" · EQ bypassed"));
        feedback(recovery?"Open Debug to finish EQ restoration.":"");
        renderPage();
    }

    private void renderPage(){
        rows.removeAllViews();
        if(directory!=null && occupied.isEmpty())
            rows.addView(text("No named presets in this bank.",14,LabStyle.MUTED,false));
        for(int i=0;i<occupied.size();i++) {
            int slot=occupied.get(i);
            String name=directory.name(group,slot);
            String label=name.isEmpty()?"Unnamed preset":name;
            boolean active=directory.selector(group)==PresetDirectory.FIRST_CUSTOM+slot;
            LinearLayout item=row();
            item.setPadding(dp(8),0,dp(8),0);
            item.setBackground(LabStyle.surface(activity,active?LabStyle.ACTIVE:LabStyle.BG,0,8));
            item.addView(text(String.format(java.util.Locale.ROOT,"%02d",slot+1),14,LabStyle.MUTED,false),dp(32),-2);
            TextView title=text(label,15,LabStyle.INK,true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            TextView suffix=text(active?"Active":"",14, LabStyle.BLUE,true);
            suffix.setPadding(dp(6),0,0,0);
            item.addView(suffix);
            item.setSelected(active);
            item.setTag(slot);
            item.setFocusable(true);
            item.setContentDescription("Slot "+(slot+1)+", "+label+(active?", active":", load preset"));
            item.setOnClickListener(v->select.accept(slot));
            rows.addView(item,-1,dp(52));
            LabStyle.divider(activity,rows);
        }
        controls();
    }

    void feedback(String message) {
        feedback.setText(message);
        feedback.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
    }

    void enabled(boolean enabled,boolean recovery,boolean canNavigate) {
        busy=!enabled;
        this.recovery=recovery;
        for(int i=0;i<dock.getChildCount();i++)dock.getChildAt(i).setEnabled(canNavigate);
        controls();
    }

    private void controls() {
        refresh.setEnabled(!busy);
        editor.setEnabled(!busy);
        for(Button bank:banks) bank.setEnabled(!busy);
        for(int i=0;i<rows.getChildCount();i++) {
            View v=rows.getChildAt(i);
            if(v.getTag()==null)continue;
            v.setEnabled(!busy && !recovery);
            v.setAlpha(v.isEnabled()?1f:.60f);
        }
    }

    void restoreRowFocus(int slot) {
        if (!rows.isInTouchMode()) {
            View row=rows.findViewWithTag(slot);
            if(row!=null)row.requestFocus();
        }
    }

    private TextView text(String s,int size,int color,boolean bold){return LabStyle.text(activity,s,size,color,bold);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void spaced(LinearLayout parent,View child,int gap){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=dp(gap);
        parent.addView(child,lp);
    }
    private int dp(int n){return LabStyle.dp(activity,n);}
}
