package ru.giga.dictaphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Лёгкая «дорожка» записи: живая амплитуда во время записи и прогресс при прослушивании.
 */
public class WaveformView extends View {

    private final List<Float> levels = new ArrayList<>();
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float density;
    private boolean live;
    private float progress;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        dimPaint.setColor(0xFF33405B);
        playedPaint.setColor(0xFF8C9BFF);
        basePaint.setColor(0xFF2A3245);
    }

    public void addLevel(float level) {
        float value = Math.max(0.03f, Math.min(1f, level));
        levels.add(value);
        int limit = Math.max(24, (int) (getWidth() / Math.max(1f, barStride())) * 2);
        while (levels.size() > limit) {
            levels.remove(0);
        }
        invalidate();
    }

    public void clear() {
        levels.clear();
        progress = 0f;
        invalidate();
    }

    public void setLive(boolean live) {
        this.live = live;
        invalidate();
    }

    /** Прогресс прослушивания 0..1 — сыгранная часть выделяется цветом. */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    private float barWidth() {
        return 3f * density;
    }

    private float barStride() {
        return 7f * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stride = barStride();
        float width = barWidth();
        float centerY = getHeight() / 2f;
        float usable = getHeight() - 6f * density;

        if (levels.isEmpty()) {
            float half = getWidth() * 0.22f;
            rect.set(getWidth() / 2f - half, centerY - density, getWidth() / 2f + half, centerY + density);
            canvas.drawRoundRect(rect, density, density, basePaint);
            return;
        }

        int maxBars = Math.max(1, (int) (getWidth() / stride));
        int count = levels.size();
        int startIndex = Math.max(0, count - maxBars);
        int drawn = count - startIndex;
        float left = (getWidth() - drawn * stride) / 2f + (stride - width) / 2f;

        int playedBars = Math.round(drawn * (live ? 1f : progress));

        for (int i = 0; i < drawn; i++) {
            float level = levels.get(startIndex + i);
            float h = Math.max(3f * density, level * usable);
            float l = left + i * stride;
            rect.set(l, centerY - h / 2f, l + width, centerY + h / 2f);
            canvas.drawRoundRect(rect, width / 2f, width / 2f, i < playedBars ? playedPaint : dimPaint);
        }
    }
}
