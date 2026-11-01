# Memoria Técnica y Arquitectura de Datos: Sistema IoT de Monitoreo Ambiental y Alerta Temprana de Gases

## 1. Delimitación del Alcance (Scope Management)

El proyecto se define exclusivamente como un **nodo sensor de telemetría, monitoreo ambiental y alerta temprana pasiva**. Se descarta cualquier control electromecánico activo (electroválvulas de corte de gas o conmutación de extractores), eliminando por completo el módulo de relevador del circuito y del firmware para maximizar la estabilidad eléctrica del microcontrolador y evitar riesgos normativos.

```
[ MVP: Telemetría, Monitoreo y Alerta Pasiva ]
  ├── Procesamiento: ESP32 NodeMCU-32S (Wi-Fi 2.4 GHz, NTP, LittleFS)
  ├── Gases: MQ-7 (Monóxido de Carbono, ciclo térmico BJT) + MQ-2 (GLP/Humo continuo)
  ├── Entorno: Sensor DHT11 (Compensación térmica y de humedad de Rs)
  ├── HMI Local (Alerta In Situ): Pantalla OLED SSD1306 (I2C) + Semáforo LED + Buzzer
  └── Resiliencia: Buffer flash local (LittleFS) ante caídas de red LAN

[ Fase 2: Trabajo Futuro y Escalabilidad ]
  ├── Sensor NDIR SCD40 (Dióxido de Carbono fotoacústico de alta selectividad)
  ├── Sensor Láser PMS5003 (Material particulado PM2.5 / PM10)
  └── Sensor BMP280 (Compensación barométrica de difusión de gases)
```

## 2. Lista de Materiales Definitiva (BOM) y Presupuesto Ejecutado

Al suprimir el relevador, el costo total disminuye, reduciendo la carga inductiva sobre la fuente común.

|**Componente**|**Función Técnica**|**Cantidad**|**Estado / Fuente**|**Costo Real (MXN)**|
|---|---|---|---|---|
|**ESP32 NodeMCU-32S (38 pines)**|SoC principal (Dual Core, Wi-Fi, ADC1)|1|En inventario|$120.00|
|**Sensor MQ-7**|Detección de Monóxido de Carbono (CO)|1|En inventario|$55.00|
|**Sensor MQ-2**|Detección de GLP, Butano y Humo|1|En inventario|$50.00|
|**Sensor DHT11**|Monitoreo térmico y humedad relativa|1|En inventario|$40.00|
|**Pantalla OLED 0.96" I2C**|Interfaz visual local (PPM, IP, Estado FSM)|1|En inventario|$80.00|
|**Transistor BJT 2N2222A (TO-92)**|Driver de conmutación PWM para filamento MQ-7|1 (de 4)|Adquirido|$16.00|
|**Diodo 1N4007**|Supresión de transitorios inductivos en filamento|1 (de 2)|Adquirido|$4.00|
|**Resistencias $10\text{ k}\Omega$ (1/4W)**|Divisores simétricos ADC1 y pull-down de Base|5|Adquirido|$5.00|
|**Resistencias $220\,\Omega$ o $330\,\Omega$**|Limitadoras de corriente en LEDs y Base del BJT|4|Adquirido|$10.00|
|**Capacitor Electrolítico $1000\,\mu\text{F}$ (25V)**|Amortiguación de caídas de tensión en riel de 5V|1|En inventario|$12.00|
|**Capacitores Cerámicos $0.1\,\mu\text{F}$ (50V)**|Desacoplo de ruido de alta frecuencia|3 (de 4)|Adquirido|$8.00|
|**Buzzer Activo + 3 LEDs + Pulsador**|Alerta acústica, semáforo visual y control de UI|1 kit|En inventario|$35.00|
|**Protoboard MB-102 + Cables**|Montaje de laboratorio y buses de distribución|1|En inventario|$90.00|
|**Cargador USB-C ($5\text{V} \ge 2\text{A}$)**|Fuente de alimentación primaria regulada|1|En inventario|$0.00|
|**Total Invertido**||||**$525.00 MXN**|

