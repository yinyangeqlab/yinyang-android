package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.SeekBar;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** A native adjustable range with a filled gauge and a readable value in either orientation. */
final class FilledVolumeBar extends SeekBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path shape = new Path();
    private final int accent;
    private int floor;
    private boolean decibels, available=true;

    FilledVolumeBar(Context context,int color) {
        super(context);
        accent=color;
        setThumb(null);
        setPadding(dp(context,4),0,dp(context,4),0);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setFocusable(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }
    void hardware(int rawFloor, boolean hasValue) {
        decibels=true; floor=rawFloor; available=hasValue; invalidate();
    }
    @Override protected synchronized void onDraw(Canvas canvas) {
        float inset=dp(getContext(),4), h=getHeight(), w=getWidth(), radius=dp(getContext(),9);
        float fraction=(getProgress()-getMin())/(float)Math.max(1,getMax()-getMin());
        bounds.set(inset,inset,w-inset,h-inset);
        paint.setColor(PANEL); canvas.drawRoundRect(bounds,radius,radius,paint);
        shape.reset(); shape.addRoundRect(bounds,radius,radius,Path.Direction.CW);
        canvas.save(); canvas.clipPath(shape);
        float split=bounds.left+fraction*bounds.width();
        int fill=isEnabled()?accent:MUTED;
        paint.setColor(fill);
        canvas.drawRect(bounds.left,bounds.top,split,bounds.bottom,paint);
        String value=!available ? "—" : decibels ? Peq.fmt("%+.1f dB",(floor+30*getProgress())/60.0) : Math.round(fraction*100)+"%";
        paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);
        boolean vertical=getRotation()==-90;
        if(vertical) paint.setTextSize(Math.min(paint.getTextSize(),paint.getTextSize()*(bounds.height()-dp(getContext(),4))/Math.max(1,paint.measureText(value))));
        for(int side=0;side<2;side++){
            canvas.save();
            canvas.clipRect(side==0?bounds.left:split,bounds.top,side==0?split:bounds.right,bounds.bottom);
            paint.setColor(side==0 ? CompanionActions.readable(fill) : INK);
            if(vertical) canvas.rotate(90,w/2,h/2);
            canvas.drawText(value,w/2,h/2-(paint.ascent()+paint.descent())/2,paint);
            canvas.restore();
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(getContext(),isFocused()?2:1));
        paint.setColor(isFocused()?INK:LINE);canvas.drawRoundRect(bounds,radius,radius,paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
