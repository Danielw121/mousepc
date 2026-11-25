"""
MousePC - servidor para Windows.

Recibe por UDP (red local) los movimientos y clics que envía la app de Android
y los convierte en eventos reales de mouse/teclado con SendInput.
Vive como icono en la bandeja del sistema (iconos ocultos, junto al reloj).
"""

import ctypes
import ctypes.wintypes as wt
import json
import os
import queue
import random
import socket
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk

import pystray
from PIL import Image, ImageDraw

APP = "MousePC"
PROTOCOL_VERSION = 1
CONFIG_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP)
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"

CLIENT_TIMEOUT = 10.0  # s sin mensajes -> cliente desconectado
MAX_BAD_PIN = 5  # intentos fallidos antes de bloquear la IP
BLOCK_SECONDS = 60

# --------------------------------------------------------------------------
# Configuración
# --------------------------------------------------------------------------

DEFAULTS = {
    "port": 5005,
    "pin": None,
    "speed": 1.0,
    "scroll_speed": 1.0,
    "enabled": True,
}


def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    if not (isinstance(cfg.get("pin"), str) and len(cfg["pin"]) == 4 and cfg["pin"].isdigit()):
        cfg["pin"] = f"{random.SystemRandom().randint(0, 9999):04d}"
    save_config(cfg)
    return cfg


def save_config(cfg):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)


def local_ips():
    ips = set()
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips)


# --------------------------------------------------------------------------
# Inyección de mouse / teclado (Win32 SendInput)
# --------------------------------------------------------------------------

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wt.DWORD), ("u", _INPUTUNION)]


