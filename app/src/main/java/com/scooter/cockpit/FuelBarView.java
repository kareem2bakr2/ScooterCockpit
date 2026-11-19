package com.scooter.cockpit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class FuelBarView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF blockRect = new RectF();
    private int fuelPercent = 64;

    public FuelBarView(Context context) {
        super(context);
    }

    public FuelBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setFuelPercent(int percent) {
        this.fuelPercent = Math.max(0, Math.min(100, percent));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int blocks = 12;
        float gap = 5f;
        float totalGap = gap * (blocks - 1);
        float blockWidth = (getWidth() - totalGap) / blocks;
        float height = getHeight();
        int litBlocks = Math.round((fuelPercent / 100f) * blocks);

        for (int i = 0; i < blocks; i++) {
            paint.setColor(i < litBlocks ? Color.parseColor("#00E5FF") : Color.parseColor("#232A35"));
            float left = i * (blockWidth + gap);
            blockRect.set(left, 0, left + blockWidth, height);
            canvas.drawRoundRect(blockRect, 3f, 3f, paint);
        }
    }
}