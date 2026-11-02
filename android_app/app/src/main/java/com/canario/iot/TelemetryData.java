package com.canario.iot;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Modelo de datos de telemetría emitido periódicamente (1 Hz) por el ESP32
 * del proyecto "El Canario Chillador".
 */
public class TelemetryData {
    private final float co;          // PPM de Monóxido de Carbono (MQ-7)
    private final float lpg;         // PPM de Gas Licuado de Petróleo / Humo (MQ-2)
    private final float temperatura; // Grados Celsius (DHT11/DHT22)
    private final float humedad;     // Porcentaje de Humedad Relativa (DHT11/DHT22)
    private final String faseMQ7;    // "PURGA" (5.0V) o "MEDICION" (1.4V)
    private final int segundosFase;  // Segundos transcurridos en la fase actual
    private final String alerta;     // "NORMAL", "PRECAUCION", "PELIGRO"
    private final boolean mute;      // Si el buzzer está silenciado temporalmente
    private final long timestamp;    // Marca de tiempo local en milisegundos

    public TelemetryData(float co, float lpg, float temperatura, float humedad,
                         String faseMQ7, int segundosFase, String alerta, boolean mute) {
        this(co, lpg, temperatura, humedad, faseMQ7, segundosFase, alerta, mute, System.currentTimeMillis());
    }

    public TelemetryData(float co, float lpg, float temperatura, float humedad,
                         String faseMQ7, int segundosFase, String alerta, boolean mute, long timestamp) {
        this.co = co;
        this.lpg = lpg;
        this.temperatura = temperatura;
        this.humedad = humedad;
        this.faseMQ7 = faseMQ7 != null ? faseMQ7 : "PURGA";
        this.segundosFase = segundosFase;
        this.alerta = alerta != null ? alerta : "NORMAL";
        this.mute = mute;
        this.timestamp = timestamp;
    }

    /**
     * Parsea de forma segura una cadena JSON emitida por el firmware del ESP32.
     */
    public static TelemetryData fromJson(String jsonStr) throws JSONException {
        if (jsonStr == null) {
            throw new JSONException("La trama JSON es nula");
        }
        // Limpieza de caracteres de escape o espacios
        String cleaned = jsonStr.trim();
        int startIdx = cleaned.indexOf('{');
        int endIdx = cleaned.lastIndexOf('}');
        if (startIdx >= 0 && endIdx > startIdx) {
            cleaned = cleaned.substring(startIdx, endIdx + 1);
        }

        JSONObject obj = new JSONObject(cleaned);
        float co = (float) obj.optDouble("co", 0.0);
        float lpg = (float) obj.optDouble("lpg", 0.0);
        float temp = (float) obj.optDouble("temp", 0.0);
        float hum = (float) obj.optDouble("hum", 0.0);
        String fase = obj.optString("fase", "PURGA");
        int seg = obj.optInt("seg", 0);
        String alerta = obj.optString("alerta", "NORMAL");
        boolean mute = obj.optBoolean("mute", false);

        return new TelemetryData(co, lpg, temp, hum, fase, seg, alerta, mute);
    }

    public float getCo() {
        return co;
    }

    public float getLpg() {
        return lpg;
    }

    public float getTemperatura() {
        return temperatura;
    }

    public float getHumedad() {
        return humedad;
    }

    public String getFaseMQ7() {
        return faseMQ7;
    }

    public int getSegundosFase() {
        return segundosFase;
    }

    public String getAlerta() {
        return alerta;
    }

    public boolean isMute() {
        return mute;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isPeligro() {
        return "PELIGRO".equalsIgnoreCase(alerta);
    }

    public boolean isPrecaucion() {
        return "PRECAUCION".equalsIgnoreCase(alerta);
    }

    public boolean isNormal() {
        return "NORMAL".equalsIgnoreCase(alerta);
    }
}
