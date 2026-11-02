package com.canario.iot;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestor robusto de comunicación Bluetooth Clásico (SPP / RFCOMM) con el ESP32.
 * Maneja hilos de conexión, lectura asíncrona continua y despacho al hilo principal.
 */
public class BluetoothManager {
    private static final String TAG = "CanarioBT";

    // UUID estándar para Serial Port Profile (SPP)
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING   = 1;
    public static final int STATE_CONNECTED    = 2;
    public static final int STATE_ERROR        = 3;

    public interface BluetoothListener {
        void onStateChanged(int state, String message);
        void onDataReceived(String rawLine);
        void onError(String errorMessage);
    }

    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler;
    private BluetoothListener listener;

    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private int currentState = STATE_DISCONNECTED;
    private BluetoothDevice currentDevice;

    public BluetoothManager(BluetoothListener listener) {
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(BluetoothListener listener) {
        this.listener = listener;
    }

    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public int getState() {
        return currentState;
    }

    public BluetoothDevice getCurrentDevice() {
        return currentDevice;
    }

    private synchronized void setState(int state, String message) {
        this.currentState = state;
        if (listener != null) {
            mainHandler.post(() -> listener.onStateChanged(state, message));
        }
    }

    /**
     * Obtiene la lista de dispositivos vinculados (Paired devices).
     */
    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getPairedDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired != null) {
                devices.addAll(paired);
            }
        }
        return devices;
    }

    /**
     * Busca automáticamente un dispositivo que se llame "Canario_Chillador_IoT"
     * o contenga "Canario" o "ESP32".
     */
    @SuppressLint("MissingPermission")
    public BluetoothDevice findDefaultCanarioDevice() {
        List<BluetoothDevice> paired = getPairedDevices();
        for (BluetoothDevice dev : paired) {
            String name = dev.getName();
            if (name != null) {
                if (name.equalsIgnoreCase("Canario_Chillador_IoT")) {
                    return dev;
                }
            }
        }
        for (BluetoothDevice dev : paired) {
            String name = dev.getName();
            if (name != null && (name.toLowerCase().contains("canario") || name.toLowerCase().contains("esp32"))) {
                return dev;
            }
        }
        return null;
    }

    /**
     * Inicia el proceso de conexión con el dispositivo Bluetooth indicado.
     */
    public synchronized void connect(BluetoothDevice device) {
        if (device == null) {
            setState(STATE_ERROR, "Dispositivo no válido");
            return;
        }

        // Cancelar cualquier conexión o intento en curso
        disconnect();

        this.currentDevice = device;
        setState(STATE_CONNECTING, "Conectando a " + getDeviceDisplayName(device) + "...");

        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    /**
     * Desconecta activamente y libera los hilos y sockets asociados.
     */
    public synchronized void disconnect() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (currentState != STATE_DISCONNECTED) {
            setState(STATE_DISCONNECTED, "Desconectado");
        }
    }

    /**
     * Envía una cadena de texto (comando) hacia el ESP32.
     */
    public synchronized boolean send(String data) {
        ConnectedThread r;
        synchronized (this) {
            if (currentState != STATE_CONNECTED || connectedThread == null) {
                return false;
            }
            r = connectedThread;
        }
        if (!data.endsWith("\n")) {
            data = data + "\n";
        }
        return r.write(data.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressLint("MissingPermission")
    public static String getDeviceDisplayName(BluetoothDevice device) {
        if (device == null) return "Desconocido";
        String name = device.getName();
        return (name != null && !name.trim().isEmpty()) ? name : device.getAddress();
    }

    // =========================================================================
    // HILO DE CONEXIÓN (CONNECT THREAD)
    // =========================================================================
    private class ConnectThread extends Thread {
        private final BluetoothDevice mmDevice;
        private BluetoothSocket mmSocket;

        @SuppressLint("MissingPermission")
        public ConnectThread(BluetoothDevice device) {
            this.mmDevice = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Error al crear socket RFCOMM: " + e.getMessage());
            }
            mmSocket = tmp;
        }

        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            setName("ConnectThread-" + mmDevice.getAddress());

            // Cancelar descubrimiento Bluetooth para acelerar la conexión
            if (bluetoothAdapter != null) {
                bluetoothAdapter.cancelDiscovery();
            }

            if (mmSocket == null) {
                setState(STATE_ERROR, "No se pudo instanciar el socket Bluetooth");
                return;
            }

            try {
                // Bloquea hasta conectar o fallar por timeout
                mmSocket.connect();
            } catch (IOException connectException) {
                Log.w(TAG, "Conexión directa falló, intentando socket de respaldo: " + connectException.getMessage());
                // Fallback para ciertos dispositivos Android: invocar createRfcommSocket por reflexión
                try {
                    mmSocket = (BluetoothSocket) mmDevice.getClass()
                            .getMethod("createRfcommSocket", int.class)
                            .invoke(mmDevice, 1);
                    if (mmSocket != null) {
                        mmSocket.connect();
                    } else {
                        throw new IOException("Fallback socket nulo");
                    }
                } catch (Exception fallbackEx) {
                    Log.e(TAG, "Fallo total de conexión Bluetooth: " + fallbackEx.getMessage());
                    try {
                        mmSocket.close();
                    } catch (IOException ignored) {}

                    final String errMsg = "Error al conectar con " + getDeviceDisplayName(mmDevice) +
                            ". ¿Está el ESP32 encendido y emparejado?";
                    setState(STATE_ERROR, errMsg);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError(errMsg));
                    }
                    return;
                }
            }

            synchronized (BluetoothManager.this) {
                connectThread = null;
            }

            // Iniciar el hilo de lectura/escritura permanente
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                if (mmSocket != null) {
                    mmSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error al cerrar socket en cancel(): " + e.getMessage());
            }
        }
    }

    private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        setState(STATE_CONNECTED, "Conectado a " + getDeviceDisplayName(device));
    }

    // =========================================================================
    // HILO DE LECTURA Y ESCRITURA CONTINUA (CONNECTED THREAD)
    // =========================================================================
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final BufferedReader mReader;
        private volatile boolean isRunning = true;

        public ConnectedThread(BluetoothSocket socket) {
            this.mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error al obtener flujos de I/O: " + e.getMessage());
            }

            this.mmInStream = tmpIn;
            this.mmOutStream = tmpOut;
            this.mReader = (tmpIn != null) ? new BufferedReader(new InputStreamReader(tmpIn, StandardCharsets.UTF_8)) : null;
        }

        @Override
        public void run() {
            setName("ConnectedThread-Canario");

            if (mReader == null) {
                setState(STATE_ERROR, "Flujo de entrada no inicializado");
                return;
            }

            // Lectura continua de tramas línea por línea
            while (isRunning) {
                try {
                    String line = mReader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        final String data = line.trim();
                        if (listener != null) {
                            mainHandler.post(() -> listener.onDataReceived(data));
                        }
                    } else if (line == null) {
                        // Fin de flujo (el ESP32 se desconectó)
                        throw new IOException("ESP32 cerró la conexión remota");
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        Log.e(TAG, "Pérdida de conexión Bluetooth: " + e.getMessage());
                        setState(STATE_DISCONNECTED, "Conexión perdida con el ESP32");
                        if (listener != null) {
                            mainHandler.post(() -> listener.onError("Conexión perdida con el nodo sensor"));
                        }
                    }
                    break;
                }
            }
        }

        public boolean write(byte[] bytes) {
            try {
                if (mmOutStream != null) {
                    mmOutStream.write(bytes);
                    mmOutStream.flush();
                    return true;
                }
            } catch (IOException e) {
                Log.e(TAG, "Error al escribir en socket BT: " + e.getMessage());
            }
            return false;
        }

        public void cancel() {
            isRunning = false;
            try {
                if (mmInStream != null) mmInStream.close();
            } catch (IOException ignored) {}
            try {
                if (mmOutStream != null) mmOutStream.close();
            } catch (IOException ignored) {}
            try {
                if (mmSocket != null) mmSocket.close();
            } catch (IOException ignored) {}
        }
    }
}
