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
advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
shell32 = ctypes.WinDLL("shell32", use_last_error=True)
ULONG_PTR = ctypes.c_size_t

kernel32.CreateMutexW.restype = wt.HANDLE
kernel32.CreateMutexW.argtypes = (ctypes.c_void_p, wt.BOOL, wt.LPCWSTR)
kernel32.OpenProcess.restype = wt.HANDLE
kernel32.OpenProcess.argtypes = (wt.DWORD, wt.BOOL, wt.DWORD)
kernel32.CloseHandle.argtypes = (wt.HANDLE,)
kernel32.QueryFullProcessImageNameW.argtypes = (wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD))
user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowThreadProcessId.argtypes = (wt.HWND, ctypes.POINTER(wt.DWORD))
advapi32.OpenProcessToken.argtypes = (wt.HANDLE, wt.DWORD, ctypes.POINTER(wt.HANDLE))
advapi32.GetTokenInformation.argtypes = (wt.HANDLE, ctypes.c_int, ctypes.c_void_p, wt.DWORD,
                                         ctypes.POINTER(wt.DWORD))
advapi32.GetSidSubAuthorityCount.restype = ctypes.POINTER(ctypes.c_ubyte)
advapi32.GetSidSubAuthorityCount.argtypes = (ctypes.c_void_p,)
advapi32.GetSidSubAuthority.restype = ctypes.POINTER(wt.DWORD)
advapi32.GetSidSubAuthority.argtypes = (ctypes.c_void_p, wt.DWORD)
shell32.ShellExecuteW.restype = ctypes.c_void_p
shell32.ShellExecuteW.argtypes = (wt.HWND, wt.LPCWSTR, wt.LPCWSTR, wt.LPCWSTR, wt.LPCWSTR, ctypes.c_int)


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

# --------------------------------------------------------------------------
# Ventanas de administrador
# Windows no deja que un programa normal mande clics/teclas a una ventana que
# corre como administrador (p. ej. el Administrador de tareas). Lo detectamos
# para avisar, y MousePC puede reabrirse como administrador.
# --------------------------------------------------------------------------

PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
TOKEN_QUERY = 0x0008
TOKEN_INTEGRITY_LEVEL = 25


def _process_integrity(hproc):
    tok = wt.HANDLE()
    if not advapi32.OpenProcessToken(hproc, TOKEN_QUERY, ctypes.byref(tok)):
        return None
    try:
        need = wt.DWORD()
        advapi32.GetTokenInformation(tok, TOKEN_INTEGRITY_LEVEL, None, 0, ctypes.byref(need))
        buf = ctypes.create_string_buffer(need.value)
        if not advapi32.GetTokenInformation(tok, TOKEN_INTEGRITY_LEVEL, buf, need, ctypes.byref(need)):
            return None
        sid = ctypes.cast(buf, ctypes.POINTER(ctypes.c_void_p))[0]  # TOKEN_MANDATORY_LABEL.Label.Sid
        count = advapi32.GetSidSubAuthorityCount(sid)[0]
        return advapi32.GetSidSubAuthority(sid, count - 1)[0]
    finally:
        kernel32.CloseHandle(tok)


OWN_INTEGRITY = _process_integrity(kernel32.GetCurrentProcess()) or 0x2000


def is_admin():
    try:
        return bool(shell32.IsUserAnAdmin())
    except Exception:
        return False


IS_ADMIN = is_admin()


def blocked_foreground():
    """Nombre del programa al frente si Windows no nos deja controlarlo; None si se puede."""
    hwnd = user32.GetForegroundWindow()
    if not hwnd:
        return None
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    if not pid.value or pid.value == os.getpid():
        return None
    h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid.value)
    if not h:
        return None  # sin datos: mejor no avisar de más
    try:
        level = _process_integrity(h)
        if level is None or level <= OWN_INTEGRITY:
            return None
        buf = ctypes.create_unicode_buffer(260)
        n = wt.DWORD(260)
        name = "una ventana de administrador"
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)):
            name = os.path.basename(buf.value)
        return name
    finally:
        kernel32.CloseHandle(h)


INPUT_TYPES = {"m", "c", "d", "u", "s", "txt", "key"}
WARN_EVERY = 15.0  # s entre avisos repetidos


class MouseServer:
    def __init__(self, cfg, on_change, on_blocked=None):
        self.cfg = cfg
        self.on_change = on_change
        self.on_blocked = on_blocked or (lambda name: None)
        self._fg_checked = 0.0
        self._last_warn = 0.0
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

    def _check_foreground(self):
        """Si hay una ventana de administrador al frente, avisa al teléfono y en la bandeja."""
        if IS_ADMIN:
            return
        now = time.monotonic()
        if now - self._fg_checked < 0.5:
            return
        self._fg_checked = now
        name = blocked_foreground()
        if name and now - self._last_warn > WARN_EVERY:
            self._last_warn = now
            for addr in list(self.clients):
                self._reply(addr, {"t": "warn", "k": "admin", "app": name})
            self.on_blocked(name)

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
        if t in INPUT_TYPES:
            self._check_foreground()

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

TASK_NAME = "MousePC"  # tarea programada para iniciar como administrador sin preguntar
CREATE_NO_WINDOW = 0x08000000


def _launch_parts():
    """(programa, argumentos) para volver a abrir este mismo MousePC."""
    if getattr(sys, "frozen", False):  # .exe de PyInstaller
        return sys.executable, ""
    exe = sys.executable
    pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")
    if os.path.exists(pyw):
        exe = pyw
    return exe, f'"{os.path.abspath(__file__)}"'


def _launch_command():
    exe, args = _launch_parts()
    return f'"{exe}" {args}'.strip()


