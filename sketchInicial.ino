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
#define ACTIVAR_BLUETOOTH_FISICO
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
#define PIN_RGB_AZUL       18    // Semáforo LED RGB (Cátodo Común): Canal Azul
#define PIN_RGB_VERDE      19    // Semáforo LED RGB (Cátodo Común): Canal Verde
#define PIN_RGB_ROJO       23    // Semáforo LED RGB (Cátodo Común): Canal Rojo
#define PIN_BOTON          13    // Pulsador multifunción (Silencio / Vista)

// Tipo de Buzzer: 1 = Buzzer Activo (2 patas DC directo), 0 = Buzzer Pasivo (PWM tone)
#define ES_BUZZER_ACTIVO   1

#define OLED_SDA           21    // Bus I2C Datos
#define OLED_SCL           22    // Bus I2C Reloj

// ==========================================
// 2. PARÁMETROS DEL OLED SSD1306
// ==========================================
#define SCREEN_WIDTH       128
#define SCREEN_HEIGHT      64
#define OLED_RESET         -1
// Bus I2C a 100 kHz para máxima estabilidad en protoboard
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET, 100000UL, 100000UL);

// ==========================================
// 3. PARÁMETROS DEL SENSOR DHT (DHT22 en Wokwi / DHT11 en Físico)
// ==========================================
#define DHTTYPE            DHT11
DHT dht(PIN_DHT, DHTTYPE);

uint8_t oledAddr = 0x3C;
bool oledConectada = false;

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
// 5. UMBRALES DE RIESGO DE GASES (NORMAS OFICIALES MEXICANAS)
// ==========================================
// Monóxido de Carbono (CO en PPM) - NOM-010-STPS-2014 / CENAPRED:
const float CO_UMBRAL_PRECAUCION = 25.0;   // NOM-010-STPS-2014 (VLE-PPT 8h en ámbito laboral)
const float CO_UMBRAL_PELIGRO    = 100.0;  // Criterio CENAPRED / Protección Civil (síntomas agudos/evacuación)

// Gas Combustible GLP / Butano / Humo (PPM equivalentes) - NOM-010-STPS / NOM-002-STPS:
const float LPG_UMBRAL_PRECAUCION = 1000.0; // NOM-010-STPS-2014 VLE-CT (Butano 15 min / ~5% LEL)
const float LPG_UMBRAL_PELIGRO    = 3200.0; // NOM-002-STPS-2010 / NOM-033-STPS-2015 (20% del LEL)

enum NivelAlerta { NORMAL, PRECAUCION, PELIGRO };
NivelAlerta nivelActual = NORMAL;

// Modo Operativo del Sistema (Rutina de Precalentamiento de 45s al arrancar)
enum ModoOperativo { PRECALENTANDO, VIGILANCIA };
ModoOperativo modoOperativo = PRECALENTANDO;
const unsigned long DURACION_PRECALENTAMIENTO_MS = 45000;
unsigned long tInicioPrecalentamiento = 0;

// ==========================================
// 6. VARIABLES GLOBALES DE SENSORES Y ESTADO
// ==========================================
float ppmCO = 0.0;
float ppmLPG = 0.0;
float temperatura = 22.0;
float humedad = 50.0;
float voltajeMQ7 = 0.0;
float voltajeMQ2 = 0.0;

// Voltajes de referencia calibrados en aire limpio (Línea base física)
// Nota: No se auto-elevan al reiniciar para conservar la lectura de gas residual en el aire.
float vBaseMQ7 = 1.30f;  // Reposo físico calibrado del MQ-7 en aire limpio (~1.33V)
float vBaseMQ2 = 0.25f;  // Reposo físico calibrado del MQ-2 en aire limpio (< 1.00V)

// Filtros de media móvil (10 muestras)
const int NUM_MUESTRAS = 10;
int muestrasMQ2[NUM_MUESTRAS];
int muestrasMQ7[NUM_MUESTRAS];
int indiceMuestra = 0;
bool bufferLleno = false;

// Control de interfaz, pulsador y polaridad de buzzer
int vistaActual = 0; // 0 = Vista Principal de Alerta, 1 = Vista Técnica de Diagnóstico
bool buzzerSilenciado = false;
bool buzzerLogicaInvertida = false; // Opcional (comando INVERTIR_BUZZER) si la pata '-' quedó en 3.3V
unsigned long tiempoSilenciado = 0;
const unsigned long DURACION_MUTE_MS = 60000; // 60 segundos de silencio temporal

// Temporizadores no bloqueantes
unsigned long tUltimaTelemetria = 0;
unsigned long tUltimoDHT = 0;
unsigned long tUltimoBuzzer = 0;
unsigned long tUltimoDebounceBoton = 0;
unsigned long tUltimoOLED = 0;
unsigned long tReintentoI2C = 0;
bool estadoBuzzerChillido = false;

