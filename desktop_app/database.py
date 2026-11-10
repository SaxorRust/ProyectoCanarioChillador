"""
Módulo de Persistencia Local SQLite para El Canario Chillador (Desktop).
Almacena cada muestra de telemetría para auditoría higiénico-industrial.
"""

import os
import sqlite3
import csv
import json
import time

class DatabaseManager:
    def __init__(self, db_path=None):
        if db_path is None:
            # Ubicación predeterminada junto al ejecutable o script
            base_dir = os.path.dirname(os.path.abspath(__file__))
            self.db_path = os.path.join(base_dir, "canario_telemetry.db")
        else:
            self.db_path = db_path
            
        self._init_db()

    def _init_db(self):
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS telemetria (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    co REAL NOT NULL,
                    lpg REAL NOT NULL,
                    temperatura REAL NOT NULL,
                    humedad REAL NOT NULL,
                    fase_mq7 TEXT NOT NULL,
                    segundos_fase INTEGER NOT NULL,
                    alerta TEXT NOT NULL,
                    mute INTEGER NOT NULL,
                    raw_json TEXT
                )
            """)
            cursor.execute("CREATE INDEX IF NOT EXISTS idx_telemetria_ts ON telemetria(timestamp)")
            conn.commit()

    def insert(self, data: dict, raw_json: str = ""):
        """Inserta una lectura de telemetría proveniente del ESP32."""
        now_ms = int(time.time() * 1000)
        co = float(data.get("co", 0.0))
        lpg = float(data.get("lpg", 0.0))
        temp = float(data.get("temp", 0.0))
        hum = float(data.get("hum", 0.0))
        fase = str(data.get("fase", "PURGA"))
        seg = int(data.get("seg", 0))
        alerta = str(data.get("alerta", "NORMAL"))
        mute = 1 if data.get("mute", False) else 0

        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                INSERT INTO telemetria (
                    timestamp, co, lpg, temperatura, humedad,
                    fase_mq7, segundos_fase, alerta, mute, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (now_ms, co, lpg, temp, hum, fase, seg, alerta, mute, raw_json))
            conn.commit()

    def get_history(self, limit: int = 5000):
        """Retorna las lecturas más recientes ordenadas cronológicamente."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT timestamp, co, lpg, temperatura, humedad,
                       fase_mq7, segundos_fase, alerta, mute, raw_json
                FROM telemetria
                ORDER BY timestamp DESC
                LIMIT ?
            """, (limit,))
            rows = cursor.fetchall()
            
        # Revertir para orden ascendente (más antiguo a más reciente)
        rows.reverse()
        result = []
        for r in rows:
            result.append({
                "timestamp": r[0],
                "co": r[1],
                "lpg": r[2],
                "temp": r[3],
                "hum": r[4],
                "fase": r[5],
                "seg": r[6],
                "alerta": r[7],
                "mute": bool(r[8]),
                "raw_json": r[9]
            })
        return result

    def get_count(self) -> int:
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT COUNT(*) FROM telemetria")
            res = cursor.fetchone()
            return res[0] if res else 0

    def clear_all(self):
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("DELETE FROM telemetria")
            conn.commit()

    def export_csv(self, filepath: str) -> int:
        """Exporta todo el historial a un archivo CSV estándar RFC 4180."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT timestamp, co, lpg, temperatura, humedad,
                       fase_mq7, segundos_fase, alerta, mute
                FROM telemetria
                ORDER BY timestamp ASC
            """)
            rows = cursor.fetchall()

        with open(filepath, mode="w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "Timestamp_ms", "Fecha_Hora_ISO", "CO_PPM", "GLP_PPM",
                "Temperatura_C", "Humedad_Pct", "Fase_MQ7", "Segundos_Fase",
                "Nivel_Alerta", "Buzzer_Mute"
            ])
            for r in rows:
                iso_time = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r[0] / 1000.0))
                writer.writerow([
                    r[0], iso_time, f"{r[1]:.2f}", f"{r[2]:.2f}",
                    f"{r[3]:.1f}", f"{r[4]:.1f}", r[5], r[6],
                    r[7], "SI" if r[8] else "NO"
                ])
        return len(rows)

    def export_json(self, filepath: str) -> int:
        """Exporta todo el historial a un archivo JSON estructurado."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT timestamp, co, lpg, temperatura, humedad,
                       fase_mq7, segundos_fase, alerta, mute
                FROM telemetria
                ORDER BY timestamp ASC
            """)
            rows = cursor.fetchall()

        items = []
        for r in rows:
            items.append({
                "timestamp": r[0],
                "datetime": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r[0] / 1000.0)),
                "co_ppm": r[1],
                "glp_ppm": r[2],
                "temperatura_c": r[3],
                "humedad_pct": r[4],
                "fase_mq7": r[5],
                "segundos_fase": r[6],
                "alerta": r[7],
                "mute": bool(r[8])
            })

        payload = {
            "dispositivo": "Canario Chillador IoT (Nodo Standalone)",
            "generado_en": time.strftime("%Y-%m-%d %H:%M:%S"),
            "total_registros": len(items),
            "telemetria": items
        }

        with open(filepath, mode="w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)

        return len(items)

    def import_file(self, filepath: str) -> int:
        """
        Importa registros de telemetría desde un archivo CSV o JSON (generado en Android o PC).
        Deduplica automáticamente por timestamp. Retorna la cantidad de registros nuevos insertados.
        """
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            existing_ts = set(r[0] for r in cursor.execute("SELECT timestamp FROM telemetria").fetchall())

            new_records = []
            if filepath.lower().endswith(".json"):
                with open(filepath, mode="r", encoding="utf-8") as f:
                    content = json.load(f)
                    items = content if isinstance(content, list) else content.get("telemetria", [])
                    for item in items:
                        ts = int(item.get("timestamp", 0))
                        if ts > 0 and ts not in existing_ts:
                            co = float(item.get("co", item.get("co_ppm", 0.0)))
                            lpg = float(item.get("lpg", item.get("glp_ppm", 0.0)))
                            temp = float(item.get("temp", item.get("temperatura", item.get("temperatura_c", 0.0))))
                            hum = float(item.get("hum", item.get("humedad", item.get("humedad_pct", 0.0))))
                            fase = str(item.get("fase", item.get("fase_mq7", "PURGA")))
                            seg = int(item.get("seg", item.get("segundos_fase", 0)))
                            alerta = str(item.get("alerta", "NORMAL"))
                            m = item.get("mute", False)
                            mute = 1 if (m is True or str(m).lower() in ("true", "1", "si")) else 0
                            new_records.append((ts, co, lpg, temp, hum, fase, seg, alerta, mute, json.dumps(item)))
                            existing_ts.add(ts)
            else:  # Formato CSV
                with open(filepath, mode="r", encoding="utf-8", errors="ignore") as f:
                    reader = csv.reader(f)
                    header = [h.strip().upper() for h in next(reader, [])]
                    idx_ts = -1
                    idx_co = -1
                    idx_lpg = -1
                    idx_temp = -1
                    idx_hum = -1
                    idx_fase = -1
                    idx_seg = -1
                    idx_alerta = -1
                    idx_mute = -1

                    for i, h in enumerate(header):
                        if "TIMESTAMP" in h: idx_ts = i
                        elif "CO" in h: idx_co = i
                        elif "LPG" in h or "GLP" in h: idx_lpg = i
                        elif "TEMP" in h: idx_temp = i
                        elif "HUM" in h: idx_hum = i
                        elif "FASE" in h: idx_fase = i
                        elif "SEG" in h: idx_seg = i
                        elif "ALERTA" in h: idx_alerta = i
                        elif "MUTE" in h: idx_mute = i

                    for row in reader:
                        if not row or len(row) < 3: continue
                        try:
                            ts = int(row[idx_ts]) if idx_ts >= 0 and row[idx_ts].isdigit() else 0
                            if ts <= 0 or ts in existing_ts: continue

                            co = float(row[idx_co]) if idx_co >= 0 else 0.0
                            lpg = float(row[idx_lpg]) if idx_lpg >= 0 else 0.0
                            temp = float(row[idx_temp]) if idx_temp >= 0 else 0.0
                            hum = float(row[idx_hum]) if idx_hum >= 0 else 0.0
                            fase = str(row[idx_fase]) if idx_fase >= 0 else "PURGA"
                            seg = int(float(row[idx_seg])) if idx_seg >= 0 else 0
                            alerta = str(row[idx_alerta]) if idx_alerta >= 0 else "NORMAL"
                            m_val = str(row[idx_mute]).upper() if idx_mute >= 0 else "FALSE"
                            mute = 1 if m_val in ("TRUE", "1", "SI") else 0

                            new_records.append((ts, co, lpg, temp, hum, fase, seg, alerta, mute, ""))
                            existing_ts.add(ts)
                        except Exception:
                            continue

            if new_records:
                cursor.executemany("""
                    INSERT INTO telemetria (
                        timestamp, co, lpg, temperatura, humedad,
                        fase_mq7, segundos_fase, alerta, mute, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new_records)
                conn.commit()

            return len(new_records)