## 3. Arquitectura Eléctrica y Justificación de Circuitos

### 3.1. Gestión Térmica del Sensor MQ-7 (PWM y Potencia Eficaz)

El filamento de óxido de estaño ($\text{SnO}_2$) del MQ-7 demanda un ciclo térmico bimétrico estricto:

1. **Fase de Purga:** 60 segundos a $5.0\text{ V}$ para volatilizar impurezas adsorbidas.
    
2. **Fase de Medición:** 90 segundos a $1.4\text{ V}$ para permitir la quimiorresorción del CO.
    

Al modular una fuente continua de $5\text{ V}$ mediante PWM, la potencia disipada por la resistencia calefactora depende del voltaje cuadrático medio ($V_{\text{RMS}}$):

$$V_{\text{RMS}} = V_{\text{in}} \sqrt{D}$$

Para obtener $1.4\text{ V}_{\text{RMS}}$ a partir de $V_{\text{in}} = 5.0\text{ V}$:

$$1.4 = 5.0 \sqrt{D} \implies \sqrt{D} = \frac{1.4}{5.0} = 0.28 \implies D = (0.28)^2 = 0.0784 \quad (\mathbf{7.84\%})$$

En un temporizador PWM del ESP32 a 8 bits de resolución ($0 - 255$ cuentas):

$$\text{Duty Cycle} = 255 \times 0.0784 \approx \mathbf{20}$$

- Durante la **purga (60 s)**: `duty = 255` ($5.0\text{ V}$ continuos).
    
- Durante la **medición (90 s)**: `duty = 20` ($1.4\text{ V}_{\text{RMS}}$). El muestreo analógico se realiza estrictamente en los últimos 5 segundos de esta fase.
    

### 3.2. Driver de Potencia con BJT 2N2222A

El calentador interno posee una resistencia $R_H \approx 33\,\Omega$, consumiendo:

$$I_C = \frac{5\text{ V}}{33\,\Omega} \approx 151.5\text{ mA}$$

Para saturar el 2N2222A con garantía ($V_{CE(\text{sat})} \approx 0.2\text{ V}$), se asume una ganancia forzada $\beta_{\text{sat}} \approx 15$:

$$I_B = \frac{I_C}{\beta_{\text{sat}}} = \frac{151.5\text{ mA}}{15} \approx 10.1\text{ mA}$$

Con la salida lógica a $3.3\text{ V}$ del GPIO 25 y $V_{BE} \approx 0.7\text{ V}$:

$$R_B = \frac{3.3\text{ V} - 0.7\text{ V}}{10.1\text{ mA}} \approx 257.4\,\Omega$$

Se instala una resistencia comercial de **$330\,\Omega$** (o **$220\,\Omega$**), entregando entre $8\text{ mA}$ y $12\text{ mA}$, corriente segura para el SoC. Una resistencia de $10\text{ k}\Omega$ en _pull-down_ entre Base y GND mantiene el transistor en corte durante el arranque (_bootloader_) del ESP32.

```
       Riel +5V (Fuente Primaria)
           │
         ┌─┴─────────┐
         │ Calentador│  (Pin VH del MQ-7)
         │   MQ-7    │
         └─┬─────────┘
           │
           ├────────────┐
           │            │  Diodo 1N4007
           │           ─── (Cátodo a 5V, Ánodo a Colector)
           │           ▲   Supresión de transitorios
           │            │
           │Colector    │
           ├────────────┘
           │
GPIO 25 ──[ 330 Ω ]─── Base (2N2222A)
                       │
                     [ 10 kΩ ] (Pull-down a GND)
                       │
                   ─── Emisor
                    │
                   GND Común
```

### 3.3. Divisor de Voltaje Simétrico para el ADC1

