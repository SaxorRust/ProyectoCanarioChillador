package com.canario.iot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Base de datos local SQLite para almacenamiento estructurado,
 * análisis de series de tiempo y auditoría del nodo sensor ESP32.
 */
public class TelemetryDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "canario_telemetry.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_READINGS = "readings";
    public static final String COL_ID = "id";
    public static final String COL_TIMESTAMP = "timestamp";
    public static final String COL_CO = "co";
    public static final String COL_LPG = "lpg";
    public static final String COL_TEMP = "temp";
    public static final String COL_HUM = "hum";
    public static final String COL_FASE = "fase";
    public static final String COL_SEG = "seg";
    public static final String COL_ALERTA = "alerta";
    public static final String COL_MUTE = "mute";

    private static TelemetryDbHelper instance;

    public static synchronized TelemetryDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new TelemetryDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private TelemetryDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_READINGS + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                COL_CO + " REAL NOT NULL, " +
                COL_LPG + " REAL NOT NULL, " +
                COL_TEMP + " REAL NOT NULL, " +
                COL_HUM + " REAL NOT NULL, " +
                COL_FASE + " TEXT NOT NULL, " +
                COL_SEG + " INTEGER NOT NULL, " +
                COL_ALERTA + " TEXT NOT NULL, " +
                COL_MUTE + " INTEGER NOT NULL" +
                ");";
        db.execSQL(createTable);

        // Índice para acelerar consultas analíticas sobre la estampa temporal
        db.execSQL("CREATE INDEX idx_readings_timestamp ON " + TABLE_READINGS + " (" + COL_TIMESTAMP + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_READINGS);
        onCreate(db);
    }

    /**
     * Inserta una lectura atómica en la base de datos.
     */
    public synchronized long insertReading(TelemetryData data) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_TIMESTAMP, data.getTimestamp());
        cv.put(COL_CO, data.getCo());
        cv.put(COL_LPG, data.getLpg());
        cv.put(COL_TEMP, data.getTemperatura());
        cv.put(COL_HUM, data.getHumedad());
        cv.put(COL_FASE, data.getFaseMQ7());
        cv.put(COL_SEG, data.getSegundosFase());
        cv.put(COL_ALERTA, data.getAlerta());
        cv.put(COL_MUTE, data.isMute() ? 1 : 0);

        return db.insert(TABLE_READINGS, null, cv);
    }

    /**
     * Obtiene lecturas en una ventana temporal desde un instante dado (en milisegundos).
     */
    public synchronized List<TelemetryData> getReadingsSince(long sinceEpochMillis) {
        List<TelemetryData> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        String selection = COL_TIMESTAMP + " >= ?";
        String[] args = { String.valueOf(sinceEpochMillis) };

        Cursor cursor = db.query(TABLE_READINGS, null, selection, args, null, null, COL_TIMESTAMP + " ASC");
        if (cursor != null) {
            try {
                int idxTime = cursor.getColumnIndexOrThrow(COL_TIMESTAMP);
                int idxCo = cursor.getColumnIndexOrThrow(COL_CO);
                int idxLpg = cursor.getColumnIndexOrThrow(COL_LPG);
                int idxTemp = cursor.getColumnIndexOrThrow(COL_TEMP);
                int idxHum = cursor.getColumnIndexOrThrow(COL_HUM);
                int idxFase = cursor.getColumnIndexOrThrow(COL_FASE);
                int idxSeg = cursor.getColumnIndexOrThrow(COL_SEG);
                int idxAlerta = cursor.getColumnIndexOrThrow(COL_ALERTA);
                int idxMute = cursor.getColumnIndexOrThrow(COL_MUTE);

                while (cursor.moveToNext()) {
                    long timestamp = cursor.getLong(idxTime);
                    float co = cursor.getFloat(idxCo);
                    float lpg = cursor.getFloat(idxLpg);
                    float temp = cursor.getFloat(idxTemp);
                    float hum = cursor.getFloat(idxHum);
                    String fase = cursor.getString(idxFase);
                    int seg = cursor.getInt(idxSeg);
                    String alerta = cursor.getString(idxAlerta);
                    boolean mute = cursor.getInt(idxMute) == 1;

                    TelemetryData td = new TelemetryData(co, lpg, temp, hum, fase, seg, alerta, mute, timestamp);
                    list.add(td);
                }
            } finally {
                cursor.close();
            }
        }
        return list;
    }

    /**
     * Cuenta el total de muestras almacenadas en la base de datos.
     */
    public synchronized int getTotalCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_READINGS, null);
        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
        }
        return count;
    }

    /**
     * Elimina todos los registros de la base de datos.
     */
    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_READINGS, null, null);
    }

    public static void copyFile(File src, File dst) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    /**
     * Guarda una copia directa del archivo en la carpeta pública Descargas (Downloads)
     * utilizando MediaStore en Android 10+ (API 29+) o FileOutputStream en Android 8/9.
     */
    public static boolean saveToPublicDownloads(Context context, String fileName, String mimeType, File sourceFile) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (java.io.InputStream in = new java.io.FileInputStream(sourceFile);
                         java.io.OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = in.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                            out.flush();
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Fallback para Android 8 o 9 (API < 29)
        try {
            File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir != null) {
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                File dest = new File(downloadsDir, fileName);
                copyFile(sourceFile, dest);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Exporta las lecturas históricas a un archivo CSV estructurado para auditoría.
     */
    public synchronized File exportToCsv(Context context) throws IOException {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        if (!dir.exists()) dir.mkdirs();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String fileName = "canario_audit_" + sdf.format(new Date()) + ".csv";
        File csvFile = new File(dir, fileName);

        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        List<TelemetryData> all = getReadingsSince(0);

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("ID,Timestamp_Epoch,Fecha_Hora,CO_PPM,LPG_PPM,Temperatura_C,Humedad_RH,MQ7_Fase,MQ7_Seg,Nivel_Alerta,Mute\n");
            int id = 1;
            for (TelemetryData d : all) {
                String dateStr = timeFormat.format(new Date(d.getTimestamp()));
                writer.write(String.format(Locale.US, "%d,%d,%s,%.2f,%.2f,%.2f,%.2f,%s,%d,%s,%s\n",
                        id++,
                        d.getTimestamp(),
                        dateStr,
                        d.getCo(),
                        d.getLpg(),
                        d.getTemperatura(),
                        d.getHumedad(),
                        d.getFaseMQ7(),
                        d.getSegundosFase(),
                        d.getAlerta(),
                        d.isMute() ? "TRUE" : "FALSE"
                ));
            }
        }
        return csvFile;
    }

    /**
     * Exporta las lecturas a un archivo JSON array formateado.
     */
    public synchronized File exportToJson(Context context) throws IOException {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        if (!dir.exists()) dir.mkdirs();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String fileName = "canario_audit_" + sdf.format(new Date()) + ".json";
        File jsonFile = new File(dir, fileName);

        List<TelemetryData> all = getReadingsSince(0);

        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write("[\n");
            for (int i = 0; i < all.size(); i++) {
                TelemetryData d = all.get(i);
                writer.write(String.format(Locale.US,
                        "  {\"timestamp\":%d,\"co\":%.2f,\"lpg\":%.2f,\"temp\":%.2f,\"hum\":%.2f,\"fase\":\"%s\",\"seg\":%d,\"alerta\":\"%s\",\"mute\":%s}%s\n",
                        d.getTimestamp(), d.getCo(), d.getLpg(), d.getTemperatura(), d.getHumedad(),
                        d.getFaseMQ7(), d.getSegundosFase(), d.getAlerta(), d.isMute() ? "true" : "false",
                        (i < all.size() - 1) ? "," : ""
                ));
            }
            writer.write("]\n");
        }
        return jsonFile;
    }
}