user32.SendInput.argtypes = (wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wt.UINT

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000
BUTTON_FLAGS = {  # botón -> (down, up)
    "l": (0x0002, 0x0004),
    "r": (0x0008, 0x0010),
    "m": (0x0020, 0x0040),
}
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

SPECIAL_KEYS = {
    "enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "delete": 0x2E, "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22,
    "win": 0x5B, "volup": 0xAF, "voldown": 0xAE, "mute": 0xAD,
    "playpause": 0xB3, "next": 0xB0, "prev": 0xB1,
}
EXTENDED_KEYS = {0x25, 0x26, 0x27, 0x28, 0x2E, 0x24, 0x23, 0x21, 0x22, 0x5B,
                 0xAF, 0xAE, 0xAD, 0xB3, 0xB0, 0xB1}


def _send(*inputs):
    arr = (INPUT * len(inputs))(*inputs)
    user32.SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def _mouse(flags, dx=0, dy=0, data=0):
    inp = INPUT(type=INPUT_MOUSE)
    inp.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    return inp


def _key(vk=0, scan=0, flags=0):
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return inp


def mouse_move(dx, dy):
    _send(_mouse(MOUSEEVENTF_MOVE, dx, dy))


def mouse_button(button, down):
    flags = BUTTON_FLAGS.get(button)
    if flags:
        _send(_mouse(flags[0] if down else flags[1]))


def mouse_click(button):
    flags = BUTTON_FLAGS.get(button)
    if flags:
        _send(_mouse(flags[0]), _mouse(flags[1]))


def mouse_wheel(amount, horizontal=False):
    _send(_mouse(MOUSEEVENTF_HWHEEL if horizontal else MOUSEEVENTF_WHEEL, data=amount))


def press_key(name):
    vk = SPECIAL_KEYS.get(name)
    if vk is None:
        return
    ext = KEYEVENTF_EXTENDEDKEY if vk in EXTENDED_KEYS else 0
    _send(_key(vk=vk, flags=ext), _key(vk=vk, flags=ext | KEYEVENTF_KEYUP))


def type_text(text):
    for ch in text:
        if ch == "\n":
            press_key("enter")
            continue
        units = ch.encode("utf-16-le")
        for i in range(0, len(units), 2):
            code = int.from_bytes(units[i:i + 2], "little")
            _send(_key(scan=code, flags=KEYEVENTF_UNICODE),
                  _key(scan=code, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))


# --------------------------------------------------------------------------
# Servidor UDP
# --------------------------------------------------------------------------

class MouseServer:
    def __init__(self, cfg, on_change):
        self.cfg = cfg
        self.on_change = on_change
        self.sock = None
        self.thread = None
        self.running = False
        self.error = None
        self.clients = {}  # addr -> {"name": str, "last": float}
        self.bad_pin = {}  # ip -> (intentos, bloqueado_hasta)
        self.ax = self.ay = 0.0  # restos fraccionarios del movimiento
        self.wy = self.wx = 0.0  # restos fraccionarios del scroll
        self.held = set()  # botones apretados (para soltarlos si se cae la conexión)

    # -- ciclo de vida --
    def start(self):
        self.error = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            s.bind(("0.0.0.0", int(self.cfg["port"])))
            s.settimeout(0.5)
        except OSError as e:
            self.error = f"No se pudo abrir el puerto {self.cfg['port']}: {e}"
            self.on_change()
            return False
        self.sock = s
        self.running = True
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()
        self.on_change()
        return True

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        if self.thread:
            self.thread.join(timeout=2)
        self.sock = None
        self.thread = None
        self._release_all()
        self.clients.clear()

    def restart(self):
        self.stop()
        return self.start()

    def kick_all(self):
        self._release_all()
        self.clients.clear()
        self.on_change()

    # -- red --
    def _reply(self, addr, msg):
        try:
            self.sock.sendto(json.dumps(msg).encode("utf-8"), addr)
        except OSError:
            pass

    def _loop(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(8192)
            except socket.timeout:
                self._expire_clients()
                continue
            except ConnectionResetError:
                # Windows avisa así cuando un paquete anterior no llegó; se ignora.
                continue
            except OSError:
                break
            try:
                msg = json.loads(data.decode("utf-8"))
                if isinstance(msg, dict):
                    self._handle(msg, addr)
            except Exception:  # un paquete malo no debe tumbar el servidor
                continue
            self._expire_clients()

    def _expire_clients(self):
        now = time.monotonic()
        gone = [a for a, c in self.clients.items() if now - c["last"] > CLIENT_TIMEOUT]
        for a in gone:
            del self.clients[a]
        if gone:
            if not self.clients:
                self._release_all()
            self.on_change()

    def _release_all(self):
        for b in list(self.held):
            mouse_button(b, False)
        self.held.clear()

    # -- protocolo --
    def _handle(self, msg, addr):
        t = msg.get("t")
        enabled = bool(self.cfg["enabled"])

        if t == "disc":
            self._reply(addr, {"t": "here", "name": socket.gethostname(),
                               "v": PROTOCOL_VERSION, "enabled": enabled})
            return

        if t == "hello":
            ip = addr[0]
            tries, until = self.bad_pin.get(ip, (0, 0))
            if time.monotonic() < until:
                self._reply(addr, {"t": "blocked", "secs": int(until - time.monotonic())})
                return
            if str(msg.get("pin", "")) != self.cfg["pin"]:
                tries += 1
                until = time.monotonic() + BLOCK_SECONDS if tries >= MAX_BAD_PIN else 0
                self.bad_pin[ip] = (0 if until else tries, until)
                self._reply(addr, {"t": "badpin"})
                return
            self.bad_pin.pop(ip, None)
            self.clients[addr] = {"name": str(msg.get("name", ip))[:40], "last": time.monotonic()}
            self._reply(addr, {"t": "ok" if enabled else "off", "name": socket.gethostname()})
            self.on_change()
            return

        client = self.clients.get(addr)
        if client is None:
            self._reply(addr, {"t": "auth"})  # la app vuelve a mandar "hello"
            return
        client["last"] = time.monotonic()

        if t == "ping":
            self._reply(addr, {"t": "pong", "enabled": enabled})
            return
        if t == "bye":
            del self.clients[addr]
            if not self.clients:
                self._release_all()
            self.on_change()
            return
        if not enabled:
            return

        if t == "m":
            speed = float(self.cfg["speed"])
            self.ax += float(msg.get("dx", 0)) * speed
            self.ay += float(msg.get("dy", 0)) * speed
            ix, iy = int(self.ax), int(self.ay)
            self.ax -= ix
            self.ay -= iy
            if ix or iy:
                mouse_move(ix, iy)
        elif t == "c":
            mouse_click(msg.get("b", "l"))
        elif t == "d":
            b = msg.get("b", "l")
            if b in BUTTON_FLAGS:
                self.held.add(b)
                mouse_button(b, True)
        elif t == "u":
            b = msg.get("b", "l")
            self.held.discard(b)
            mouse_button(b, False)
        elif t == "s":
            sp = float(self.cfg["scroll_speed"])
            self.wy += float(msg.get("v", 0)) * sp
            self.wx += float(msg.get("h", 0)) * sp
            iy, ix = int(self.wy), int(self.wx)
            self.wy -= iy
            self.wx -= ix
            if iy:
                mouse_wheel(iy)
            if ix:
                mouse_wheel(ix, horizontal=True)
        elif t == "txt":
            type_text(str(msg.get("s", ""))[:500])
        elif t == "key":
            press_key(str(msg.get("k", "")))

    # -- estado --
    def status_text(self):
        if self.error:
            return "Error: puerto ocupado"
        if not self.cfg["enabled"]:
            return "Desactivado"
        if self.clients:
            names = ", ".join(c["name"] for c in self.clients.values())
            return f"Conectado: {names}"
        return "Esperando conexión…"


# --------------------------------------------------------------------------
# Inicio con Windows
# --------------------------------------------------------------------------

def _launch_command():
    if getattr(sys, "frozen", False):  # .exe de PyInstaller
        return f'"{sys.executable}"'
    exe = sys.executable
    pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")
    if os.path.exists(pyw):
        exe = pyw
    return f'"{exe}" "{os.path.abspath(__file__)}"'


def autostart_enabled():
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP)
            return True
    except OSError:
        return False


def set_autostart(on):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, _launch_command())
        else:
            try:
                winreg.DeleteValue(k, APP)
            except OSError:
                pass