Los sensores MQ operan a $5\text{ V}$ en su terminal de sensado. Para no superar el rango dinámico lineal del ESP32 (que pierde linealidad arriba de los $2.6\text{ V}$ aun con atenuación de 11 dB), se implementa un divisor simétrico de **$10\text{ k}\Omega / 10\text{ k}\Omega$** para cada canal analógico:

$$V_{\text{ESP32}} = V_{\text{sensor}} \times \left( \frac{10\text{ k}\Omega}{10\text{ k}\Omega + 10\text{ k}\Omega} \right) = \frac{V_{\text{sensor}}}{2}$$

- Voltaje máximo admitido en el pin ADC: $5.0\text{ V} \times 0.5 = \mathbf{2.5\text{ V}}$ (operación completamente lineal).
    
- Factor de reconstrucción en código: $V_{\text{sensor}} = V_{\text{medido}} \times 2.0$.
    

### 3.4. Red de Desacoplo y Supresión de Ruido

- **Electrolítico de $1000\,\mu\text{F}$ (25V):** Instalado en paralelo directo entre el riel de $+5\text{ V}$ y GND. Amortigua caídas de potencial transitorias (_brownouts_) cuando el módem Wi-Fi emite ráfagas de paquetes.
    
- **Cerámicos de $0.1\,\mu\text{F}$ (104):** Desacoplo de alta frecuencia en paralelo con:
    
    1. Riel principal de $5\text{ V}$.
        
    2. Riel de $3.3\text{ V}$ del microcontrolador.
        
    3. Pines VCC/GND de la pantalla OLED para blindar las líneas del bus I2C contra ruido de conmutación.
        

## 4. Matriz de Conexiones y Distribución de Pines (Pinout)

Todos los canales analógicos se asignan exclusivamente al bloque **ADC1**, garantizando que el uso del radio Wi-Fi no congele ni corrompa las lecturas analógicas.

|**Dispositivo / Periférico**|**Pin del Módulo**|**Pin ESP32 (38 Pines)**|**Función / Canal**|**Alimentación**|**Detalle de Conexión**|
|---|---|---|---|---|---|
|**MQ-2**|A0 (Analógico)|**GPIO 34 (D34)**|ADC1_CH6 (Input Only)|Riel 5V|Divisor simétrico $10\text{ k}\Omega / 10\text{ k}\Omega$ a GND|
|**MQ-7**|A0 (Analógico)|**GPIO 35 (D35)**|ADC1_CH7 (Input Only)|Riel 5V|Divisor simétrico $10\text{ k}\Omega / 10\text{ k}\Omega$ a GND|
|**Driver MQ-7**|Base (2N2222A)|**GPIO 25 (D25)**|Salida PWM (LEDC)|Riel 5V (Colector)|En serie con $330\,\Omega$ + Pull-down $10\text{ k}\Omega$|
|**OLED SSD1306**|SDA|**GPIO 21 (D21)**|Bus I2C Datos|Riel 3.3V|Con capacitor cerámico $0.1\,\mu\text{F}$ local|
|**OLED SSD1306**|SCL|**GPIO 22 (D22)**|Bus I2C Reloj|Riel 3.3V|Línea directa de reloj|
|**Sensor DHT11**|DATA / OUT|**GPIO 4 (D4)**|One-Wire Bidireccional|Riel 3.3V|Adquisición de temperatura y humedad|
|**Buzzer Activo**|VCC / SIG|**GPIO 27 (D27)**|Salida Digital|—|Disparo sonoro directo de alerta|
|**LED Verde**|Ánodo (+)|**GPIO 18 (D18)**|Salida Digital|—|Indicador de estado Normal ($220\,\Omega$ a GND)|
|**LED Amarillo**|Ánodo (+)|**GPIO 19 (D19)**|Salida Digital|—|Indicador de Precaución ($220\,\Omega$ a GND)|
|**LED Rojo**|Ánodo (+)|**GPIO 23 (D23)**|Salida Digital|—|Indicador de Alarma Crítica ($220\,\Omega$ a GND)|
|**Pulsador**|Terminal A|**GPIO 13 (D13)**|Entrada Digital|—|Terminal B a GND (`INPUT_PULLUP` interno)|
|_(Sin uso)_|_(Desconectado)_|**GPIO 26 (D26)**|GPIO Libre / Reserva|—|**Reservado** (Relevador suprimido)|