def relaunch_elevated():
    """Abre MousePC como administrador (Windows pregunta). True si el usuario aceptó."""
    exe, args = _launch_parts()
    r = shell32.ShellExecuteW(None, "runas", exe, f"{args} --replace".strip(), None, 1)
    return (r or 0) > 32


def _schtasks(*args):
    import subprocess
    return subprocess.run(["schtasks", *args], capture_output=True, text=True,
                          creationflags=CREATE_NO_WINDOW)


def admin_task_exists():
    return _schtasks("/Query", "/TN", TASK_NAME).returncode == 0


def _run_key_exists():
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP)
            return True
    except OSError:
        return False


def _set_run_key(on):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, _launch_command())
        else:
            try:
                winreg.DeleteValue(k, APP)
            except OSError:
                pass


def _create_admin_task():
    from xml.sax.saxutils import escape
    import tempfile
    exe, args = _launch_parts()
    user = escape(f'{os.environ.get("USERDOMAIN", "")}\\{os.environ.get("USERNAME", "")}')
    # Sin límite de tiempo y también con batería (los valores por defecto de Windows lo cortarían).
    xml = f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>Inicia MousePC como administrador al entrar a Windows.</Description></RegistrationInfo>
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{user}</UserId></LogonTrigger></Triggers>
  <Principals><Principal id="Author"><UserId>{user}</UserId><LogonType>InteractiveToken</LogonType>
    <RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>7</Priority>
  </Settings>
  <Actions Context="Author"><Exec><Command>{escape(exe)}</Command><Arguments>{escape(args)}</Arguments></Exec></Actions>
</Task>
"""
    fd, path = tempfile.mkstemp(suffix=".xml")
    try:
        with os.fdopen(fd, "w", encoding="utf-16") as f:
            f.write(xml)
        r = _schtasks("/Create", "/TN", TASK_NAME, "/XML", path, "/F")
    finally:
        os.remove(path)
    if r.returncode != 0:
        raise OSError((r.stderr or r.stdout).strip() or "schtasks falló")


def autostart_enabled():
    return _run_key_exists() or admin_task_exists()


def set_autostart(on, admin=False):
    """Inicio con Windows: normal (registro) o como administrador (tarea programada)."""
    if on and admin:
        if not IS_ADMIN:
            raise OSError("Para iniciar como administrador, primero abre MousePC como administrador "
                          "(menú del icono → Ejecutar como administrador).")
        _create_admin_task()
        _set_run_key(False)
        return
    if admin_task_exists():
        r = _schtasks("/Delete", "/TN", TASK_NAME, "/F")
        if r.returncode != 0:
            raise OSError("Para quitar el inicio como administrador, abre MousePC como administrador.")
    _set_run_key(on)


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
        self.server = MouseServer(self.cfg, lambda: self.events.put("refresh"),
                                  lambda name: self.events.put(("blocked", name)))
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
            M("Ejecutar como administrador", self._post("elevate"), visible=not IS_ADMIN),
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
        admin = " (administrador)" if IS_ADMIN else ""
        self.icon.title = f"{APP}{admin} – {self.server.status_text()}"[:127]
        self.icon.update_menu()

    def _pump(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if isinstance(ev, tuple) and ev[0] == "blocked":
                    self._notify_blocked(ev[1])
                elif ev == "elevate":
                    if relaunch_elevated():
                        self.quit()  # la copia de administrador toma el relevo
                        return
                elif ev == "refresh":
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

    def _notify_blocked(self, name):
        try:
            self.icon.notify(
                f"{name} se abrió como administrador y Windows no deja controlarlo desde el teléfono.\n"
                "Clic derecho en el icono de MousePC → Ejecutar como administrador.",
                "MousePC no puede controlar esta ventana",
            )
        except Exception:
            pass

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
        self.admin_task_before = admin_task_exists()
        self.admin_start = tk.BooleanVar(value=self.admin_task_before)
        ttk.Checkbutton(frm, text="…como administrador (para controlar también ventanas de administrador)",
                        variable=self.admin_start,
                        state="normal" if IS_ADMIN or self.admin_task_before else "disabled").grid(
            row=r, column=0, columnspan=3, sticky="w", padx=(22, 0))
        r += 1
        ttk.Label(frm, foreground="#555",
                  text=("Ahora corre como administrador." if IS_ADMIN else
                        "Para activarlo, abre MousePC como administrador desde el menú del icono.")).grid(
            row=r, column=0, columnspan=3, sticky="w", padx=(22, 0))
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
            want_admin = self.autostart.get() and self.admin_start.get()
            if self.autostart.get() != autostart_enabled() or want_admin != self.admin_task_before:
                set_autostart(self.autostart.get(), admin=want_admin)
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

_mutex = None


def single_instance(wait=0.0):
    """True si somos el único MousePC. Con wait>0 espera a que el anterior termine de cerrarse."""
    global _mutex
    deadline = time.monotonic() + wait
    while True:
        ctypes.set_last_error(0)
        h = kernel32.CreateMutexW(None, False, "Local\\MousePC_single_instance")
        if ctypes.get_last_error() != 183:  # ERROR_ALREADY_EXISTS
            _mutex = h
            return True
        kernel32.CloseHandle(h)
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.3)


def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # ventana nítida en pantallas HiDPI
    except Exception:
        pass
    if not single_instance(wait=10.0 if "--replace" in sys.argv else 0.0):
        r = tk.Tk()
        r.withdraw()
        messagebox.showinfo(APP, "MousePC ya está abierto. Búscalo en los iconos ocultos de la barra de tareas.")
        return
    TrayApp().run()


if __name__ == "__main__":
    main()
