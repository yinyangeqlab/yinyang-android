package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

final class CompanionResponse extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[][] traces;
    private final int leftAccent,rightAccent;
    CompanionResponse(Context c,PeqResponse shared,PeqResponse[] channels,int left,int right) {
        super(c);leftAccent=left;rightAccent=right;
        update(shared,channels);
    }
    void update(PeqResponse shared,PeqResponse[] channels){
        traces=new float[channels.length][161];
        for(int ear=0;ear<channels.length;ear++)for(int i=0;i<161;i++){
            double hz=20*Math.pow(1000,i/160.0);
            traces[ear][i]=(float)((shared==null?0:shared.db(hz))+(channels[ear]==null?0:channels[ear].db(hz)));
        }
        invalidate();
        setContentDescription(channels.length==2?"Estimated left and right filter response plot.":"Estimated shared filter response plot.");
    }
    private int dp(int size){return LabStyle.dp(getContext(),size);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(32), right = getWidth() - dp(3), top = dp(14), bottom = getHeight() - dp(24);
            float amplitude = 6;
            for (float[] trace : traces) for (float value : trace) amplitude = Math.max(amplitude, Math.abs(value));
            amplitude = (float) (Math.ceil(amplitude / 6) * 6);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(10));
            int[] frequencies = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
            for (int hz : frequencies) {
                float x = left + (right - left) * (float)(Math.log10(hz / 20.0) / 3);
                paint.setColor(LINE);
                paint.setStrokeWidth(dp(1));
                canvas.drawLine(x, top, x, bottom, paint);
                if (hz == 20 || hz == 100 || hz == 1000 || hz == 5000 || hz == 20000) {
                    String label = hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
                    paint.setColor(MUTED);
                    float offset = hz == 20 ? 0 : hz == 20000 ? paint.measureText(label) : paint.measureText(label) / 2;
                    canvas.drawText(label, x - offset, bottom + dp(15), paint);
                }
            }
            for (int tick = -2; tick <= 2; tick++) {
                float y = top + (bottom - top) * (2 + tick) / 4;
                paint.setColor(LINE);
                canvas.drawLine(left, y, right, y, paint);
                paint.setColor(MUTED);
                canvas.drawText(String.valueOf(Math.round(-tick * amplitude / 2)), dp(1), y + dp(4), paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            for (int channel = 0; channel < traces.length; channel++) {
                int accent = channel == 0 ? leftAccent : rightAccent;
                if (accent == 0xffffffff && AppearanceActivity.theme(getContext()) == 2) accent = 0xff616772;
                paint.setColor(traces.length == 1 ? BLUE : accent);
                paint.setStrokeWidth(dp(channel == 0 ? 2 : 1));
                paint.setPathEffect(null);
                Path path = new Path();
                for (int point = 0; point < traces[channel].length; point++) {
                    float x = left + (right - left) * point / (traces[channel].length - 1);
                    float y = top + (bottom - top) * (1 - traces[channel][point] / amplitude) / 2;
                    if (point == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                canvas.drawPath(path, paint);
            }
            paint.setPathEffect(null);
        }
    }
