package com.canario.iot;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Actividad Principal del Canario Chillador:
 * Interfaz de telemetría y supervisión de alto desempeño bajo la norma ANSI/ISA-101.01-2015.
 * Integra base de datos local SQLite, motor analítico estadístico (TWA, derivada dPPM/dt,
 * correlación cruzada de Pearson) y gráficas de series de tiempo interactivas en Canvas.
 */
public class MainActivity extends Activity implements TelemetryService.ServiceCallback {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    // Gestores del Sistema
    private BluetoothManager btManager;
    private BluetoothDevice targetDevice;
    private TelemetryDbHelper dbHelper;

    // Componentes de Conectividad
    private TextView tvBtBadge;
    private TextView tvBtStatus;
    private TextView tvBtDeviceInfo;
    private Button btnSelectDevice;
    private Button btnConnectToggle;

    // Pestañas ISA-101
    private Button btnTabL1, btnTabL2, btnTabL3, btnTabL4;
    private View layoutLevel1, layoutLevel2, layoutLevel3, layoutLevel4;

    // --- Componentes NIVEL 1: PANORAMA ---
    private View cardHeroAlert;
    private TextView tvAlertTitle;
    private TextView tvAlertDesc;
    private TextView tvMuteIndicator;
    private TextView tvL1Predictive;
    private TextView tvL1Mq7;
    private TextView tvL1CoVal;
    private TextView tvL1LpgVal;

    // --- Componentes NIVEL 2: SUPERVISIÓN ---
    private TextView tvCoValue, tvCoPeak;
    private ProgressBar progressCoBar;
    private TextView tvLpgValue, tvLpgPeak;
    private ProgressBar progressLpgBar;
    private TextView tvTempValue, tvHumValue;
    private TextView tvMq7Phase, tvMq7Seconds;
    private ProgressBar progressMq7Bar;
    private Button btnCmdMute, btnCmdVista, btnCmdPing;

    // --- Componentes NIVEL 3: TENDENCIAS ---
    private Button btnChartCo, btnChartLpg, btnChartEnv;
    private TextView tvChartTitle;
    private TimeSeriesChartView chartView;
    private Button btnWindow2m, btnWindow10m, btnWindowAll;
    private long currentWindowFilterMillis = 0; // 0 = Todo

    // --- Componentes NIVEL 4: ANALÍTICA ---
    private TextView tvAnalyticsTwa;
    private TextView tvAnalyticsNom010;
    private TextView tvAnalyticsOsha;
    private TextView tvAnalyticsDcoDt;
    private TextView tvAnalyticsDlpgDt;
    private TextView tvAnalyticsPearson;
    private TextView tvAnalyticsDeriva;
    private TextView tvAnalyticsConclusion;
    private TextView tvAnalyticsAction;
    private TextView tvAnalyticsCount;
    private Button btnExportCsv, btnExportJson, btnClearHistory;

    // Consola y Registro
    private TextView tvRawJson;

    // Cache local de datos
    private final List<TelemetryData> sessionReadings = new ArrayList<>();
    private float peakCO = 0.0f;
    private float peakLPG = 0.0f;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = TelemetryDbHelper.getInstance(this);

        initViews();
        setupNavigationTabs();
        setupListeners();

        // Iniciar Servicio en Primer Plano para persistencia ininterrumpida
        startTelemetryService();

        // Conectar callbacks de telemetría y Bluetooth
        TelemetryService.setCallback(this);
        if (TelemetryService.getInstance() != null) {
            btManager = TelemetryService.getInstance().getBluetoothManager();
        } else {
            btManager = new BluetoothManager(null);
        }

        // Cargar lecturas históricas previas de SQLite para las gráficas
        loadHistoryFromDatabase();

