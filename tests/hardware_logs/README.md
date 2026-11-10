# Registro de Pruebas Físicas y Validación Empírica en Hardware (ESP32)

Este directorio documenta las sesiones de telemetría y pruebas físicas en laboratorio y ambiente real efectuadas sobre el nodo **El Canario Chillador** conectado a sensores de silicio y óxido metálico ($\text{SnO}_2$).

---

## 1. Agentes Químicos Utilizados en Pruebas Físicas

A diferencia de proyectos meramente simulados, este sistema fue sometido a validación empírica con gases y aerosoles reales:

1. **Gas Butano / GLP (Encendedor comercial):**
   - *Comportamiento:* Difusión gaseosa rápida, respuesta instantánea en el sensor MQ-2 ($\Delta V > +0.8\text{ V}$) y dispersión acelerada en presencia de ventilación natural ($dPPM/dt < -2.0\text{ PPM/s}$).
2. **Humo de Cerillo (Combustión de Azufre y Clorato de Potasio):**
   - *Comportamiento:* Generación súbita de dióxido de azufre ($\text{SO}_2$) y partículas carbonosas. Provoca una reducción química agresiva inmediata en la pastilla de dióxido de estaño, con picos de saturación transitoria.
3. **Humo de Espiral Antimosquitos / "Raidolito" (Aerosol de Piretroides y Resinas Orgánicas):**
   - *Comportamiento:* Emisión de aerosoles densos con aglutinantes aceitosos y piretroides (d-aletrina). Estos compuestos se condensan temporalmente sobre la malla de acero inoxidable y los microporos del $\text{SnO}_2$, requiriendo calor sostenido ($> 300\ ^\circ\text{C}$ durante la fase de 5.0V) para desorberse y recuperar el estado de reposo.

---

## 2. Hallazgos de Ingeniería y Mejoras Derivadas

El análisis de estos registros (`logs1am` a `logs6`) permitió resolver tres problemas críticos de instrumentación en hardware:

* **El "Efecto Trampa" de la Línea Base en Frío:**
  - *Problema:* Si la línea base ($V_{\text{base}}$) se capturaba en los primeros 200 ms de encendido con el sensor frío ($\sim 0.04\text{ V}$), al alcanzar el régimen térmico de trabajo ($\sim 0.30\text{ V}$) el sistema interpretaba permanentemente el aire limpio como gas acumulado.
  - *Solución:* Implementación de la **rutina de precalentamiento inicial de 45 segundos** a 5.0V continuo antes de fijar la línea base física.
* **Seguimiento Asimétrico de Piso Limpio (Anti-Drift):**
  - Incorporación de un filtro que solo permite actualizar la línea base hacia abajo (`voltaje < vBase`) tras 30 muestras consecutivas estables, protegiendo las lecturas cuantitativas ante variaciones térmicas lentas.
* **Filtro de Desacoplamiento de Pulsador (GPIO 13):**
  - Implementación de máquina de estados anti-rebote (40 ms) que desacopló el toque corto (< 1000 ms = Silencio del Buzzer) del toque sostenido ($\ge 1000\text{ ms}$ = Cambio de Vista OLED), previniendo activaciones duplicadas por contacto físico.

---

## 3. Índice de Bitácoras

| Archivo | Fecha / Hora | Tipo de Prueba | Fenómeno Analizado |
| :--- | :--- | :--- | :--- |
| `logs1amCanarioPrototipo.txt` | 07/Nov 01:36 | Arranque frío y enlace Bluetooth | Enlace inicial SPP y telemetría JSON continua a 1 Hz. |
| `logs2am.txt` | 07/Nov 01:49 | Exposición a gas butano | Evaluación de la curva de transducción logarítmica y pico máximo de GLP. |
| `logs3.txt` | 07/Nov 02:34 | Prueba acústica y pulsador | Comprobación de corte a 0V en GPIO 27 y lógica anti-rebote. |
| `logs4.txt` | 09/Nov 13:55 | Humo de cerillo y ventilación | Saturación de $\text{SO}_2$ y recuperación con flujo de aire natural. |
| `logs5.txt` | 09/Nov 16:14 | Humo denso de piretroides | Análisis de la desorción térmica de resinas a 5.0V en ciclo MQ-7. |
| `logs6.txt` | 09/Nov 17:39 | Calibración post-precalentamiento | Estabilización del reposo térmico en aire limpio tras 45 segundos. |
