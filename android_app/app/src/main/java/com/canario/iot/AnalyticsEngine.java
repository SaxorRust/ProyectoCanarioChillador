package com.canario.iot;

import java.util.List;
import java.util.Locale;

/**
 * Motor Analítico e Inferencias Estadísticas Profundas:
 * Ejecuta cálculos deterministas sobre las series temporales de datos crudos
 * del ESP32: TWA (NOM-010-STPS / OSHA), derivada temporal dPPM/dt (predicción temprana),
 * correlación cruzada higrotérmica de Pearson y diagnóstico pericial automatizado.
 */
public class AnalyticsEngine {

    // Umbrales Normativos Oficiales
    public static final float NOM_010_VLE_PPT_CO = 25.0f;  // Límite Máximo Permisible Ponderado en 8h (México)
    public static final float OSHA_PEL_TWA_CO    = 50.0f;  // Permissible Exposure Limit (EE. UU.)
    public static final float UL_2034_MIN_ALARM  = 30.0f;  // Umbral mínimo para evitar falsos positivos
    public static final float NFPA_LEL_PREV_LPG  = 400.0f; // Advertencia preventiva (10% de LEL)
    public static final float NFPA_LEL_EVAC_LPG  = 1000.0f;// Alarma crítica de evacuación

    public static class AnalyticsReport {
        public float twaCO = 0.0f;
        public float percentNom010 = 0.0f;
        public float percentOsha = 0.0f;

        public float dCoDt = 0.0f;    // Derivada de CO (PPM/segundo)
        public float dLpgDt = 0.0f;   // Derivada de GLP (PPM/segundo)

        public float rCoTemp = 0.0f;  // Coeficiente Pearson CO vs Temp
        public float rCoHum = 0.0f;   // Coeficiente Pearson CO vs Humedad

        public float maxCo = 0.0f;
        public float maxLpg = 0.0f;
        public float avgCo = 0.0f;
        public float avgLpg = 0.0f;
        public float stdDevCo = 0.0f;
        public float stdDevLpg = 0.0f;

        public int sampleCount = 0;
        public long durationMillis = 0;

        public String estadoPredictivo = "ESTABLE";
        public String derivasHigrotermicas = "DESACOPLADO (SEÑAL PURA)";
        public String conclusionGeneral = "";
        public String accionRecomendada = "";
    }

