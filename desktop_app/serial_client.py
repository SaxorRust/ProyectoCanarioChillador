"""
Cliente de Comunicación Serial / Bluetooth SPP para El Canario Chillador.
Maneja la conexión en segundo plano sin congelar la interfaz gráfica de usuario.
"""

import threading
import time
import json
from typing import Callable, Optional, List, Tuple

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    serial = None

class SerialClient:
    def __init__(self,
                 on_telemetry: Optional[Callable[[dict, str], None]] = None,
                 on_log: Optional[Callable[[str], None]] = None,
                 on_state_change: Optional[Callable[[bool, str], None]] = None):
        self.on_telemetry = on_telemetry
        self.on_log = on_log
        self.on_state_change = on_state_change

        self.ser: Optional[serial.Serial] = None
        self.port: str = ""
        self.baudrate: int = 115200
        self.is_running: bool = False
        self.thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

    @staticmethod
    def list_ports() -> List[Tuple[str, str]]:
        """Retorna lista de tuplas (dispositivo, descripcion_amigable)."""
        if not serial:
            return []
        ports = []
        for p in serial.tools.list_ports.comports():
            desc = p.description if p.description else "Puerto Serial Estándar"
            ports.append((p.device, f"{p.device} - {desc}"))
        return ports

    def is_connected(self) -> bool:
        return self.ser is not None and self.ser.is_open

    def connect(self, port: str, baudrate: int = 115200) -> bool:
        if not serial:
            if self.on_state_change:
                self.on_state_change(False, "Error: Librería pyserial no disponible.")
            return False

        self.disconnect()

        self.port = port
        self.baudrate = baudrate
        try:
            self.ser = serial.Serial(port, baudrate, timeout=1.0)
            self.is_running = True
            self.thread = threading.Thread(target=self._read_loop, daemon=True)
            self.thread.start()

            if self.on_state_change:
                self.on_state_change(True, f"Conectado a {port} ({baudrate} bps)")
            if self.on_log:
                self.on_log(f"[CONEXION] Enlace abierto en {port} a {baudrate} baudios.")
            return True
        except Exception as e:
            self.ser = None
            self.is_running = False
            msg = f"Error al abrir {port}: {str(e)}"
            if self.on_state_change:
                self.on_state_change(False, msg)
            if self.on_log:
                self.on_log(f"[ERROR] {msg}")
            return False

    def disconnect(self):
        self.is_running = False
        if self.thread and self.thread.is_alive():
            try:
                self.thread.join(timeout=0.8)
            except Exception:
                pass
        self.thread = None

        with self._lock:
            if self.ser:
                try:
                    self.ser.close()
                except Exception:
                    pass
                self.ser = None

        if self.on_state_change:
            self.on_state_change(False, "Desconectado")
        if self.on_log:
            self.on_log("[CONEXION] Enlace serial cerrado.")

    def send_command(self, cmd: str) -> bool:
        with self._lock:
            if not self.is_connected():
                return False
            try:
                msg = (cmd.strip() + "\n").encode("utf-8")
                self.ser.write(msg)
                self.ser.flush()
                if self.on_log:
                    self.on_log(f"[COMANDO ENVIADO] >> {cmd.strip()}")
                return True
            except Exception as e:
                if self.on_log:
                    self.on_log(f"[ERROR TX] No se pudo enviar '{cmd}': {e}")
                return False

    def _read_loop(self):
        buffer = ""
        while self.is_running and self.ser and self.ser.is_open:
            try:
                raw_bytes = self.ser.readline()
                if not raw_bytes:
                    continue

                line = raw_bytes.decode("utf-8", errors="ignore").strip()
                if not line:
                    continue

                # Diagnóstico en vivo
                if self.on_log:
                    self.on_log(line)

                # Detección de tramas JSON de telemetría: {"co": ..., "lpg": ...}
                start_idx = line.find("{")
                end_idx = line.rfind("}")
                if start_idx != -1 and end_idx > start_idx:
                    json_str = line[start_idx:end_idx + 1]
                    try:
                        data = json.loads(json_str)
                        if "co" in data and "lpg" in data:
                            if self.on_telemetry:
                                self.on_telemetry(data, line)
                    except json.JSONDecodeError:
                        pass
            except Exception as e:
                if self.is_running:
                    if self.on_log:
                        self.on_log(f"[ERROR RX] Fallo de lectura: {e}")
                break

        if self.is_running:
            # Desconexión inesperada (cable desconectado)
            self.is_running = False
            if self.on_state_change:
                self.on_state_change(False, "Conexión perdida con el dispositivo")
