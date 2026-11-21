package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.function.Consumer;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** Edit the entire filter atomically. A partial number never reaches a device. */
final class FilterTable {
    static final String[] TYPES={"Off","Low pass","High pass","Low shelf","High shelf","Peak"};
    interface Apply {void set(int type,String hz,String gain,String q);}
    static void cell(LinearLayout row,android.view.View v,float weight){
        row.addView(v,new LinearLayout.LayoutParams(0,-2,weight));
    }
    static EditText number(LinearLayout row,String label,String value,boolean enabled){
        EditText n=field(row.getContext(),label,value,enabled);numericCell(row,n);return n;
    }
    private static EditText field(Context c,String label,String value,boolean enabled){
        EditText n=new EditText(c);n.setTextColor(INK);
        n.setTextSize(15); n.setSingleLine(true);n.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        n.setGravity(Gravity.CENTER);n.setFontFeatureSettings("tnum");n.setPadding(dp(c,2),0,dp(c,2),0);
        n.setBackgroundTintList(null);n.setBackground(surface(c,PANEL,LINE,5));
        n.setMinHeight(dp(c,44));n.setText(value);n.setSelectAllOnFocus(true);n.setContentDescription(label);n.setEnabled(enabled);
        return n;
    }
    private static void numericCell(LinearLayout row,EditText n){
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,-2,1);
        int gap=dp(row.getContext(),2);size.setMargins(gap,gap,gap,gap);row.addView(n,size);
    }
    static void pre(LinearLayout parent,String name,int raw,boolean enabled,Consumer<String> apply){
        Context c=parent.getContext();LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);parent.addView(row);
        row.addView(text(c,name+" (dB)",15,INK,true));
        final boolean[] negative={raw<=0};
        Button sign=CompanionActions.buttonForSize(c,"",false,()->{});
        int inset=dp(c,8);
        sign.setBackground(new android.graphics.drawable.InsetDrawable(sign.getBackground(),inset));
        sign.setTag("preamp-sign");sign.setPadding(0,0,0,0);sign.setTextSize(18);
        row.addView(sign,dp(c,48),dp(c,48));
        EditText n=number(row,name+" magnitude in decibels",Peq.fmt("%.1f",Math.abs(raw)/10.0),enabled);
        n.setLayoutParams(new LinearLayout.LayoutParams(dp(c,80),-2));
        n.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.+-−"));
        n.setRawInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Runnable refresh=()->{
            String v=n.getText().toString();
            boolean explicit=v.startsWith("-")||v.startsWith("+")||v.startsWith("−");
            boolean minus=explicit ? !v.startsWith("+") : negative[0];
            sign.setText(minus?"−":"+");
            sign.setEnabled(enabled && !explicit);sign.setAlpha(sign.isEnabled()?1f:.42f);
            sign.setContentDescription(explicit ? name+" sign set by entry" :
                    name+(minus?" negative. Switch to positive":" positive. Switch to negative"));
        };
        class Commit implements Runnable {
            int saved=raw;
            public void run(){
                refresh.run();
                String value=n.getText().toString().replace('−','-');
                if(!complete(value))return;
                if(!value.startsWith("-") && !value.startsWith("+") && negative[0])value="-"+value;
                try{
                    int next=Peq.number(value,-24,24,10,name);
                    if(next!=saved){apply.accept(value);saved=next;}
                    n.setError(null);
                }catch(IllegalArgumentException error){n.setError(error.getMessage());}
            }
        }
        Commit save=new Commit();
        sign.setOnClickListener(v->{negative[0]=!negative[0];save.run();});
        watch(n,save);refresh.run();
    }
    private static boolean complete(String value){
        return value.matches("[+-]?(?:[0-9]+(?:[.][0-9]+)?|[.][0-9]+)");
    }
    private static void watch(EditText field,Runnable save){
        field.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(android.text.Editable s){save.run();}
        });
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
    }
    private static void removeSpace(LinearLayout row,android.view.View view){
        row.addView(view,new LinearLayout.LayoutParams(dp(row.getContext(),36),-2));
    }
    static final class SyncStatus extends LinearLayout {
        private final TextView description,state;
        private long started;
        private final Runnable settled=()->showVerified();
        SyncStatus(Context c){
            super(c);setGravity(Gravity.CENTER_VERTICAL);
            description=text(c,"",13,MUTED,false);addView(description);
            state=text(c,"",13,CompanionEditor.right(c),true);state.setPadding(dp(c,8),0,0,0);addView(state);
        }
        void message(String value){removeCallbacks(settled);started=0;description.setText(value);state.setText("");}
        void syncing(){
            removeCallbacks(settled);
            started=android.os.SystemClock.uptimeMillis();description.setText("EQ verification");state.setText("Syncing");
        }
        void verified(){
            removeCallbacks(settled);
            long delay=started+320-android.os.SystemClock.uptimeMillis();
            if(started!=0 && delay>0)postDelayed(settled,delay);else showVerified();
        }
        private void showVerified(){started=0;description.setText("EQ verified");state.setText("Synced");}
        @Override protected void onDetachedFromWindow(){removeCallbacks(settled);super.onDetachedFromWindow();}
    }
    static void preampScope(LinearLayout parent,boolean withChannelMatch) {
        String hint = withChannelMatch ? "Applies to both ears in Main EQ. Each ear’s Channel Match preamp adds to this value."
                : "Applies to both ears in Main EQ.";
        parent.addView(text(parent.getContext(),hint,13,MUTED,false));
    }
    static void headers(LinearLayout p){
        Context c=p.getContext();LinearLayout r=new LinearLayout(c);r.setBackgroundColor(PANEL);p.addView(r);
        removeSpace(r,new android.view.View(c));
        for(String v:new String[]{"Band","Filter","Hz","dB","Q"}){
            TextView head=text(c,v,12,MUTED,true);head.setGravity(Gravity.CENTER);head.setMinHeight(dp(c,32));
            cell(r,head,v.equals("Band")?.55f:v.equals("Filter")?1.6f:1);
        }

    }
    enum Removal { LEFT, RIGHT, BOTH }
    interface ApplyPair {void set(int type,String hz,String left,String right,String q);}
    static void row(LinearLayout parent,String label,String[] types,int type,int hz,int gain,int q,boolean enabled,Apply apply,Runnable remove){
        editor(parent,label,types,type,hz,gain,null,q,enabled,
            (t,f,g,unused,width)->apply.set(t,f,g,width),scope->remove.run());
    }
    static void pairedRow(CompanionEditor.MatchRow parent,String label,String[] types,int type,int hz,int left,int right,int q,
                          boolean enabled,ApplyPair apply,Consumer<Removal> remove){
        editor(parent,label,types,type,hz,left,right,q,enabled,apply,remove);
    }
    private static String gainText(int gain,boolean signed){
        return Peq.fmt(signed && gain>0?"+%.1f":"%.1f",gain/10.0);
    }
    private static void editor(LinearLayout parent,String label,String[] types,int type,int hz,int gain,Integer otherGain,int q,
                               boolean enabled,ApplyPair apply,Consumer<Removal> remove){
        Context c=parent.getContext();
        CompanionEditor.MatchRow grid=parent instanceof CompanionEditor.MatchRow?(CompanionEditor.MatchRow)parent:null;
        boolean paired=otherGain!=null;
        String bandName="band "+label;
        String entry=grid==null?"Band "+label:paired?"Both ears "+bandName:(grid.side==0?"Left ":"Right ")+bandName;
        TextView band=text(c,label,14,INK,true);band.setGravity(Gravity.CENTER);
        Button del=CompanionActions.buttonForSize(c,"×",false,()->remove.accept(Removal.BOTH));
        quiet(del);del.setPadding(0,0,0,0);del.setMinimumWidth(0);del.setMinWidth(0);del.setTextSize(20);
        del.setContentDescription("Remove "+entry);del.setEnabled(enabled);
        if(paired)del.setOnClickListener(v->{
            PopupMenu menu=new PopupMenu(c,del);
            for(Removal scope:Removal.values()){
                String side=scope==Removal.LEFT?"left":scope==Removal.RIGHT?"right":"both ears";
                menu.getMenu().add("Remove "+side).setOnMenuItemClickListener(item->{remove.accept(scope);return true;});
            }
            menu.show();
        });
        Button typeButton=CompanionActions.buttonForSize(c,types[type],false,()->{});
        typeButton.setPadding(dp(c,2),0,dp(c,2),0);typeButton.setEnabled(enabled);
        typeButton.setContentDescription(entry+" filter type, "+types[type]);
        final int[] choice={type};
        EditText f=field(c,entry+" frequency",String.valueOf(hz),enabled);
        EditText g=field(c,(paired?"Left "+bandName:entry)+" gain",gainText(gain,grid!=null),enabled);
        EditText rightGain=paired?field(c,"Right "+bandName+" gain",gainText(otherGain,true),enabled):null;
        EditText width=field(c,entry+" Q",Peq.fmt("%.4f",q/1024.0).replaceFirst("0+$","").replaceFirst("[.]$",""),enabled);
        EditText[] inputs=paired?new EditText[]{f,g,rightGain,width}:new EditText[]{f,g,width};
        if(grid!=null){
            int left=CompanionEditor.left(c,null),right=CompanionEditor.right(c);
            boolean rightRow=grid.side==1;
            if(!paired){band.setTextColor(rightRow?right:left);del.setTextColor(rightRow?right:left);}
            g.setTextColor(rightRow?right:left);
            if(paired)rightGain.setTextColor(right);
            typeButton.setTextSize(12);for(EditText n:inputs)n.setTextSize(13);
            grid.cells(new View[]{del,band,typeButton,f,rightRow?null:g,rightRow?g:rightGain,width},false);
        }else{
            LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);parent.addView(row);divider(c,parent);
            removeSpace(row,del);cell(row,band,.55f);cell(row,typeButton,1.6f);for(EditText n:inputs)numericCell(row,n);
        }
        class Commit implements Runnable {
            String signature(){return choice[0]+"/"+f.getText()+"/"+g.getText()+"/"+(paired?rightGain.getText():"")+"/"+width.getText();}
            String saved=signature();
            public void run(){
                String now=signature();
                if(saved.equals(now))return;
                for(EditText n:inputs)if(!complete(n.getText().toString()))return;
                String previous=saved;saved=now;
                try{
                    apply.set(choice[0],f.getText().toString(),g.getText().toString(),paired?rightGain.getText().toString():null,width.getText().toString());
                    for(EditText n:inputs)if(n.getError()!=null)n.setError(null);
                }catch(IllegalArgumentException e){saved=previous;
                    String msg=e.getMessage();EditText bad=msg!=null && msg.toLowerCase(java.util.Locale.ROOT).contains("gain")?
                        (paired && rightGain.hasFocus()?rightGain:g):msg!=null && msg.toLowerCase(java.util.Locale.ROOT).contains("frequency")?f:width;
                    bad.setError(msg);}
            }
        }
        Commit save=new Commit();
        typeButton.setOnClickListener(v->{PopupMenu menu=new PopupMenu(c,v);for(int i=0;i<types.length;i++)menu.getMenu().add(0,i+1,i,types[i]);
            menu.setOnMenuItemClickListener(item->{choice[0]=item.getItemId()-1;typeButton.setText(types[choice[0]]);typeButton.setContentDescription(entry+" filter type, "+types[choice[0]]);save.run();return true;});menu.show();});
        for(EditText n:inputs)watch(n,save);
    }
}