## 5. Arquitectura de Firmware y Lógica Edge

```
                          [ INICIO / SETUP ]
                                  │
                  ┌───────────────┴───────────────┐
                  ▼                               ▼
       Configurar GPIOs y ADC             Iniciar LittleFS y Wi-Fi
       PWM Canal LEDC (Freq: 1 kHz)       Sincronizar NTP (Reloj UTC)
                  └───────────────┬───────────────┘
                                  │
                                  ▼
                     [ BUCLE PRINCIPAL NO BLOQUEANTE ]
                                  │
         ┌────────────────────────┼────────────────────────┐
         ▼                        ▼                        ▼
  [ Máquina MQ-7 ]        [ Tareas Periódicas ]    [ Resiliencia LAN ]
  Fase Purga: 60s @ 5V    DHT11 cada 2000 ms       Verificar enlace HTTP/MQTT
  (Duty = 255)            MQ-2 cada 1000 ms        • Enlace OK: Enviar JSON
         │                Filtro móvil (10 m.)     • Enlace Caído: Buffer LittleFS
         ▼                Actualizar OLED / LEDs   • Reconexión: Descarga ráfaga
  Fase Medición: 90s      Buzzer si rebase umbral
  (Duty = 20)
  Muestreo: Últimos 5s
```

### 5.1. Máquina de Estados Finitos (FSM) del MQ-7

El firmware controla el tiempo mediante marcas de `millis()` sin funciones de retardo bloqueante (`delay()`):

C++

```
enum EstadoMQ7 { PURGA, ESPERA_MEDICION, MUESTREO };
EstadoMQ7 estadoActual = PURGA;
unsigned long tiempoInicioFase = 0;

void manejarCicloMQ7() {
  unsigned long transcurrido = millis() - tiempoInicioFase;

  switch (estadoActual) {
    case PURGA:
      ledcWrite(CANAL_PWM, 255); // 5.0V continuos
      if (transcurrido >= 60000) {
        tiempoInicioFase = millis();
        estadoActual = ESPERA_MEDICION;
      }
      break;

    case ESPERA_MEDICION:
      ledcWrite(CANAL_PWM, 20);  // 1.4V RMS (Duty 7.84%)
      if (transcurrido >= 85000) { // Ventana final de 5 segundos
        estadoActual = MUESTREO;
      }
      break;

    case MUESTREO:
      tomarLecturaMQ7();         // Promedio móvil en ventana activa
      if (transcurrido >= 90000) {
        tiempoInicioFase = millis();
        estadoActual = PURGA;    // Reinicia ciclo
      }
      break;
  }
}
```

### 5.2. Conversión Analógica y Ecuaciones de Transducción

1. **Promedio móvil:** Ventana de 10 muestras espaciadas 20 ms sobre el canal ADC1.
    
2. **Reconstrucción de tensión:**
    
    $$V_{\text{real}} = \left( \frac{\text{ADC}_{\text{filtrado}}}{4095.0} \times 3.3\text{ V} \right) \times 2.0$$
    
3. **Cálculo de quimiorresistencia ($R_s$):** Con la resistencia de carga de la placa ($R_L$):
    
    $$R_s = R_L \times \left( \frac{5.0\text{ V} - V_{\text{real}}}{V_{\text{real}}} \right)$$
    
4. **Compensación ambiental:** Aplicando el factor dependiente de temperatura y humedad relativa del DHT11:
    
    $$R_{s,\text{comp}} = \frac{R_s}{f(T, RH)}$$
    
