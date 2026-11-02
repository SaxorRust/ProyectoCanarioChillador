package com.canario.iot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Vista de Gráfica Nativa de Series de Tiempo (Canvas 2D de Alto Desempeño):
 * Renderiza fluidamente curvas de gases y parámetros higrotérmicos con
 * líneas de umbral normativas (NOM/OSHA), cursor táctil interactivo y tooltip.
 */
public class TimeSeriesChartView extends View {

    public static final int MODE_CO  = 0;
    public static final int MODE_LPG = 1;
    public static final int MODE_ENV = 2;

    private int currentMode = MODE_CO;
    private final List<TelemetryData> dataPoints = new ArrayList<>();

    // Pinturas y Estilos
    private Paint linePaint;
    private Paint linePaintSecondary;
    private Paint fillPaint;
    private Paint gridPaint;
    private Paint textPaint;
    private Paint thresholdYellowPaint;
    private Paint thresholdRedPaint;
    private Paint cursorPaint;
    private Paint tooltipBgPaint;
    private Paint tooltipTextPaint;

    // Rutas de dibujo
    private final Path curvePath = new Path();
    private final Path fillPath = new Path();
    private final Path curvePathSecondary = new Path();

    // Cursor táctil
    private float touchX = -1.0f;
    private boolean isTouching = false;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public TimeSeriesChartView(Context context) {
        super(context);
        init();
    }