        // Verificar y solicitar permisos de Bluetooth y Notificaciones
        if (!hasRequiredPermissions()) {
            requestPermissions();
        } else {
            attemptAutoDetectDevice();
        }
    }

    private void startTelemetryService() {
        Intent serviceIntent = new Intent(this, TelemetryService.class);
        serviceIntent.setAction(TelemetryService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TelemetryService.setCallback(this);
        loadHistoryFromDatabase();

        if (TelemetryService.getInstance() != null) {
            btManager = TelemetryService.getInstance().getBluetoothManager();
            if (btManager != null) {
                targetDevice = btManager.getCurrentDevice();
                updateTargetDeviceInfo();
                onBluetoothStateChanged(btManager.getState(), "Enlace en segundo plano activo");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        TelemetryService.setCallback(null);
    }

    private void initViews() {
        tvBtBadge = findViewById(R.id.tv_bt_badge);
        tvBtStatus = findViewById(R.id.tv_bt_status);
        tvBtDeviceInfo = findViewById(R.id.tv_bt_device_info);
        btnSelectDevice = findViewById(R.id.btn_select_device);
        btnConnectToggle = findViewById(R.id.btn_connect_toggle);

        btnTabL1 = findViewById(R.id.btn_tab_l1);
        btnTabL2 = findViewById(R.id.btn_tab_l2);
        btnTabL3 = findViewById(R.id.btn_tab_l3);
        btnTabL4 = findViewById(R.id.btn_tab_l4);

        layoutLevel1 = findViewById(R.id.layout_level1);
        layoutLevel2 = findViewById(R.id.layout_level2);
        layoutLevel3 = findViewById(R.id.layout_level3);
        layoutLevel4 = findViewById(R.id.layout_level4);

        // Nivel 1
        cardHeroAlert = findViewById(R.id.card_hero_alert);
        tvAlertTitle = findViewById(R.id.tv_alert_title);
        tvAlertDesc = findViewById(R.id.tv_alert_desc);
        tvMuteIndicator = findViewById(R.id.tv_mute_indicator);
        tvL1Predictive = findViewById(R.id.tv_l1_predictive);
        tvL1Mq7 = findViewById(R.id.tv_l1_mq7);
        tvL1CoVal = findViewById(R.id.tv_l1_co_val);
        tvL1LpgVal = findViewById(R.id.tv_l1_lpg_val);

        // Nivel 2
        tvCoValue = findViewById(R.id.tv_co_value);
        tvCoPeak = findViewById(R.id.tv_co_peak);
        progressCoBar = findViewById(R.id.progress_co_bar);

        tvLpgValue = findViewById(R.id.tv_lpg_value);
        tvLpgPeak = findViewById(R.id.tv_lpg_peak);
        progressLpgBar = findViewById(R.id.progress_lpg_bar);

        tvTempValue = findViewById(R.id.tv_temp_value);
        tvHumValue = findViewById(R.id.tv_hum_value);
        tvMq7Phase = findViewById(R.id.tv_mq7_phase);
        tvMq7Seconds = findViewById(R.id.tv_mq7_seconds);
        progressMq7Bar = findViewById(R.id.progress_mq7_bar);

        btnCmdMute = findViewById(R.id.btn_cmd_mute);
        btnCmdVista = findViewById(R.id.btn_cmd_vista);
        btnCmdPing = findViewById(R.id.btn_cmd_ping);

        // Nivel 3
        btnChartCo = findViewById(R.id.btn_chart_co);
        btnChartLpg = findViewById(R.id.btn_chart_lpg);
        btnChartEnv = findViewById(R.id.btn_chart_env);
        tvChartTitle = findViewById(R.id.tv_chart_title);
        chartView = findViewById(R.id.chart_view);
        btnWindow2m = findViewById(R.id.btn_window_2m);
        btnWindow10m = findViewById(R.id.btn_window_10m);
        btnWindowAll = findViewById(R.id.btn_window_all);

        // Nivel 4
        tvAnalyticsTwa = findViewById(R.id.tv_analytics_twa);
        tvAnalyticsNom010 = findViewById(R.id.tv_analytics_nom010);
        tvAnalyticsOsha = findViewById(R.id.tv_analytics_osha);
        tvAnalyticsDcoDt = findViewById(R.id.tv_analytics_dcodt);
        tvAnalyticsDlpgDt = findViewById(R.id.tv_analytics_dlpgdt);
        tvAnalyticsPearson = findViewById(R.id.tv_analytics_pearson);
        tvAnalyticsDeriva = findViewById(R.id.tv_analytics_deriva);
        tvAnalyticsConclusion = findViewById(R.id.tv_analytics_conclusion);
        tvAnalyticsAction = findViewById(R.id.tv_analytics_action);
        tvAnalyticsCount = findViewById(R.id.tv_analytics_count);
        btnExportCsv = findViewById(R.id.btn_export_csv);
        btnExportJson = findViewById(R.id.btn_export_json);
        btnClearHistory = findViewById(R.id.btn_clear_history);

        tvRawJson = findViewById(R.id.tv_raw_json);
    }

    private void setupNavigationTabs() {
        btnTabL1.setOnClickListener(v -> switchLevel(1));
        btnTabL2.setOnClickListener(v -> switchLevel(2));
        btnTabL3.setOnClickListener(v -> switchLevel(3));
        btnTabL4.setOnClickListener(v -> switchLevel(4));
    }

    private void switchLevel(int level) {
        layoutLevel1.setVisibility(level == 1 ? View.VISIBLE : View.GONE);
        layoutLevel2.setVisibility(level == 2 ? View.VISIBLE : View.GONE);
        layoutLevel3.setVisibility(level == 3 ? View.VISIBLE : View.GONE);
        layoutLevel4.setVisibility(level == 4 ? View.VISIBLE : View.GONE);

        resetTabButton(btnTabL1, level == 1);
        resetTabButton(btnTabL2, level == 2);
        resetTabButton(btnTabL3, level == 3);
        resetTabButton(btnTabL4, level == 4);

        if (level == 3) {
            refreshChart();
        } else if (level == 4) {
            refreshAnalytics(sessionReadings.isEmpty() ? null : sessionReadings.get(sessionReadings.size() - 1));
        }
    }

    private void resetTabButton(Button btn, boolean selected) {
        if (selected) {
            btn.setBackgroundResource(R.drawable.bg_tab_selected);
            btn.setTextColor(0xFF000000);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            btn.setBackgroundResource(R.drawable.bg_tab_unselected);
            btn.setTextColor(getColor(R.color.text_primary));
            btn.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private void setupListeners() {
        // Selector de dispositivo
        btnSelectDevice.setOnClickListener(v -> {
            if (!hasRequiredPermissions()) {
                requestPermissions();
                return;
            }
            DeviceDialog.show(this, btManager, device -> {
                targetDevice = device;
                updateTargetDeviceInfo();
                if (TelemetryService.getInstance() != null) {
                    TelemetryService.getInstance().connect(targetDevice);
                } else if (btManager != null) {
                    btManager.connect(targetDevice);
                }
            });
        });

        // Alternar Conexión
        btnConnectToggle.setOnClickListener(v -> {
            if (!hasRequiredPermissions()) {
                requestPermissions();
                return;
            }
            int state = (btManager != null) ? btManager.getState() : BluetoothManager.STATE_DISCONNECTED;
            if (state == BluetoothManager.STATE_CONNECTED || state == BluetoothManager.STATE_CONNECTING) {
                if (TelemetryService.getInstance() != null) {
                    TelemetryService.getInstance().disconnect();
                } else if (btManager != null) {
                    btManager.disconnect();
                }
            } else {
                if (targetDevice == null && btManager != null) {
                    targetDevice = btManager.findDefaultCanarioDevice();
                }
                if (targetDevice != null) {
                    if (TelemetryService.getInstance() != null) {
                        TelemetryService.getInstance().connect(targetDevice);
                    } else if (btManager != null) {
                        btManager.connect(targetDevice);
                    }
                } else {
                    DeviceDialog.show(this, btManager, device -> {
                        targetDevice = device;
                        updateTargetDeviceInfo();
                        if (TelemetryService.getInstance() != null) {
                            TelemetryService.getInstance().connect(targetDevice);
                        } else if (btManager != null) {
                            btManager.connect(targetDevice);
                        }
                    });
                }
            }
        });

        // Comandos Remotos
        btnCmdMute.setOnClickListener(v -> {
            if (ensureConnected()) {
                boolean ok = (TelemetryService.getInstance() != null)
                        ? TelemetryService.getInstance().sendCommand("MUTE")
                        : (btManager != null && btManager.send("MUTE"));
                if (ok) Toast.makeText(this, "Comando enviado: MUTE", Toast.LENGTH_SHORT).show();
            }
        });

        btnCmdVista.setOnClickListener(v -> {
            if (ensureConnected()) {
                boolean ok = (TelemetryService.getInstance() != null)
                        ? TelemetryService.getInstance().sendCommand("VISTA")
                        : (btManager != null && btManager.send("VISTA"));
                if (ok) Toast.makeText(this, "Comando enviado: CAMBIAR PANTALLA", Toast.LENGTH_SHORT).show();
            }
        });

        btnCmdPing.setOnClickListener(v -> {
            if (ensureConnected()) {
                boolean ok = (TelemetryService.getInstance() != null)
                        ? TelemetryService.getInstance().sendCommand("PING")
                        : (btManager != null && btManager.send("PING"));
                if (ok) Toast.makeText(this, "Ping enviado al Canario", Toast.LENGTH_SHORT).show();
            }
        });

        // Controles de Gráfica (Nivel 3)
        btnChartCo.setOnClickListener(v -> {
            chartView.setMode(TimeSeriesChartView.MODE_CO);
            tvChartTitle.setText("SERIE TEMPORAL: MONÓXIDO DE CARBONO (CO)");
            tvChartTitle.setTextColor(getColor(R.color.sensor_co));
            btnChartCo.setBackgroundResource(R.drawable.bg_tab_selected);
            btnChartCo.setTextColor(0xFF000000);
            btnChartLpg.setBackgroundResource(R.drawable.bg_tab_unselected);
            btnChartLpg.setTextColor(getColor(R.color.text_primary));
            btnChartEnv.setBackgroundResource(R.drawable.bg_tab_unselected);
            btnChartEnv.setTextColor(getColor(R.color.text_primary));
        });

        btnChartLpg.setOnClickListener(v -> {
            chartView.setMode(TimeSeriesChartView.MODE_LPG);
            tvChartTitle.setText("SERIE TEMPORAL: GAS COMBUSTIBLE (GLP / HUMO)");
            tvChartTitle.setTextColor(getColor(R.color.sensor_lpg));
            btnChartLpg.setBackgroundResource(R.drawable.bg_tab_selected);
            btnChartLpg.setTextColor(0xFF000000);
            btnChartCo.setBackgroundResource(R.drawable.bg_tab_unselected);
            btnChartCo.setTextColor(getColor(R.color.text_primary));
            btnChartEnv.setBackgroundResource(R.drawable.bg_tab_unselected);
            btnChartEnv.setTextColor(getColor(R.color.text_primary));
        });

        btnChartEnv.setOnClickListener(v -> {
            chartView.setMode(TimeSeriesChartView.MODE_ENV);
            tvChartTitle.setText("SERIE TEMPORAL HIGROTÉRMICA: TEMP (°C) / HUM (%)");
            tvChartTitle.setTextColor(getColor(R.color.sensor_temp));
            btnChartEnv.setBackgroundResource(R.drawable.bg_tab_selected);
            btnChartEnv.setTextColor(0xFF000000);
            btnChartCo.setBackgroundResource(R.drawable.bg_tab_unselected);
            btnChartCo.setTextColor(getColor(R.color.text_primary));
            btnChartLpg.setBackgroundResource(R.drawable.bg_tab_unselected);
            btnChartLpg.setTextColor(getColor(R.color.text_primary));
        });

        // Filtro de Ventana Temporal
        btnWindow2m.setOnClickListener(v -> {
            currentWindowFilterMillis = 120_000;
            updateWindowFilterButtons(btnWindow2m);
            refreshChart();
        });
        btnWindow10m.setOnClickListener(v -> {
            currentWindowFilterMillis = 600_000;
            updateWindowFilterButtons(btnWindow10m);
            refreshChart();
        });
        btnWindowAll.setOnClickListener(v -> {
            currentWindowFilterMillis = 0;
            updateWindowFilterButtons(btnWindowAll);
            refreshChart();
        });

        // Exportación y Auditoría (Nivel 4)
        btnExportCsv.setOnClickListener(v -> exportAuditFile(true));
        btnExportJson.setOnClickListener(v -> exportAuditFile(false));

        btnClearHistory.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Limpiar Base de Datos")
                    .setMessage("¿Deseas purgar todas las muestras y reiniciar las estadísticas históricas?")
                    .setPositiveButton("Limpiar", (d, w) -> {
                        dbHelper.clearAll();
                        sessionReadings.clear();
                        peakCO = 0.0f;
                        peakLPG = 0.0f;
                        refreshChart();
                        refreshAnalytics(null);
                        Toast.makeText(this, "Base de datos reseteada", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    private void updateWindowFilterButtons(Button activeBtn) {
        btnWindow2m.setBackgroundResource(R.drawable.bg_button_secondary);
        btnWindow2m.setTextColor(getColor(R.color.text_primary));
        btnWindow10m.setBackgroundResource(R.drawable.bg_button_secondary);
        btnWindow10m.setTextColor(getColor(R.color.text_primary));
        btnWindowAll.setBackgroundResource(R.drawable.bg_button_secondary);
        btnWindowAll.setTextColor(getColor(R.color.text_primary));

        activeBtn.setBackgroundResource(R.drawable.bg_button_primary);
        activeBtn.setTextColor(0xFF000000);
    }

    private void loadHistoryFromDatabase() {
        List<TelemetryData> history = dbHelper.getReadingsSince(0);
        sessionReadings.clear();
        sessionReadings.addAll(history);

        for (TelemetryData d : sessionReadings) {
            if (d.getCo() > peakCO) peakCO = d.getCo();
            if (d.getLpg() > peakLPG) peakLPG = d.getLpg();
        }

        refreshChart();
        refreshAnalytics(sessionReadings.isEmpty() ? null : sessionReadings.get(sessionReadings.size() - 1));
    }

    private void exportAuditFile(boolean isCsv) {
        try {
            File exported = isCsv ? dbHelper.exportToCsv(this) : dbHelper.exportToJson(this);
            String mimeType = isCsv ? "text/csv" : "application/json";

            // 1. Guardar permanentemente en la carpeta pública Descargas (Downloads) usando MediaStore en Android 10+
            boolean guardadoEnDescargas = TelemetryDbHelper.saveToPublicDownloads(this, exported.getName(), mimeType, exported);

            // 2. Obtener URI del ContentProvider nativo (CanarioFileProvider)
            Uri contentUri = CanarioFileProvider.getUriForFile(exported);

            // 3. Crear Intent estricto de archivo (sin EXTRA_TEXT para que Quick Share no lo reduzca a solo texto)
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, exported.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // 4. Mensaje claro al usuario
            String msg = guardadoEnDescargas
                    ? "¡Archivo guardado en Descargas!\n" + exported.getName()
                    : "Guardado en almacenamiento:\n" + exported.getAbsolutePath();
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

            // 5. Lanzar selector de compartir
            try {
                Intent chooser = Intent.createChooser(shareIntent, "Enviar archivo " + exported.getName());
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error al exportar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean ensureConnected() {
        BluetoothManager mgr = (TelemetryService.getInstance() != null)
                ? TelemetryService.getInstance().getBluetoothManager()
                : btManager;
        if (mgr == null || mgr.getState() != BluetoothManager.STATE_CONNECTED) {
            Toast.makeText(this, "Conecta el ESP32 por Bluetooth primero", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void attemptAutoDetectDevice() {
        if (!btManager.isBluetoothSupported()) {
            tvBtStatus.setText("Dispositivo sin soporte Bluetooth");
            btnConnectToggle.setEnabled(false);
            btnSelectDevice.setEnabled(false);
            return;
        }
        targetDevice = btManager.findDefaultCanarioDevice();
        updateTargetDeviceInfo();
    }

    @SuppressLint("MissingPermission")
    private void updateTargetDeviceInfo() {
        if (targetDevice != null) {
            String name = targetDevice.getName();
            String addr = targetDevice.getAddress();
            tvBtDeviceInfo.setText("Nodo: " + (name != null ? name : "ESP32") + " (" + addr + ")");
        } else {
            tvBtDeviceInfo.setText("Nodo: Canario_Chillador_IoT (No vinculado)");
        }
    }

    // =========================================================================
    // CALLBACKS DE TELEMETRY SERVICE (BLUETOOTH & DATOS)
    // =========================================================================
    @SuppressLint("MissingPermission")
    @Override
    public void onBluetoothStateChanged(int state, String message) {
        runOnUiThread(() -> {
            switch (state) {
                case BluetoothManager.STATE_DISCONNECTED:
                    tvBtBadge.setText("● OFFLINE");
                    tvBtBadge.setTextColor(getColor(R.color.bt_disconnected));
                    tvBtStatus.setText("Bluetooth: Desconectado");
                    btnConnectToggle.setText("Conectar");
                    btnConnectToggle.setBackgroundResource(R.drawable.bg_button_primary);
                    btnConnectToggle.setTextColor(0xFF000000);
                    break;

                case BluetoothManager.STATE_CONNECTING:
                    tvBtBadge.setText("● CONECTANDO");
                    tvBtBadge.setTextColor(getColor(R.color.bt_connecting));
                    tvBtStatus.setText(message);
                    btnConnectToggle.setText("Cancelar");
                    btnConnectToggle.setBackgroundResource(R.drawable.bg_button_secondary);
                    btnConnectToggle.setTextColor(getColor(R.color.text_primary));
                    break;

                case BluetoothManager.STATE_CONNECTED:
                    tvBtBadge.setText("● EN VIVO");
                    tvBtBadge.setTextColor(getColor(R.color.bt_connected));
                    String devName = BluetoothManager.getDeviceDisplayName(targetDevice);
                    tvBtStatus.setText("Conectado: " + devName);
                    btnConnectToggle.setText("Desconectar");
                    btnConnectToggle.setBackgroundResource(R.drawable.bg_button_secondary);
                    btnConnectToggle.setTextColor(getColor(R.color.text_primary));
                    break;

                case BluetoothManager.STATE_ERROR:
                    tvBtBadge.setText("● ERROR");
                    tvBtBadge.setTextColor(getColor(R.color.status_danger));
                    tvBtStatus.setText(message);
                    btnConnectToggle.setText("Reintentar");
                    btnConnectToggle.setBackgroundResource(R.drawable.bg_button_primary);
                    btnConnectToggle.setTextColor(0xFF000000);
                    break;
            }
        });
    }

    @Override
    public void onTelemetryReceived(TelemetryData data, String rawLine) {
        runOnUiThread(() -> {
            String timeStr = timeFormat.format(new Date());
            tvRawJson.setText("[" + timeStr + "] " + rawLine);

            sessionReadings.add(data);
            if (sessionReadings.size() > 5000) {
                sessionReadings.remove(0);
            }

            renderTelemetry(data);

            if (layoutLevel3.getVisibility() == View.VISIBLE) {
                refreshChart();
            }
            if (layoutLevel4.getVisibility() == View.VISIBLE) {
                refreshAnalytics(data);
            }
        });
    }

    @Override
    public void onBluetoothError(String errorMessage) {
        runOnUiThread(() -> Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show());
    }

    // =========================================================================
    // RENDERIZADO VISUAL
    // =========================================================================
    private void renderTelemetry(TelemetryData data) {
        // --- NIVEL 1: PANORAMA RÁPIDO (< 5 SEGUNDOS) ---
        if (data.isPrecalentando()) {
            cardHeroAlert.setBackgroundResource(R.drawable.bg_card_secondary);
            tvAlertTitle.setText("PRECALENTANDO SILICIO");
            tvAlertTitle.setTextColor(getColor(R.color.canario_yellow));
            tvAlertDesc.setText("Estabilizando filamentos térmicos MQ-2 y MQ-7 (" + data.getSegundosFase() + "/45 s). Por favor mantenga en aire ventilado.");
        } else if (data.isPeligro()) {
            cardHeroAlert.setBackgroundResource(R.drawable.bg_card_alert_danger);
            tvAlertTitle.setText("¡PELIGRO: EVACUAR!");
            tvAlertTitle.setTextColor(getColor(R.color.status_danger));
            tvAlertDesc.setText("¡Nivel crítico de gas detectado! Alarma sonora física activada.");
        } else if (data.isPrecaucion()) {
            cardHeroAlert.setBackgroundResource(R.drawable.bg_card_alert_caution);
            tvAlertTitle.setText("PRECAUCIÓN / PURGA");
            tvAlertTitle.setTextColor(getColor(R.color.status_caution));
            tvAlertDesc.setText("Calentamiento térmico MQ-7 o gas en umbral preventivo.");
        } else {
            cardHeroAlert.setBackgroundResource(R.drawable.bg_card_alert_normal);
            tvAlertTitle.setText("ESTADO SEGURO");
            tvAlertTitle.setTextColor(getColor(R.color.status_normal));
            tvAlertDesc.setText("Atmósfera nominal inocua. Todo el sistema opera con normalidad.");
        }

        if (data.isMute()) {
            tvMuteIndicator.setText("SILENCIADO (MUTE)");
            tvMuteIndicator.setTextColor(getColor(R.color.status_caution));
            btnCmdMute.setText("QUITAR MUTE");
        } else {
            tvMuteIndicator.setText("CHILLIDO ACTIVO");
            tvMuteIndicator.setTextColor(getColor(R.color.text_secondary));
            btnCmdMute.setText("MUTE BUZZER");
        }

        tvL1CoVal.setText(String.format(Locale.US, "%.1f PPM", data.getCo()));
        tvL1LpgVal.setText(String.format(Locale.US, "%.0f PPM", data.getLpg()));

        boolean esPurga = "PURGA".equalsIgnoreCase(data.getFaseMQ7());
        tvL1Mq7.setText(esPurga ? "PURGA (5.0V)" : "MEDICIÓN (1.4V)");
        tvL1Mq7.setTextColor(esPurga ? getColor(R.color.canario_yellow) : getColor(R.color.status_normal));

        // --- NIVEL 2: SUPERVISIÓN DETALLADA ---
        float co = data.getCo();
        tvCoValue.setText(String.format(Locale.US, "%.1f", co));
        progressCoBar.setProgress((int) co);
        if (co > peakCO) {
            peakCO = co;
            tvCoPeak.setText(String.format(Locale.US, "Pico: %.1f ppm", peakCO));
        }

        float lpg = data.getLpg();
        tvLpgValue.setText(String.format(Locale.US, "%.0f", lpg));
        progressLpgBar.setProgress((int) lpg);
        if (lpg > peakLPG) {
            peakLPG = lpg;
            tvLpgPeak.setText(String.format(Locale.US, "Pico: %.0f ppm", peakLPG));
        }

        tvTempValue.setText(String.format(Locale.US, "%.1f °C", data.getTemperatura()));
        tvHumValue.setText(String.format(Locale.US, "%.0f %%", data.getHumedad()));

        if (data.isPrecalentando()) {
            tvMq7Phase.setText("MQ-7 / MQ-2: PRECALENTANDO");
            tvMq7Seconds.setText(data.getSegundosFase() + " / 45 s");
            progressMq7Bar.setMax(45);
            progressMq7Bar.setProgress(data.getSegundosFase());
        } else {
            int segMax = esPurga ? 60 : 90;
            tvMq7Phase.setText(esPurga ? "MQ-7: FASE PURGA (5.0V)" : "MQ-7: FASE MEDICIÓN (1.4V)");
            tvMq7Seconds.setText(data.getSegundosFase() + " / " + segMax + " s");
            progressMq7Bar.setMax(segMax);
            progressMq7Bar.setProgress(data.getSegundosFase());
        }
    }

    private void refreshChart() {
        List<TelemetryData> chartData = sessionReadings;
        if (currentWindowFilterMillis > 0 && !sessionReadings.isEmpty()) {
            long cutoff = System.currentTimeMillis() - currentWindowFilterMillis;
            List<TelemetryData> filtered = new ArrayList<>();
            for (TelemetryData d : sessionReadings) {
                if (d.getTimestamp() >= cutoff) filtered.add(d);
            }
            chartData = filtered;
        }
        chartView.setData(chartData);
    }

    private void refreshAnalytics(TelemetryData current) {
        AnalyticsEngine.AnalyticsReport report = AnalyticsEngine.analyze(sessionReadings, current);

        tvL1Predictive.setText(report.estadoPredictivo);
        if (report.estadoPredictivo.contains("FUGA")) {
            tvL1Predictive.setTextColor(getColor(R.color.status_danger));
        } else if (report.estadoPredictivo.contains("INCREMENTO")) {
            tvL1Predictive.setTextColor(getColor(R.color.status_caution));
        } else {
            tvL1Predictive.setTextColor(getColor(R.color.status_normal));
        }

        tvAnalyticsTwa.setText(String.format(Locale.US, "%.1f", report.twaCO));
        tvAnalyticsNom010.setText(String.format(Locale.US, "NOM-010-STPS (VLE-PPT 25 PPM): %.1f%% de dosis máxima", report.percentNom010));
        tvAnalyticsOsha.setText(String.format(Locale.US, "OSHA PEL (TWA 50 PPM): %.1f%% de límite permisible", report.percentOsha));

        tvAnalyticsDcoDt.setText(String.format(Locale.US, "%+.2f PPM/s", report.dCoDt));
        tvAnalyticsDlpgDt.setText(String.format(Locale.US, "%+.2f PPM/s", report.dLpgDt));

        tvAnalyticsPearson.setText(String.format(Locale.US, "Correlación Pearson: r(CO, Temp) = %+.2f | r(CO, RH) = %+.2f",
                report.rCoTemp, report.rCoHum));
        tvAnalyticsDeriva.setText("Diagnóstico Quimiorresistencia: " + report.derivasHigrotermicas);

        tvAnalyticsConclusion.setText(report.conclusionGeneral);
        tvAnalyticsAction.setText("Acción recomendada: " + report.accionRecomendada);

        int totalCount = dbHelper.getTotalCount();
        tvAnalyticsCount.setText("Muestras recopiladas y almacenadas en SQLite: " + totalCount);
    }

    // =========================================================================
    // PERMISOS DINÁMICOS
    // =========================================================================
    private boolean hasRequiredPermissions() {
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ok = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = ok && (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        }
        return ok;
    }

    private void requestPermissions() {
        List<String> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
            list.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            list.add(Manifest.permission.BLUETOOTH);
            list.add(Manifest.permission.BLUETOOTH_ADMIN);
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(list.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show();
                attemptAutoDetectDevice();
            } else {
                Toast.makeText(this, "Se requieren permisos para monitoreo completo", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TelemetryService.setCallback(null);
        // NOTA: No desconectamos aquí para que el Foreground Service
        // mantenga la persistencia SQLite activa con la app cerrada.
    }
}