5. **Cálculo de concentración en volumen:**
    
    $$\text{PPM} = A \cdot \left(\frac{R_{s,\text{comp}}}{R_0}\right)^B$$
    

## 6. Arquitectura de Datos, Backend y Seguridad (Local-First)

La arquitectura no depende de nubes públicas para operar; el backend y la base de datos se alojan en un equipo host de la red local (LAN), permitiendo opcionalmente túneles inversos seguros si se requiere acceso remoto.

```
[ NODO SENSOR (ESP32) ]
  │  • Lectura de sensores y alerta física local (OLED, Buzzer, LEDs)
  │  • Buffer no volátil en LittleFS (/telemetria.json) ante desconexión
  │
  ▼ (Wi-Fi 2.4 GHz - MQTTS con TLS 1.3 o REST local)
[ HOST LOCAL (PC / Laptop / Mini-PC en LAN) ]
  │  • Servidor de Tiempo: RTC de hardware del Host (servidor NTP para la LAN)
  │  • Broker MQTT local (Eclipse Mosquitto) con credenciales únicas
  │  • Backend: Python (FastAPI) o Node.js
  │  • Base de Datos: Series de tiempo (TimescaleDB / PostgreSQL o SQLite)
  │  • Frontend Web Local: React / TypeScript servido vía mDNS (ej. http://gassafety.local)
  │  • Mecanismo de Acceso: Contraseña forzada + TOTP (RFC 6238)
  │
  ▼ (Capa Opcional: Solo si el cliente dispone de internet y desea acceso externo)
[ TÚNEL INVERSO SEGURO (Cloudflare Tunnel / Tailscale / WireGuard) ]
  │  • Sin reenvío de puertos (Port-Forwarding) ni IPs públicas abiertas
  ▼
[ CLIENTE REMOTO (Navegador / Smartphone fuera de casa) ]
```

### 6.1. Sello Temporal y Manejo de Tiempo Offline

- El ESP32 sincroniza su reloj vía NTP contra la IP del host local en la red LAN.
    
- En redes aisladas (_air-gapped_), el host local aprovecha el RTC de hardware de su placa madre para actuar como servidor NTP local, evitando el desvío temporal (_clock drift_).
    

### 6.2. Payload JSON Homologado

Se eliminan todas las referencias a relés o actuadores. El payload se enfoca exclusivamente en telemetría de variables físicas y metadatos de diagnóstico de red:

JSON

```
{
  "device_id": "ESP32-GAS-01",
  "seq_id": 1042,
  "timestamp": 1729000000,
  "location": "cocina_planta_baja",
  "co_ppm": 24.5,
  "lpg_ppm": 120.0,
  "temperature": 22.4,
  "humidity": 55.0,
  "mq7_phase": "MEDICION",
  "rssi_dbm": -62,
  "network_status": "ONLINE"
}
```

### 6.3. Autenticación Robusta en Entorno Local (TOTP + Onboarding)

- **Credenciales por defecto:** El sistema inicializa con credenciales predeterminadas. El primer acceso intercepta la petición y obliga a establecer una contraseña definitiva mediante un formulario exclusivo.
    
- **TOTP (RFC 6238):** Se asocia una semilla criptográfica generada en Base32 escaneable por código QR.
    
- **Ventana de tolerancia temporal:** El backend local valida el token con una holgura de $\pm 1$ paso temporal ($\pm 30\text{ s}$, cubriendo un margen de $90\text{ s}$ en total), garantizando que desfases leves entre el teléfono y el host no bloqueen al usuario aun sin internet activo.
    

## 7. Respuestas a los Puntos Clave de la Investigación

### 1. Preguntas básicas que el sistema responderá

1. ¿Cuál es la concentración actual de Monóxido de Carbono (CO) y Gas LP/Humo en el área supervisada?
    
2. ¿Se rebasaron los límites de exposición permisibles o existe riesgo de intoxicación o acumulación peligrosa de gas combustible?
    
