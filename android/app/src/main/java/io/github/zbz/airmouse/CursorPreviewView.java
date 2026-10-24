package io.github.zbz.airmouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Draws the laptop screen as a rectangle and the cursor as a square inside it.
 *
 * <p>The square blinks on a click and turns into a triangle pointing in the scroll direction
 * while scrolling. The cursor position is supplied as normalized coordinates reported back by
 * the laptop.
 */
final class CursorPreviewView extends View {
    private static final long BLINK_INTERVAL_MS = 120;
    private static final int BLINK_TOGGLES = 4;
    private static final long SCROLL_DISPLAY_MS = 250;

    private final AirMouseConfig config;
    private final float aspectRatio;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trianglePath = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float cursorNormX = 0.5f;
    private float cursorNormY = 0.5f;
    private boolean blinkHidden;
    private int blinkTogglesLeft;
    private int scrollDirection;
    private long scrollUntilMillis;

    CursorPreviewView(Context context, AirMouseConfig config, int screenWidth, int screenHeight) {
        super(context);
        this.config = config;
        if (screenWidth > 0 && screenHeight > 0) {
            this.aspectRatio = (float) screenWidth / (float) screenHeight;
        } else {
            this.aspectRatio = (float) config.fallbackAspectWidth / (float) config.fallbackAspectHeight;
        }
        fillPaint.setColor(config.previewScreenFillColor);
        strokePaint.setColor(config.previewScreenStrokeColor);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(config.previewStrokeWidthPx);
        cursorPaint.setColor(config.previewCursorColor);
    }

    void setCursorNormalized(float normX, float normY) {
        cursorNormX = clamp01(normX);
        cursorNormY = clamp01(normY);
        invalidate();
    }

    void triggerClickBlink() {
        blinkTogglesLeft = BLINK_TOGGLES;
        blinkHidden = true;
        handler.removeCallbacks(blinkRunnable);
        handler.post(blinkRunnable);
    }

    void triggerScroll(int amount) {
        scrollDirection = amount;
        scrollUntilMillis = System.currentTimeMillis() + SCROLL_DISPLAY_MS;
        invalidate();
        handler.removeCallbacks(scrollClearRunnable);
        handler.postDelayed(scrollClearRunnable, SCROLL_DISPLAY_MS);
    }

    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            blinkTogglesLeft--;
            blinkHidden = !blinkHidden;
            invalidate();
            if (blinkTogglesLeft > 0) {
                handler.postDelayed(this, BLINK_INTERVAL_MS);
            } else {
                blinkHidden = false;
                invalidate();
            }
        }
    };

    private final Runnable scrollClearRunnable = new Runnable() {
        @Override
        public void run() {
            scrollDirection = 0;
            invalidate();
        }
    };

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width == 0) {
            width = config.previewDefaultWidthPx;
        }
        int desiredHeight = Math.round(width / aspectRatio);
        int height = Math.max(config.previewMinHeightPx, Math.min(desiredHeight, config.previewMaxHeightPx));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF screen = computeScreenRect();
        canvas.drawRect(screen, fillPaint);
        canvas.drawRect(screen, strokePaint);

        float cx = screen.left + cursorNormX * screen.width();
        float cy = screen.top + cursorNormY * screen.height();
        float half = config.previewCursorSizePx / 2f;
        cx = clamp(cx, screen.left + half, screen.right - half);
        cy = clamp(cy, screen.top + half, screen.bottom - half);

        boolean scrolling = scrollDirection != 0 && System.currentTimeMillis() < scrollUntilMillis;
        if (scrolling) {
            cursorPaint.setColor(config.previewCursorScrollColor);
            drawTriangle(canvas, cx, cy, half, scrollDirection > 0);
            cursorPaint.setColor(config.previewCursorColor);
        } else if (!blinkHidden) {
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, cursorPaint);
        }
    }

    private void drawTriangle(Canvas canvas, float cx, float cy, float half, boolean pointingUp) {
        trianglePath.reset();
        if (pointingUp) {
            trianglePath.moveTo(cx, cy - half);
            trianglePath.lineTo(cx - half, cy + half);
            trianglePath.lineTo(cx + half, cy + half);
        } else {
            trianglePath.moveTo(cx, cy + half);
            trianglePath.lineTo(cx - half, cy - half);
            trianglePath.lineTo(cx + half, cy - half);
        }
        trianglePath.close();
        canvas.drawPath(trianglePath, cursorPaint);
    }

    private RectF computeScreenRect() {
        float pad = config.previewStrokeWidthPx;
        float availableWidth = getWidth() - 2 * pad;
        float availableHeight = getHeight() - 2 * pad;
        float rectWidth = availableWidth;
        float rectHeight = rectWidth / aspectRatio;
        if (rectHeight > availableHeight) {
            rectHeight = availableHeight;
            rectWidth = rectHeight * aspectRatio;
        }
        float left = (getWidth() - rectWidth) / 2f;
        float top = (getHeight() - rectHeight) / 2f;
        return new RectF(left, top, left + rectWidth, top + rectHeight);
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
}