# --------------------------------------------------------------------------
# Icono de bandeja + ventana de configuración
# --------------------------------------------------------------------------

COLORS = {"off": "#8a8a8a", "idle": "#4f8cff", "connected": "#2ecc71", "error": "#e74c3c"}


def make_icon(state):
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    color = COLORS[state]
    d.rounded_rectangle((14, 4, 50, 60), radius=17, fill=color, outline="white", width=3)
    d.line((32, 6, 32, 28), fill="white", width=3)
    d.line((16, 28, 48, 28), fill="white", width=3)
    if state == "off":
        d.line((8, 56, 56, 8), fill="#e74c3c", width=6)
    return img


class TrayApp:
    def __init__(self):
        self.cfg = load_config()
        self.events = queue.Queue()
        self.root = tk.Tk()
        self.root.withdraw()
        self.settings_win = None
        self.server = MouseServer(self.cfg, lambda: self.events.put("refresh"))
        self.icon = pystray.Icon(APP, make_icon("idle"), APP, menu=self._menu())

    # Los callbacks de pystray corren en otro hilo: se pasan a tkinter por la cola.
    def _post(self, what):
        return lambda icon=None, item=None: self.events.put(what)

    def _menu(self):
        M = pystray.MenuItem
        return pystray.Menu(
            M(lambda i: self.server.status_text(), None, enabled=False),
            M(lambda i: f"PIN: {self.cfg['pin']}   ·   Puerto: {self.cfg['port']}", None, enabled=False),
            M(lambda i: "IP: " + (", ".join(local_ips()) or "sin red"), None, enabled=False),
            pystray.Menu.SEPARATOR,
            M("Activado", self._post("toggle"), checked=lambda i: bool(self.cfg["enabled"])),
            M("Desconectar teléfonos", self._post("kick"),
              enabled=lambda i: bool(self.server.clients)),
            M("Configuración…", self._post("settings"), default=True),
            pystray.Menu.SEPARATOR,
            M("Salir", self._post("quit")),
        )

    def _refresh(self):
        if self.server.error:
            state = "error"
        elif not self.cfg["enabled"]:
            state = "off"
        elif self.server.clients:
            state = "connected"
        else:
            state = "idle"
        self.icon.icon = make_icon(state)
        self.icon.title = f"{APP} – {self.server.status_text()}"[:127]
        self.icon.update_menu()

    def _pump(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if ev == "refresh":
                    self._refresh()
                elif ev == "toggle":
                    self.cfg["enabled"] = not self.cfg["enabled"]
                    save_config(self.cfg)
                    if not self.cfg["enabled"]:
                        self.server._release_all()
                    self._refresh()
                elif ev == "kick":
                    self.server.kick_all()
                elif ev == "settings":
                    self.open_settings()
                elif ev == "quit":
                    self.quit()
                    return
        except queue.Empty:
            pass
        self.root.after(100, self._pump)

    def open_settings(self):
        if self.settings_win is not None and self.settings_win.winfo_exists():
            self.settings_win.deiconify()
            self.settings_win.lift()
            self.settings_win.focus_force()
            return
        SettingsWindow(self)

    def run(self):
        if not self.server.start():
            messagebox.showerror(APP, self.server.error + "\n\nCambia el puerto en Configuración.")
        self.icon.run_detached()
        self._refresh()
        self.root.after(100, self._pump)
        self.root.mainloop()

    def quit(self):
        self.server.stop()
        self.icon.stop()
        self.root.destroy()


class SettingsWindow:
    def __init__(self, app: TrayApp):
        self.app = app
        cfg = app.cfg
        w = tk.Toplevel(app.root)
        app.settings_win = w
        w.title(f"{APP} – Configuración")
        w.resizable(False, False)
        w.attributes("-topmost", True)
        frm = ttk.Frame(w, padding=16)
        frm.grid()

        r = 0
        ttk.Label(frm, text="Conecta el teléfono a la misma red WiFi que este PC.",
                  foreground="#555").grid(row=r, column=0, columnspan=3, sticky="w", pady=(0, 10))
        r += 1
        ttk.Label(frm, text="IP de este PC:").grid(row=r, column=0, sticky="w")
        ttk.Label(frm, text=", ".join(local_ips()) or "sin red",
                  font=("Segoe UI", 10, "bold")).grid(row=r, column=1, columnspan=2, sticky="w")
        r += 1

        ttk.Label(frm, text="PIN (4 dígitos):").grid(row=r, column=0, sticky="w", pady=4)
        self.pin = tk.StringVar(value=cfg["pin"])
        ttk.Entry(frm, textvariable=self.pin, width=8, font=("Segoe UI", 12, "bold")).grid(
            row=r, column=1, sticky="w")
        ttk.Button(frm, text="Generar otro", command=self._new_pin).grid(row=r, column=2, sticky="w")
        r += 1

        ttk.Label(frm, text="Puerto UDP:").grid(row=r, column=0, sticky="w", pady=4)
        self.port = tk.StringVar(value=str(cfg["port"]))
        ttk.Spinbox(frm, from_=1024, to=65535, textvariable=self.port, width=8).grid(
            row=r, column=1, sticky="w")
        r += 1

        self.speed = tk.DoubleVar(value=float(cfg["speed"]))
        self.scroll = tk.DoubleVar(value=float(cfg["scroll_speed"]))
        for label, var in (("Velocidad del puntero:", self.speed), ("Velocidad del scroll:", self.scroll)):
            ttk.Label(frm, text=label).grid(row=r, column=0, sticky="w", pady=4)
            val = ttk.Label(frm, width=5)
            ttk.Scale(frm, from_=0.2, to=3.0, variable=var, length=180,
                      command=lambda v, l=val: l.config(text=f"{float(v):.1f}x")).grid(
                row=r, column=1, sticky="w")
            val.config(text=f"{var.get():.1f}x")
            val.grid(row=r, column=2, sticky="w")
            r += 1

        self.enabled = tk.BooleanVar(value=bool(cfg["enabled"]))
        ttk.Checkbutton(frm, text="Control activado", variable=self.enabled).grid(
            row=r, column=0, columnspan=3, sticky="w", pady=(8, 0))
        r += 1
        self.autostart = tk.BooleanVar(value=autostart_enabled())
        ttk.Checkbutton(frm, text="Iniciar con Windows", variable=self.autostart).grid(
            row=r, column=0, columnspan=3, sticky="w")
        r += 1

        btns = ttk.Frame(frm)
        btns.grid(row=r, column=0, columnspan=3, sticky="e", pady=(14, 0))
        ttk.Button(btns, text="Cancelar", command=w.destroy).grid(row=0, column=0, padx=4)
        ttk.Button(btns, text="Guardar", command=self._save).grid(row=0, column=1)
        w.bind("<Return>", lambda e: self._save())
        w.bind("<Escape>", lambda e: w.destroy())
        w.focus_force()

    def _new_pin(self):
        self.pin.set(f"{random.SystemRandom().randint(0, 9999):04d}")

    def _save(self):
        pin = self.pin.get().strip()
        if not (len(pin) == 4 and pin.isdigit()):
            messagebox.showerror(APP, "El PIN debe tener 4 dígitos.", parent=self.app.settings_win)
            return
        try:
            port = int(self.port.get())
            assert 1024 <= port <= 65535
        except (ValueError, AssertionError):
            messagebox.showerror(APP, "Puerto inválido (1024–65535).", parent=self.app.settings_win)
            return

        cfg = self.app.cfg
        port_changed = port != cfg["port"] or self.app.server.error is not None
        pin_changed = pin != cfg["pin"]
        cfg.update(pin=pin, port=port, speed=round(self.speed.get(), 2),
                   scroll_speed=round(self.scroll.get(), 2), enabled=self.enabled.get())
        save_config(cfg)
        try:
            if self.autostart.get() != autostart_enabled():
                set_autostart(self.autostart.get())
        except OSError as e:
            messagebox.showwarning(APP, f"No se pudo cambiar el inicio automático: {e}",
                                   parent=self.app.settings_win)
        if port_changed:
            if not self.app.server.restart():
                messagebox.showerror(APP, self.app.server.error, parent=self.app.settings_win)
        elif pin_changed:
            self.app.server.kick_all()  # los teléfonos tendrán que usar el PIN nuevo
        if not cfg["enabled"]:
            self.app.server._release_all()
        self.app.events.put("refresh")
        self.app.settings_win.destroy()


# --------------------------------------------------------------------------

def single_instance():
    kernel32.CreateMutexW(None, False, "Local\\MousePC_single_instance")
    return ctypes.get_last_error() != 183  # ERROR_ALREADY_EXISTS


def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # ventana nítida en pantallas HiDPI
    except Exception:
        pass
    if not single_instance():
        r = tk.Tk()
        r.withdraw()
        messagebox.showinfo(APP, "MousePC ya está abierto. Búscalo en los iconos ocultos de la barra de tareas.")
        return
    TrayApp().run()


if __name__ == "__main__":
    main()
