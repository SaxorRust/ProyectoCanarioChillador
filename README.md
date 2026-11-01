# El Canario Chillador 🐤⚠️
## Sistema IoT Autónomo de Detección de Gases y Alerta Temprana Pasiva
**Materia:** Internet de las Cosas (IoT) — Proyecto Final

---

## 1. Descripción del Proyecto
Inspirado en la histórica metáfora del *canario en la mina de carbón*, este sistema es un nodo sensor de telemetría y alerta temprana in situ que detecta concentraciones peligrosas de **Monóxido de Carbono (CO)** y **Gases Combustibles / Humo (GLP, Butano)**.

### Principio Operativo: Autonomía Total (Edge Computing / Zero-Internet)
* El dispositivo opera de manera **100% autónoma y offline**. No depende de conexión Wi-Fi, servidores en la nube ni de un teléfono emparejado para salvar vidas.
* **HMI Local de Alerta:** Pantalla OLED SSD1306, semáforo tricolor de LEDs y un buzzer activo ("el chillido") con patrones de alarma según la norma **NOM-016-SSA2-2012 / OSHA**.
* **Telemetría Standalone:** Emite tramas JSON periódicas (1 Hz) por UART Serial y Bluetooth Clásico SPP para la estación base (App de Android encargada de gráficos e históricos).

---

## 2. Lista de Materiales Definitiva (BOM)

| Componente | Función Técnica | Pin ESP32 (38 Pines) |
| :--- | :--- | :--- |
| **ESP32 NodeMCU-32S** | SoC Principal (Dual Core, ADC1, Bluetooth) | Placa principal |
| **Sensor MQ-7** | Detección de Monóxido de Carbono (CO) | **GPIO 35** (ADC1_CH7) |
| **Sensor MQ-2** | Detección de GLP, Gas Combustible y Humo | **GPIO 34** (ADC1_CH6) |
| **Sensor DHT11 / DHT22** | Monitoreo térmico y compensación ambiental $f(T, RH)$ | **GPIO 4** (One-Wire) |
| **Transistor 2N2222A (BJT)** | Driver conmutación PWM para ciclo térmico del MQ-7 | **GPIO 25** (LEDC PWM) |
| **Diodo 1N4007** | Supresión de transitorios inductivos en filamento | Antiparalelo a filamento |
| **Pantalla OLED 0.96" I2C** | Interfaz visual local (PPM, estados, diagnóstico) | **GPIO 21** (SDA) / **GPIO 22** (SCL) |
| **Buzzer Activo** | "El Chillido" (Alarma acústica modulada) | **GPIO 27** |
| **LED Verde** | Indicador de estado Seguro / Normal | **GPIO 18** |
| **LED Amarillo** | Indicador de Precaución / Calentamiento MQ-7 | **GPIO 19** |
| **LED Rojo** | Indicador de Alarma Crítica de Peligro | **GPIO 23** |
| **Pulsador** | Multifunción: MUTE temporal (30s) / Cambio de pantalla | **GPIO 13** (INPUT_PULLUP) |
| **Resistencias $10\text{ k}\Omega$** | Divisores simétricos ADC1 y pull-down de base BJT | Divisores de tensión |
| **Resistencias $220\,\Omega$ / $330\,\Omega$** | Limitadoras de corriente en LEDs y Base del BJT | Serie con pines lógicos |

---

## 3. Matriz de Umbrales y Comportamiento de Alerta

| Estado | Condición | Semáforo LED | OLED SSD1306 | Buzzer ("El Chillido") |
| :--- | :--- | :--- | :--- | :--- |
| **NORMAL** | $\text{CO} < 35\text{ ppm}$ y $\text{GLP} < 400\text{ ppm}$ | **Verde** fijo | `CANARIO: [SEGURO]` | Silencio |
| **PRECAUCIÓN** | $35 \le \text{CO} < 100\text{ ppm}$ o Purga | **Amarillo** fijo | `CANARIO: [PRECAUCION]` | 1 pitido corto ($100\text{ ms}$) cada 2.5 s |
| **PELIGRO** | $\mathbf{CO \ge 100\text{ ppm}}$ o $\mathbf{GLP \ge 1000\text{ ppm}}$ | **Rojo** parpadeo ($5\text{ Hz}$) | `!PELIGRO: EVACUAR!` | **Chillido bitono continuo** ($1800\text{ Hz} \leftrightarrow 2400\text{ Hz}$) |

---

## 4. Estructura de Archivos

```
proyectoFinal_IOT/
├── diagram.json            # Circuito completo y diagramación para Wokwi
├── diagramaInicial.json    # Respaldo del circuito de simulación
├── sketchInicial.ino       # Firmware C++ completo (FSM, ADC1, HMI, JSON)
├── libraries.txt           # Dependencias para el simulador Wokwi
├── wokwi.toml              # Archivo de configuración para Wokwi CLI
├── Consideraciones.txt     # Alcance standalone y enlace Bluetooth a Android
├── proyectofinal finale.md # Memoria técnica, BOM y justificación de circuitos
├── .gitignore              # Protección de secretos (.env) y binarios
└── README.md               # Documentación general del repositorio
```

---

## 5. Simulación y Ejecución

### Opción A: Wokwi Web / Extensión VS Code
1. Abre [`diagram.json`](diagram.json) y [`sketchInicial.ino`](sketchInicial.ino) en el simulador.
2. Inicia la simulación para observar el arranque en fase de Purga ($5\text{ V}$) del MQ-7 y el semáforo en verde.
3. Desplaza los deslizadores de los sensores de gas para disparar las alertas acústicas y visuales.
4. Presiona el botón azul para probar el **MUTE temporal** en alarma o alternar vistas en estado normal.

### Opción B: Wokwi CLI
```bash
# Validar el circuito
wokwi-cli lint diagram.json

# Ejecutar simulación con expectativa de telemetría UART
wokwi-cli . --expect-text '"alerta":"NORMAL"'
```
