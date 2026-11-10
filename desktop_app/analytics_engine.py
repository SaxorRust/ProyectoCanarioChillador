"""
Motor de Analítica Predictiva, Higiene Industrial y Diagnóstico de Silicio
para El Canario Chillador (Desktop).
Cumplimiento normativo oficial: NOM-010-STPS-2014, NOM-002-STPS-2010, CENAPRED, OSHA.
"""

import math
from typing import List, Dict, Optional

class AnalyticsReport:
    def __init__(self):
        self.twa_co = 0.0
        self.percent_nom010 = 0.0
        self.percent_osha = 0.0
        self.d_co_dt = 0.0
        self.d_lpg_dt = 0.0
        self.r_co_temp = 0.0
        self.r_co_hum = 0.0
        self.max_co = 0.0
        self.max_lpg = 0.0
        self.avg_co = 0.0
        self.avg_lpg = 0.0
        self.std_dev_co = 0.0
        self.std_dev_lpg = 0.0
        self.total_samples = 0
        self.estado_predictivo = "ATMÓSFERA ESTABLE (𝑑PPM/𝑑t ≈ 0)"
        self.derivas_higrotermicas = "EMISIÓN QUÍMICA GENUINA"
        self.conclusion_general = "ATMÓSFERA NOMINAL: Niveles dentro de los estándares de higiene ocupacional inocua."
        self.accion_recomendada = "Operación regular. No se requieren acciones mitigadoras."