    /**
     * Procesa la lista de datos históricos y genera el reporte analítico completo.
     */
    public static AnalyticsReport analyze(List<TelemetryData> dataList, TelemetryData current) {
        AnalyticsReport report = new AnalyticsReport();
        if (dataList == null || dataList.isEmpty()) {
            if (current != null) {
                report.maxCo = current.getCo();
                report.maxLpg = current.getLpg();
                report.avgCo = current.getCo();
                report.avgLpg = current.getLpg();
                report.sampleCount = 1;
                report.conclusionGeneral = "Recopilando muestras iniciales para el análisis de series de tiempo...";
                report.accionRecomendada = "Mantener supervisión normal.";
            }
            return report;
        }

        int n = dataList.size();
        report.sampleCount = n;
        long tStart = dataList.get(0).getTimestamp();
        long tEnd = dataList.get(n - 1).getTimestamp();
        report.durationMillis = Math.max(1000, tEnd - tStart);

        // 1. CÁLCULO DE PROMEDIOS, MÁXIMOS Y DESVIACIÓN ESTÁNDAR
        double sumCo = 0.0, sumLpg = 0.0;
        double sumCoWeighted = 0.0;
        double totalTimeSec = 0.0;
        float maxCo = -1.0f, maxLpg = -1.0f;

        for (int i = 0; i < n; i++) {
            TelemetryData d = dataList.get(i);
            float co = d.getCo();
            float lpg = d.getLpg();

            sumCo += co;
            sumLpg += lpg;

            if (co > maxCo) maxCo = co;
            if (lpg > maxLpg) maxLpg = lpg;

            // Peso ponderado por tiempo para TWA
            long dtMillis = 1000; // Por defecto 1 seg entre tramas
            if (i > 0) {
                dtMillis = Math.max(100, d.getTimestamp() - dataList.get(i - 1).getTimestamp());
            }
            double dtSec = dtMillis / 1000.0;
            sumCoWeighted += (co * dtSec);
            totalTimeSec += dtSec;
        }

        report.maxCo = maxCo;
        report.maxLpg = maxLpg;
        report.avgCo = (float) (sumCo / n);
        report.avgLpg = (float) (sumLpg / n);

        // TWA (Time-Weighted Average)
        if (totalTimeSec > 0) {
            report.twaCO = (float) (sumCoWeighted / totalTimeSec);
        } else {
            report.twaCO = report.avgCo;
        }
        report.percentNom010 = (report.twaCO / NOM_010_VLE_PPT_CO) * 100.0f;
        report.percentOsha = (report.twaCO / OSHA_PEL_TWA_CO) * 100.0f;

        // Desviación Estándar
        double varSumCo = 0.0, varSumLpg = 0.0;
        for (TelemetryData d : dataList) {
            varSumCo += Math.pow(d.getCo() - report.avgCo, 2);
            varSumLpg += Math.pow(d.getLpg() - report.avgLpg, 2);
        }
        report.stdDevCo = (float) Math.sqrt(varSumCo / n);
        report.stdDevLpg = (float) Math.sqrt(varSumLpg / n);

        // 2. CÁLCULO DE DERIVADA TEMPORAL (dPPM/dt) EN VENTANA RECIENTE
        // Tomamos los últimos 15 a 30 segundos
        int windowPoints = Math.min(n, 15);
        if (windowPoints >= 2) {
            TelemetryData dOld = dataList.get(n - windowPoints);
            TelemetryData dNow = dataList.get(n - 1);
            double dt = (dNow.getTimestamp() - dOld.getTimestamp()) / 1000.0;
            if (dt > 0.5) {
                report.dCoDt = (float) ((dNow.getCo() - dOld.getCo()) / dt);
                report.dLpgDt = (float) ((dNow.getLpg() - dOld.getLpg()) / dt);
            }
        }

        // 3. COEFICIENTES DE CORRELACIÓN DE PEARSON (CO vs TEMP, CO vs HUM)
        report.rCoTemp = calculatePearson(dataList, true);
        report.rCoHum = calculatePearson(dataList, false);

        // 4. INFERENCIAS PROFUNDAS Y DIAGNÓSTICO
        evaluatePredictiveAndDiagnostic(report, current);

        return report;
    }