3. ¿Cuál es la tendencia histórica y el comportamiento térmico/humedad en las últimas 24 horas?
    
4. ¿El nodo está operando en tiempo real o vaciando datos acumulados desde su memoria interna LittleFS tras un corte de red?
    
5. ¿En qué estado del ciclo de calentamiento térmico se encuentra el sensor MQ-7 (purga vs. medición)?
    

### 2. Matriz de datos y fuentes para cada respuesta

|**Pregunta del Usuario**|**Variable / Métrica**|**Unidad / Formato**|**Fuente Técnica**|
|---|---|---|---|
|Concentración de CO|Nivel de CO|PPM ($0 - 1000$)|MQ-7 vía GPIO 35 (ADC1) en ventana final de medición|
|Presencia de GLP/Humo|Nivel de Gas combustible|PPM ($200 - 10000$)|MQ-2 vía GPIO 34 (ADC1) en muestreo continuo|
|Compensación ambiental|Temperatura y Humedad|°C ($0-50$) / %RH ($20-90$)|Sensor DHT11 vía GPIO 4|
|Alerta visual/sonora local|Nivel de riesgo|Estado (`NORMAL`, `PRECAUCIÓN`, `ALARMA`)|Lógica de umbrales gobernando LEDs (GPIO 18, 19, 23) y Buzzer (GPIO 27)|
|Sincronización temporal|Estampa de tiempo|Epoch UTC (segundos/milisegundos)|Reloj del ESP32 sincronizado por NTP contra el host|
|Tolerancia a fallos|Registros pendientes|Conteo de objetos JSON|Memoria flash SPI gestionada con LittleFS (`/telemetria.json`)|

### 3. Protocolos, estándares, normas, metadatos y lenguajes

- **Protocolos de comunicación:**
    
    - Red/Transporte: TCP/IP sobre Wi-Fi IEEE 802.11 b/g/n (2.4 GHz).
        
    - Aplicación: MQTT v3.1.1/v5.0 (QoS 1) para telemetría ligera; HTTPS/REST para panel web y configuración.
        
    - Sincronización: NTP (RFC 5905) para fechado cronológico consistente.
        
- **Estándares y Normas:**
    
    - Seguridad y Salud: NOM-016-SSA2-2012 / OSHA Permissible Exposure Limits (Umbral preventivo de CO a 35 PPM; alerta crítica a >100 PPM).
        
    - Comunicaciones: IEEE 802.11 b/g/n, ISO/IEC 20922:2019 (MQTT).
        
- **Metadatos obligatorios en el mensaje:**
    
    - `device_id`: Identificador MAC único del ESP32.
        
    - `seq_id`: Contador incremental para detectar paquetes perdidos.
        
    - `rssi_dbm`: Indicador de intensidad de señal Wi-Fi.
        
    - `mq7_phase`: Fase térmica activa (`PURGA`, `MEDICION`).
        
- **Lenguajes utilizados:**
    
    - Firmware (Edge): C++ sobre marco Arduino Core / ESP-IDF.
        
    - Serialización: JSON.
        
    - Backend: Python (FastAPI) o Node.js.
        
    - Frontend: TypeScript / JavaScript con HTML5 y CSS3.
        

### 4. Vías o medios de transmisión

- **Canal inalámbrico local:** Ondas de radiofrecuencia a 2.4 GHz (Wi-Fi) entre el ESP32 y el punto de acceso LAN.
    
- **Canal cableado/LAN:** Tránsito por conmutadores y cables de red Ethernet hacia el host local donde reside el backend.
    
- **Medio no volátil transitorio:** Partición flash SPI mediante LittleFS en el ESP32, asegurando almacenamiento en cola de ráfaga ante pérdidas de enlace Wi-Fi.
    
- **Exposición a Internet (Opcional):** Túnel inverso saliente hacia un dominio seguro, sin apertura de puertos locales.
    

