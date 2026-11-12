package com.bowentown.oldtimecamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** The sprocket holes along the edge of a 35mm film strip. Pure decoration. */
public final class PerforationView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public PerforationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.argb(190, 239, 231, 214));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = getHeight();
        float holeH = h * 0.46f;
        float holeW = h * 0.72f;
        float gap = holeW * 0.62f;
        float top = (h - holeH) / 2f;
        float radius = holeH * 0.22f;
        for (float x = h * 0.5f; x + holeW < getWidth(); x += holeW + gap) {
            rect.set(x, top, x + holeW, top + holeH);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