    public TimeSeriesChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimeSeriesChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4.0f);
        linePaint.setColor(0xFF00E5FF); // Cyan para CO

        linePaintSecondary = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaintSecondary.setStyle(Paint.Style.STROKE);
        linePaintSecondary.setStrokeWidth(4.0f);
        linePaintSecondary.setColor(0xFFFF6E40); // Naranja para Temp

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0xFF263238);
        gridPaint.setStrokeWidth(1.5f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFF90A4AE);
        textPaint.setTextSize(26.0f);

        // Umbrales normativos discontinuos
        DashPathEffect dashEffect = new DashPathEffect(new float[]{12, 10}, 0);

        thresholdYellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thresholdYellowPaint.setStyle(Paint.Style.STROKE);
        thresholdYellowPaint.setStrokeWidth(2.5f);
        thresholdYellowPaint.setColor(0xFFFFB300); // Ámbar preventivo
        thresholdYellowPaint.setPathEffect(dashEffect);

        thresholdRedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thresholdRedPaint.setStyle(Paint.Style.STROKE);
        thresholdRedPaint.setStrokeWidth(2.5f);
        thresholdRedPaint.setColor(0xFFF44336); // Rojo crítico
        thresholdRedPaint.setPathEffect(dashEffect);

        cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(2.0f);
        cursorPaint.setColor(0xFFFFFFFF);

        tooltipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipBgPaint.setColor(0xEE1E2633);
        tooltipBgPaint.setStyle(Paint.Style.FILL);

        tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tooltipTextPaint.setColor(0xFFFFFFFF);
        tooltipTextPaint.setTextSize(28.0f);
        tooltipTextPaint.setFakeBoldText(true);
    }

    public void setMode(int mode) {
        this.currentMode = mode;
        if (mode == MODE_CO) {
            linePaint.setColor(0xFF00E5FF); // Cyan
        } else if (mode == MODE_LPG) {
            linePaint.setColor(0xFFE040FB); // Magenta
        } else {
            linePaint.setColor(0xFF40C4FF); // Humedad Azul
            linePaintSecondary.setColor(0xFFFF6E40); // Temp Naranja
        }
        invalidate();
    }

    public void setData(List<TelemetryData> data) {
        dataPoints.clear();
        if (data != null) {
            dataPoints.addAll(data);
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                isTouching = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isTouching = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // Márgenes del área de trazado
        float padLeft = 80.0f;
        float padRight = 30.0f;
        float padTop = 40.0f;
        float padBottom = 60.0f;

        float plotW = width - padLeft - padRight;
        float plotH = height - padTop - padBottom;

        if (plotW <= 0 || plotH <= 0) return;

        // Si no hay datos, mostrar mensaje instructivo
        if (dataPoints.isEmpty()) {
            String emptyMsg = "Sin datos de serie de tiempo recopilados aún.";
            float textW = textPaint.measureText(emptyMsg);
            canvas.drawText(emptyMsg, (width - textW) / 2.0f, height / 2.0f, textPaint);
            return;
        }

        // Determinar límites de escala según el modo
        float minY = 0.0f;
        float maxY = 150.0f;
        float thresholdYellow = 35.0f;
        float thresholdRed = 100.0f;

        if (currentMode == MODE_CO) {
            maxY = 150.0f;
            thresholdYellow = 35.0f;
            thresholdRed = 100.0f;
            for (TelemetryData d : dataPoints) {
                if (d.getCo() > maxY) maxY = d.getCo() * 1.15f;
            }
        } else if (currentMode == MODE_LPG) {
            maxY = 1500.0f;
            thresholdYellow = 400.0f;
            thresholdRed = 1000.0f;
            for (TelemetryData d : dataPoints) {
                if (d.getLpg() > maxY) maxY = d.getLpg() * 1.15f;
            }
        } else {
            // MODE_ENV (Temp 0-60°C / Humedad 0-100%)
            maxY = 100.0f;
            thresholdYellow = -1.0f; // No aplican para ambiente
            thresholdRed = -1.0f;
        }

        // 1. DIBUJAR CUADRÍCULA Y ESCALAS Y
        int gridDivs = 4;
        for (int i = 0; i <= gridDivs; i++) {
            float yNorm = (float) i / gridDivs;
            float yPos = padTop + plotH * (1.0f - yNorm);
            canvas.drawLine(padLeft, yPos, width - padRight, yPos, gridPaint);

            float valLabel = minY + (maxY - minY) * yNorm;
            String label = (currentMode == MODE_LPG) ? String.format(Locale.US, "%.0f", valLabel) : String.format(Locale.US, "%.0f", valLabel);
            canvas.drawText(label, 15.0f, yPos + 8.0f, textPaint);
        }

        // 2. DIBUJAR LÍNEAS DE UMBRAL NORMATIVO
        if (thresholdYellow > 0 && thresholdYellow <= maxY) {
            float yNorm = (thresholdYellow - minY) / (maxY - minY);
            float yPos = padTop + plotH * (1.0f - yNorm);
            canvas.drawLine(padLeft, yPos, width - padRight, yPos, thresholdYellowPaint);
            canvas.drawText(String.format(Locale.US, "PREV: %.0f", thresholdYellow), width - padRight - 140.0f, yPos - 6.0f, thresholdYellowPaint);
        }

        if (thresholdRed > 0 && thresholdRed <= maxY) {
            float yNorm = (thresholdRed - minY) / (maxY - minY);
            float yPos = padTop + plotH * (1.0f - yNorm);
            canvas.drawLine(padLeft, yPos, width - padRight, yPos, thresholdRedPaint);
            canvas.drawText(String.format(Locale.US, "CRÍT: %.0f", thresholdRed), width - padRight - 140.0f, yPos - 6.0f, thresholdRedPaint);
        }

        // 3. CONSTRUCCIÓN DE RUTAS DE LA SERIE TEMPORAL
        curvePath.reset();
        fillPath.reset();
        curvePathSecondary.reset();

        int n = dataPoints.size();
        float stepX = (n > 1) ? (plotW / (n - 1)) : plotW;

        for (int i = 0; i < n; i++) {
            TelemetryData d = dataPoints.get(i);
            float val = 0.0f;
            if (currentMode == MODE_CO) val = d.getCo();
            else if (currentMode == MODE_LPG) val = d.getLpg();
            else val = d.getHumedad(); // Humedad como serie principal en MODE_ENV

            float yNorm = (val - minY) / (maxY - minY);
            if (yNorm < 0) yNorm = 0;
            if (yNorm > 1) yNorm = 1;

            float x = padLeft + i * stepX;
            float y = padTop + plotH * (1.0f - yNorm);

            if (i == 0) {
                curvePath.moveTo(x, y);
                fillPath.moveTo(x, padTop + plotH);
                fillPath.lineTo(x, y);
            } else {
                curvePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }

            if (i == n - 1) {
                fillPath.lineTo(x, padTop + plotH);
                fillPath.close();
            }

            // Serie secundaria (Temperatura en MODE_ENV)
            if (currentMode == MODE_ENV) {
                float valTemp = d.getTemperatura();
                float yNormTemp = (valTemp - minY) / (maxY - minY);
                float yTemp = padTop + plotH * (1.0f - yNormTemp);
                if (i == 0) {
                    curvePathSecondary.moveTo(x, yTemp);
                } else {
                    curvePathSecondary.lineTo(x, yTemp);
                }
            }
        }

        // Gradiente bajo la curva
        int startColor = (currentMode == MODE_CO) ? 0x6600E5FF : ((currentMode == MODE_LPG) ? 0x66E040FB : 0x6640C4FF);
        LinearGradient fillGrad = new LinearGradient(
                0, padTop, 0, padTop + plotH,
                startColor, 0x00000000,
                Shader.TileMode.CLAMP
        );
        fillPaint.setShader(fillGrad);

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(curvePath, linePaint);

        if (currentMode == MODE_ENV) {
            canvas.drawPath(curvePathSecondary, linePaintSecondary);
        }

        // Marcas de tiempo en el eje X (primera y última)
        if (n > 0) {
            String t0 = timeFormat.format(new Date(dataPoints.get(0).getTimestamp()));
            String t1 = timeFormat.format(new Date(dataPoints.get(n - 1).getTimestamp()));
            canvas.drawText(t0, padLeft, height - 15.0f, textPaint);
            float w1 = textPaint.measureText(t1);
            canvas.drawText(t1, width - padRight - w1, height - 15.0f, textPaint);
        }

        // 4. INTERACTIVIDAD TÁCTIL (CURSOR Y TOOLTIP)
        if (isTouching && touchX >= padLeft && touchX <= (width - padRight)) {
            int selectedIdx = Math.round((touchX - padLeft) / stepX);
            if (selectedIdx < 0) selectedIdx = 0;
            if (selectedIdx >= n) selectedIdx = n - 1;

            TelemetryData d = dataPoints.get(selectedIdx);
            float selX = padLeft + selectedIdx * stepX;

            // Línea de cursor vertical
            canvas.drawLine(selX, padTop, selX, padTop + plotH, cursorPaint);

            // Contenido del Tooltip
            String timeStr = timeFormat.format(new Date(d.getTimestamp()));
            String infoStr;
            if (currentMode == MODE_CO) {
                infoStr = String.format(Locale.US, "CO: %.1f PPM | %s", d.getCo(), d.getAlerta());
            } else if (currentMode == MODE_LPG) {
                infoStr = String.format(Locale.US, "GLP: %.0f PPM | %s", d.getLpg(), d.getAlerta());
            } else {
                infoStr = String.format(Locale.US, "T: %.1f°C | H: %.0f%%", d.getTemperatura(), d.getHumedad());
            }

            float ttW = Math.max(tooltipTextPaint.measureText(infoStr), tooltipTextPaint.measureText(timeStr)) + 40.0f;
            float ttH = 90.0f;

            float ttX = selX - (ttW / 2.0f);
            if (ttX < padLeft) ttX = padLeft;
            if (ttX + ttW > width - padRight) ttX = width - padRight - ttW;

            float ttY = padTop + 20.0f;

            RectF tipRect = new RectF(ttX, ttY, ttX + ttW, ttY + ttH);
            canvas.drawRoundRect(tipRect, 14.0f, 14.0f, tooltipBgPaint);

            canvas.drawText(timeStr, ttX + 20.0f, ttY + 36.0f, textPaint);
            canvas.drawText(infoStr, ttX + 20.0f, ttY + 72.0f, tooltipTextPaint);
        }
    }
}
