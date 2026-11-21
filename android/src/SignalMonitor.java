package com.yinyangeq.qudelixprobe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.text.InputType;
import android.widget.*;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** RMS-based proximity warning. Firmware RMS is not a sample peak or a digital overload latch. */
final class SignalMonitor extends LinearLayout {
    boolean nominal;
    private final TextView signal, spl;
    private final HeadroomBar signalBar;
    private final ProgressBar splBar;
    private final TextView specs;
    private double previewDb,lastSpl=Double.NaN;
    private final SharedPreferences settings;
    private double left,right,mv,ohms,sensitivity;
    private static final double UNKNOWN=Double.NaN;
    SignalMonitor(Context c) {
        super(c);setOrientation(VERTICAL);
        settings=c.getSharedPreferences("volume-headphone-load",Context.MODE_PRIVATE);
        spl=text(c,"",17,INK,true);addView(spl);
        splBar=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);
        splBar.setMax(1000);splBar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        addView(splBar,-1,dp(c,12));
        LinearLayout marks=new LinearLayout(c);addView(marks);
        for(String unit:new String[]{"40","80","120 dB SPL"}){
            TextView mark=text(c,unit,11,MUTED,false);
            mark.setGravity(unit.equals("40")?android.view.Gravity.START:unit.equals("80")?android.view.Gravity.CENTER:android.view.Gravity.END);
            marks.addView(mark,new LinearLayout.LayoutParams(0,-2,1));
        }
        signal=text(c,"",14,INK,true); addView(signal); signalBar=meter();
        specs=text(c,"Headphone specifications",13,BLUE,false);specs.setGravity(android.view.Gravity.CENTER_VERTICAL);
        specs.setMinHeight(dp(c,48));specs.setFocusable(true);specs.setOnClickListener(v->editLoad());
        addView(specs,new LinearLayout.LayoutParams(-2,-2));
        update(UNKNOWN,UNKNOWN,UNKNOWN,0,0);
    }
    private HeadroomBar meter(){
        HeadroomBar bar=new HeadroomBar(getContext());addView(bar,-1,dp(getContext(),15));return bar;
    }
    private static final class HeadroomBar extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rail=new RectF();
        private final Path clip=new Path();
        private double level=Double.NaN;
        HeadroomBar(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(double level){if(Double.compare(this.level,level)!=0){this.level=level;invalidate();}}
        @Override protected void onDraw(Canvas canvas){
            float h=getHeight(),w=getWidth(),radius=dp(getContext(),3);
            rail.set(0,h*.22f,w,h*.78f);paint.setColor(LINE);canvas.drawRoundRect(rail,radius,radius,paint);
            if(Double.isNaN(level)) return;
            clip.reset();clip.addRoundRect(rail,radius,radius,Path.Direction.CW);
            canvas.save();canvas.clipPath(clip);
            // The scale spans -36 through +3 dB relative to digital full scale.
            float x=(float)(w*Math.max(0,Math.min(1,(level+36)/39)));
            float green=w*30/39, yellow=w*35/39;
            band(canvas,0,green,x,0xff48b979);
            band(canvas,green,yellow,x,0xffffc24a);
            band(canvas,yellow,w,x,0xfff2616b);
            if(Double.isFinite(level)){paint.setColor(INK);canvas.drawRect(x-dp(getContext(),1),rail.top,x+dp(getContext(),1),rail.bottom,paint);}
            canvas.restore();
        }
        private void band(Canvas c,float start,float end,float cursor,int color){
            paint.setColor(color);paint.setAlpha(68);c.drawRect(start,rail.top,end,rail.bottom,paint);
            if(cursor>start){paint.setAlpha(255);c.drawRect(start,rail.top,Math.min(end,cursor),rail.bottom,paint);}
        }
    }
    private EditText number(LinearLayout fields,String title,String key) {
        fields.addView(text(getContext(),title,14,INK,false));
        EditText input=new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(settings.getString(key,""));fields.addView(input);return input;
    }
    private void editLoad(){
        Context c=getContext();
        LinearLayout fields=new LinearLayout(c);fields.setOrientation(VERTICAL);
        int pad=dp(c,20);fields.setPadding(pad,0,pad,0);
        EditText z=number(fields,"Impedance (Ω)","ohms"),s=number(fields,"Sensitivity (dB SPL/mW)","sensitivity");
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle("Headphone specifications").setView(fields)
                .setPositiveButton("Use these values",null).setNegativeButton("Cancel",null)
                .setNeutralButton("Use device values",(d,w)->{ settings.edit().clear().apply();refresh(); }).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            try {
                double impedance=Double.parseDouble(z.getText().toString()),sens=Double.parseDouble(s.getText().toString());
                if(!(impedance>0 && impedance<=20000)){z.setError("Enter a value above 0 and up to 20000 Ω");return;}
                if(!(sens>0 && sens<=200)){s.setError("Enter a value above 0 and up to 200 dB SPL/mW");return;}
                settings.edit().putString("ohms",Double.toString(impedance)).putString("sensitivity",Double.toString(sens)).apply();
                refresh();dialog.dismiss();
            }catch(NumberFormatException empty){Toast.makeText(c,"Enter both headphone specifications.",Toast.LENGTH_SHORT).show();}
        }));dialog.show();
    }
    void update(double left,double right,double mv,double impedance,double sensitivity){
        this.left=left;this.right=right;this.mv=mv;this.ohms=impedance;this.sensitivity=sensitivity;refresh();
    }
    String sliderEstimate(){
        if(lastSpl==Double.NEGATIVE_INFINITY)return " · Silence";
        return Double.isFinite(lastSpl)?Peq.fmt(" · ≈ %.1f dB SPL",lastSpl):"";
    }
    void previewGain(double deltaDb){
        previewDb=Double.isFinite(deltaDb)?deltaDb:0;
        refresh();
    }
    private void refresh(){
        boolean override=settings.contains("ohms") && settings.contains("sensitivity");
        double z=override ? Double.parseDouble(settings.getString("ohms","0")) : ohms;
        double sens=override ? Double.parseDouble(settings.getString("sensitivity","0")) : sensitivity;
        double level=Math.max(left,right);
        boolean unavailable=Double.isNaN(level);

        SignalReading.State state=SignalReading.state(level);
        String warning;
        switch(state) {
            case CLIPPING_RISK: warning="Clipping risk · full scale";break;
            case NEAR_FULL_SCALE: warning="Clipping risk";break;
            case LOW_HEADROOM: warning="Approaching full scale";break;
            default: warning="";
        }
        boolean light=android.graphics.Color.luminance(BG)>.5;
        int color=unavailable ? MUTED : level>=-1 ? (light?0xffa62329:0xffff757c) : level>=-6 ?
                (light?0xff765400:0xffffcd60) : (light?0xff146d41:0xff75dba4);
        signal.setText("Input RMS   "+(unavailable?"Awaiting audio":level==Double.NEGATIVE_INFINITY?"−∞ dBFS":Peq.fmt("%.1f dBFS",level))
                +(warning.isEmpty()?"":"   ·   "+warning));
        signal.setTextColor(color);signalBar.set(level);
        double measured=SignalReading.spl(level,mv,z,sens);
        double projected=measured+previewDb;lastSpl=projected;
        String value=Double.isFinite(projected)?Peq.fmt("%.1f dB",projected):projected==Double.NEGATIVE_INFINITY?"Silence":"—";
        spl.setText((previewDb!=0?"Projected SPL":nominal?"Estimated SPL (pre-EQ)":"Estimated SPL")+"   "+value);
        splBar.setProgress(Double.isFinite(projected)?(int)Math.round(1000*Math.max(0,Math.min(1,(projected-40)/80))):0);
        splBar.setContentDescription("Estimated sound pressure "+value);
        specs.setText(z>0 && sens>0?Peq.fmt("Headphone specs · %.1f Ω / %.1f dB SPL/mW",z,sens):"Set headphone specs for SPL");
        specs.setContentDescription("Edit headphone specifications for the SPL estimate");

    }
}
