package com.example.mafia;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * BloodDripView — накладывается поверх TextView (или любого View).
 * Рисует капли крови, стекающие сверху вниз.
 * Надпись НЕ двигается — только кровь анимируется.
 *
 * Использование в XML:
 *   Добавить в FrameLayout вместе с TextView, выровнять по тому же view.
 *   Вызвать startDripping() когда нужно запустить анимацию.
 */
public class BloodDripView extends View {

    // ── Структура одной капли ────────────────────────────────────
    private static class Drip {
        float x;           // горизонтальная позиция (фиксирована)
        float startY;      // верхняя точка — нижний край буквы
        float length;      // текущая длина стекающей нити
        float maxLength;   // максимальная длина этой капли
        float speed;       // пикселей за кадр
        float bulbRadius;  // радиус шарика на конце
        float delay;       // задержка в мс перед стартом
        boolean started;
        boolean finished;
    }

    // ── Состояние ────────────────────────────────────────────────
    private final List<Drip> drips = new ArrayList<>();
    private final Paint bloodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private final Random rng = new Random();

    // Настраиваемые параметры
    private float sourceY = 0f;   // Y-координата откуда течёт кровь (нижняя граница текста)
    private int dripCount = 8;    // количество капель
    private long totalDuration = 4000; // мс — полная длительность анимации

    // ── Конструкторы ─────────────────────────────────────────────

    public BloodDripView(Context context) {
        super(context);
        init();
    }

    public BloodDripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BloodDripView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bloodPaint.setColor(0xFFCC0000);   // тёмно-красный
        bloodPaint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Публичный API ────────────────────────────────────────────

    /**
     * @param sourceY   Y в координатах этого View, откуда начинается кровь
     * @param viewWidth ширина View, чтобы равномерно расставить капли
     */
    public void configure(float sourceY, float viewWidth) {
        this.sourceY = sourceY;
        buildDrips(viewWidth);
    }

    /** Запустить анимацию стекания. */
    public void startDripping() {
        if (animator != null) animator.cancel();

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(totalDuration);
        animator.setInterpolator(new AccelerateInterpolator(0.6f));
        animator.addUpdateListener(a -> {
            long elapsed = (long)(a.getAnimatedFraction() * totalDuration);
            tick(elapsed);
            invalidate();
        });
        animator.start();
    }

    /** Остановить и сбросить. */
    public void stopDripping() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        for (Drip d : drips) {
            d.length = 0;
            d.started = false;
            d.finished = false;
        }
        invalidate();
    }

    // ── Внутренняя логика ────────────────────────────────────────

    private void buildDrips(float viewWidth) {
        drips.clear();
        float margin = viewWidth * 0.08f;
        float usable = viewWidth - 2 * margin;

        for (int i = 0; i < dripCount; i++) {
            Drip d = new Drip();
            // Равномерно + небольшой джиттер
            float base = margin + usable * i / (dripCount - 1);
            d.x = base + (rng.nextFloat() - 0.5f) * (usable / dripCount * 0.4f);
            d.startY = sourceY;
            d.maxLength = 80 + rng.nextFloat() * 160;  // от 80 до 240 dp
            d.speed = 0.06f + rng.nextFloat() * 0.06f; // скорость в долях от maxLength за мс
            d.bulbRadius = 6 + rng.nextFloat() * 8;
            d.delay = rng.nextFloat() * (totalDuration * 0.5f);
            d.length = 0;
            d.started = false;
            d.finished = false;
        }
    }

    private void tick(long elapsedMs) {
        for (Drip d : drips) {
            if (elapsedMs < d.delay) continue;
            d.started = true;
            long active = elapsedMs - (long) d.delay;
            d.length = Math.min(active * d.speed, d.maxLength);
            if (d.length >= d.maxLength) d.finished = true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Drip d : drips) {
            if (!d.started || d.length <= 0) continue;
            drawDrip(canvas, d);
        }
    }

    private void drawDrip(Canvas canvas, Drip d) {
        float x = d.x;
        float top = d.startY;
        float bottom = top + d.length;

        // Тело капли — сужающаяся нить
        float bodyWidth = 4f + d.bulbRadius * 0.3f;

        Path path = new Path();
        path.moveTo(x - bodyWidth / 2f, top);
        path.lineTo(x + bodyWidth / 2f, top);

        // Сужаем к концу
        float tipWidth = Math.max(1.5f, bodyWidth * 0.3f);
        path.lineTo(x + tipWidth / 2f, bottom);
        path.lineTo(x - tipWidth / 2f, bottom);
        path.close();

        canvas.drawPath(path, bloodPaint);

        // Шарик на кончике (только если достаточно длинная)
        if (d.length > 20) {
            float progress = d.length / d.maxLength;
            float bulb = d.bulbRadius * Math.min(progress * 2f, 1f);
            canvas.drawCircle(x, bottom + bulb * 0.5f, bulb, bloodPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }
}
