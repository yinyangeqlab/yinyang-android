package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

final class CompanionResponse extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ResponseTraces.Stage stage;
    private final EqGraphInputs inputs;
    private ResponseTraces traces;
    private PeqResponse main;
    private PeqResponse[] ears;

    CompanionResponse(Context context, ResponseTraces.Stage stage, EqGraphInputs inputs,
                      PeqResponse main, PeqResponse... ears) {
        super(context);
        this.stage=stage;
        this.inputs=inputs;
        update(main,ears);
    }

    void update(PeqResponse main,PeqResponse[] ears){
        this.main=main;
        this.ears=ears;
        refresh();
    }

    void refresh(){
        traces=new ResponseTraces(stage,main,ears,inputs.left,inputs.right,inputs.target,inputs.after);
        String title=stage==ResponseTraces.Stage.MATCH
                ? traces.measured ? (inputs.after?"After match, ":"Before match, ")+"left and right deviation from the fixed reference"
                                  : "Left and right filter correction"
                : (traces.measured?"Estimated headphone response":"EQ filter response")+(traces.target?" and target":"");
        setContentDescription(title);
        invalidate();
    }

    private int dp(int size){return LabStyle.dp(getContext(),size);}

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(11));
        float amplitude=traces.amplitude;
        float left=Math.max(dp(32),paint.measureText("-"+Math.round(amplitude))+dp(6));
        float right=getWidth()-dp(5), top=dp(12), bottom=getHeight()-dp(23);
        int[] frequencies={20,100,1000,5000,20000};
        String[] labels={"20 Hz","100","1k","5k","20k"};
        for(int i=0;i<frequencies.length;i++){
            float x=left+(right-left)*(float)(Math.log10(frequencies[i]/20.0)/3);
            paint.setColor(LINE);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(x,top,x,bottom,paint);
            paint.setColor(MUTED);
            float width=paint.measureText(labels[i]);
            canvas.drawText(labels[i],x-(i==0?0:i==frequencies.length-1?width:width/2),bottom+dp(15),paint);
        }
        for(int tick=-2;tick<=2;tick++){
            float y=top+(bottom-top)*(2+tick)/4;
            boolean reference=stage==ResponseTraces.Stage.MATCH && tick==0;
            if(!reference || inputs.zeroLine){
                paint.setColor(reference?MUTED:LINE);
                canvas.drawLine(left,y,right,y,paint);
            }
            paint.setColor(MUTED);
            canvas.drawText(String.valueOf(Math.round(-tick*amplitude/2)),dp(1),y+dp(4),paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        for(int channel=traces.lines.length-1;channel>=0;channel--){
            int accent=stage==ResponseTraces.Stage.MATCH
                    ? channel==0?CompanionEditor.left(getContext(),null):CompanionEditor.right(getContext())
                    : channel==0?BLUE:MUTED;
            paint.setColor(accent);
            paint.setStrokeWidth(dp(stage==ResponseTraces.Stage.MATCH || channel==0?2:1));
            Path line=new Path();
            boolean started=false;
            float[] values=traces.lines[channel];
            for(int point=0;point<values.length;point++){
                if(!Float.isFinite(values[point])){started=false;continue;}
                float x=left+(right-left)*point/(values.length-1);
                float y=top+(bottom-top)*(1-values[point]/amplitude)/2;
                if(started)line.lineTo(x,y);
                else line.moveTo(x,y);
                started=true;
            }
            canvas.drawPath(line,paint);
        }
    }
}
