package com.scooter.cockpit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class RpmArcView extends View {

    private final Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private int currentRpm = 6400;
    private int maxRpm = 10000;

    public RpmArcView(Context context) {
        super(context);
        init();
    }

    public RpmArcView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        segPaint.setStyle(Paint.Style.STROKE);
        segPaint.setStrokeCap(Paint.Cap.BUTT);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
    }

    public void setRpm(int rpm, int max) {
        this.currentRpm = Math.max(0, Math.min(rpm, max));
        this.maxRpm = max;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float stroke = 18f;
        segPaint.setStrokeWidth(stroke);

        float diameter = Math.min(w * 0.86f, h * 1.95f);
        float radius = diameter / 2f;
        float cx = w / 2f;
        float cy = radius + stroke * 0.75f;
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        int totalSegments = 34;
        float startAngle = 198f;
        float totalSweep = 144f;
        float gap = 1.6f;
        float segSweep = (totalSweep - (totalSegments - 1) * gap) / totalSegments;
        int activeSegments = Math.round(((float) currentRpm / maxRpm) * totalSegments);

        for (int i = 0; i < totalSegments; i++) {
            boolean isLit = i < activeSegments;
            if (i >= 30) {
                segPaint.setColor(isLit ? Color.parseColor("#EF4444") : Color.parseColor("#5C1919"));
            } else if (i >= 27) {
                segPaint.setColor(isLit ? Color.parseColor("#F59E0B") : Color.parseColor("#593A12"));
            } else {
                segPaint.setColor(isLit ? Color.parseColor("#00E5FF") : Color.parseColor("#232A35"));
            }
            float angle = startAngle + i * (segSweep + gap);
            canvas.drawArc(arcBounds, angle, segSweep, false, segPaint);
        }

        // Draw 0..10 numbers inside the arc
        labelPaint.setTextSize(15f);
        float labelRadius = radius - stroke - 16f;
        for (int n = 0; n <= 10; n++) {
            if (n >= 9) {
                labelPaint.setColor(Color.parseColor("#EF4444"));
            } else if (n == 8) {
                labelPaint.setColor(Color.parseColor("#F59E0B"));
            } else {
                labelPaint.setColor(Color.parseColor("#8B949E"));
            }
            double rad = Math.toRadians(startAngle + (n / 10.0) * totalSweep);
            float lx = (float) (cx + labelRadius * Math.cos(rad));
            float ly = (float) (cy + labelRadius * Math.sin(rad)) + 5f;
            canvas.drawText(String.valueOf(n), lx, ly, labelPaint);
        }
    }
}