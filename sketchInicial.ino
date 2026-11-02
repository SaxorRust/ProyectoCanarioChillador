/**
 * ============================================================================
 * PROYECTO FINAL IoT: "EL CANARIO CHILLADOR"
 * Sistema Autónomo de Detección de Gases y Alerta Temprana Pasiva (Offline / Standalone)
 * autor: Ubaldo Edgar Gonzalez Flores
 * 
 * Principio Operativo:
 *  - 100% Autónomo y Standalone: No depende de Wi-Fi, nube ni Bluetooth para salvar vidas.
 *  - Telemetría Broadcast: Emite JSON por Serial/Bluetooth periódicamente para la App Android.
 * ============================================================================
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <DHT.h>

// Si se sube a un ESP32 físico con Bluetooth clásico, descomentar:
// #define ACTIVAR_BLUETOOTH_FISICO
#ifdef ACTIVAR_BLUETOOTH_FISICO
  #include "BluetoothSerial.h"
  BluetoothSerial SerialBT;
#endif

// ==========================================
// 1. ASIGNACIÓN DE PINES (PINOUT)
// ==========================================
#define PIN_MQ2_ADC        34    // ADC1_CH6 (MQ-2 GLP / Humo)
#define PIN_MQ7_ADC        35    // ADC1_CH7 (MQ-7 Monóxido de Carbono)
#define PIN_DHT            4     // Sensor ambiental DHT11
#define PIN_MQ7_HEATER     25    // Conmutación PWM calefactor MQ-7 (Base BJT)
#define PIN_BUZZER         27    // Alerta sonora (Buzzer "chillador")
#define PIN_LED_VERDE      18    // Semáforo: Estado Seguro / Normal
#define PIN_LED_AMARILLO   19    // Semáforo: Estado Precaución / Calentamiento
#define PIN_LED_ROJO       23    // Semáforo: Estado Peligro Crítico
#define PIN_BOTON          13    // Pulsador multifunción (Silencio / Vista)

#define OLED_SDA           21    // Bus I2C Datos
#define OLED_SCL           22    // Bus I2C Reloj

// ==========================================
// 2. PARÁMETROS DEL OLED SSD1306
// ==========================================
#define SCREEN_WIDTH       128
#define SCREEN_HEIGHT      64
#define OLED_RESET         -1
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// ==========================================
// 3. PARÁMETROS DEL SENSOR DHT (DHT22 en Wokwi / DHT11 en Físico)
// ==========================================
#define DHTTYPE            DHT22
DHT dht(PIN_DHT, DHTTYPE);

// ==========================================
// 4. TIEMPOS Y PARÁMETROS DEL CICLO MQ-7
// ==========================================
// Ciclo térmico normativo MQ-7:
//  - Fase Purga: 60 s a 5.0V (Duty 255)
//  - Fase Medición: 90 s a 1.4V RMS (Duty 20 = 7.84%)
//  - Muestreo: últimos 5 segundos de la fase de medición
const unsigned long DURACION_PURGA_MS     = 60000;
const unsigned long DURACION_MEDICION_MS  = 90000;
const unsigned long VENTANA_MUESTREO_MS   = 5000;

enum EstadoMQ7 { FASE_PURGA, FASE_MEDICION };
EstadoMQ7 estadoMQ7 = FASE_PURGA;
unsigned long tiempoInicioFaseMQ7 = 0;

// Duty cycles para ESP32 (resolución 8 bits: 0 - 255)
const uint8_t DUTY_PURGA_5V    = 255; // 5.0 V RMS
const uint8_t DUTY_MEDICION_1V = 20;  // 1.4 V RMS (D = 0.0784)

// ==========================================
// 5. UMBRALES DE RIESGO DE GASES (NOM / OSHA)
// ==========================================
// Monóxido de Carbono (CO en PPM):
const float CO_UMBRAL_PRECAUCION = 35.0;   // Límite de exposición preventiva
const float CO_UMBRAL_PELIGRO    = 100.0;  // Concentración crítica de evacuación

// Gas Combustible GLP / Butano / Humo (PPM equivalentes):
const float LPG_UMBRAL_PRECAUCION = 400.0;
const float LPG_UMBRAL_PELIGRO    = 1000.0;

enum NivelAlerta { NORMAL, PRECAUCION, PELIGRO };
NivelAlerta nivelActual = NORMAL;

// ==========================================
// 6. VARIABLES GLOBALES DE SENSORES Y ESTADO
// ==========================================
float ppmCO = 0.0;
float ppmLPG = 0.0;
float temperatura = 22.0;
float humedad = 50.0;
float voltajeMQ7 = 0.0;
float voltajeMQ2 = 0.0;

// Filtros de media móvil (10 muestras)
const int NUM_MUESTRAS = 10;
int muestrasMQ2[NUM_MUESTRAS];
int muestrasMQ7[NUM_MUESTRAS];
int indiceMuestra = 0;
bool bufferLleno = false;

// Control de interfaz y pulsador
int vistaActual = 0; // 0 = Vista Principal de Alerta, 1 = Vista Técnica de Diagnóstico
bool buzzerSilenciado = false;
unsigned long tiempoSilenciado = 0;
const unsigned long DURACION_MUTE_MS = 30000; // 30 segundos de silencio temporal

// Temporizadores no bloqueantes
unsigned long tUltimaTelemetria = 0;
unsigned long tUltimoDHT = 0;
unsigned long tUltimoBuzzer = 0;
unsigned long tUltimoDebounceBoton = 0;
bool estadoBuzzerChillido = false;

// ==========================================
// FUNCIONES AUXILIARES: PWM Y HARDWARE
// ==========================================
void aplicarPWMCalentador(uint8_t duty) {
  // analogWrite() en ESP32 Core configura y conmuta automáticamente el canal LEDC
  analogWrite(PIN_MQ7_HEATER, duty);
}

// Factor de corrección ambiental para resistencia de óxido metálico f(T, RH)
float factorCompensacionAmbiental(float t, float rh) {
  if (isnan(t) || isnan(rh)) return 1.0;
  // Aproximación de datasheet de sensores MQ: punto neutro a 20°C y 65% RH
  float factor = 1.0 + 0.005 * (t - 20.0) - 0.002 * (rh - 65.0);
  if (factor < 0.3) factor = 0.3;
  return factor;
}

// Convierte cuentas ADC a voltaje considerando el divisor simétrico 10k / 10k
float adcAVoltaje(int rawAdc) {
  // Rango 0-4095 a 3.3V, multiplicado por 2.0 por el divisor de voltaje
  return ((float)rawAdc / 4095.0f) * 3.3f * 2.0f;
}

// Conversión analógica a PPM estimada
float voltajeAPPM_CO(float v, float factorEnv) {
  // En Wokwi wokwi-gas-sensor entrega tensión directa proporcional al gas
  // Para realismo físico: Vout aumenta con la concentración
  if (v < 0.1f) v = 0.1f;
  float ppm = pow((v / 5.0f), 2.2f) * 500.0f;
  return ppm / factorEnv;
}

float voltajeAPPM_LPG(float v, float factorEnv) {
  if (v < 0.1f) v = 0.1f;
  float ppm = pow((v / 5.0f), 2.0f) * 2000.0f;
  return ppm / factorEnv;
}

// ==========================================
// SETUP: INICIALIZACIÓN OFFLINE ROBUSTA
// ==========================================
void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println(F("\n=============================================="));
  Serial.println(F(" CANARIO CHILLADOR - NODO IOT STANDALONE "));
  Serial.println(F(" Modo 100% Autonomo / Cero Dependencia de Red "));
  Serial.println(F("=============================================="));

#ifdef ACTIVAR_BLUETOOTH_FISICO
  SerialBT.begin("Canario_Chillador_IoT");
  Serial.println(F("[BT] Bluetooth Classic SPP iniciado: 'Canario_Chillador_IoT'"));
#endif

  // Configuración de pines de entrada y salida
  pinMode(PIN_MQ2_ADC, INPUT);
  pinMode(PIN_MQ7_ADC, INPUT);
  pinMode(PIN_DHT, INPUT);
  pinMode(PIN_BOTON, INPUT_PULLUP);

  pinMode(PIN_MQ7_HEATER, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_LED_VERDE, OUTPUT);
  pinMode(PIN_LED_AMARILLO, OUTPUT);
  pinMode(PIN_LED_ROJO, OUTPUT);

  // Inicialización de actuadores en estado seguro
  digitalWrite(PIN_BUZZER, LOW);
  digitalWrite(PIN_LED_VERDE, HIGH);
  digitalWrite(PIN_LED_AMARILLO, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);

  // Inicializar pantalla OLED I2C
  Wire.begin(OLED_SDA, OLED_SCL);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println(F("[WARN] OLED SSD1306 no responde en 0x3C, reintentando..."));
  } else {
    display.clearDisplay();
    display.setTextColor(SSD1306_WHITE);
    display.setTextSize(1);
    display.setCursor(10, 15);
    display.println(F("CANARIO CHILLADOR"));
    display.setCursor(10, 30);
    display.println(F("Sistema de Alerta"));
    display.setCursor(10, 45);
    display.println(F("Iniciando sensores..."));
    display.display();
  }

  // Inicializar DHT11
  dht.begin();

  // Iniciar ciclo térmico del MQ-7 en fase de PURGA inicial (5.0V)
  estadoMQ7 = FASE_PURGA;
  tiempoInicioFaseMQ7 = millis();
  aplicarPWMCalentador(DUTY_PURGA_5V);

  // Inicializar buffer de promedio móvil
  for (int i = 0; i < NUM_MUESTRAS; i++) {
    muestrasMQ2[i] = analogRead(PIN_MQ2_ADC);
    muestrasMQ7[i] = analogRead(PIN_MQ7_ADC);
  }

  delay(1000);
}

// ==========================================
// LOOP PRINCIPAL: MULTITAREA NO BLOQUEANTE
// ==========================================
void loop() {
  unsigned long ahora = millis();

  // 1. GESTIÓN DE LA FSM DEL MQ-7 (CICLO TÉRMICO ESTRICTO)
  gestionarCicloTermicoMQ7(ahora);

  // 2. LECTURA AMBIENTAL PERIÓDICA (DHT11 cada 2000 ms)
  if (ahora - tUltimoDHT >= 2000) {
    tUltimoDHT = ahora;
    float t = dht.readTemperature();
    float h = dht.readHumidity();
    if (!isnan(t)) temperatura = t;
    if (!isnan(h)) humedad = h;
  }

  // 3. ADQUISICIÓN Y FILTRADO CONTINUO DE GASES (cada 100 ms)
  static unsigned long tUltimoMuestreo = 0;
  if (ahora - tUltimoMuestreo >= 100) {
    tUltimoMuestreo = ahora;
    muestrasMQ2[indiceMuestra] = analogRead(PIN_MQ2_ADC);
    muestrasMQ7[indiceMuestra] = analogRead(PIN_MQ7_ADC);
    indiceMuestra = (indiceMuestra + 1) % NUM_MUESTRAS;

    // Calcular promedio móvil
    long sumaMQ2 = 0, sumaMQ7 = 0;
    for (int i = 0; i < NUM_MUESTRAS; i++) {
      sumaMQ2 += muestrasMQ2[i];
      sumaMQ7 += muestrasMQ7[i];
    }
    int promMQ2 = sumaMQ2 / NUM_MUESTRAS;
    int promMQ7 = sumaMQ7 / NUM_MUESTRAS;

    voltajeMQ2 = adcAVoltaje(promMQ2);
    voltajeMQ7 = adcAVoltaje(promMQ7);

    float fEnv = factorCompensacionAmbiental(temperatura, humedad);
    ppmLPG = voltajeAPPM_LPG(voltajeMQ2, fEnv);

    // El sensor MQ-7 se actualiza de manera continua o en ventana de medición
    ppmCO = voltajeAPPM_CO(voltajeMQ7, fEnv);
  }

  // 4. EVALUACIÓN DE NIVELES DE RIESGO Y ALERTA (LÓGICA DEL CANARIO)
  evaluarNivelRiesgo();

  // 5. GESTIÓN DEL PULSADOR (MUTE / VISTA)
  gestionarPulsador(ahora);

  // 6. GESTIÓN DE ACTUADORES LOCALES (EL CHILLIDO Y EL SEMÁFORO)
  gestionarActuadoresLocales(ahora);

  // 7. ACTUALIZACIÓN VISUAL DE LA PANTALLA OLED (cada 250 ms)
  static unsigned long tUltimoOLED = 0;
  if (ahora - tUltimoOLED >= 250) {
    tUltimoOLED = ahora;
    actualizarOLED();
  }

  // 8. TELEMETRÍA STANDALONE (SERIAL / BLUETOOTH cada 1000 ms)
  if (ahora - tUltimaTelemetria >= 1000) {
    tUltimaTelemetria = ahora;
    emitirTelemetriaJSON();
  }

  // 9. PROCESAMIENTO DE COMANDOS BIDIRECCIONALES (APP ANDROID / TERMINAL)
  gestionarComandosEntrantes();
}

// ==========================================
// RECEPCIÓN DE COMANDOS BIDIRECCIONALES
// ==========================================
void procesarComandoTexto(String cmd) {
  cmd.trim();
  cmd.toUpperCase();
  if (cmd.length() == 0) return;

  if (cmd == "MUTE") {
    buzzerSilenciado = !buzzerSilenciado;
    if (buzzerSilenciado) {
      tiempoSilenciado = millis();
      digitalWrite(PIN_BUZZER, LOW);
      noTone(PIN_BUZZER);
      Serial.println(F("[REMOTE] Buzzer silenciado via comando (MUTE)."));
    } else {
      Serial.println(F("[REMOTE] Silencio desactivado via comando."));
    }
  } else if (cmd == "VISTA") {
    vistaActual = (vistaActual + 1) % 2;
    Serial.print(F("[REMOTE] Pantalla OLED cambiada via comando a vista: "));
    Serial.println(vistaActual);
  } else if (cmd == "PING" || cmd == "TEST") {
    emitirTelemetriaJSON();
  }
}

void gestionarComandosEntrantes() {
#ifdef ACTIVAR_BLUETOOTH_FISICO
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    procesarComandoTexto(cmd);
  }
#endif
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    procesarComandoTexto(cmd);
  }
}

// ==========================================
// FSM DEL SENSOR MQ-7
// ==========================================
void gestionarCicloTermicoMQ7(unsigned long ahora) {
  unsigned long transcurrido = ahora - tiempoInicioFaseMQ7;

  switch (estadoMQ7) {
    case FASE_PURGA:
      aplicarPWMCalentador(DUTY_PURGA_5V);
      if (transcurrido >= DURACION_PURGA_MS) {
        estadoMQ7 = FASE_MEDICION;
        tiempoInicioFaseMQ7 = ahora;
        aplicarPWMCalentador(DUTY_MEDICION_1V);
      }
      break;

    case FASE_MEDICION:
      aplicarPWMCalentador(DUTY_MEDICION_1V);
      if (transcurrido >= DURACION_MEDICION_MS) {
        estadoMQ7 = FASE_PURGA;
        tiempoInicioFaseMQ7 = ahora;
        aplicarPWMCalentador(DUTY_PURGA_5V);
      }
      break;
  }
}

// ==========================================
// EVALUACIÓN DE UMBRALES DE SEGURIDAD
// ==========================================
void evaluarNivelRiesgo() {
  if (ppmCO >= CO_UMBRAL_PELIGRO || ppmLPG >= LPG_UMBRAL_PELIGRO) {
    nivelActual = PELIGRO;
  } else if (ppmCO >= CO_UMBRAL_PRECAUCION || ppmLPG >= LPG_UMBRAL_PRECAUCION || estadoMQ7 == FASE_PURGA) {
    nivelActual = PRECAUCION;
  } else {
    nivelActual = NORMAL;
  }

  // Reset del silencio si los gases vuelven a rango seguro
  if (nivelActual == NORMAL && buzzerSilenciado) {
    buzzerSilenciado = false;
  }
}

// ==========================================
// CONTROL DEL PULSADOR (GPIO 13)
// ==========================================
void gestionarPulsador(unsigned long ahora) {
  static int estadoBotonPrevio = HIGH;
  int lectura = digitalRead(PIN_BOTON);

  if (lectura == LOW && estadoBotonPrevio == HIGH) {
    if (ahora - tUltimoDebounceBoton > 250) {
      tUltimoDebounceBoton = ahora;

      if (nivelActual == PELIGRO || nivelActual == PRECAUCION) {
        // En alarma: Botón funciona como MUTE temporal
        buzzerSilenciado = !buzzerSilenciado;
        if (buzzerSilenciado) {
          tiempoSilenciado = ahora;
          digitalWrite(PIN_BUZZER, LOW);
          Serial.println(F("[HMI] Alarma acustica silenciada temporalmente (MUTE)."));
        } else {
          Serial.println(F("[HMI] Silencio desactivado manualmente."));
        }
      } else {
        // En normalidad: Conmuta la pantalla OLED
        vistaActual = (vistaActual + 1) % 2;
        Serial.print(F("[HMI] Cambio de pantalla a vista: "));
        Serial.println(vistaActual);
      }
    }
  }
  estadoBotonPrevio = lectura;

  // Auto-expiración del mute de 30 segundos
  if (buzzerSilenciado && (ahora - tiempoSilenciado >= DURACION_MUTE_MS)) {
    buzzerSilenciado = false;
    Serial.println(F("[HMI] Mute temporal expirado."));
  }
}

// ==========================================
// ACTUADORES LOCALES: SEMÁFORO Y EL CHILLIDO
// ==========================================
void gestionarActuadoresLocales(unsigned long ahora) {
  switch (nivelActual) {
    case NORMAL:
      digitalWrite(PIN_LED_VERDE, HIGH);
      digitalWrite(PIN_LED_AMARILLO, LOW);
      digitalWrite(PIN_LED_ROJO, LOW);
      digitalWrite(PIN_BUZZER, LOW);
      break;

    case PRECAUCION:
      digitalWrite(PIN_LED_VERDE, LOW);
      digitalWrite(PIN_LED_AMARILLO, HIGH);
      digitalWrite(PIN_LED_ROJO, LOW);

      // Pitido corto preventivo cada 2.5 segundos si no está silenciado
      if (!buzzerSilenciado) {
        if (ahora - tUltimoBuzzer >= 2500) {
          tUltimoBuzzer = ahora;
          tone(PIN_BUZZER, 1200, 100); // Tono suave de advertencia
        }
      } else {
        digitalWrite(PIN_BUZZER, LOW);
      }
      break;

    case PELIGRO:
      // ¡EL CANARIO CHILLA! 
      // Parpadeo agresivo de LED Rojo (5 Hz)
      digitalWrite(PIN_LED_VERDE, LOW);
      digitalWrite(PIN_LED_AMARILLO, LOW);
      digitalWrite(PIN_LED_ROJO, (ahora % 200 < 100) ? HIGH : LOW);

      // Chillido continuo modulado si no está en mute
      if (!buzzerSilenciado) {
        if (ahora - tUltimoBuzzer >= 150) {
          tUltimoBuzzer = ahora;
          estadoBuzzerChillido = !estadoBuzzerChillido;
          if (estadoBuzzerChillido) {
            tone(PIN_BUZZER, 2400); // Frecuencia chillona aguda
          } else {
            tone(PIN_BUZZER, 1800); // Bitono oscilante tipo alarma
          }
        }
      } else {
        noTone(PIN_BUZZER);
        digitalWrite(PIN_BUZZER, LOW);
      }
      break;
  }
}

// ==========================================
// PANTALLA OLED: INTERFAZ VISUAL IN SITU
// ==========================================
void actualizarOLED() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);

  if (vistaActual == 0) {
    // --- VISTA 0: DASHBOARD DE SEGURIDAD (HMI PRINCIPAL) ---
    // Encabezado de estado
    display.setTextSize(1);
    display.setCursor(0, 0);
    if (nivelActual == NORMAL) {
      display.print(F("CANARIO: [SEGURO]"));
    } else if (nivelActual == PRECAUCION) {
      display.print(F("CANARIO: [PRECAUCION]"));
    } else {
      display.print(F("!PELIGRO: EVACUAR!"));
    }

    if (buzzerSilenciado) {
      display.setCursor(100, 0);
      display.print(F("[M]"));
    }

    display.drawLine(0, 9, 127, 9, SSD1306_WHITE);

    // Lectura de Monóxido de Carbono (CO)
    display.setCursor(0, 14);
    display.print(F("CO:  "));
    display.setTextSize(2);
    display.print(ppmCO, 1);
    display.setTextSize(1);
    display.print(F(" ppm"));

    // Barra de nivel visual para CO (0 a 150 ppm)
    int barraCO = map((int)ppmCO, 0, 150, 0, 120);
    if (barraCO > 120) barraCO = 120;
    if (barraCO < 0) barraCO = 0;
    display.drawRect(0, 31, 122, 5, SSD1306_WHITE);
    display.fillRect(1, 32, barraCO, 3, SSD1306_WHITE);

    // Lectura de Gas Combustible / Humo (GLP)
    display.setTextSize(1);
    display.setCursor(0, 39);
    display.print(F("GLP: "));
    display.setTextSize(2);
    display.print(ppmLPG, 0);
    display.setTextSize(1);
    display.print(F(" ppm"));

    // Barra de nivel visual para GLP (0 a 1500 ppm)
    int barraLPG = map((int)ppmLPG, 0, 1500, 0, 120);
    if (barraLPG > 120) barraLPG = 120;
    if (barraLPG < 0) barraLPG = 0;
    display.drawRect(0, 56, 122, 5, SSD1306_WHITE);
    display.fillRect(1, 57, barraLPG, 3, SSD1306_WHITE);

  } else {
    // --- VISTA 1: DIAGNÓSTICO TÉCNICO Y AMBIENTAL ---
    display.setTextSize(1);
    display.setCursor(0, 0);
    display.print(F("DIAGNOSTICO TECNICO"));
    display.drawLine(0, 9, 127, 9, SSD1306_WHITE);

    // Temperatura y Humedad del DHT11
    display.setCursor(0, 13);
    display.print(F("Temp: "));
    display.print(temperatura, 1);
    display.print(F(" C  H: "));
    display.print(humedad, 0);
    display.print(F("%"));

    // Voltajes crudos de los sensores
    display.setCursor(0, 25);
    display.print(F("V_MQ7: "));
    display.print(voltajeMQ7, 2);
    display.print(F("V"));

    display.setCursor(0, 37);
    display.print(F("V_MQ2: "));
    display.print(voltajeMQ2, 2);
    display.print(F("V"));

    // Estado térmico del MQ-7 y segundero
    unsigned long segFase = (millis() - tiempoInicioFaseMQ7) / 1000;
    display.setCursor(0, 49);
    display.print(F("MQ7: "));
    if (estadoMQ7 == FASE_PURGA) {
      display.print(F("PURGA ("));
      display.print(segFase);
      display.print(F("/60s)"));
    } else {
      display.print(F("MEDICION ("));
      display.print(segFase);
      display.print(F("/90s)"));
    }
  }

  display.display();
}

// ==========================================
// TELEMETRÍA JSON: BROADCAST SERIAL / BLUETOOTH
// ==========================================
void emitirTelemetriaJSON() {
  unsigned long segFase = (millis() - tiempoInicioFaseMQ7) / 1000;
  const char* estadoTexto = (nivelActual == NORMAL) ? "NORMAL" : ((nivelActual == PRECAUCION) ? "PRECAUCION" : "PELIGRO");
  const char* faseTexto   = (estadoMQ7 == FASE_PURGA) ? "PURGA" : "MEDICION";

  // Formato JSON compacto y estandarizado delimitado por salto de línea
  String json = "{";
  json += "\"co\":" + String(ppmCO, 1) + ",";
  json += "\"lpg\":" + String(ppmLPG, 1) + ",";
  json += "\"temp\":" + String(temperatura, 1) + ",";
  json += "\"hum\":" + String(humedad, 1) + ",";
  json += "\"fase\":\"" + String(faseTexto) + "\",";
  json += "\"seg\":" + String(segFase) + ",";
  json += "\"alerta\":\"" + String(estadoTexto) + "\",";
  json += "\"mute\":" + String(buzzerSilenciado ? "true" : "false");
  json += "}";

  // Envío por puerto Serial UART (Monitor de Wokwi / Cable USB)
  Serial.println(json);

#ifdef ACTIVAR_BLUETOOTH_FISICO
  // Envío por Bluetooth hacia la App de Android
  SerialBT.println(json);
#endif
}