class AnalyticsEngine:
    # Umbrales Oficiales Mexicanos e Internacionales
    NOM_010_VLE_PPT_CO = 25.0    # ppm (Promedio Ponderado en Tiempo 8h)
    CENAPRED_PELIGRO_CO = 100.0   # ppm (Umbral Crítico de Evacuación Inmediata)
    NOM_010_VLE_CT_LPG = 1000.0   # ppm (Límite Corto Tiempo / ~5% LEL)
    NOM_002_LEL_EVAC_LPG = 3200.0 # ppm (20% del LEL, Riesgo Inminente de Deflagración)
    OSHA_PEL_TWA_CO = 50.0        # ppm (Límite Legal Federal EE.UU.)

    @classmethod
    def compute(cls, readings: List[Dict], current_reading: Optional[Dict] = None) -> AnalyticsReport:
        report = AnalyticsReport()
        if not readings:
            if current_reading:
                cls._eval_silicon_notes(report, current_reading)
            return report

        n = len(readings)
        report.total_samples = n

        sum_co = 0.0
        sum_lpg = 0.0
        max_co = 0.0
        max_lpg = 0.0
        sum_co_weighted = 0.0
        total_time_sec = 0.0

        for i, d in enumerate(readings):
            co = float(d.get("co", 0.0))
            lpg = float(d.get("lpg", 0.0))
            sum_co += co
            sum_lpg += lpg
            if co > max_co: max_co = co
            if lpg > max_lpg: max_lpg = lpg

            dt_sec = 1.0
            if i > 0:
                dt_ms = max(100, d.get("timestamp", 0) - readings[i - 1].get("timestamp", 0))
                dt_sec = dt_ms / 1000.0
            sum_co_weighted += (co * dt_sec)
            total_time_sec += dt_sec

        report.max_co = max_co
        report.max_lpg = max_lpg
        report.avg_co = sum_co / n
        report.avg_lpg = sum_lpg / n

        # TWA (Time-Weighted Average)
        if total_time_sec > 0:
            report.twa_co = sum_co_weighted / total_time_sec
        else:
            report.twa_co = report.avg_co

        report.percent_nom010 = (report.twa_co / cls.NOM_010_VLE_PPT_CO) * 100.0
        report.percent_osha = (report.twa_co / cls.OSHA_PEL_TWA_CO) * 100.0

        # Desviación Estándar
        var_sum_co = sum((float(d.get("co", 0.0)) - report.avg_co) ** 2 for d in readings)
        var_sum_lpg = sum((float(d.get("lpg", 0.0)) - report.avg_lpg) ** 2 for d in readings)
        report.std_dev_co = math.sqrt(var_sum_co / n)
        report.std_dev_lpg = math.sqrt(var_sum_lpg / n)

        # 2. CÁLCULO DE DERIVADA TEMPORAL (dPPM/dt) EN VENTANA RECIENTE (15 puntos)
        window_points = min(n, 15)
        if window_points >= 2:
            d_old = readings[n - window_points]
            d_now = readings[n - 1]
            dt = (d_now.get("timestamp", 0) - d_old.get("timestamp", 0)) / 1000.0
            if dt > 0.5:
                report.d_co_dt = (float(d_now.get("co", 0.0)) - float(d_old.get("co", 0.0))) / dt
                report.d_lpg_dt = (float(d_now.get("lpg", 0.0)) - float(d_old.get("lpg", 0.0))) / dt

        # 3. COEFICIENTES DE CORRELACIÓN DE PEARSON
        report.r_co_temp = cls._calculate_pearson(readings, vs_temp=True)
        report.r_co_hum = cls._calculate_pearson(readings, vs_temp=False)

        # 4. EVALUACIÓN PREDICTIVA Y DIAGNÓSTICA
        cls._evaluate_predictive(report, current_reading)

        return report

    @classmethod
    def _calculate_pearson(cls, readings: List[Dict], vs_temp: bool) -> float:
        n = len(readings)
        if n < 3: return 0.0

        sum_x = sum(float(d.get("co", 0.0)) for d in readings)
        key_y = "temp" if vs_temp else "hum"
        sum_y = sum(float(d.get(key_y, 0.0)) for d in readings)

        mean_x = sum_x / n
        mean_y = sum_y / n

        num = 0.0
        den_x = 0.0
        den_y = 0.0
        for d in readings:
            dx = float(d.get("co", 0.0)) - mean_x
            dy = float(d.get(key_y, 0.0)) - mean_y
            num += (dx * dy)
            den_x += (dx * dx)
            den_y += (dy * dy)

        denom = math.sqrt(den_x * den_y)
        if denom == 0.0: return 0.0

        r = num / denom
        return max(-1.0, min(1.0, r))

    @classmethod
    def _evaluate_predictive(cls, report: AnalyticsReport, current: Optional[Dict]):
        fuga_acelerada_co = report.d_co_dt >= 1.0
        fuga_acelerada_lpg = report.d_lpg_dt >= 5.0
        incremento_mod = report.d_co_dt >= 0.3 or report.d_lpg_dt >= 2.0

        if fuga_acelerada_co or fuga_acelerada_lpg:
            report.estado_predictivo = "¡FUGA ACELERADA DETECTADA!"
        elif incremento_mod:
            report.estado_predictivo = "INCREMENTO TRANSITORIO"
        elif report.d_co_dt <= -0.3 or report.d_lpg_dt <= -2.0:
            report.estado_predictivo = "DISPERSIÓN / VENTILACIÓN ACTIVA"
        else:
            report.estado_predictivo = "ATMÓSFERA ESTABLE (𝑑PPM/𝑑t ≈ 0)"

        fuerte_temp = abs(report.r_co_temp) >= 0.75
        fuerte_hum = abs(report.r_co_hum) >= 0.75
        if report.avg_co < 30.0 and (fuerte_temp or fuerte_hum):
            report.derivas_higrotermicas = (
                f"DERIVA AMBIENTAL PROBABLE (r_T={report.r_co_temp:.2f}, r_RH={report.r_co_hum:.2f})"
            )
        else:
            report.derivas_higrotermicas = (
                f"EMISIÓN QUÍMICA GENUINA (r_T={report.r_co_temp:.2f}, r_RH={report.r_co_hum:.2f})"
            )

        # Conclusiones según regulaciones
        co = float(current.get("co", 0.0)) if current else report.avg_co
        lpg = float(current.get("lpg", 0.0)) if current else report.avg_lpg

        sb = []
        sa = []

        if co >= cls.CENAPRED_PELIGRO_CO or lpg >= cls.NOM_002_LEL_EVAC_LPG:
            sb.append("CONDICIÓN CRÍTICA DE EMERGENCIA: Concentración en nivel peligroso o explosivo (≥20% LEL). ")
            sa.append("EVACUAR INMEDIATAMENTE. No accionar interruptores eléctricos ni producir chispas. Ventilar el área.")
        elif fuga_acelerada_co or fuga_acelerada_lpg:
            sb.append(f"PRE-ALARMA PREDICTIVA: La tasa de cambio (dPPM/dt = +{report.d_co_dt:.2f} PPM/s) anticipa un siniestro antes de alcanzar el umbral de corte. ")
            sa.append("Cerrar llaves de paso de gas y revisar quemadores o conductos de escape.")
        elif report.twa_co >= cls.NOM_010_VLE_PPT_CO:
            sb.append(f"EXPOSICIÓN CRÓNICA: El promedio ponderado en el tiempo (TWA = {report.twa_co:.1f} PPM) rebasa la norma NOM-010-STPS (Límite 25 PPM). ")
            sa.append("El personal no debe permanecer en el recinto sin ventilación forzada.")
        elif co >= cls.NOM_010_VLE_PPT_CO or lpg >= cls.NOM_010_VLE_CT_LPG:
            sb.append("PRECAUCIÓN AMBIENTAL: Elevación de gases en rango preventivo normado (NOM-010-STPS). ")
            sa.append("Inspeccionar el sistema de combustión/llaves de paso y permitir circulación de aire natural.")
        else:
            sb.append("ATMÓSFERA NOMINAL: Niveles dentro de los estándares de higiene ocupacional inocua. ")
            sa.append("Operación regular. No se requieren acciones mitigadoras.")

        cls._eval_silicon_notes(report, current, sb)
        report.conclusion_general = "".join(sb)
        report.accion_recomendada = "".join(sa)

    @classmethod
    def _eval_silicon_notes(cls, report: AnalyticsReport, current: Optional[Dict], sb: Optional[List[str]] = None):
        if sb is None:
            sb = [report.conclusion_general]

        if current:
            alerta = str(current.get("alerta", "")).upper()
            fase = str(current.get("fase", "")).upper()
            seg = int(current.get("seg", 0))

            if alerta == "PRECALENTANDO":
                restante = max(0, 45 - seg)
                sb.append(
                    f"\n\n[Nota Silicio - Precalentamiento Inicial]: "
                    f"Acondicionamiento térmico de pastillas semiconductoras de SnO₂. "
                    f"Tiempo restante: {restante} s. "
                    f"Los filamentos térmicos del MQ-2 y MQ-7 se encuentran elevando su temperatura interna hacia el régimen de trabajo (~300 °C). "
                    f"Durante estos primeros 45 segundos se produce la desorción forzada de humedad ambiental y COV acumulados en frío. "
                    f"La línea base física de aire limpio se calibrará automáticamente al completar los 45 segundos."
                )
            elif fase == "PURGA":
                sb.append(
                    f"\n\n[Nota Silicio - Ciclo Térmico a 5.0V]: "
                    f"Sensor MQ-7 en fase de purga y desorción térmica ({seg}/60 s). "
                    f"El calefactor opera a 5.0V continuo (PWM Duty 255) para evaporar y limpiar impurezas químicas en la capa de SnO₂. "
                    f"Durante estos 60 segundos la resistencia interna desciende abruptamente por calentamiento extremo, "
                    f"lo que produce picos transitorios de PPM que NO corresponden a una fuga real de gas. "
                    f"La medición cuantitativa válida se consolida únicamente al descender a la fase de 1.4V."
                )
            elif fase == "MEDICION":
                sb.append(
                    f"\n\n[Nota Silicio - Sensado Cuantitativo a 1.4V]: "
                    f"Sensor MQ-7 en fase de quimiorresorción catalítica a 1.4V RMS ({seg}/90 s). "
                    f"El calefactor conmuta a modulación PWM (7.84% eficaz), alcanzando la temperatura molecular óptima "
                    f"para la adsorción selectiva de Monóxido de Carbono (CO). "
                    f"Lecturas analógicas estabilizadas en ventana cuantitativa certificada bajo la norma NOM-010-STPS."
                )