### 5. Algoritmos, candados y mecanismos de seguridad

- **Cifrado en tránsito:** Canal MQTTS encapsulado mediante TLS 1.3 (puerto 8883) empleando conjuntos de cifrado AES-128 o AES-256.
    
- **Integridad de datos:** Validación mediante HMAC-SHA256 para constatar la autenticidad del payload.
    
- **Protección contra ataques de repetición:** Validación estricta del timestamp UTC y descarte de números de secuencia (`seq_id`) repetidos o desordenados.
    
- **Control de acceso:** Credenciales locales forzadas a cambio en primer inicio, autenticación de dos factores TOTP (RFC 6238) y tokens de sesión JWT de corta duración.
    

### 6. Visualización de mensajes y señales

- **Nodo local (HMI física en el prototipo):**
    
    - Pantalla OLED SSD1306 (I2C): Muestra en tiempo real concentraciones en PPM de CO y GLP, temperatura, humedad, IP local, estampa horaria y fase térmica activa del MQ-7.
        
    - Semáforo LED tricolor: Verde (seguro), Amarillo (advertencia/calentamiento térmico), Rojo (peligro por concentración elevada).
        
    - Buzzer activo: Emite alarmas audibles intermitentes ante concentraciones fuera de norma.
        
- **Nodo destino (Dashboard Web en host/LAN):**
    
    - Tarjetas de estado con código de color dinámico.
        
    - Gráficas lineales continuas de dispersión de PPM contra tiempo.
        
    - Indicadores visuales radiales de nivel actual de gas.
        
    - Bitácora histórica con filtros de picos máximos y exportación de datos.
        

### 7. Tres escenarios de aplicación orientados a monitoreo y alerta pasiva

#### Escenario 1: Vivienda Residencial / Doméstica

- **Contexto:** Vigilancia de cocinas y áreas de calentadores de agua domésticos propensos a combustión incompleta o escapes de gas LP.
    
- **Operación:**
    
    - El ESP32 envía telemetría continua a la computadora del hogar vía Wi-Fi local.
        
    - Los ocupantes consultan el tablero desde teléfonos conectados a la misma red LAN.
        
    - Si la concentración rebasa el límite seguro, el dispositivo detona inmediatamente el buzzer y el LED rojo en la habitación; los residentes reciben la advertencia sonora in situ y la notificación en pantalla para ventilar de inmediato el área y cerrar las llaves de paso manuales.
        

#### Escenario 2: Cocina Comunitaria o Espacio Periurbano/Rural

- **Contexto:** Instalaciones con estufas de leña o fogones donde la combustión genera monóxido de carbono continuo en recintos cerrados, y donde la conexión a internet es nula o deficiente.
    
- **Operación:**
    
    - Toda la red opera en una subred LAN aislada (_air-gapped_) conectada a una laptop de administración.
        
    - El host local suministra la hora correcta vía NTP utilizando su RTC de hardware.
        
    - El acceso a la consola web requiere TOTP para evitar que personas ajenas alteren los parámetros de alerta.
        
    - Ante acumulaciones de humo o CO, el sistema activa la alarma acústica y visual para alertar al personal que evacúe o abra ventanas.
        

#### Escenario 3: Taller Mecánico Automotriz / Nave Pequeña

- **Contexto:** Zonas de afinación y fosas mecánicas donde motores en marcha emiten gases tóxicos sin renovación adecuada de aire.
    
- **Operación:**
    
    - Varios nodos sensores distribuidos en puntos críticos envían telemetría por MQTT hacia un host central en la recepción o jefatura de taller.
        
    - La base de datos local consolida las mediciones y expone el panel general.
        
    - Si un vehículo emite gases que saturan una fosa, el nodo correspondiente activa su buzzer y LED rojo in situ, alertando a los operarios para apagar el motor y salir del área confinada, registrando el incidente en la bitácora histórica para auditoría de seguridad e higiene laboral.