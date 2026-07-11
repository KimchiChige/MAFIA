package com.example.mafia;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Круглая палитра цветов (color wheel) с выбором оттенка/насыщенности касанием
 * и отдельным ползунком яркости (рисуется этим же View снизу).
 *
 * Использование в XML:
 *   <com.example.mafia.ColorWheelView
 *       android:id="@+id/colorWheel"
 *       android:layout_width="240dp"
 *       android:layout_height="280dp"/>
 *
 * В коде:
 *   colorWheel.setOnColorChangeListener(hex -> { ... });
 *   colorWheel.setColor("#8B0000");   // выставить текущий цвет программно
 */
public class ColorWheelView extends View {

    public interface OnColorChangeListener {
        void onColorChanged(String hexColor);
    }

    private Paint wheelPaint;
    private Paint selectorPaint;
    private Paint brightnessBarPaint;
    private Paint brightnessSelectorPaint;

    private Bitmap wheelBitmap;
    private float wheelRadius;
    private float wheelCenterX, wheelCenterY;

    private float hue = 0f;          // 0..360
    private float saturation = 1f;   // 0..1
    private float brightness = 1f;   // 0..1  (управляется нижней полосой)

    private static final int BRIGHTNESS_BAR_HEIGHT_DP = 36;
    private static final int BRIGHTNESS_BAR_MARGIN_DP  = 16;
    private float brightnessBarHeight;
    private float brightnessBarMargin;

    private OnColorChangeListener listener;
    private boolean draggingWheel = false;
    private boolean draggingBrightness = false;

    public ColorWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        brightnessBarHeight = BRIGHTNESS_BAR_HEIGHT_DP * density;
        brightnessBarMargin = BRIGHTNESS_BAR_MARGIN_DP * density;

        wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectorPaint.setStyle(Paint.Style.STROKE);
        selectorPaint.setStrokeWidth(3f * density);
        selectorPaint.setColor(Color.WHITE);

        brightnessBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        brightnessSelectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        brightnessSelectorPaint.setStyle(Paint.Style.STROKE);
        brightnessSelectorPaint.setStrokeWidth(3f * density);
        brightnessSelectorPaint.setColor(Color.WHITE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float availableHeight = h - brightnessBarHeight - brightnessBarMargin;
        wheelRadius = Math.min(w, availableHeight) / 2f - 8f;
        wheelCenterX = w / 2f;
        wheelCenterY = availableHeight / 2f;
        buildWheelBitmap();
    }

    private void buildWheelBitmap() {
        int size = (int) (wheelRadius * 2);
        if (size <= 0) return;
        wheelBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(wheelBitmap);

        // Sweep gradient — оттенок (hue) по кругу
        int[] hueColors = new int[37];
        for (int i = 0; i <= 36; i++) {
            hueColors[i] = Color.HSVToColor(new float[]{i * 10f, 1f, 1f});
        }
        SweepGradient sweep = new SweepGradient(size / 2f, size / 2f, hueColors, null);

        // Radial gradient — насыщенность (белый в центре -> прозрачный к краю)
        RadialGradient radial = new RadialGradient(
                size / 2f, size / 2f, size / 2f,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);

        Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        huePaint.setShader(sweep);
        c.drawCircle(size / 2f, size / 2f, size / 2f, huePaint);

        Paint satPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        satPaint.setShader(radial);
        c.drawCircle(size / 2f, size / 2f, size / 2f, satPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wheelBitmap != null) {
            canvas.drawBitmap(wheelBitmap,
                    wheelCenterX - wheelRadius, wheelCenterY - wheelRadius, null);
        }

        // Селектор на колесе
        double angleRad = Math.toRadians(hue);
        float selX = wheelCenterX + (float) Math.cos(angleRad) * saturation * wheelRadius;
        float selY = wheelCenterY + (float) Math.sin(angleRad) * saturation * wheelRadius;
        canvas.drawCircle(selX, selY, 10f * getResources().getDisplayMetrics().density, selectorPaint);

        // Полоса яркости снизу (градиент от чёрного до чистого hue/sat цвета)
        float barTop = wheelCenterY + wheelRadius + brightnessBarMargin;
        float barBottom = barTop + brightnessBarHeight;
        int pureColor = Color.HSVToColor(new float[]{hue, saturation, 1f});
        android.graphics.LinearGradient barGradient = new android.graphics.LinearGradient(
                0, 0, getWidth(), 0,
                Color.BLACK, pureColor, Shader.TileMode.CLAMP);
        brightnessBarPaint.setShader(barGradient);
        canvas.drawRoundRect(0, barTop, getWidth(), barBottom,
                barBottom - barTop, barBottom - barTop, brightnessBarPaint);

        // Селектор яркости
        float bx = brightness * getWidth();
        canvas.drawCircle(bx, (barTop + barBottom) / 2f,
                (barBottom - barTop) / 2f + 4f, brightnessSelectorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float barTop = wheelCenterY + wheelRadius + brightnessBarMargin;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Запрещаем родительскому ScrollView перехватывать жест,
                // иначе движение пальцем вверх/вниз по колесу скроллит страницу
                // вместо перемещения выбора цвета.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (y < barTop) {
                    draggingWheel = true;
                    updateWheelPosition(x, y);
                } else {
                    draggingBrightness = true;
                    updateBrightnessPosition(x);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (draggingWheel) updateWheelPosition(x, y);
                else if (draggingBrightness) updateBrightnessPosition(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                draggingWheel = false;
                draggingBrightness = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateWheelPosition(float x, float y) {
        float dx = x - wheelCenterX;
        float dy = y - wheelCenterY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) angle += 360;

        hue = (float) angle;
        saturation = (float) Math.min(1.0, dist / wheelRadius);

        invalidate();
        notifyColorChanged();
    }

    private void updateBrightnessPosition(float x) {
        brightness = Math.max(0f, Math.min(1f, x / getWidth()));
        invalidate();
        notifyColorChanged();
    }

    private void notifyColorChanged() {
        if (listener != null) listener.onColorChanged(getHexColor());
    }

    public String getHexColor() {
        int color = Color.HSVToColor(new float[]{hue, saturation, brightness});
        return String.format("#%06X", (0xFFFFFF & color));
    }

    public void setOnColorChangeListener(OnColorChangeListener l) {
        this.listener = l;
    }

    /** Устанавливает текущий цвет программно (например при загрузке сохранённых настроек). */
    public void setColor(String hexColor) {
        try {
            int color = Color.parseColor(hexColor);
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hue = hsv[0];
            saturation = hsv[1];
            brightness = hsv[2];
            invalidate();
        } catch (Exception ignored) {}
    }
}