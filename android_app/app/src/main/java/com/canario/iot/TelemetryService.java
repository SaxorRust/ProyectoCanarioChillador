package com.canario.iot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.Locale;

/**
 * Servicio en Primer Plano (Foreground Service) para el monitoreo ininterrumpido
 * de telemetría y persistencia en SQLite incluso cuando la app está minimizada,
 * en segundo plano o con la pantalla apagada.
 */
public class TelemetryService extends Service implements BluetoothManager.BluetoothListener {

    public static final String CHANNEL_ID = "canario_telemetry_channel";
    public static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_START = "com.canario.iot.action.START";
    public static final String ACTION_STOP  = "com.canario.iot.action.STOP";

    public interface ServiceCallback {
        void onBluetoothStateChanged(int state, String message);
        void onTelemetryReceived(TelemetryData data, String rawLine);
        void onBluetoothError(String error);
    }

    private static TelemetryService instance;
    private static ServiceCallback activeCallback;

    private BluetoothManager btManager;
    private TelemetryDbHelper dbHelper;
    private NotificationManager notificationManager;

    public static TelemetryService getInstance() {
        return instance;
    }

    public static void setCallback(ServiceCallback callback) {
        activeCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        dbHelper = TelemetryDbHelper.getInstance(this);
        btManager = new BluetoothManager(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando servicio de telemetría...", "Esperando enlace"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (btManager != null) {
                btManager.disconnect();
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Si ya hay un dispositivo por defecto y no estamos conectados, intentar conectar
        if (btManager != null && btManager.getState() == BluetoothManager.STATE_DISCONNECTED) {
            BluetoothDevice defaultDev = btManager.findDefaultCanarioDevice();
            if (defaultDev != null) {
                btManager.connect(defaultDev);
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public BluetoothManager getBluetoothManager() {
        return btManager;
    }

    public void connect(BluetoothDevice device) {
        if (btManager != null && device != null) {
            btManager.connect(device);
        }
    }

    public void disconnect() {
        if (btManager != null) {
            btManager.disconnect();
        }
    }

    public boolean sendCommand(String cmd) {
        return btManager != null && btManager.send(cmd);
    }

    // =========================================================================
    // CALLBACKS DE BLUETOOTH
    // =========================================================================
    @Override
    public void onStateChanged(int state, String message) {
        String title = "El Canario Chillador";
        String content = message;

        switch (state) {
            case BluetoothManager.STATE_CONNECTED:
                title = "Canario Conectado: Monitoreo 24/7";
                content = "Guardando telemetría continua en SQLite local.";
                break;
            case BluetoothManager.STATE_CONNECTING:
                title = "Enlazando con el Canario...";
                content = message;
                break;
            case BluetoothManager.STATE_DISCONNECTED:
                title = "Canario Desconectado";
                content = "En espera de reconexión.";
                break;
            case BluetoothManager.STATE_ERROR:
                title = "Error de Enlace Bluetooth";
                content = message;
                break;
        }

        updateNotification(title, content);

        if (activeCallback != null) {
            activeCallback.onBluetoothStateChanged(state, message);
        }
    }

    @Override
    public void onDataReceived(String rawLine) {
        try {
            TelemetryData data = TelemetryData.fromJson(rawLine);

            // 1. PERSISTENCIA ATÓMICA EN SQLITE (Incluso con la App cerrada)
            dbHelper.insertReading(data);

            // 2. ACTUALIZACIÓN VISUAL DE LA NOTIFICACIÓN DE ESTADO
            String alertTag = data.getAlerta();
            String title = String.format(Locale.US, "CO: %.1f ppm | GLP: %.0f ppm [%s]",
                    data.getCo(), data.getLpg(), alertTag);

            String content = String.format(Locale.US, "Temp: %.1f°C | Hum: %.0f%% | MQ7: %s (%ds)",
                    data.getTemperatura(), data.getHumedad(), data.getFaseMQ7(), data.getSegundosFase());

            if (data.isPeligro()) {
                title = "¡PELIGRO: EVACUAR! " + title;
            } else if (data.isPrecaucion()) {
                title = "PRECAUCIÓN: " + title;
            }

            updateNotification(title, content);

            // 3. DESPACHO A LA INTERFAZ DE USUARIO (Si está abierta)
            if (activeCallback != null) {
                activeCallback.onTelemetryReceived(data, rawLine);
            }

        } catch (Exception e) {
            // Ignora tramas incompletas
        }
    }

    @Override
    public void onError(String errorMessage) {
        if (activeCallback != null) {
            activeCallback.onBluetoothError(errorMessage);
        }
    }

    // =========================================================================
    // GESTIÓN DE NOTIFICACIONES EN PRIMER PLANO
    // =========================================================================
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Monitoreo Continuo - El Canario Chillador",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitoreo activo y persistencia de telemetría de gases en SQLite.");
            channel.enableVibration(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent stopIntent = new Intent(this, TelemetryService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            Notification.Action stopAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Detener", stopPendingIntent).build();
            builder.addAction(stopAction);
        }

        return builder.build();
    }

    private void updateNotification(String title, String content) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, content));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (btManager != null) {
            btManager.disconnect();
        }
        instance = null;
        activeCallback = null;
    }
}