// ==========================================
// FUNCIONES AUXILIARES: PWM Y HARDWARE
// ==========================================
// Conmutador unificado de buzzer (compatible con activo de 2 patas, pasivo y polaridad invertida)
void sonarBuzzer(bool encender, int frecuencia) {
  uint8_t nivelOn  = buzzerLogicaInvertida ? LOW : HIGH;
  uint8_t nivelOff = buzzerLogicaInvertida ? HIGH : LOW;

  if (!encender) {
#if !ES_BUZZER_ACTIVO
    noTone(PIN_BUZZER);
#endif
    digitalWrite(PIN_BUZZER, nivelOff);
  } else {
#if ES_BUZZER_ACTIVO
    (void)frecuencia;
    digitalWrite(PIN_BUZZER, nivelOn);
#else
    tone(PIN_BUZZER, frecuencia);
#endif
  }
}

// Lectura analógica robusta con sobremuestreo y rechazo de ruido / falsos contactos dupont
int leerADCRobusto(int pin) {
  long sum = 0;
  int minV = 4096, maxV = -1;
  for (int i = 0; i < 6; i++) {
    int val = analogRead(pin);
    if (val < minV) minV = val;
    if (val > maxV) maxV = val;
    sum += val;
    delayMicroseconds(60);
  }
  // Elimina los picos extremos generados por vibraciones mecánicas de cables
  return (sum - minV - maxV) / 4;
}

void aplicarPWMCalentador(uint8_t duty) {
  // analogWrite() en ESP32 Core configura y conmuta automáticamente el canal LEDC
  analogWrite(PIN_MQ7_HEATER, duty);
}

// Controla el LED RGB de cátodo común evitando escrituras PWM redundantes
void establecerColorRGB(uint8_t r, uint8_t g, uint8_t b) {
  static int ultR = -1, ultG = -1, ultB = -1;
  if (r == ultR && g == ultG && b == ultB) return;
  ultR = r;
  ultG = g;
  ultB = b;
  analogWrite(PIN_RGB_ROJO, r);
  analogWrite(PIN_RGB_VERDE, g);
  analogWrite(PIN_RGB_AZUL, b);
}