    private static float calculatePearson(List<TelemetryData> list, boolean vsTemp) {
        int n = list.size();
        if (n < 3) return 0.0f;

        double sumX = 0.0, sumY = 0.0;
        for (TelemetryData d : list) {
            sumX += d.getCo();
            sumY += vsTemp ? d.getTemperatura() : d.getHumedad();
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double num = 0.0;
        double denX = 0.0;
        double denY = 0.0;

        for (TelemetryData d : list) {
            double dx = d.getCo() - meanX;
            double dy = (vsTemp ? d.getTemperatura() : d.getHumedad()) - meanY;
            num += (dx * dy);
            denX += (dx * dx);
            denY += (dy * dy);
        }

        double denom = Math.sqrt(denX * denY);
        if (denom == 0.0) return 0.0f;

        float r = (float) (num / denom);
        if (Float.isNaN(r)) return 0.0f;
        if (r > 1.0f) return 1.0f;
        if (r < -1.0f) return -1.0f;
        return r;
    }

    private static void evaluatePredictiveAndDiagnostic(AnalyticsReport report, TelemetryData current) {
        // Evaluación de derivada y tasa de acumulación
        boolean fugaAceleradaCO = report.dCoDt >= 1.0f;
        boolean fugaAceleradaLPG = report.dLpgDt >= 5.0f;
        boolean incrementoModerado = report.dCoDt >= 0.3f || report.dLpgDt >= 2.0f;

        if (fugaAceleradaCO || fugaAceleradaLPG) {
            report.estadoPredictivo = "¡FUGA ACELERADA DETECTADA!";
        } else if (incrementoModerado) {
            report.estadoPredictivo = "INCREMENTO TRANSITORIO";
        } else if (report.dCoDt <= -0.3f || report.dLpgDt <= -2.0f) {
            report.estadoPredictivo = "DISPERSIÓN / VENTILACIÓN ACTIVA";
        } else {
            report.estadoPredictivo = "ATMÓSFERA ESTABLE (𝑑PPM/𝑑t ≈ 0)";
        }

        // Evaluación de derivas higrotérmicas
        boolean fuerteCorrTemp = Math.abs(report.rCoTemp) >= 0.75f;
        boolean fuerteCorrHum = Math.abs(report.rCoHum) >= 0.75f;

        if (report.avgCo < 30.0f && (fuerteCorrTemp || fuerteCorrHum)) {
            report.derivasHigrotermicas = String.format(Locale.US,
                    "DERIVA AMBIENTAL PROBABLE (r_T=%.2f, r_RH=%.2f)", report.rCoTemp, report.rCoHum);
        } else {
            report.derivasHigrotermicas = String.format(Locale.US,
                    "EMISIÓN QUÍMICA GENUINA (r_T=%.2f, r_RH=%.2f)", report.rCoTemp, report.rCoHum);
        }

        // Conclusión General e Inferencia
        StringBuilder sb = new StringBuilder();
        StringBuilder sa = new StringBuilder();

        float co = (current != null) ? current.getCo() : report.avgCo;
        float lpg = (current != null) ? current.getLpg() : report.avgLpg;

        if (co >= 100.0f || lpg >= NFPA_LEL_EVAC_LPG) {
            sb.append("CONDICIÓN CRÍTICA DE EMERGENCIA: Concentración en nivel letal o explosivo. ");
            sa.append("EVACUAR INMEDIATAMENTE. No accionar interruptores eléctricos. Ventilar el área.");
        } else if (fugaAceleradaCO || fugaAceleradaLPG) {
            sb.append("PRE-ALARMA PREDICTIVA: La tasa de cambio (dPPM/dt = ")
              .append(String.format(Locale.US, "+%.2f PPM/s", report.dCoDt))
              .append(") anticipa un siniestro antes de alcanzar el umbral de corte. ");
            sa.append("Cerrar llaves de paso de gas y revisar quemadores o conductos de escape.");
        } else if (report.twaCO >= NOM_010_VLE_PPT_CO) {
            sb.append("EXPOSICIÓN CRÓNICA: El promedio ponderado en el tiempo (TWA = ")
              .append(String.format(Locale.US, "%.1f PPM", report.twaCO))
              .append(") rebasa la norma NOM-010-STPS (Límite 25 PPM). ");
            sa.append("El personal no debe permanecer en el recinto sin ventilación forzada.");
        } else if (co >= 35.0f || lpg >= NFPA_LEL_PREV_LPG) {
            sb.append("PRECAUCIÓN AMBIENTAL: Elevación de gases en rango de atención preventiva. ");
            sa.append("Inspeccionar el sistema de combustión y permitir circulación de aire natural.");
        } else {
            sb.append("ATMÓSFERA NOMINAL: Niveles dentro de los estándares de higiene ocupacional inocua. ");
            sa.append("Operación regular. No se requieren acciones mitigadoras.");
        }

        // Si el MQ-7 está en purga
        if (current != null && "PURGA".equalsIgnoreCase(current.getFaseMQ7())) {
            sb.append("\n[Nota Silicio]: MQ-7 en calentamiento térmico (5.0V); mediciones en período de desorción.");
        }

        report.conclusionGeneral = sb.toString();
        report.accionRecomendada = sa.toString();
    }
}
