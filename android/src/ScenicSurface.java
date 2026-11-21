package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

/** One scenic surface behind the existing controls. Reserve the lower half for the EQ. */
final class ScenicSurface extends Drawable {
    private static final SparseArray<Bitmap> images = new SparseArray<>();
    private final Bitmap image;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int base;
    private final int topShade, titleShade;
    private final float dp;
    private final RectF frame = new RectF();
    private final Rect crop = new Rect();
    private Shader fade;

    ScenicSurface(Context context, int resource) {
        Bitmap bitmap = images.get(resource);
        if (bitmap == null) {
            bitmap = BitmapFactory.decodeResource(context.getResources(), resource);
            images.put(resource, bitmap);
        }
        image = bitmap;
        base = LabStyle.BG;
        // Light from the canopy passes behind the title. Preserve its contrast in that part of the image.
        boolean canopy = resource == R.drawable.theme_forest;
        topShade = canopy ? 0x87000000 : 0x55000000;
        titleShade = canopy ? 0x95000000 : 0x38000000;
        dp = context.getResources().getDisplayMetrics().density;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        float h = Math.min(bounds.height()*.46f, 220*dp);
        frame.set(bounds.left, bounds.top, bounds.right, bounds.top+h);
        if (frame.isEmpty()) return;
        float scale = Math.max(frame.width()/image.getWidth(),h/image.getHeight());
        int w = Math.round(frame.width()/scale), ih = Math.round(h/scale);
        int x = (image.getWidth()-w)/2, y = (image.getHeight()-ih)/3;
        crop.set(x,y,x+w,y+ih);
        int rgb = base & 0x00ffffff;
        fade = new LinearGradient(0,frame.top,0,frame.bottom,
            new int[]{rgb|topShade,rgb|titleShade,rgb|0x7a000000,rgb|0xd9000000,base},
            new float[]{0,.22f,.43f,.71f,1}, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawColor(base);
        if (frame.isEmpty()) return;
        paint.setShader(null);
        canvas.drawBitmap(image,crop,frame,paint);
        paint.setShader(fade);
        canvas.drawRect(frame,paint);
        paint.setShader(null);
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity(){return android.graphics.PixelFormat.OPAQUE;}
}
