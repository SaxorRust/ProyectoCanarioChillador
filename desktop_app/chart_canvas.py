"""
Gráfica Temporal en Tiempo Real basada en Canvas de Tkinter para El Canario Chillador.
Renderizado nativo de alto rendimiento sin dependencias pesadas de terceros.
"""

import tkinter as tk
import time
from typing import List, Dict

class TimeSeriesCanvas(tk.Frame):
    def __init__(self, parent, bg="#0d1117", **kwargs):
        super().__init__(parent, bg=bg, **kwargs)
        self.bg_color = bg
        self.canvas = tk.Canvas(self, bg=self.bg_color, highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)

        self.data: List[Dict] = []
        self.window_mode = "2m"  # "2m", "10m", "all"
        self.variable_mode = "AMBOS"  # "CO", "GLP", "AMBOS"

        # Colores temáticos
        self.COLOR_GRID = "#21262d"
        self.COLOR_AXIS = "#30363d"
        self.COLOR_TEXT = "#8b949e"
        self.COLOR_CO = "#58a6ff"       # Azul cian
        self.COLOR_LPG = "#f0883e"      # Naranja cálido
        self.COLOR_NOM010 = "#d29922"   # Ámbar precaución (25 ppm)
        self.COLOR_PELIGRO = "#da3633"  # Rojo peligro (100 ppm)

        self.canvas.bind("<Configure>", lambda e: self.redraw())

    def set_data(self, data: List[Dict]):
        self.data = data
        self.redraw()

    def set_window_mode(self, mode: str):
        self.window_mode = mode
        self.redraw()

    def set_variable_mode(self, mode: str):
        self.variable_mode = mode
        self.redraw()

    def redraw(self):
        self.canvas.delete("all")
        width = self.canvas.winfo_width()
        height = self.canvas.winfo_height()

        if width < 100 or height < 100:
            return

        # Márgenes
        pad_left = 65
        pad_right = 30
        pad_top = 25
        pad_bottom = 35

        plot_w = width - pad_left - pad_right
        plot_h = height - pad_top - pad_bottom

        # Filtrar datos según ventana de tiempo
        now_ms = int(time.time() * 1000)
        if self.data:
            last_ts = self.data[-1].get("timestamp", now_ms)
        else:
            last_ts = now_ms

        if self.window_mode == "2m":
            cutoff = last_ts - (120 * 1000)
            visible_data = [d for d in self.data if d.get("timestamp", 0) >= cutoff]
        elif self.window_mode == "10m":
            cutoff = last_ts - (600 * 1000)
            visible_data = [d for d in self.data if d.get("timestamp", 0) >= cutoff]
        else:
            visible_data = self.data

        # Determinar escalas máximas
        max_co = 30.0
        max_lpg = 1200.0

        if visible_data:
            for d in visible_data:
                co = float(d.get("co", 0.0))
                lpg = float(d.get("lpg", 0.0))
                if co > max_co: max_co = co
                if lpg > max_lpg: max_lpg = lpg

        # Añadir margen superior del 15%
        max_co *= 1.15
        max_lpg *= 1.15

        # Dibujar rejilla horizontal y marcas de escala Y
        num_grids = 5
        for i in range(num_grids + 1):
            ratio = i / float(num_grids)
            y = pad_top + plot_h - (ratio * plot_h)
            self.canvas.create_line(pad_left, y, pad_left + plot_w, y, fill=self.COLOR_GRID, dash=(2, 4))

            # Etiquetas numéricas según el modo
            if self.variable_mode == "CO":
                val = ratio * max_co
                self.canvas.create_text(pad_left - 8, y, text=f"{val:.1f}", fill=self.COLOR_CO, anchor="e", font=("Consolas", 8))
            elif self.variable_mode == "GLP":
                val = ratio * max_lpg
                self.canvas.create_text(pad_left - 8, y, text=f"{int(val)}", fill=self.COLOR_LPG, anchor="e", font=("Consolas", 8))
            else:
                # Eje CO a la izquierda, escala compartida o doble indicación
                val_co = ratio * max_co
                self.canvas.create_text(pad_left - 8, y, text=f"{val_co:.0f}", fill=self.COLOR_CO, anchor="e", font=("Consolas", 8))

        # Eje de abscisas (Tiempo) y ordenadas
        self.canvas.create_line(pad_left, pad_top + plot_h, pad_left + plot_w, pad_top + plot_h, fill=self.COLOR_AXIS, width=1)
        self.canvas.create_line(pad_left, pad_top, pad_left, pad_top + plot_h, fill=self.COLOR_AXIS, width=1)

        # Líneas de referencia normativas si se grafica CO o AMBOS
        if self.variable_mode in ("CO", "AMBOS"):
            # NOM-010 25 PPM
            if max_co >= 25.0:
                y_25 = pad_top + plot_h - ((25.0 / max_co) * plot_h)
                self.canvas.create_line(pad_left, y_25, pad_left + plot_w, y_25, fill=self.COLOR_NOM010, dash=(4, 4), width=1)
                self.canvas.create_text(pad_left + plot_w - 5, y_25 - 8, text="25 ppm NOM-010 (VLE-PPT)", fill=self.COLOR_NOM010, anchor="e", font=("Segoe UI", 8, "italic"))

            # CENAPRED 100 PPM
            if max_co >= 100.0:
                y_100 = pad_top + plot_h - ((100.0 / max_co) * plot_h)
                self.canvas.create_line(pad_left, y_100, pad_left + plot_w, y_100, fill=self.COLOR_PELIGRO, dash=(4, 4), width=1)
                self.canvas.create_text(pad_left + plot_w - 5, y_100 - 8, text="100 ppm CENAPRED (Peligro)", fill=self.COLOR_PELIGRO, anchor="e", font=("Segoe UI", 8, "bold"))

        # Si no hay suficientes datos para trazar
        if not visible_data or len(visible_data) < 2:
            self.canvas.create_text(
                pad_left + plot_w / 2, pad_top + plot_h / 2,
                text="Esperando flujo de telemetría del ESP32...",
                fill=self.COLOR_TEXT, font=("Segoe UI", 10, "italic")
            )
            return

        t_min = visible_data[0].get("timestamp", 0)
        t_max = visible_data[-1].get("timestamp", 0)
        t_range = max(1000, t_max - t_min)

        pts_co = []
        pts_lpg = []

        for d in visible_data:
            ts = d.get("timestamp", 0)
            co = float(d.get("co", 0.0))
            lpg = float(d.get("lpg", 0.0))

            rx = (ts - t_min) / float(t_range)
            x = pad_left + (rx * plot_w)

            # Coordenada Y para CO
            ry_co = co / max_co
            y_co = pad_top + plot_h - (ry_co * plot_h)
            pts_co.extend([x, y_co])

            # Coordenada Y para GLP (normalizado a su escala)
            if self.variable_mode == "GLP":
                ry_lpg = lpg / max_lpg
            else:
                # En modo AMBOS mapeamos GLP a la altura del gráfico según max_lpg
                ry_lpg = lpg / max_lpg
            y_lpg = pad_top + plot_h - (ry_lpg * plot_h)
            pts_lpg.extend([x, y_lpg])

        # Renderizado de polilíneas
        if self.variable_mode in ("CO", "AMBOS") and len(pts_co) >= 4:
            self.canvas.create_line(pts_co, fill=self.COLOR_CO, width=2, smooth=True)

        if self.variable_mode in ("GLP", "AMBOS") and len(pts_lpg) >= 4:
            self.canvas.create_line(pts_lpg, fill=self.COLOR_LPG, width=2, smooth=True)

        # Etiquetas de tiempo en eje X (Inicio, medio, final)
        time_format = "%H:%M:%S"
        t0_str = time.strftime(time_format, time.localtime(t_min / 1000.0))
        t1_str = time.strftime(time_format, time.localtime(t_max / 1000.0))
        self.canvas.create_text(pad_left, pad_top + plot_h + 12, text=t0_str, fill=self.COLOR_TEXT, anchor="w", font=("Consolas", 8))
        self.canvas.create_text(pad_left + plot_w, pad_top + plot_h + 12, text=t1_str, fill=self.COLOR_TEXT, anchor="e", font=("Consolas", 8))
