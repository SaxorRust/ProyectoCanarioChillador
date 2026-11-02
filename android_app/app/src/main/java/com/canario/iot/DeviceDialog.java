package com.canario.iot;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.ArrayAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Diálogo rápido para listar y seleccionar los dispositivos Bluetooth vinculados
 * o abrir los Ajustes del sistema para vincular el ESP32.
 */
public class DeviceDialog {

    public interface OnDeviceSelectedListener {
        void onDeviceSelected(BluetoothDevice device);
    }

    @SuppressLint("MissingPermission")
    public static void show(Context context, BluetoothManager btManager, OnDeviceSelectedListener listener) {
        if (!btManager.isBluetoothEnabled()) {
            new AlertDialog.Builder(context)
                    .setTitle("Bluetooth Desactivado")
                    .setMessage("Por favor activa el Bluetooth en tu teléfono para conectarte al ESP32.")
                    .setPositiveButton("Activar en Ajustes", (dialog, which) -> {
                        context.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }

        List<BluetoothDevice> pairedDevices = btManager.getPairedDevices();
        if (pairedDevices.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle("Sin Dispositivos Vinculados")
                    .setMessage("No se encontraron dispositivos vinculados.\n\n" +
                            "1. Enciende tu ESP32 ('Canario_Chillador_IoT').\n" +
                            "2. Abre los Ajustes de Bluetooth de Android.\n" +
                            "3. Vincula el ESP32 y regresa a esta app.")
                    .setPositiveButton("Abrir Ajustes Bluetooth", (dialog, which) -> {
                        context.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    })
                    .setNegativeButton("Cerrar", null)
                    .show();
            return;
        }

        List<String> deviceLabels = new ArrayList<>();
        int defaultSelection = -1;

        for (int i = 0; i < pairedDevices.size(); i++) {
            BluetoothDevice dev = pairedDevices.get(i);
            String name = dev.getName();
            String address = dev.getAddress();
            String label = (name != null ? name : "Dispositivo Desconocido") + "\n" + address;
            deviceLabels.add(label);

            if (name != null && name.equalsIgnoreCase("Canario_Chillador_IoT")) {
                defaultSelection = i;
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_list_item_1,
                deviceLabels
        );

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("Seleccionar Nodo ESP32")
                .setAdapter(adapter, (dialog, which) -> {
                    BluetoothDevice selected = pairedDevices.get(which);
                    if (listener != null) {
                        listener.onDeviceSelected(selected);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Ajustes BT", (dialog, which) -> {
                    context.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                });

        builder.show();
    }
}
