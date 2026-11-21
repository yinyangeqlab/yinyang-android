package com.yinyangeq.qudelixprobe;
import android.content.Context;
import android.graphics.*;
import android.view.View;

final class ResponseView extends View {
    private final PeqResponse[] curves;
    private final int channel;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    ResponseView(Context c, Peq peq, int ch) {
        super(c);
        channel = ch;
        curves = new PeqResponse[peq.channels];
        for (int n = 0; n < curves.length; n++) curves[n] = new PeqResponse(peq, n);
        setContentDescription("Draft EQ frequency response, 20 hertz to 20 kilohertz, at 48 kilohertz");
    }
    ResponseView(Context c, Peq a, Peq b, int ch, int heard) {
        super(c);
        channel = heard == 1 ? 1 : 0;
        curves = new PeqResponse[]{new PeqResponse(a,ch),new PeqResponse(b,ch)};
        setContentDescription("Comparison response before level match. A and B, 20 hertz to 20 kilohertz.");
    }
    @Override
    protected void onDraw(Canvas canvas) {
        float scale = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.scale(scale, scale);
        float left = 32, right = getWidth() / scale - 9, top = 12, bottom = getHeight() / scale - 24;
        if (right <= left || bottom <= top) {
            canvas.restore();
            return;
        }
        double[][] db = new double[curves.length][160];
        double bound = 12;
        for (int c = 0; c < curves.length; c++)
            for (int i = 0; i < 160; i++) {
                db[c][i] = curves[c].db(20 * Math.pow(1000, i / 159.0));
                bound = Math.max(bound, Math.abs(db[c][i]));
            }
        bound = Math.min(72, Math.ceil(bound / 6) * 6);
        paint.setTextSize(10);
        paint.setStrokeWidth(1);
        paint.setStyle(Paint.Style.FILL);
        for (int k = -1; k <= 1; k++) {
            float y = (top + bottom) / 2 - k * (bottom - top) / 2;
            paint.setColor(LabStyle.LINE);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(LabStyle.MUTED);
            canvas.drawText(k == 0 ? "0 dB" : (k == 1 ? "+" : "−") + (int) bound, 1, y + 3, paint);
        }
        int[] ticks = {20, 100, 1000, 5000, 20000};
        String[] labels = {"20", "100", "1k", "5k", "20k"};
        for (int i = 0; i < ticks.length; i++) {
            float x = left + (right - left) * (float) (Math.log(ticks[i] / 20.0) / Math.log(1000));
            paint.setColor(LabStyle.LINE);
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setColor(LabStyle.MUTED);
            float width = paint.measureText(labels[i]);
            canvas.drawText(
                    labels[i], Math.max(left, Math.min(right - width, x - width / 2)), bottom + 15, paint);
        }
        canvas.clipRect(left, top - 2, right + 1, bottom + 2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int n = 0; n < curves.length; n++) {
            int c = curves.length == 1 ? 0 : (n == 0 ? 1 - channel : channel);
            paint.setColor(c == 0 ? LabStyle.BLUE : LabStyle.CORAL);
            paint.setAlpha(c == channel ? 255 : 160);
            paint.setStrokeWidth(c == channel ? 2.6f : 1.4f);
            Path p = new Path();
            for (int i = 0; i < 160; i++) {
                float x = left + (right - left) * i / 159,
                      y = (float) ((bottom + top) / 2 - db[c][i] * (bottom - top) / (2 * bound));
                if (i == 0)
                    p.moveTo(x, y);
                else
                    p.lineTo(x, y);
            }
            canvas.drawPath(p, paint);
        }
        paint.setAlpha(255);
        canvas.restore();
    }
}
