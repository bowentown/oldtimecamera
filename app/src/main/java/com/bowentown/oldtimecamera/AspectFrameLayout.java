package com.bowentown.oldtimecamera;

import android.content.Context;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Aspect-locked viewfinder container that measures itself and all overlay/date
 * children to the exact active camera buffer aspect ratio.
 */
public final class AspectFrameLayout extends FrameLayout {

    private int ratioW = 3;
    private int ratioH = 4;

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return;
        Runnable apply = () -> {
            if (ratioW != width || ratioH != height) {
                ratioW = width;
                ratioH = height;
                requestLayout();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            post(apply);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (width > 0 && height > 0 && ratioW > 0 && ratioH > 0) {
            float ratio = (float) ratioW / (float) ratioH;
            if ((float) width / (float) height > ratio) {
                width = Math.max(1, Math.round(height * ratio));
            } else {
                height = Math.max(1, Math.round(width / ratio));
            }
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
