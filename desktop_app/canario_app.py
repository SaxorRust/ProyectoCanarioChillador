"""
CANARIO CHILLADOR - ESTACIÓN DE MONITOREO DE ESCRITORIO (DESKTOP)
Aplicación HMI profesional para supervisión de gases tóxicos y combustibles.
Integración completa con normas oficiales mexicanas: NOM-010-STPS, NOM-002-STPS, CENAPRED.
"""

import sys
import os
import time
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from typing import Dict, List, Optional

# Módulos del proyecto
from database import DatabaseManager
from analytics_engine import AnalyticsEngine, AnalyticsReport
from serial_client import SerialClient
from chart_canvas import TimeSeriesCanvas

def resource_path(relative_path):
    """Obtiene la ruta absoluta a recursos, compatible con PyInstaller bundle."""
    try:
        base_path = sys._MEIPASS
    except Exception:
        base_path = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base_path, relative_path)

class CanarioDesktopApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Canario Chillador - Estación de Monitoreo IoT")
        self.root.geometry("1100x750")
        self.root.minsize(950, 650)

        # Icono de la ventana
        ico_file = resource_path("canario.ico")
        if os.path.exists(ico_file):
            try:
                self.root.iconbitmap(ico_file)
            except Exception:
                pass

        # Paleta de colores Dark Theme industrial
        self.C_BG = "#0d1117"
        self.C_CARD = "#161b22"
        self.C_CARD_HOVER = "#21262d"
        self.C_BORDER = "#30363d"
        self.C_TEXT = "#f0f6fc"
        self.C_TEXT_MUTED = "#8b949e"
        self.C_BLUE = "#58a6ff"
        self.C_GREEN = "#238636"
        self.C_GREEN_BG = "#13231b"
        self.C_AMBER = "#d29922"
        self.C_AMBER_BG = "#2a1f0a"
        self.C_ORANGE = "#f0883e"
        self.C_RED = "#da3633"
        self.C_RED_BG = "#2b1114"

        self.root.configure(bg=self.C_BG)

        # Base de datos y estado
        self.db = DatabaseManager()
        self.session_data: List[Dict] = self.db.get_history(limit=2000)
        self.current_reading: Optional[Dict] = None

        self.peak_co = 0.0
        self.peak_lpg = 0.0
        for d in self.session_data:
            c = float(d.get("co", 0.0))
            l = float(d.get("lpg", 0.0))
            if c > self.peak_co: self.peak_co = c
            if l > self.peak_lpg: self.peak_lpg = l

        # Cliente Serial
        self.serial_client = SerialClient(
            on_telemetry=self._on_telemetry_thread,
            on_log=self._on_log_thread,
            on_state_change=self._on_state_change_thread
        )

        self._configure_styles()
        self._build_ui()
        self._refresh_ports()

        # Iniciar ticker periódico de actualización de gráficas y analítica
        self.root.after(1000, self._periodic_timer)

    def _configure_styles(self):
        style = ttk.Style()
        style.theme_use("clam")

        # Configuración general
        style.configure(".", background=self.C_BG, foreground=self.C_TEXT, font=("Segoe UI", 10))
        style.configure("TFrame", background=self.C_BG)
        style.configure("Card.TFrame", background=self.C_CARD, relief="flat")

        # Pestañas / Notebook
        style.configure("TNotebook", background=self.C_BG, borderwidth=0)
        style.configure("TNotebook.Tab", background=self.C_CARD, foreground=self.C_TEXT,
                        padding=[16, 8], font=("Segoe UI", 10, "bold"), borderwidth=0)
        style.map("TNotebook.Tab",
                  background=[("selected", self.C_CARD_HOVER), ("active", "#30363d")],
                  foreground=[("selected", self.C_BLUE)])

        # Botones
        style.configure("Primary.TButton", background="#1f6feb", foreground="#ffffff",
                        font=("Segoe UI", 10, "bold"), borderwidth=0, padding=[12, 6])
        style.map("Primary.TButton", background=[("active", "#388bfd"), ("disabled", "#21262d")])

        style.configure("Secondary.TButton", background=self.C_CARD_HOVER, foreground=self.C_TEXT,
                        font=("Segoe UI", 10), borderwidth=0, padding=[10, 5])
        style.map("Secondary.TButton", background=[("active", "#30363d")])

        style.configure("Danger.TButton", background=self.C_RED, foreground="#ffffff",
                        font=("Segoe UI", 10, "bold"), borderwidth=0, padding=[10, 5])
        style.map("Danger.TButton", background=[("active", "#f85149")])

        # Barras de Progreso
        style.configure("Co.Horizontal.TProgressbar", background=self.C_BLUE, troughcolor=self.C_CARD_HOVER, borderwidth=0)
        style.configure("Lpg.Horizontal.TProgressbar", background=self.C_ORANGE, troughcolor=self.C_CARD_HOVER, borderwidth=0)
        style.configure("Mq7.Horizontal.TProgressbar", background=self.C_AMBER, troughcolor=self.C_CARD_HOVER, borderwidth=0)

    def _build_ui(self):
        # 1. BARRA SUPERIOR DE CONEXIÓN
        self.top_bar = tk.Frame(self.root, bg=self.C_CARD, height=65, highlightthickness=1, highlightbackground=self.C_BORDER)
        self.top_bar.pack(fill=tk.X, side=tk.TOP)

        # Logo / Título
        title_box = tk.Frame(self.top_bar, bg=self.C_CARD)
        title_box.pack(side=tk.LEFT, padx=15, pady=8)

        lbl_app = tk.Label(title_box, text="EL CANARIO CHILLADOR", bg=self.C_CARD, fg=self.C_TEXT,
                           font=("Segoe UI", 13, "bold"))
        lbl_app.pack(anchor="w")
        lbl_sub = tk.Label(title_box, text="Estación IoT de Seguridad Atmosférica & Higiene Industrial",
                           bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 8))
        lbl_sub.pack(anchor="w")

        # Controles de Conexión a la derecha
        conn_box = tk.Frame(self.top_bar, bg=self.C_CARD)
        conn_box.pack(side=tk.RIGHT, padx=15, pady=10)

        self.lbl_badge = tk.Label(conn_box, text="● OFFLINE", bg=self.C_CARD, fg="#f85149", font=("Segoe UI", 10, "bold"))
        self.lbl_badge.pack(side=tk.LEFT, padx=(0, 15))

        tk.Label(conn_box, text="Puerto:", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=(0, 4))
        self.cb_ports = ttk.Combobox(conn_box, state="readonly", width=30)
        self.cb_ports.pack(side=tk.LEFT, padx=(0, 6))

        btn_refresh = tk.Button(conn_box, text="↻", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                                activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9, "bold"),
                                command=self._refresh_ports, padx=6)
        btn_refresh.pack(side=tk.LEFT, padx=(0, 10))

        tk.Label(conn_box, text="Baud:", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=(0, 4))
        self.cb_baud = ttk.Combobox(conn_box, state="readonly", width=8, values=["115200", "9600", "57600"])
        self.cb_baud.set("115200")
        self.cb_baud.pack(side=tk.LEFT, padx=(0, 10))

        self.btn_connect = tk.Button(conn_box, text="Conectar", bg="#238636", fg="#ffffff",
                                     activebackground="#2ea043", relief="flat", font=("Segoe UI", 10, "bold"),
                                     command=self._toggle_connection, padx=14, pady=3)
        self.btn_connect.pack(side=tk.LEFT)

        # 2. NOTEBOOK / PESTAÑAS PRINCIPALES
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=12, pady=10)

        # Pestañas
        self.tab_l1 = ttk.Frame(self.notebook)
        self.tab_l2 = ttk.Frame(self.notebook)
        self.tab_l3 = ttk.Frame(self.notebook)
        self.tab_l4 = ttk.Frame(self.notebook)
        self.tab_console = ttk.Frame(self.notebook)

        self.notebook.add(self.tab_l1, text="  Nivel 1: Alerta Rápida  ")
        self.notebook.add(self.tab_l2, text="  Nivel 2: Instrumentación  ")
        self.notebook.add(self.tab_l3, text="  Nivel 3: Gráfica Temporal  ")
        self.notebook.add(self.tab_l4, text="  Nivel 4: Analítica & Auditoría  ")
        self.notebook.add(self.tab_console, text="  Consola Serial  ")

        self._build_tab_l1()
        self._build_tab_l2()
        self._build_tab_l3()
        self._build_tab_l4()
        self._build_tab_console()

        # 3. BARRA DE ESTADO INFERIOR
        self.status_bar = tk.Frame(self.root, bg=self.C_CARD, height=26, highlightthickness=1, highlightbackground=self.C_BORDER)
        self.status_bar.pack(fill=tk.X, side=tk.BOTTOM)

        self.lbl_status = tk.Label(self.status_bar, text="Listo. Selecciona el puerto COM del ESP32 para iniciar.",
                                   bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 8))
        self.lbl_status.pack(side=tk.LEFT, padx=12)

        self.lbl_db_count = tk.Label(self.status_bar, text=f"Registros en BD: {self.db.get_count()}",
                                     bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 8))
        self.lbl_db_count.pack(side=tk.RIGHT, padx=12)

    # -------------------------------------------------------------------------
    # TAB 1: ALERTA RÁPIDA (NIVEL 1)
    # -------------------------------------------------------------------------
    def _build_tab_l1(self):
        container = tk.Frame(self.tab_l1, bg=self.C_BG)
        container.pack(fill=tk.BOTH, expand=True, padx=20, pady=15)

        # HERO ALERT CARD
        self.card_hero = tk.Frame(container, bg=self.C_GREEN_BG, highlightthickness=2, highlightbackground=self.C_GREEN)
        self.card_hero.pack(fill=tk.X, pady=(0, 20))

        hero_inner = tk.Frame(self.card_hero, bg=self.C_GREEN_BG)
        hero_inner.pack(fill=tk.X, padx=20, pady=18)

        self.lbl_hero_title = tk.Label(hero_inner, text="ESTADO SEGURO", bg=self.C_GREEN_BG, fg="#3fb950",
                                       font=("Segoe UI", 20, "bold"))
        self.lbl_hero_title.pack(anchor="w")

        self.lbl_hero_desc = tk.Label(hero_inner, text="Atmósfera nominal inocua. Concentraciones dentro de los límites de higiene industrial.",
                                      bg=self.C_GREEN_BG, fg=self.C_TEXT, font=("Segoe UI", 11))
        self.lbl_hero_desc.pack(anchor="w", pady=(4, 0))

        # Badges complementarios en la tarjeta Hero
        badges_frame = tk.Frame(hero_inner, bg=self.C_GREEN_BG)
        badges_frame.pack(anchor="w", pady=(12, 0))

        self.lbl_hero_mq7 = tk.Label(badges_frame, text="MQ-7: MEDICIÓN (1.4V)", bg="#161b22", fg=self.C_BLUE,
                                     font=("Segoe UI", 9, "bold"), padx=10, pady=3)
        self.lbl_hero_mq7.pack(side=tk.LEFT, padx=(0, 10))

        self.lbl_hero_mute = tk.Label(badges_frame, text="CHILLIDO ACTIVO", bg="#161b22", fg=self.C_TEXT_MUTED,
                                      font=("Segoe UI", 9, "bold"), padx=10, pady=3)
        self.lbl_hero_mute.pack(side=tk.LEFT, padx=(0, 10))

        self.lbl_hero_predictive = tk.Label(badges_frame, text="ATMÓSFERA ESTABLE (𝑑PPM/𝑑t ≈ 0)", bg="#161b22", fg="#3fb950",
                                            font=("Segoe UI", 9, "bold"), padx=10, pady=3)
        self.lbl_hero_predictive.pack(side=tk.LEFT)

        # TARJETAS DE GASES (CO Y GLP)
        kpi_grid = tk.Frame(container, bg=self.C_BG)
        kpi_grid.pack(fill=tk.BOTH, expand=True)
        kpi_grid.columnconfigure(0, weight=1)
        kpi_grid.columnconfigure(1, weight=1)

        # Card CO
        card_co = tk.Frame(kpi_grid, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_co.grid(row=0, column=0, sticky="nsew", padx=(0, 10), pady=5)

        tk.Label(card_co, text="MONÓXIDO DE CARBONO (CO)", bg=self.C_CARD, fg=self.C_BLUE,
                 font=("Segoe UI", 12, "bold")).pack(anchor="w", padx=20, pady=(18, 4))
        tk.Label(card_co, text="Sensor Catalítico MQ-7 | Gas Asfixiante Químico", bg=self.C_CARD, fg=self.C_TEXT_MUTED,
                 font=("Segoe UI", 9)).pack(anchor="w", padx=20)

        self.lbl_l1_co_val = tk.Label(card_co, text="0.0 PPM", bg=self.C_CARD, fg=self.C_TEXT,
                                      font=("Consolas", 42, "bold"))
        self.lbl_l1_co_val.pack(anchor="w", padx=20, pady=15)

        tk.Label(card_co, text="• NOM-010-STPS (VLE-PPT): 25.0 PPM\n• CENAPRED (Peligro Inmediato): 100.0 PPM",
                 bg=self.C_CARD, fg=self.C_TEXT_MUTED, justify=tk.LEFT, font=("Segoe UI", 9)).pack(anchor="w", padx=20, pady=(0, 15))

        # Card GLP
        card_lpg = tk.Frame(kpi_grid, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_lpg.grid(row=0, column=1, sticky="nsew", padx=(10, 0), pady=5)

        tk.Label(card_lpg, text="GAS COMBUSTIBLE / HUMO (GLP)", bg=self.C_CARD, fg=self.C_ORANGE,
                 font=("Segoe UI", 12, "bold")).pack(anchor="w", padx=20, pady=(18, 4))
        tk.Label(card_lpg, text="Sensor Semiconductivo MQ-2 | Butano / Propano / Metano", bg=self.C_CARD, fg=self.C_TEXT_MUTED,
                 font=("Segoe UI", 9)).pack(anchor="w", padx=20)

        self.lbl_l1_lpg_val = tk.Label(card_lpg, text="0 PPM", bg=self.C_CARD, fg=self.C_TEXT,
                                       font=("Consolas", 42, "bold"))
        self.lbl_l1_lpg_val.pack(anchor="w", padx=20, pady=15)

        tk.Label(card_lpg, text="• NOM-010-STPS (VLE-CT): 1000.0 PPM (~5% LEL)\n• NOM-002-STPS (Peligro Evacuación): 3200.0 PPM (20% LEL)",
                 bg=self.C_CARD, fg=self.C_TEXT_MUTED, justify=tk.LEFT, font=("Segoe UI", 9)).pack(anchor="w", padx=20, pady=(0, 15))

    # -------------------------------------------------------------------------
    # TAB 2: INSTRUMENTACIÓN Y MANDOS (NIVEL 2)
    # -------------------------------------------------------------------------
    def _build_tab_l2(self):
        container = tk.Frame(self.tab_l2, bg=self.C_BG)
        container.pack(fill=tk.BOTH, expand=True, padx=20, pady=15)

        left_col = tk.Frame(container, bg=self.C_BG)
        left_col.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 12))

        right_col = tk.Frame(container, bg=self.C_BG, width=320)
        right_col.pack(side=tk.RIGHT, fill=tk.BOTH, padx=(12, 0))

        # --- Medidor Detallado CO ---
        card_m_co = tk.Frame(left_col, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_m_co.pack(fill=tk.X, pady=(0, 12))

        f_co_head = tk.Frame(card_m_co, bg=self.C_CARD)
        f_co_head.pack(fill=tk.X, padx=16, pady=(12, 4))
        tk.Label(f_co_head, text="Concentración de CO (0 - 150 PPM)", bg=self.C_CARD, fg=self.C_BLUE,
                 font=("Segoe UI", 11, "bold")).pack(side=tk.LEFT)
        self.lbl_l2_peak_co = tk.Label(f_co_head, text="Pico: 0.0 ppm", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9))
        self.lbl_l2_peak_co.pack(side=tk.RIGHT)

        self.bar_co = ttk.Progressbar(card_m_co, style="Co.Horizontal.TProgressbar", maximum=150)
        self.bar_co.pack(fill=tk.X, padx=16, pady=8)

        self.lbl_l2_val_co = tk.Label(card_m_co, text="0.0 PPM", bg=self.C_CARD, fg=self.C_TEXT, font=("Consolas", 14, "bold"))
        self.lbl_l2_val_co.pack(anchor="w", padx=16, pady=(0, 12))

        # --- Medidor Detallado GLP ---
        card_m_lpg = tk.Frame(left_col, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_m_lpg.pack(fill=tk.X, pady=(0, 12))

        f_lpg_head = tk.Frame(card_m_lpg, bg=self.C_CARD)
        f_lpg_head.pack(fill=tk.X, padx=16, pady=(12, 4))
        tk.Label(f_lpg_head, text="Concentración de GLP (0 - 3500 PPM)", bg=self.C_CARD, fg=self.C_ORANGE,
                 font=("Segoe UI", 11, "bold")).pack(side=tk.LEFT)
        self.lbl_l2_peak_lpg = tk.Label(f_lpg_head, text="Pico: 0 ppm", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9))
        self.lbl_l2_peak_lpg.pack(side=tk.RIGHT)

        self.bar_lpg = ttk.Progressbar(card_m_lpg, style="Lpg.Horizontal.TProgressbar", maximum=3500)
        self.bar_lpg.pack(fill=tk.X, padx=16, pady=8)

        self.lbl_l2_val_lpg = tk.Label(card_m_lpg, text="0 PPM", bg=self.C_CARD, fg=self.C_TEXT, font=("Consolas", 14, "bold"))
        self.lbl_l2_val_lpg.pack(anchor="w", padx=16, pady=(0, 12))

        # --- Ciclo Térmico MQ-7 ---
        card_m_mq7 = tk.Frame(left_col, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_m_mq7.pack(fill=tk.X)

        f_mq7_head = tk.Frame(card_m_mq7, bg=self.C_CARD)
        f_mq7_head.pack(fill=tk.X, padx=16, pady=(12, 4))
        self.lbl_l2_mq7_title = tk.Label(f_mq7_head, text="MQ-7: FASE PURGA (5.0V)", bg=self.C_CARD, fg=self.C_AMBER,
                                         font=("Segoe UI", 11, "bold"))
        self.lbl_l2_mq7_title.pack(side=tk.LEFT)
        self.lbl_l2_mq7_sec = tk.Label(f_mq7_head, text="0 / 60 s", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9))
        self.lbl_l2_mq7_sec.pack(side=tk.RIGHT)

        self.bar_mq7 = ttk.Progressbar(card_m_mq7, style="Mq7.Horizontal.TProgressbar", maximum=60)
        self.bar_mq7.pack(fill=tk.X, padx=16, pady=8)

        self.lbl_l2_mq7_desc = tk.Label(card_m_mq7, text="Evaporando humedad e impurezas sobre la capa de SnO2 a 5.0V constante.",
                                        bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9))
        self.lbl_l2_mq7_desc.pack(anchor="w", padx=16, pady=(0, 12))

        # --- PANEL DERECHO: AMBIENTAL Y COMANDOS REMOTOS ---
        # Card Ambiental DHT11
        card_env = tk.Frame(right_col, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_env.pack(fill=tk.X, pady=(0, 12))

        tk.Label(card_env, text="CONDICIONES AMBIENTALES (DHT11)", bg=self.C_CARD, fg=self.C_TEXT,
                 font=("Segoe UI", 10, "bold")).pack(anchor="w", padx=16, pady=(12, 8))

        f_env_vals = tk.Frame(card_env, bg=self.C_CARD)
        f_env_vals.pack(fill=tk.X, padx=16, pady=(0, 12))

        self.lbl_l2_temp = tk.Label(f_env_vals, text="--.- °C", bg=self.C_CARD, fg=self.C_TEXT, font=("Consolas", 18, "bold"))
        self.lbl_l2_temp.pack(side=tk.LEFT, padx=(0, 20))
        self.lbl_l2_hum = tk.Label(f_env_vals, text="-- % RH", bg=self.C_CARD, fg=self.C_TEXT, font=("Consolas", 18, "bold"))
        self.lbl_l2_hum.pack(side=tk.LEFT)

        # Card Comandos de Control Remoto
        card_cmds = tk.Frame(right_col, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_cmds.pack(fill=tk.BOTH, expand=True)

        tk.Label(card_cmds, text="CENTRO DE MANDO REMOTO", bg=self.C_CARD, fg=self.C_TEXT,
                 font=("Segoe UI", 10, "bold")).pack(anchor="w", padx=16, pady=(12, 10))

        btn_w = 26
        self.btn_cmd_mute = tk.Button(card_cmds, text="MUTE BUZZER (60s)", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                                      activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9, "bold"),
                                      command=lambda: self._send_cmd("MUTE"), width=btn_w)
        self.btn_cmd_mute.pack(pady=4, padx=16, fill=tk.X)

        btn_vista = tk.Button(card_cmds, text="CAMBIAR VISTA OLED", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                              activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9),
                              command=lambda: self._send_cmd("VISTA"), width=btn_w)
        btn_vista.pack(pady=4, padx=16, fill=tk.X)

        btn_ping = tk.Button(card_cmds, text="PING DE ENLACE", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                             activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9),
                             command=lambda: self._send_cmd("PING"), width=btn_w)
        btn_ping.pack(pady=4, padx=16, fill=tk.X)

        btn_volts = tk.Button(card_cmds, text="DIAGNÓSTICO VOLTAJES", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                              activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9),
                              command=lambda: self._send_cmd("VOLTS"), width=btn_w)
        btn_volts.pack(pady=4, padx=16, fill=tk.X)

        btn_calib = tk.Button(card_cmds, text="CALIBRAR BASE CERO (CALIB)", bg=self.C_CARD_HOVER, fg=self.C_AMBER,
                              activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9, "bold"),
                              command=lambda: self._send_cmd("CALIB"), width=btn_w)
        btn_calib.pack(pady=4, padx=16, fill=tk.X)

    # -------------------------------------------------------------------------
    # TAB 3: GRÁFICA TEMPORAL (NIVEL 3)
    # -------------------------------------------------------------------------
    def _build_tab_l3(self):
        container = tk.Frame(self.tab_l3, bg=self.C_BG)
        container.pack(fill=tk.BOTH, expand=True, padx=15, pady=12)

        # Barra de Controles de la Gráfica
        toolbar = tk.Frame(container, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        toolbar.pack(fill=tk.X, pady=(0, 10), ipady=4)

        tk.Label(toolbar, text="Variable:", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=(12, 4))
        self.btn_g_both = tk.Button(toolbar, text="CO y GLP", bg=self.C_BLUE, fg="#ffffff", relief="flat",
                                    font=("Segoe UI", 8, "bold"), command=lambda: self._set_chart_var("AMBOS"))
        self.btn_g_both.pack(side=tk.LEFT, padx=3)

        self.btn_g_co = tk.Button(toolbar, text="Solo CO", bg=self.C_CARD_HOVER, fg=self.C_TEXT, relief="flat",
                                  font=("Segoe UI", 8), command=lambda: self._set_chart_var("CO"))
        self.btn_g_co.pack(side=tk.LEFT, padx=3)

        self.btn_g_lpg = tk.Button(toolbar, text="Solo GLP", bg=self.C_CARD_HOVER, fg=self.C_TEXT, relief="flat",
                                   font=("Segoe UI", 8), command=lambda: self._set_chart_var("GLP"))
        self.btn_g_lpg.pack(side=tk.LEFT, padx=3)

        tk.Label(toolbar, text="|  Ventana:", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=(15, 4))
        self.btn_w_2m = tk.Button(toolbar, text="2 min", bg=self.C_BLUE, fg="#ffffff", relief="flat",
                                  font=("Segoe UI", 8, "bold"), command=lambda: self._set_chart_window("2m"))
        self.btn_w_2m.pack(side=tk.LEFT, padx=3)

        self.btn_w_10m = tk.Button(toolbar, text="10 min", bg=self.C_CARD_HOVER, fg=self.C_TEXT, relief="flat",
                                   font=("Segoe UI", 8), command=lambda: self._set_chart_window("10m"))
        self.btn_w_10m.pack(side=tk.LEFT, padx=3)

        self.btn_w_all = tk.Button(toolbar, text="Todo el Historial", bg=self.C_CARD_HOVER, fg=self.C_TEXT, relief="flat",
                                   font=("Segoe UI", 8), command=lambda: self._set_chart_window("all"))
        self.btn_w_all.pack(side=tk.LEFT, padx=3)

        # Leyenda a la derecha
        leg_box = tk.Frame(toolbar, bg=self.C_CARD)
        leg_box.pack(side=tk.RIGHT, padx=15)
        tk.Label(leg_box, text="■ CO (MQ-7)", bg=self.C_CARD, fg=self.C_BLUE, font=("Segoe UI", 8, "bold")).pack(side=tk.LEFT, padx=6)
        tk.Label(leg_box, text="■ GLP (MQ-2)", bg=self.C_CARD, fg=self.C_ORANGE, font=("Segoe UI", 8, "bold")).pack(side=tk.LEFT, padx=6)
        tk.Label(leg_box, text="-- NOM-010 (25 ppm)", bg=self.C_CARD, fg=self.C_AMBER, font=("Segoe UI", 8)).pack(side=tk.LEFT, padx=6)

        # Canvas interactivo
        self.chart = TimeSeriesCanvas(container, bg="#0d1117", highlightthickness=1, highlightbackground=self.C_BORDER)
        self.chart.pack(fill=tk.BOTH, expand=True)
        self.chart.set_data(self.session_data)

    def _set_chart_var(self, var_mode: str):
        self.chart.set_variable_mode(var_mode)
        self.btn_g_both.configure(bg=self.C_BLUE if var_mode == "AMBOS" else self.C_CARD_HOVER,
                                  fg="#ffffff" if var_mode == "AMBOS" else self.C_TEXT)
        self.btn_g_co.configure(bg=self.C_BLUE if var_mode == "CO" else self.C_CARD_HOVER,
                                fg="#ffffff" if var_mode == "CO" else self.C_TEXT)
        self.btn_g_lpg.configure(bg=self.C_BLUE if var_mode == "GLP" else self.C_CARD_HOVER,
                                 fg="#ffffff" if var_mode == "GLP" else self.C_TEXT)

    def _set_chart_window(self, win_mode: str):
        self.chart.set_window_mode(win_mode)
        self.btn_w_2m.configure(bg=self.C_BLUE if win_mode == "2m" else self.C_CARD_HOVER,
                                fg="#ffffff" if win_mode == "2m" else self.C_TEXT)
        self.btn_w_10m.configure(bg=self.C_BLUE if win_mode == "10m" else self.C_CARD_HOVER,
                                 fg="#ffffff" if win_mode == "10m" else self.C_TEXT)
        self.btn_w_all.configure(bg=self.C_BLUE if win_mode == "all" else self.C_CARD_HOVER,
                                 fg="#ffffff" if win_mode == "all" else self.C_TEXT)

    # -------------------------------------------------------------------------
    # TAB 4: ANALÍTICA INDUSTRIAL Y AUDITORÍA (NIVEL 4)
    # -------------------------------------------------------------------------
    def _build_tab_l4(self):
        container = tk.Frame(self.tab_l4, bg=self.C_BG)
        container.pack(fill=tk.BOTH, expand=True, padx=20, pady=15)

        # FILA 1: KPIs Matemáticos y Normativos
        grid_kpi = tk.Frame(container, bg=self.C_BG)
        grid_kpi.pack(fill=tk.X, pady=(0, 12))
        for col in range(4):
            grid_kpi.columnconfigure(col, weight=1)

        # TWA CO
        c_twa = tk.Frame(grid_kpi, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        c_twa.grid(row=0, column=0, sticky="nsew", padx=4)
        tk.Label(c_twa, text="TWA CO (8h / SESIÓN)", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 8)).pack(padx=10, pady=(8, 2))
        self.lbl_an_twa = tk.Label(c_twa, text="0.0 PPM", bg=self.C_CARD, fg=self.C_BLUE, font=("Consolas", 15, "bold"))
        self.lbl_an_twa.pack(padx=10, pady=(0, 8))

        # % NOM-010-STPS
        c_nom = tk.Frame(grid_kpi, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        c_nom.grid(row=0, column=1, sticky="nsew", padx=4)
        tk.Label(c_nom, text="EXP. NOM-010-STPS", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 8)).pack(padx=10, pady=(8, 2))
        self.lbl_an_nom = tk.Label(c_nom, text="0.0 % (25 ppm)", bg=self.C_CARD, fg="#3fb950", font=("Consolas", 15, "bold"))
        self.lbl_an_nom.pack(padx=10, pady=(0, 8))

        # Derivada dCO/dt
        c_der = tk.Frame(grid_kpi, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        c_der.grid(row=0, column=2, sticky="nsew", padx=4)
        tk.Label(c_der, text="TASA dPPM/dt (CO)", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 8)).pack(padx=10, pady=(8, 2))
        self.lbl_an_der = tk.Label(c_der, text="+0.00 PPM/s", bg=self.C_CARD, fg=self.C_TEXT, font=("Consolas", 15, "bold"))
        self.lbl_an_der.pack(padx=10, pady=(0, 8))

        # Correlación Pearson
        c_pea = tk.Frame(grid_kpi, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        c_pea.grid(row=0, column=3, sticky="nsew", padx=4)
        tk.Label(c_pea, text="CORRELACIÓN PEARSON", bg=self.C_CARD, fg=self.C_TEXT_MUTED, font=("Segoe UI", 8)).pack(padx=10, pady=(8, 2))
        self.lbl_an_pea = tk.Label(c_pea, text="r_T: 0.00 | r_RH: 0.00", bg=self.C_CARD, fg=self.C_TEXT, font=("Consolas", 13, "bold"))
        self.lbl_an_pea.pack(padx=10, pady=(0, 8))

        # FILA 2: Diagnóstico y Conclusión Predictiva
        card_diag = tk.Frame(container, bg=self.C_CARD, highlightthickness=1, highlightbackground=self.C_BORDER)
        card_diag.pack(fill=tk.BOTH, expand=True, pady=(0, 12))

        tk.Label(card_diag, text="INFERENCIA PREDICTIVA Y DIAGNÓSTICO DE HIGIENE INDUSTRIAL",
                 bg=self.C_CARD, fg=self.C_TEXT, font=("Segoe UI", 10, "bold")).pack(anchor="w", padx=16, pady=(12, 6))

        self.txt_conclusion = tk.Text(card_diag, bg="#0d1117", fg=self.C_TEXT, relief="flat",
                                      font=("Segoe UI", 10), wrap=tk.WORD, height=8, padx=12, pady=10)
        self.txt_conclusion.pack(fill=tk.BOTH, expand=True, padx=16, pady=(0, 12))
        self.txt_conclusion.insert(tk.END, "Esperando datos suficientes para evaluar la atmósfera...")
        self.txt_conclusion.configure(state="disabled")

        # FILA 3: Botones de Exportación, Importación y Auditoría
        act_bar = tk.Frame(container, bg=self.C_BG)
        act_bar.pack(fill=tk.X)

        btn_import = tk.Button(act_bar, text="📥 Sincronizar / Importar Bitácora", bg="#1f6feb", fg="#ffffff",
                               activebackground="#388bfd", relief="flat", font=("Segoe UI", 9, "bold"),
                               command=self._import_file, padx=14, pady=5)
        btn_import.pack(side=tk.LEFT, padx=(0, 8))

        btn_csv = tk.Button(act_bar, text="Exportar CSV", bg="#238636", fg="#ffffff",
                            activebackground="#2ea043", relief="flat", font=("Segoe UI", 9),
                            command=self._export_csv, padx=14, pady=5)
        btn_csv.pack(side=tk.LEFT, padx=(0, 8))

        btn_json = tk.Button(act_bar, text="Exportar JSON", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                             activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9),
                             command=self._export_json, padx=14, pady=5)
        btn_json.pack(side=tk.LEFT, padx=(0, 8))

        btn_clear = tk.Button(act_bar, text="Borrar Historial de BD", bg=self.C_CARD_HOVER, fg="#f85149",
                              activebackground=self.C_BORDER, relief="flat", font=("Segoe UI", 9),
                              command=self._clear_history, padx=14, pady=5)
        btn_clear.pack(side=tk.RIGHT)

    # -------------------------------------------------------------------------
    # TAB 5: CONSOLA SERIAL
    # -------------------------------------------------------------------------
    def _build_tab_console(self):
        container = tk.Frame(self.tab_console, bg=self.C_BG)
        container.pack(fill=tk.BOTH, expand=True, padx=15, pady=12)

        top_ctrl = tk.Frame(container, bg=self.C_BG)
        top_ctrl.pack(fill=tk.X, pady=(0, 8))

        self.auto_scroll_var = tk.BooleanVar(value=True)
        chk = tk.Checkbutton(top_ctrl, text="Auto-desplazamiento", variable=self.auto_scroll_var,
                             bg=self.C_BG, fg=self.C_TEXT, selectcolor=self.C_CARD, activebackground=self.C_BG)
        chk.pack(side=tk.LEFT)

        btn_clear_log = tk.Button(top_ctrl, text="Limpiar Consola", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                                  relief="flat", font=("Segoe UI", 8), command=self._clear_console)
        btn_clear_log.pack(side=tk.RIGHT, padx=4)

        btn_copy_log = tk.Button(top_ctrl, text="Copiar al Portapapeles", bg=self.C_CARD_HOVER, fg=self.C_TEXT,
                                 relief="flat", font=("Segoe UI", 8), command=self._copy_console)
        btn_copy_log.pack(side=tk.RIGHT, padx=4)

        self.txt_console = tk.Text(container, bg="#0d1117", fg="#c9d1d9", font=("Consolas", 9),
                                   relief="flat", wrap=tk.CHAR, padx=10, pady=10)
        self.txt_console.pack(fill=tk.BOTH, expand=True)

    # -------------------------------------------------------------------------
    # MÉTODOS DE COMUNICACIÓN Y HILOS
    # -------------------------------------------------------------------------
    def _refresh_ports(self):
        ports = SerialClient.list_ports()
        device_list = [p[0] for p in ports]
        display_list = [p[1] for p in ports]

        self.cb_ports["values"] = display_list
        if display_list:
            # Preferir Silicon Labs CP210x o primer puerto disponible
            chosen_idx = 0
            for i, desc in enumerate(display_list):
                if "CP210" in desc or "CH340" in desc or "USB" in desc:
                    chosen_idx = i
                    break
            self.cb_ports.current(chosen_idx)
        else:
            self.cb_ports.set("Sin puertos detectados")

    def _toggle_connection(self):
        if self.serial_client.is_connected():
            self.serial_client.disconnect()
        else:
            sel = self.cb_ports.get()
            if not sel or "Sin puertos" in sel:
                messagebox.showwarning("Conexión", "Por favor selecciona un puerto COM válido.")
                return
            port_name = sel.split(" - ")[0].strip()
            try:
                baud = int(self.cb_baud.get())
            except ValueError:
                baud = 115200

            self.lbl_status.config(text=f"Intentando abrir {port_name} a {baud} bps...")
            self.serial_client.connect(port_name, baud)

    def _send_cmd(self, cmd: str):
        if not self.serial_client.is_connected():
            messagebox.showinfo("Centro de Mando", "Conecta el ESP32 antes de enviar comandos.")
            return
        ok = self.serial_client.send_command(cmd)
        if ok:
            self.lbl_status.config(text=f"Comando '{cmd}' transmitido al ESP32.")

    def _on_state_change_thread(self, is_connected: bool, message: str):
        self.root.after(0, lambda: self._update_connection_state(is_connected, message))

    def _update_connection_state(self, is_connected: bool, message: str):
        if is_connected:
            self.lbl_badge.config(text="● ONLINE", fg="#3fb950")
            self.btn_connect.config(text="Desconectar", bg=self.C_RED, activebackground="#f85149")
            self.lbl_status.config(text=f"Enlace activo: {message}")
        else:
            self.lbl_badge.config(text="● OFFLINE", fg="#f85149")
            self.btn_connect.config(text="Conectar", bg="#238636", activebackground="#2ea043")
            self.lbl_status.config(text=message)

    def _on_log_thread(self, line: str):
        self.root.after(0, lambda: self._append_console(line))

    def _append_console(self, line: str):
        t_str = time.strftime("%H:%M:%S")
        self.txt_console.insert(tk.END, f"[{t_str}] {line}\n")
        # Limitar longitud de texto en consola para no degradar memoria
        line_count = int(self.txt_console.index('end-1c').split('.')[0])
        if line_count > 3000:
            self.txt_console.delete("1.0", "500.0")
        if self.auto_scroll_var.get():
            self.txt_console.see(tk.END)

    def _clear_console(self):
        self.txt_console.delete("1.0", tk.END)

    def _copy_console(self):
        text = self.txt_console.get("1.0", tk.END)
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        messagebox.showinfo("Consola", "Registro copiado al portapapeles.")

    def _on_telemetry_thread(self, data: dict, raw_line: str):
        # Guardar en SQLite
        try:
            self.db.insert(data, raw_line)
        except Exception:
            pass

        # Agregar a datos de sesión con marca de tiempo actual en milisegundos
        data["timestamp"] = int(time.time() * 1000)
        self.root.after(0, lambda: self._process_telemetry_ui(data))

    def _process_telemetry_ui(self, data: dict):
        self.current_reading = data
        self.session_data.append(data)
        if len(self.session_data) > 5000:
            self.session_data.pop(0)

        co = float(data.get("co", 0.0))
        lpg = float(data.get("lpg", 0.0))
        if co > self.peak_co: self.peak_co = co
        if lpg > self.peak_lpg: self.peak_lpg = lpg

        # 1. RENDERIZADO NIVEL 1
        alerta = str(data.get("alerta", "NORMAL")).upper()
        fase = str(data.get("fase", "PURGA")).upper()
        mute = bool(data.get("mute", False))
        seg = int(data.get("seg", 0))

        if alerta == "PRECALENTANDO":
            self.card_hero.configure(bg=self.C_AMBER_BG, highlightbackground=self.C_AMBER)
            self.lbl_hero_title.configure(text="PRECALENTANDO SILICIO", fg=self.C_AMBER, bg=self.C_AMBER_BG)
            self.lbl_hero_desc.configure(
                text=f"Estabilizando filamentos térmicos MQ-2 y MQ-7 ({seg}/45 s). Por favor mantenga en aire limpio ventilado.",
                bg=self.C_AMBER_BG
            )
        elif alerta == "PELIGRO":
            self.card_hero.configure(bg=self.C_RED_BG, highlightbackground=self.C_RED)
            self.lbl_hero_title.configure(text="¡PELIGRO: EVACUAR!", fg="#f85149", bg=self.C_RED_BG)
            self.lbl_hero_desc.configure(
                text="¡Nivel crítico de gas detectado! Alarma sonora física activada.",
                bg=self.C_RED_BG
            )
        elif alerta == "PRECAUCION":
            self.card_hero.configure(bg=self.C_AMBER_BG, highlightbackground=self.C_AMBER)
            self.lbl_hero_title.configure(text="PRECAUCIÓN / GAS MODERADO", fg=self.C_AMBER, bg=self.C_AMBER_BG)
            self.lbl_hero_desc.configure(
                text="Gas en umbral preventivo (NOM-010-STPS) o ciclo térmico transitorio.",
                bg=self.C_AMBER_BG
            )
        else:
            self.card_hero.configure(bg=self.C_GREEN_BG, highlightbackground=self.C_GREEN)
            self.lbl_hero_title.configure(text="ESTADO SEGURO", fg="#3fb950", bg=self.C_GREEN_BG)
            self.lbl_hero_desc.configure(
                text="Atmósfera nominal inocua. Todo el sistema opera con normalidad.",
                bg=self.C_GREEN_BG
            )

        # Badges Hero
        if alerta == "PRECALENTANDO":
            self.lbl_hero_mq7.config(text=f"PRECALENTANDO ({seg}/45s)", fg=self.C_AMBER)
        else:
            self.lbl_hero_mq7.config(
                text="MQ-7: FASE PURGA (5.0V)" if fase == "PURGA" else "MQ-7: MEDICIÓN (1.4V)",
                fg=self.C_AMBER if fase == "PURGA" else self.C_BLUE
            )

        self.lbl_hero_mute.config(
            text="SILENCIADO (MUTE)" if mute else "CHILLIDO ACTIVO",
            fg=self.C_AMBER if mute else self.C_TEXT_MUTED
        )
        self.btn_cmd_mute.config(text="QUITAR MUTE" if mute else "MUTE BUZZER (60s)")

        self.lbl_l1_co_val.config(text=f"{co:.1f} PPM")
        self.lbl_l1_lpg_val.config(text=f"{lpg:.0f} PPM")

        # 2. RENDERIZADO NIVEL 2
        self.bar_co["value"] = min(150, co)
        self.lbl_l2_val_co.config(text=f"{co:.1f} PPM")
        self.lbl_l2_peak_co.config(text=f"Pico: {self.peak_co:.1f} ppm")

        self.bar_lpg["value"] = min(3500, lpg)
        self.lbl_l2_val_lpg.config(text=f"{lpg:.0f} PPM")
        self.lbl_l2_peak_lpg.config(text=f"Pico: {self.peak_lpg:.0f} ppm")

        if alerta == "PRECALENTANDO":
            self.lbl_l2_mq7_title.config(text="MQ-7 / MQ-2: PRECALENTANDO SILICIO", fg=self.C_AMBER)
            self.lbl_l2_mq7_sec.config(text=f"{seg} / 45 s")
            self.bar_mq7.configure(maximum=45)
            self.bar_mq7["value"] = min(45, seg)
            self.lbl_l2_mq7_desc.config(text="Estabilizando filamentos térmicos en aire limpio antes de calibrar la línea base.")
        else:
            seg_max = 60 if fase == "PURGA" else 90
            self.lbl_l2_mq7_title.config(
                text="MQ-7: FASE PURGA (5.0V)" if fase == "PURGA" else "MQ-7: FASE MEDICIÓN (1.4V)",
                fg=self.C_AMBER if fase == "PURGA" else self.C_BLUE
            )
            self.lbl_l2_mq7_sec.config(text=f"{seg} / {seg_max} s")
            self.bar_mq7.configure(maximum=seg_max)
            self.bar_mq7["value"] = min(seg_max, seg)
            self.lbl_l2_mq7_desc.config(
                text="Evaporando humedad e impurezas sobre la capa de SnO2 a 5.0V constante." if fase == "PURGA"
                     else "Sensado cuantitativo catalítico a 1.4V RMS para adsorción selectiva de CO."
            )

        temp = float(data.get("temp", 0.0))
        hum = float(data.get("hum", 0.0))
        self.lbl_l2_temp.config(text=f"{temp:.1f} °C")
        self.lbl_l2_hum.config(text=f"{hum:.0f} % RH")

        self.lbl_db_count.config(text=f"Registros en BD: {self.db.get_count()}")

    def _periodic_timer(self):
        """Actualiza la gráfica y el cálculo analítico cada 1 segundo."""
        # Si la pestaña de gráfica está visible
        try:
            current_tab = self.notebook.tab(self.notebook.select(), "text")
            if "Gráfica" in current_tab:
                self.chart.set_data(self.session_data)
            elif "Analítica" in current_tab:
                self._refresh_analytics()
        except Exception:
            pass

        self.root.after(1000, self._periodic_timer)

    def _refresh_analytics(self):
        report = AnalyticsEngine.compute(self.session_data, self.current_reading)

        self.lbl_an_twa.config(text=f"{report.twa_co:.1f} PPM")
        self.lbl_an_nom.config(text=f"{report.percent_nom010:.1f} % (25 ppm)")
        self.lbl_an_der.config(text=f"{report.d_co_dt:+.2f} PPM/s")
        self.lbl_an_pea.config(text=f"r_T: {report.r_co_temp:.2f} | r_RH: {report.r_co_hum:.2f}")

        self.lbl_hero_predictive.config(text=report.estado_predictivo)

        texto_completo = (
            f"ESTADO PREDICTIVO: {report.estado_predictivo}\n"
            f"EVALUACIÓN HIGROTÉRMICA: {report.derivas_higrotermicas}\n\n"
            f"CONCLUSIÓN:\n{report.conclusion_general}\n\n"
            f"ACCIÓN RECOMENDADA:\n{report.accion_recomendada}"
        )

        self.txt_conclusion.configure(state="normal")
        self.txt_conclusion.delete("1.0", tk.END)
        self.txt_conclusion.insert(tk.END, texto_completo)
        self.txt_conclusion.configure(state="disabled")

    # -------------------------------------------------------------------------
    # EXPORTACIÓN, IMPORTACIÓN Y AUDITORÍA
    # -------------------------------------------------------------------------
    def _import_file(self):
        path = filedialog.askopenfilename(
            filetypes=[("Archivos de Bitácora (*.csv, *.json)", "*.csv;*.json"),
                       ("Archivos CSV", "*.csv"),
                       ("Archivos JSON", "*.json")],
            title="Seleccionar Bitácora Exportada (desde Android o PC)"
        )
        if not path:
            return

        try:
            inserted = self.db.import_file(path)
            # Recargar registros desde la base de datos
            self.session_data = self.db.get_history(limit=5000)
            self.peak_co = 0.0
            self.peak_lpg = 0.0
            for d in self.session_data:
                c = float(d.get("co", 0.0))
                l = float(d.get("lpg", 0.0))
                if c > self.peak_co: self.peak_co = c
                if l > self.peak_lpg: self.peak_lpg = l

            self.chart.set_data(self.session_data)
            self._refresh_analytics()
            total = self.db.get_count()
            self.lbl_db_count.config(text=f"Registros en BD: {total}")
            messagebox.showinfo(
                "Sincronización Exitosa",
                f"Se importaron {inserted} registros nuevos desde:\n{os.path.basename(path)}\n\nTotal acumulado en base de datos: {total} muestras."
            )
        except Exception as e:
            messagebox.showerror("Error al Importar", f"No se pudo sincronizar el archivo:\n{e}")

    def _export_csv(self):
        ts_str = time.strftime("%Y%m%d_%H%M%S")
        default_name = f"Bitacora_Canario_{ts_str}.csv"
        path = filedialog.asksaveasfilename(
            defaultextension=".csv",
            filetypes=[("Archivos CSV", "*.csv")],
            initialfile=default_name,
            title="Exportar Bitácora de Telemetría a CSV"
        )
        if path:
            count = self.db.export_csv(path)
            messagebox.showinfo("Exportación Exitosa", f"Se exportaron exitosamente {count} registros a:\n{path}")

    def _export_json(self):
        ts_str = time.strftime("%Y%m%d_%H%M%S")
        default_name = f"Bitacora_Canario_{ts_str}.json"
        path = filedialog.asksaveasfilename(
            defaultextension=".json",
            filetypes=[("Archivos JSON", "*.json")],
            initialfile=default_name,
            title="Exportar Bitácora de Telemetría a JSON"
        )
        if path:
            count = self.db.export_json(path)
            messagebox.showinfo("Exportación Exitosa", f"Se exportaron exitosamente {count} registros a:\n{path}")

    def _clear_history(self):
        ans = messagebox.askyesno(
            "Confirmar Borrado",
            "¿Estás seguro de que deseas eliminar todo el historial de la base de datos local?\nEsta acción no se puede deshacer."
        )
        if ans:
            self.db.clear_all()
            self.session_data.clear()
            self.peak_co = 0.0
            self.peak_lpg = 0.0
            self.chart.set_data([])
            self.lbl_db_count.config(text="Registros en BD: 0")
            messagebox.showinfo("Base de Datos", "El historial ha sido reiniciado.")

def main():
    root = tk.Tk()
    app = CanarioDesktopApp(root)

    def on_closing():
        if app.serial_client.is_connected():
            app.serial_client.disconnect()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_closing)
    root.mainloop()

if __name__ == "__main__":
    main()