// Diagnóstico eléctrico de líneas I2C y auto-detección de OLED (incluso con SDA/SCL invertidos)
bool intentarIniciarOLED(bool mostrarDiagnostico) {
  static bool wireIniciado = false;
  if (wireIniciado) {
    Wire.end();
    wireIniciado = false;
  }

  if (mostrarDiagnostico) {
    pinMode(OLED_SDA, INPUT_PULLDOWN);
    pinMode(OLED_SCL, INPUT_PULLDOWN);
    delay(5);
    int pull21 = digitalRead(OLED_SDA);
    int pull22 = digitalRead(OLED_SCL);
    Serial.print(F("[DIAG OLED] Contacto pull-up 3.3V -> G21(SDA)="));
    Serial.print(pull21 ? F("OK") : F("SIN_CONTACTO(0V)"));
    Serial.print(F(" | G22(SCL)="));
    Serial.println(pull22 ? F("OK") : F("SIN_CONTACTO(0V)"));
  }

  const uint8_t pares[2][2] = { {OLED_SDA, OLED_SCL}, {OLED_SCL, OLED_SDA} };
  for (int p = 0; p < 2; p++) {
    if (wireIniciado) {
      Wire.end();
    }
    uint8_t sda = pares[p][0];
    uint8_t scl = pares[p][1];
    pinMode(sda, INPUT_PULLUP);
    pinMode(scl, INPUT_PULLUP);
    Wire.begin(sda, scl, 100000UL);
    wireIniciado = true;

    for (uint8_t addr = 0x3C; addr <= 0x3D; addr++) {
      Wire.beginTransmission(addr);
      if (Wire.endTransmission() == 0) {
        oledAddr = addr;
        // periphBegin = false para conservar el par SDA/SCL detectado
        if (display.begin(SSD1306_SWITCHCAPVCC, oledAddr, true, false)) {
          oledConectada = true;
          Serial.print(F("[I2C OK] Pantalla OLED activa en 0x"));
          Serial.print(oledAddr, HEX);
          if (p == 1) {
            Serial.println(F(" (Auto-corregidos SDA=G22 y SCL=G21 invertidos!)"));
          } else {
            Serial.println(F(" (SDA=G21, SCL=G22)"));
          }
          return true;
        }
      }
    }
  }

  // Dejar configurado el par estándar (SDA=21, SCL=22)
  Wire.end();
  pinMode(OLED_SDA, INPUT_PULLUP);
  pinMode(OLED_SCL, INPUT_PULLUP);
  Wire.begin(OLED_SDA, OLED_SCL, 100000UL);
  wireIniciado = true;
  return false;
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

// Conversión analógica a PPM relativa de alta sensibilidad (capta trazas residuales en el aire)
float voltajeAPPM_CO(float v, float factorEnv) {
  float vNeto = v - vBaseMQ7;
  if (vNeto <= 0.015f) return 0.0f; // Aire limpio sin trazas
  // Exponente suave (1.25) sensible a gas flotando en el cuarto:
  // +0.05V -> ~4 PPM | +0.30V -> ~36 PPM (Precaución) | +0.68V -> ~100 PPM (Peligro)
  float ppm = pow((vNeto / 1.40f), 1.25f) * 260.0f;
  return ppm / factorEnv;
}

float voltajeAPPM_LPG(float v, float factorEnv) {
  float vNeto = v - vBaseMQ2;
  if (vNeto <= 0.010f) return 0.0f; // Aire limpio sin trazas
  // Curva de transducción para sensor MQ-2 (300 a 10,000 PPM):
  // +0.05V -> ~20 PPM | +0.35V -> ~250 PPM | +1.15V -> ~1000 PPM (NOM-010-STPS Precaución) | +3.00V -> ~3200 PPM (20% LEL Peligro)
  float ppm = pow((vNeto / 1.50f), 1.20f) * 1400.0f;
  return ppm / factorEnv;
}

// ==========================================
// SETUP: INICIALIZACIÓN OFFLINE ROBUSTA
// ==========================================
void setup() {
  // Asegurar silencio inmediato en GPIO 27 desde el milisegundo cero
  pinMode(PIN_BUZZER, OUTPUT);
  sonarBuzzer(false, 0);

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
  pinMode(PIN_RGB_ROJO, OUTPUT);
  pinMode(PIN_RGB_VERDE, OUTPUT);
  pinMode(PIN_RGB_AZUL, OUTPUT);

  // Inicialización de actuadores y prueba secuencial del LED RGB (350 ms c/u)
  Serial.println(F("[TEST RGB] Probando canales: ROJO(G23) -> VERDE(G19) -> AZUL(G18) -> AMARILLO(R+G)"));
  establecerColorRGB(255, 0, 0);   // Rojo
  delay(350);
  establecerColorRGB(0, 255, 0);   // Verde
  delay(350);
  establecerColorRGB(0, 0, 255);   // Azul
  delay(350);
  establecerColorRGB(255, 130, 0); // Amarillo / Ámbar (mezcla R + G)
  delay(350);

  // Prueba acústica única de 150 ms y corte estricto a 0V en GPIO 27
  Serial.println(F("[TEST BUZZER] Pulso unico 150ms en GPIO 27 -> luego queda en SILENCIO (0.0V)"));
  sonarBuzzer(true, 2200);
  delay(150);
  sonarBuzzer(false, 0);

  // Diagnóstico del botón de mudez / vista (GPIO 13)
  delay(20);
  int estadoBtnBoot = digitalRead(PIN_BOTON);
  Serial.print(F("[DIAG BOTON] GPIO 13 estado inicial: "));
  if (estadoBtnBoot == LOW) {
    Serial.println(F("PEGADO A TIERRA (LOW) -> ¡Corto o patas puenteadas! Conecta en diagonal opuesta o gira el boton 90 deg"));
  } else {
    Serial.println(F("REPOSO 3.3V (PULLUP OK) -> Listo para pulsar"));
  }

  // Inicializar bus I2C, diagnosticar cables y buscar pantalla OLED
  if (intentarIniciarOLED(true)) {
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
    delay(800);
  } else {
    Serial.println(F("[I2C ERROR] OLED no responde. Revisa la linea [DIAG OLED] de arriba y GND de la OLED."));
  }

  // Inicializar DHT11
  dht.begin();

  // Iniciar ciclo térmico del MQ-7 en fase de PURGA inicial (5.0V)
  estadoMQ7 = FASE_PURGA;
  tiempoInicioFaseMQ7 = millis();
  aplicarPWMCalentador(DUTY_PURGA_5V);

  // Iniciar temporizador de Precalentamiento de 45 segundos
  tInicioPrecalentamiento = millis();
  modoOperativo = PRECALENTANDO;

  // Inicializar buffer de promedio móvil con muestreo robusto
  long sumaCalibMQ2 = 0, sumaCalibMQ7 = 0;
  for (int i = 0; i < NUM_MUESTRAS; i++) {
    muestrasMQ2[i] = leerADCRobusto(PIN_MQ2_ADC);
    muestrasMQ7[i] = leerADCRobusto(PIN_MQ7_ADC);
    sumaCalibMQ2 += muestrasMQ2[i];
    sumaCalibMQ7 += muestrasMQ7[i];
    delay(20);
  }
  voltajeMQ2 = adcAVoltaje(sumaCalibMQ2 / NUM_MUESTRAS);
  voltajeMQ7 = adcAVoltaje(sumaCalibMQ7 / NUM_MUESTRAS);

  Serial.println(F("\n[SISTEMA] ==============================================="));
  Serial.println(F("[SISTEMA] INICIANDO RUTINA DE PRECALENTAMIENTO (45s)..."));
  Serial.println(F("[SISTEMA] Los sensores estan estabilizando filamentos termicos."));
  Serial.println(F("[SISTEMA] Buzzer silenciado. Al finalizar se fijara la linea base real."));
  Serial.println(F("[SISTEMA] ===============================================\n"));
}

// ==========================================
// LOOP PRINCIPAL: MULTITAREA NO BLOQUEANTE
// ==========================================
void loop() {
  unsigned long ahora = millis();

  // 0. GESTIÓN DE LA RUTINA DE PRECALENTAMIENTO (45 SEGUNDOS AL ARRANCAR)
  if (modoOperativo == PRECALENTANDO) {
    if (ahora - tInicioPrecalentamiento >= DURACION_PRECALENTAMIENTO_MS) {
      // PRECALENTAMIENTO COMPLETADO: Fijar la línea base térmica real con sensores calientes
      vBaseMQ2 = max(0.08f, voltajeMQ2);
      vBaseMQ7 = max(0.20f, voltajeMQ7);
      modoOperativo = VIGILANCIA;
      tiempoInicioFaseMQ7 = ahora;
      estadoMQ7 = FASE_MEDICION;
      aplicarPWMCalentador(DUTY_MEDICION_1V);

      Serial.println(F("\n=================================================="));
      Serial.println(F(" [CALIB INICIAL] Precalentamiento de 45s finalizado."));
      Serial.print(F(" [CALIB INICIAL] Base MQ-2 fijada: "));
      Serial.print(vBaseMQ2, 2);
      Serial.print(F("V | Base MQ-7 fijada: "));
      Serial.print(vBaseMQ7, 2);
      Serial.println(F("V"));
      Serial.println(F(" [SISTEMA] ¡Sensores estabilizados! Canario en guardia."));
      Serial.println(F("==================================================\n"));

      // Doble bip amigable de confirmación de fin de calentamiento
      sonarBuzzer(true, 2400);
      delay(60);
      sonarBuzzer(false, 0);
      delay(60);
      sonarBuzzer(true, 2400);
      delay(60);
      sonarBuzzer(false, 0);
    }
  }

  // 1. GESTIÓN DE LA FSM DEL MQ-7 (CICLO TÉRMICO ESTRICTO)
  gestionarCicloTermicoMQ7(ahora);

  // 2. LECTURA AMBIENTAL PERIÓDICA (DHT11 cada 2000 ms)
  if (ahora - tUltimoDHT >= 2000) {
    tUltimoDHT = ahora;
    float t = dht.readTemperature();
    float h = dht.readHumidity();
    if (isnan(t) || isnan(h)) {
      Serial.println(F("[DHT WARN] Sin lectura del DHT11 en GPIO 4 (revisa VCC, GND y pin DATA)."));
    } else {
      temperatura = t;
      humedad = h;
    }
  }

  // 3. ADQUISICIÓN Y FILTRADO CONTINUO DE GASES (cada 100 ms)
  static unsigned long tUltimoMuestreo = 0;
  if (ahora - tUltimoMuestreo >= 100) {
    tUltimoMuestreo = ahora;
    muestrasMQ2[indiceMuestra] = leerADCRobusto(PIN_MQ2_ADC);
    muestrasMQ7[indiceMuestra] = leerADCRobusto(PIN_MQ7_ADC);
    indiceMuestra = (indiceMuestra + 1) % NUM_MUESTRAS;

    // Calcular promedio móvil de ventana
    long sumaMQ2 = 0, sumaMQ7 = 0;
    for (int i = 0; i < NUM_MUESTRAS; i++) {
      sumaMQ2 += muestrasMQ2[i];
      sumaMQ7 += muestrasMQ7[i];
    }
    int promMQ2 = sumaMQ2 / NUM_MUESTRAS;
    int promMQ7 = sumaMQ7 / NUM_MUESTRAS;

    float vInstMQ2 = adcAVoltaje(promMQ2);
    float vInstMQ7 = adcAVoltaje(promMQ7);

    // Filtro pasa-bajas exponencial (EMA): amortigua vibraciones mecánicas y ruido en rieles
    static bool filtroIniciado = false;
    if (!filtroIniciado) {
      voltajeMQ2 = vInstMQ2;
      voltajeMQ7 = vInstMQ7;
      filtroIniciado = true;
    } else {
      voltajeMQ2 = (voltajeMQ2 * 0.70f) + (vInstMQ2 * 0.30f);
      voltajeMQ7 = (voltajeMQ7 * 0.70f) + (vInstMQ7 * 0.30f);
    }

    // Seguimiento de piso limpio SOLO hacia abajo durante vigilancia activa
    if (modoOperativo == VIGILANCIA) {
      static uint8_t contPisoMQ2 = 0, contPisoMQ7 = 0;
      if (voltajeMQ2 >= 0.04f && voltajeMQ2 < vBaseMQ2) {
        if (++contPisoMQ2 >= 30) {
          vBaseMQ2 = voltajeMQ2;
          contPisoMQ2 = 0;
        }
      } else {
        contPisoMQ2 = 0;
      }

      if (voltajeMQ7 >= 0.15f && voltajeMQ7 < vBaseMQ7) {
        if (++contPisoMQ7 >= 30) {
          vBaseMQ7 = voltajeMQ7;
          contPisoMQ7 = 0;
        }
      } else {
        contPisoMQ7 = 0;
      }

      float fEnv = factorCompensacionAmbiental(temperatura, humedad);
      ppmLPG = voltajeAPPM_LPG(voltajeMQ2, fEnv);
      ppmCO  = voltajeAPPM_CO(voltajeMQ7, fEnv);
    } else {
      // En precalentamiento las PPM se mantienen en cero
      ppmLPG = 0.0f;
      ppmCO  = 0.0f;
    }
  }

  // 4. EVALUACIÓN DE NIVELES DE RIESGO Y ALERTA (LÓGICA DEL CANARIO)
  evaluarNivelRiesgo();

  // 5. GESTIÓN DEL PULSADOR (MUTE / VISTA)
  gestionarPulsador(ahora);

  // 6. GESTIÓN DE ACTUADORES LOCALES (EL CHILLIDO Y EL SEMÁFORO)
  gestionarActuadoresLocales(ahora);

  // 7. ACTUALIZACIÓN VISUAL DE LA PANTALLA OLED (cada 250 ms si está conectada)
  if (oledConectada) {
    if (ahora - tUltimoOLED >= 250) {
      tUltimoOLED = ahora;
      actualizarOLED();
    }
  } else if (ahora - tReintentoI2C >= 2000) {
    tReintentoI2C = ahora;
    intentarIniciarOLED(false);
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
    tiempoSilenciado = millis();
    sonarBuzzer(false, 0);
    Serial.println(buzzerSilenciado
      ? F("[REMOTE] Buzzer silenciado via comando (MUTE 60s).")
      : F("[REMOTE] Silencio desactivado via comando."));
  } else if (cmd == "VISTA") {
    vistaActual = (vistaActual + 1) % 2;
    Serial.print(F("[REMOTE] Pantalla OLED cambiada via comando a vista: "));
    Serial.println(vistaActual);
  } else if (cmd == "PING" || cmd == "TEST") {
    emitirTelemetriaJSON();
  } else if (cmd == "VOLTS" || cmd == "DIAG") {
    Serial.print(F("[DIAG VOLTS] MQ-2(G34)="));
    Serial.print(voltajeMQ2, 2);
    Serial.print(F("V (Base="));
    Serial.print(vBaseMQ2, 2);
    Serial.print(F("V) | MQ-7(G35)="));
    Serial.print(voltajeMQ7, 2);
    Serial.print(F("V (Base="));
    Serial.print(vBaseMQ7, 2);
    Serial.println(F("V)"));
  } else if (cmd == "CALIB" || cmd == "TARE") {
    vBaseMQ2 = max(0.03f, voltajeMQ2 - 0.01f);
    vBaseMQ7 = max(0.10f, voltajeMQ7 - 0.01f);
    Serial.print(F("[CALIB] Nueva base de aire limpio fijada -> MQ-2: "));
    Serial.print(vBaseMQ2, 2);
    Serial.print(F("V | MQ-7: "));
    Serial.print(vBaseMQ7, 2);
    Serial.println(F("V"));
    emitirTelemetriaJSON();
  } else if (cmd == "INVERTIR_BUZZER") {
    buzzerLogicaInvertida = !buzzerLogicaInvertida;
    sonarBuzzer(false, 0);
    Serial.print(F("[DIAG BUZZER] Polaridad GPIO 27 cambiada a: "));
    Serial.println(buzzerLogicaInvertida ? F("ACTIVO-BAJO (reposo 3.3V)") : F("ACTIVO-ALTO normal (reposo 0V)"));
    Serial.println(F(" -> Si se callo al activar ACTIVO-BAJO, la pata '-' del buzzer esta en 3.3V en vez de GND."));
    Serial.println(F(" -> Si NO cambio nada y sigue sonando fijo, tu cable NO esta en G27 (revisa G25 calefactor o VCC)!"));
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
  // Durante la rutina de precalentamiento inicial (45s), mantener a 5.0V continuo para desorción
  if (modoOperativo == PRECALENTANDO) {
    aplicarPWMCalentador(DUTY_PURGA_5V);
    return;
  }

  unsigned long transcurrido = ahora - tiempoInicioFaseMQ7;

  switch (estadoMQ7) {
    case FASE_PURGA:
      if (transcurrido >= DURACION_PURGA_MS) {
        estadoMQ7 = FASE_MEDICION;
        tiempoInicioFaseMQ7 = ahora;
        aplicarPWMCalentador(DUTY_MEDICION_1V);
      }
      break;

    case FASE_MEDICION:
      if (transcurrido >= DURACION_MEDICION_MS) {
        estadoMQ7 = FASE_PURGA;
        tiempoInicioFaseMQ7 = ahora;
        aplicarPWMCalentador(DUTY_PURGA_5V);
      }
      break;
  }
}

// ==========================================
// EVALUACIÓN DE UMBRALES DE SEGURIDAD (CON HISTÉRESIS ANTI-RUIDO)
// ==========================================
void evaluarNivelRiesgo() {
  if (modoOperativo == PRECALENTANDO) {
    nivelActual = NORMAL;
    return;
  }

  bool condPeligro = (ppmCO >= CO_UMBRAL_PELIGRO || ppmLPG >= LPG_UMBRAL_PELIGRO);
  bool condPrecaucion = (ppmCO >= CO_UMBRAL_PRECAUCION || ppmLPG >= LPG_UMBRAL_PRECAUCION);

  // Histéresis de seguridad para evitar saltos caóticos de alarma por vibraciones en cables dupont
  if (nivelActual == PELIGRO) {
    // Para salir de PELIGRO, el gas debe descender holgadamente (-15%) por debajo del umbral
    if (ppmCO < (CO_UMBRAL_PELIGRO * 0.85f) && ppmLPG < (LPG_UMBRAL_PELIGRO * 0.85f)) {
      nivelActual = condPrecaucion ? PRECAUCION : NORMAL;
    }
  } else if (nivelActual == PRECAUCION) {
    if (condPeligro) {
      nivelActual = PELIGRO;
    } else if (ppmCO < (CO_UMBRAL_PRECAUCION * 0.80f) && ppmLPG < (LPG_UMBRAL_PRECAUCION * 0.80f)) {
      // Para salir de PRECAUCIÓN a NORMAL, debe descender (-20%) por debajo
      nivelActual = NORMAL;
    }
  } else { // NORMAL
    if (condPeligro) {
      nivelActual = PELIGRO;
    } else if (condPrecaucion) {
      nivelActual = PRECAUCION;
    }
  }
}

// ==========================================
// CONTROL DEL PULSADOR (GPIO 13): TOQUE CORTO = MUTE / SOSTENIDO = VISTA
// ==========================================
void gestionarPulsador(unsigned long ahora) {
  static int ultimoEstadoFiltro = -1;
  static int lecturaEstable = -1;
  static unsigned long tUltimoCambio = 0;
  static unsigned long tPresionado = 0;
  static bool holdEjecutado = false;
  static unsigned long tAlertaPegado = 0;

  int lecturaCruda = digitalRead(PIN_BOTON);

  // Inicialización segura en el primer ciclo
  if (ultimoEstadoFiltro == -1) {
    ultimoEstadoFiltro = lecturaCruda;
    lecturaEstable = lecturaCruda;
    tUltimoCambio = ahora;
    if (lecturaEstable == LOW) {
      holdEjecutado = true; // Inhibe disparo espurio si el ESP32 arranca con pin en GND
      tAlertaPegado = ahora;
    }
    return;
  }

  // 1. FILTRO ANTI-REBOTE (Debounce temporal de 40 ms)
  if (lecturaCruda != ultimoEstadoFiltro) {
    ultimoEstadoFiltro = lecturaCruda;
    tUltimoCambio = ahora;
  }

  // Si la lectura se mantiene estable más de 40 ms, procesamos los flancos
  if ((ahora - tUltimoCambio) >= 40) {
    if (ultimoEstadoFiltro != lecturaEstable) {
      int estadoAnterior = lecturaEstable;
      lecturaEstable = ultimoEstadoFiltro;

      // Flanco de bajada (HIGH -> LOW): Botón recién presionado
      if (estadoAnterior == HIGH && lecturaEstable == LOW) {
        tPresionado = ahora;
        holdEjecutado = false;
        tAlertaPegado = ahora;
      }
      // Flanco de subida (LOW -> HIGH): Botón liberado
      else if (estadoAnterior == LOW && lecturaEstable == HIGH) {
        unsigned long duracion = ahora - tPresionado;
        // Solo si fue toque corto (< 1000 ms) y NO se consumió en hold sostenido
        if (duracion < 1000 && !holdEjecutado) {
          buzzerSilenciado = !buzzerSilenciado;
          tiempoSilenciado = ahora;
          sonarBuzzer(false, 0);
          Serial.print(F("[HMI] Pulsador (toque corto) -> MUTE "));
          Serial.println(buzzerSilenciado ? F("ACTIVADO (Silencio 60s)") : F("DESACTIVADO (Audio activo)"));
        }
        holdEjecutado = false;
      }
    }
  }

  // 2. DETECCIÓN DE TOQUE SOSTENIDO (> 1000 ms) MIENTRAS PERMANECE PRESIONADO
  if (lecturaEstable == LOW) {
    if (!holdEjecutado && (ahora - tPresionado >= 1000)) {
      holdEjecutado = true; // Consume el evento para que no active MUTE al soltar
      vistaActual = (vistaActual + 1) % 2;
      Serial.print(F("[HMI] Pulsador (1s sostenido) -> Vista OLED cambiada a: "));
      Serial.println(vistaActual);
    }
    // Advertencia de diagnóstico si queda en GND de forma continua
    if (ahora - tAlertaPegado >= 5000) {
      tAlertaPegado = ahora;
      Serial.println(F("[ALERTA BOTON] GPIO 13 sigue pegado a GND (>5s). Revisa patas del pulsador o cables."));
    }
  } else {
    tAlertaPegado = ahora;
  }

  // 3. AUTO-EXPIRACIÓN DEL MUTE (60 segundos)
  if (buzzerSilenciado && (ahora - tiempoSilenciado >= DURACION_MUTE_MS)) {
    buzzerSilenciado = false;
    Serial.println(F("[HMI] Mute temporal expirado (reactivando audio)."));
  }
}

// ==========================================
// ACTUADORES LOCALES: SEMÁFORO Y EL CHILLIDO
// ==========================================
void gestionarActuadoresLocales(unsigned long ahora) {
  // Durante el precalentamiento (45s), silencio total del buzzer y pulso ámbar suave
  if (modoOperativo == PRECALENTANDO) {
    bool pulsoAmbar = (ahora % 1000 < 500);
    if (pulsoAmbar) {
      establecerColorRGB(220, 100, 0); // Ámbar cálido
    } else {
      establecerColorRGB(50, 20, 0);   // Ámbar tenue
    }
    sonarBuzzer(false, 0); // Silencio absoluto de buzzer durante precalentamiento
    return;
  }

  switch (nivelActual) {
    case NORMAL:
      // Semáforo RGB: Verde puro
      establecerColorRGB(0, 255, 0);
      sonarBuzzer(false, 0);
      break;

    case PRECAUCION:
      // Semáforo RGB: Amarillo / Ámbar (mezcla calibrada Rojo + Verde)
      establecerColorRGB(255, 130, 0);

      // Pitidos pausados preventivos: 80 ms ON cada 3500 ms (silencio largo entre bips)
      if (!buzzerSilenciado) {
        unsigned long cicloPrecaucion = ahora % 3500;
        sonarBuzzer(cicloPrecaucion < 80, 1400);
      } else {
        sonarBuzzer(false, 0);
      }
      break;

    case PELIGRO:
      // ¡EL CANARIO CHILLA! 
      // Parpadeo agresivo en Rojo puro (5 Hz)
      if (ahora % 200 < 100) {
        establecerColorRGB(255, 0, 0);
      } else {
        establecerColorRGB(0, 0, 0);
      }

      // Chillido continuo modulado si no está en mute
      if (!buzzerSilenciado) {
        bool oscilador = (ahora % 260 < 130);
        sonarBuzzer(oscilador, 2200);
      } else {
        sonarBuzzer(false, 0);
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

  // Pantalla especial durante el precalentamiento inicial (45s)
  if (modoOperativo == PRECALENTANDO) {
    unsigned long segCal = (millis() - tInicioPrecalentamiento) / 1000;
    if (segCal > 45) segCal = 45;

    display.setTextSize(1);
    display.setCursor(0, 0);
    display.print(F("PRECALENTANDO MQ"));
    display.drawLine(0, 9, 127, 9, SSD1306_WHITE);

    display.setCursor(0, 14);
    display.print(F("Estabilizando sensor"));
    display.setCursor(0, 25);
    display.print(F("Progreso: "));
    display.print(segCal);
    display.print(F("/45 s"));

    // Barra de progreso de 0 a 45s (ancho 120 px)
    int barra = map((int)segCal, 0, 45, 0, 120);
    if (barra > 120) barra = 120;
    if (barra < 0) barra = 0;
    display.drawRect(0, 36, 122, 6, SSD1306_WHITE);
    display.fillRect(1, 37, barra, 4, SSD1306_WHITE);

    display.setCursor(0, 46);
    display.print(F("MQ2:"));
    display.print(voltajeMQ2, 2);
    display.print(F("V MQ7:"));
    display.print(voltajeMQ7, 2);
    display.print(F("V"));

    display.setCursor(0, 56);
    display.print(F("Mantener en aire limp"));

    display.display();
    return;
  }

  if (vistaActual == 0) {
    // --- VISTA 0: DASHBOARD DE SEGURIDAD (HMI PRINCIPAL) ---
    // Encabezado compacto si MUTE está activo para evitar superposición en los 128 px
    display.setTextSize(1);
    display.setCursor(0, 0);
    if (buzzerSilenciado) {
      if (nivelActual == NORMAL) {
        display.print(estadoMQ7 == FASE_PURGA ? F("PURGA MQ7 OK") : F("AIRE: SEGURO"));
      } else if (nivelActual == PRECAUCION) {
        display.print(F("!PRECAUCION!"));
      } else {
        display.print(F("!PELIGRO GAS!"));
      }
      // Etiqueta invertida de alto contraste a la derecha (x = 92..127)
      display.fillRect(92, 0, 36, 8, SSD1306_WHITE);
      display.setTextColor(SSD1306_BLACK);
      display.setCursor(98, 0);
      display.print(F("MUTE"));
      display.setTextColor(SSD1306_WHITE);
    } else {
      if (nivelActual == NORMAL) {
        display.print(estadoMQ7 == FASE_PURGA ? F("CANARIO: [PURGA OK]") : F("CANARIO: [SEGURO]"));
      } else if (nivelActual == PRECAUCION) {
        display.print(F("CANARIO: [PRECAUCION]"));
      } else {
        display.print(F("!PELIGRO: EVACUAR!"));
      }
    }

    display.drawLine(0, 9, 127, 9, SSD1306_WHITE);

    // Lectura de Monóxido de Carbono (CO)
    display.setCursor(0, 14);
    display.print(F("CO:  "));
    display.setTextSize(2);
    display.print(ppmCO, (ppmCO < 100.0f) ? 1 : 0);
    display.setTextSize(1);
    display.print(F(" ppm"));

    // Barra de nivel visual para CO (0 a 150 ppm)
    int barraCO = map((int)ppmCO, 0, 150, 0, 120);
    if (barraCO > 120) barraCO = 120;
    if (barraCO < 0) barraCO = 0;
    display.drawRect(0, 31, 122, 5, SSD1306_WHITE);
    display.fillRect(1, 32, barraCO, 3, SSD1306_WHITE);

    // Lectura de Gas Combustible / Humo (GLP) - muestra 1 decimal en trazas bajas (<100 ppm)
    display.setTextSize(1);
    display.setCursor(0, 39);
    display.print(F("GLP: "));
    display.setTextSize(2);
    display.print(ppmLPG, (ppmLPG < 100.0f) ? 1 : 0);
    display.setTextSize(1);
    display.print(F(" ppm"));

    // Barra de nivel visual para GLP (0 a 3500 ppm según 20% LEL NOM-002-STPS)
    int barraLPG = map((int)ppmLPG, 0, 3500, 0, 120);
    if (barraLPG > 120) barraLPG = 120;
    if (barraLPG < 0) barraLPG = 0;
    display.drawRect(0, 56, 122, 5, SSD1306_WHITE);
    display.fillRect(1, 57, barraLPG, 3, SSD1306_WHITE);

  } else {
    // --- VISTA 1: DIAGNÓSTICO TÉCNICO Y AMBIENTAL ---
    display.setTextSize(1);
    display.setCursor(0, 0);
    if (buzzerSilenciado) {
      display.print(F("DIAGNOSTICO"));
      display.fillRect(92, 0, 36, 8, SSD1306_WHITE);
      display.setTextColor(SSD1306_BLACK);
      display.setCursor(98, 0);
      display.print(F("MUTE"));
      display.setTextColor(SSD1306_WHITE);
    } else {
      display.print(F("DIAGNOSTICO TECNICO"));
    }
    display.drawLine(0, 9, 127, 9, SSD1306_WHITE);

    // Temperatura y Humedad del DHT11
    display.setCursor(0, 13);
    display.print(F("Temp: "));
    display.print(temperatura, 1);
    display.print(F(" C  H: "));
    display.print(humedad, 0);
    display.print(F("%"));

    // Voltajes en vivo + línea base limpia activa
    display.setCursor(0, 25);
    display.print(F("MQ7:"));
    display.print(voltajeMQ7, 2);
    display.print(F("V (B:"));
    display.print(vBaseMQ7, 2);
    display.print(F("V)"));

    display.setCursor(0, 37);
    display.print(F("MQ2:"));
    display.print(voltajeMQ2, 2);
    display.print(F("V (B:"));
    display.print(vBaseMQ2, 2);
    display.print(F("V)"));

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
// TELEMETRÍA JSON: BROADCAST SERIAL / BLUETOOTH CON BUFFER OFFLINE
// ==========================================
#ifdef ACTIVAR_BLUETOOTH_FISICO
// Buffer circular de respaldo en RAM cuando el teléfono está desconectado (hasta 60 s de resiliencia)
const int CAP_BUFFER_OFFLINE = 60;
String bufferOfflineESP[CAP_BUFFER_OFFLINE];
int idxBufferOffline = 0;
int cantBufferOffline = 0;
bool clienteBTPrevio = false;
#endif

void emitirTelemetriaJSON() {
  unsigned long segFase = (modoOperativo == PRECALENTANDO)
      ? ((millis() - tInicioPrecalentamiento) / 1000)
      : ((millis() - tiempoInicioFaseMQ7) / 1000);
  if (modoOperativo == PRECALENTANDO && segFase > 45) segFase = 45;

  const char* estadoTexto = (modoOperativo == PRECALENTANDO)
      ? "PRECALENTANDO"
      : ((nivelActual == NORMAL) ? "NORMAL" : ((nivelActual == PRECAUCION) ? "PRECAUCION" : "PELIGRO"));

  const char* faseTexto = (modoOperativo == PRECALENTANDO)
      ? "PURGA"
      : ((estadoMQ7 == FASE_PURGA) ? "PURGA" : "MEDICION");

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
  bool clienteConectado = SerialBT.hasClient();

  // Si el teléfono se acaba de reconectar y hay datos acumulados en el buffer offline:
  if (clienteConectado && !clienteBTPrevio) {
    if (cantBufferOffline > 0) {
      Serial.print(F("[BT RESILIENCIA] Sincronizando "));
      Serial.print(cantBufferOffline);
      Serial.println(F(" muestras guardadas offline hacia la App..."));
      for (int i = 0; i < cantBufferOffline; i++) {
        int pos = (idxBufferOffline - cantBufferOffline + i + CAP_BUFFER_OFFLINE) % CAP_BUFFER_OFFLINE;
        SerialBT.println(bufferOfflineESP[pos]);
        delay(12); // Pacing para evitar saturación de buffer RFCOMM
      }
      cantBufferOffline = 0;
    }
  }

  if (clienteConectado) {
    // Transmisión en tiempo real
    SerialBT.println(json);
  } else {
    // Si la app está cerrada o fuera de rango, acumular en buffer circular
    bufferOfflineESP[idxBufferOffline] = json;
    idxBufferOffline = (idxBufferOffline + 1) % CAP_BUFFER_OFFLINE;
    if (cantBufferOffline < CAP_BUFFER_OFFLINE) {
      cantBufferOffline++;
    }
  }
  clienteBTPrevio = clienteConectado;
#endif
}
