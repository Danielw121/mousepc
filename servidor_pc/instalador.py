"""
Asistente de instalación de MousePC para Windows (también lo desinstala).

Se ejecuta con el Python que viene dentro del paquete (carpeta runtime\\),
así que no hace falta tener Python instalado. No necesita permisos de
administrador: instala para el usuario actual.

Uso:
    pythonw instalador.py                  asistente con ventanas
    pythonw instalador.py --uninstall      desinstalar
    python  instalador.py --silent [--dest DIR] [--no-desktop] [--autostart]
                                   [--no-shortcuts] [--no-registry] [--no-start]
"""

import argparse
import ctypes
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import winreg
from tkinter import filedialog, messagebox, ttk

APP = "MousePC"
BUNDLE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # contiene runtime\ y app\
DEFAULT_DEST = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "Programs", APP)
UNINSTALL_KEY = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\MousePC"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
MUTEX_NAME = "Local\\MousePC_single_instance"
TASK_NAME = "MousePC"  # tarea de "iniciar como administrador" (la crea el propio MousePC)
CREATE_NO_WINDOW = 0x08000000


def read_version(base=BUNDLE):
    try:
        with open(os.path.join(base, "app", "VERSION"), encoding="utf-8") as f:
            return f.read().strip() or "dev"
    except OSError:
        return "dev"


def installed_location():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, UNINSTALL_KEY) as k:
            return winreg.QueryValueEx(k, "InstallLocation")[0]
    except OSError:
        return None


def desktop_dir():
    buf = ctypes.create_unicode_buffer(260)
    ctypes.windll.shell32.SHGetFolderPathW(None, 0x0010, None, 0, buf)  # CSIDL_DESKTOPDIRECTORY
    return buf.value


def start_menu_dir():
    return os.path.join(os.environ["APPDATA"], r"Microsoft\Windows\Start Menu\Programs")


def paths_for(dest):
    return {
        "pythonw": os.path.join(dest, "runtime", "pythonw.exe"),
        "server": os.path.join(dest, "app", "mousepc_server.py"),
        "installer": os.path.join(dest, "app", "instalador.py"),
        "icon": os.path.join(dest, "app", "mousepc.ico"),
    }


# --------------------------------------------------------------------------
# MousePC abierto
# --------------------------------------------------------------------------

def server_running():
    h = ctypes.windll.kernel32.OpenMutexW(0x00100000, False, MUTEX_NAME)  # SYNCHRONIZE
    if h:
        ctypes.windll.kernel32.CloseHandle(h)
        return True
    return False


STOP_PS = ("Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*mousepc_server.py*' } "
           "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")


def _run_elevated(exe, args):
    """Ejecuta algo como administrador (Windows pregunta) y espera a que termine."""
    ps = (f"Start-Process -FilePath '{exe}' -ArgumentList '{args.replace(chr(39), chr(39) * 2)}' "
          "-Verb RunAs -WindowStyle Hidden -Wait")
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                   creationflags=CREATE_NO_WINDOW, check=False)


def stop_server():
    """Cierra cualquier MousePC abierto (de cualquier carpeta)."""
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", STOP_PS],
                   creationflags=CREATE_NO_WINDOW, check=False)
    for _ in range(10):
        if not server_running():
            return
        time.sleep(0.3)
    # Sigue abierto: seguramente corre como administrador; hace falta permiso para cerrarlo.
    _run_elevated("powershell", f'-NoProfile -Command "{STOP_PS}"')


def admin_task_exists():
    return subprocess.run(["schtasks", "/Query", "/TN", TASK_NAME], capture_output=True,
                          creationflags=CREATE_NO_WINDOW).returncode == 0


def remove_admin_task():
    if not admin_task_exists():
        return
    r = subprocess.run(["schtasks", "/Delete", "/TN", TASK_NAME, "/F"], capture_output=True,
                       creationflags=CREATE_NO_WINDOW)
    if r.returncode != 0:
        _run_elevated("schtasks", f"/Delete /TN {TASK_NAME} /F")


# --------------------------------------------------------------------------
# Accesos directos y registro
# --------------------------------------------------------------------------

def make_shortcut(lnk_path, target, args, workdir, icon, description):
    # WScript.Shell desde PowerShell; las rutas van por variables de entorno para evitar problemas con comillas.
    env = dict(os.environ, MPC_LNK=lnk_path, MPC_TARGET=target, MPC_ARGS=args,
               MPC_WD=workdir, MPC_ICON=icon, MPC_DESC=description)
    ps = ("$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:MPC_LNK);"
          "$s.TargetPath=$env:MPC_TARGET;$s.Arguments=$env:MPC_ARGS;$s.WorkingDirectory=$env:MPC_WD;"
          "$s.IconLocation=$env:MPC_ICON+',0';$s.Description=$env:MPC_DESC;$s.Save()")
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                   env=env, creationflags=CREATE_NO_WINDOW, check=True)


def shortcut_paths():
    return [os.path.join(start_menu_dir(), f"{APP}.lnk"), os.path.join(desktop_dir(), f"{APP}.lnk")]


def folder_size_kb(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total // 1024


def register_uninstall(dest, version):
    p = paths_for(dest)
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, UNINSTALL_KEY) as k:
        winreg.SetValueEx(k, "DisplayName", 0, winreg.REG_SZ, APP)
        winreg.SetValueEx(k, "DisplayVersion", 0, winreg.REG_SZ, version)
        winreg.SetValueEx(k, "Publisher", 0, winreg.REG_SZ, "MousePC")
        winreg.SetValueEx(k, "DisplayIcon", 0, winreg.REG_SZ, p["icon"])
        winreg.SetValueEx(k, "InstallLocation", 0, winreg.REG_SZ, dest)
        winreg.SetValueEx(k, "UninstallString", 0, winreg.REG_SZ,
                          f'"{p["pythonw"]}" "{p["installer"]}" --uninstall')
        winreg.SetValueEx(k, "NoModify", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(k, "NoRepair", 0, winreg.REG_DWORD, 1)
        winreg.SetValueEx(k, "EstimatedSize", 0, winreg.REG_DWORD, folder_size_kb(dest))


def set_autostart(dest, on):
    if on and admin_task_exists():
        on = False  # ya inicia como administrador con la tarea programada; no abrirlo dos veces
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            p = paths_for(dest)
            winreg.SetValueEx(k, APP, 0, winreg.REG_SZ, f'"{p["pythonw"]}" "{p["server"]}"')
        else:
            try:
                winreg.DeleteValue(k, APP)
            except OSError:
                pass


# --------------------------------------------------------------------------
# Instalar / desinstalar
# --------------------------------------------------------------------------

def install(dest, desktop=True, autostart=False, shortcuts=True, registry=True, log=print):
    dest = os.path.abspath(dest)
    version = read_version()
    same_place = os.path.normcase(dest) == os.path.normcase(BUNDLE)

    if server_running():
        log("Cerrando MousePC…")
        stop_server()

    if not same_place:
        os.makedirs(dest, exist_ok=True)
        for part in ("runtime", "app"):
            target = os.path.join(dest, part)
            if os.path.exists(target):
                log(f"Quitando versión anterior ({part})…")
                shutil.rmtree(target)
            log(f"Copiando {part}…")
            shutil.copytree(os.path.join(BUNDLE, part), target)

    p = paths_for(dest)
    if shortcuts:
        log("Creando accesos directos…")
        start_lnk, desk_lnk = shortcut_paths()
        desc = "Controlar el mouse del PC desde el teléfono"
        make_shortcut(start_lnk, p["pythonw"], f'"{p["server"]}"', os.path.dirname(p["server"]), p["icon"], desc)
        if desktop:
            make_shortcut(desk_lnk, p["pythonw"], f'"{p["server"]}"', os.path.dirname(p["server"]), p["icon"], desc)
        elif os.path.exists(desk_lnk):
            os.remove(desk_lnk)
    if registry:
        log("Registrando en Aplicaciones de Windows…")
        register_uninstall(dest, version)
        set_autostart(dest, autostart)
    log("Listo.")
    return p


def launch(dest):
    p = paths_for(dest)
    subprocess.Popen([p["pythonw"], p["server"]], cwd=os.path.dirname(p["server"]),
                     creationflags=0x00000008 | 0x00000200)  # DETACHED_PROCESS | NEW_PROCESS_GROUP


def uninstall(remove_config=False):
    dest = installed_location()  # None = no está instalado (p. ej. se usa portable): no se borra ninguna carpeta
    if server_running():
        stop_server()
    for lnk in shortcut_paths():
        if os.path.exists(lnk):
            os.remove(lnk)
    set_autostart(dest or BUNDLE, False)
    remove_admin_task()
    try:
        winreg.DeleteKey(winreg.HKEY_CURRENT_USER, UNINSTALL_KEY)
    except OSError:
        pass
    if remove_config:
        shutil.rmtree(os.path.join(os.environ.get("APPDATA", ""), APP), ignore_errors=True)
    # Esta misma carpeta está en uso (aquí corre Python): se borra unos segundos después de cerrar.
    if dest and os.path.isdir(os.path.join(dest, "runtime")) and os.path.isfile(os.path.join(dest, "app", "instalador.py")):
        subprocess.Popen(f'cmd /c ping 127.0.0.1 -n 4 >nul & rmdir /s /q "{dest}"',
                         cwd=tempfile.gettempdir(), creationflags=CREATE_NO_WINDOW)


# --------------------------------------------------------------------------
# Asistente con ventanas
# --------------------------------------------------------------------------

class Wizard:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title(f"Instalar {APP}")
        self.root.resizable(False, False)
        try:
            self.root.iconbitmap(os.path.join(BUNDLE, "app", "mousepc.ico"))
        except tk.TclError:
            pass
        style = ttk.Style(self.root)
        style.configure("Title.TLabel", font=("Segoe UI", 15, "bold"))
        style.configure("Muted.TLabel", foreground="#555")
        self.body = ttk.Frame(self.root, padding=(24, 20, 24, 8), width=520, height=280)
        self.body.grid(row=0, column=0, sticky="nsew")
        self.body.grid_propagate(False)
        self.body.columnconfigure(0, weight=1)
        ttk.Separator(self.root).grid(row=1, column=0, sticky="ew")
        self.buttons = ttk.Frame(self.root, padding=(24, 10))
        self.buttons.grid(row=2, column=0, sticky="ew")

        self.existing = installed_location()
        self.dest = tk.StringVar(value=self.existing or DEFAULT_DEST)
        self.desktop = tk.BooleanVar(value=True)
        self.autostart = tk.BooleanVar(value=False)
        self.start_now = tk.BooleanVar(value=True)
        self.page_welcome()

    def _clear(self):
        for w in self.body.winfo_children() + self.buttons.winfo_children():
            w.destroy()

    def _buttons(self, *specs):
        for i, (text, cmd, primary) in enumerate(reversed(specs)):
            b = ttk.Button(self.buttons, text=text, command=cmd)
            b.pack(side="right", padx=(8, 0))
            if primary:
                b.focus_set()
                self.root.bind("<Return>", lambda e, c=cmd: c())

    def page_welcome(self):
        self._clear()
        v = read_version()
        ttk.Label(self.body, text=f"Bienvenido a {APP} {v}", style="Title.TLabel").grid(sticky="w")
        ttk.Label(self.body, wraplength=470, justify="left", text=(
            "MousePC te deja controlar el mouse y el teclado de este PC desde tu teléfono Android, "
            "con el dedo como un touchpad o moviendo el teléfono con el giroscopio.\n\n"
            "Este asistente lo instala solo para tu usuario (no pide permisos de administrador) "
            "y crea un acceso directo. Después lo encontrarás como un icono de mouse junto al reloj.")
        ).grid(sticky="w", pady=(12, 0))
        if self.existing:
            ttk.Label(self.body, style="Muted.TLabel", wraplength=470,
                      text=f"Ya hay una versión instalada en:\n{self.existing}\nSe actualizará.").grid(sticky="w", pady=(14, 0))
        self._buttons(("Cancelar", self.root.destroy, False), ("Siguiente >", self.page_options, True))

    def page_options(self):
        self._clear()
        ttk.Label(self.body, text="Opciones", style="Title.TLabel").grid(sticky="w")
        ttk.Label(self.body, text="Carpeta de instalación:").grid(sticky="w", pady=(14, 2))
        row = ttk.Frame(self.body)
        row.grid(sticky="ew")
        row.columnconfigure(0, weight=1)
        ttk.Entry(row, textvariable=self.dest).grid(row=0, column=0, sticky="ew")
        ttk.Button(row, text="Examinar…", command=self._browse).grid(row=0, column=1, padx=(6, 0))
        ttk.Checkbutton(self.body, text="Crear acceso directo en el Escritorio",
                        variable=self.desktop).grid(sticky="w", pady=(16, 2))
        ttk.Checkbutton(self.body, text="Iniciar MousePC al encender el PC",
                        variable=self.autostart).grid(sticky="w", pady=2)
        ttk.Label(self.body, style="Muted.TLabel", wraplength=470, text=(
            "Siempre se crea un acceso en el menú Inicio y aparece en Configuración → Aplicaciones "
            "para desinstalarlo cuando quieras.")).grid(sticky="w", pady=(14, 0))
        self._buttons(("< Atrás", self.page_welcome, False), ("Instalar", self.page_install, True))

    def _browse(self):
        d = filedialog.askdirectory(initialdir=os.path.dirname(self.dest.get()), parent=self.root)
        if d:
            self.dest.set(os.path.join(os.path.normpath(d), APP))

    def page_install(self):
        self._clear()
        self.root.unbind("<Return>")
        ttk.Label(self.body, text="Instalando…", style="Title.TLabel").grid(sticky="w")
        bar = ttk.Progressbar(self.body, mode="indeterminate", length=470)
        bar.grid(sticky="ew", pady=(20, 8))
        bar.start(12)
        status = ttk.Label(self.body, text="")
        status.grid(sticky="w")
        dest = self.dest.get().strip()

        def work():
            try:
                install(dest, desktop=self.desktop.get(), autostart=self.autostart.get(),
                        log=lambda m: self.root.after(0, status.config, {"text": m}))
                self.root.after(0, self.page_done)
            except Exception as e:  # noqa: BLE001 - se muestra al usuario
                self.root.after(0, self.page_error, str(e))

        threading.Thread(target=work, daemon=True).start()

    def page_done(self):
        self._clear()
        ttk.Label(self.body, text="¡Listo!", style="Title.TLabel").grid(sticky="w")
        ttk.Label(self.body, wraplength=470, justify="left", text=(
            "MousePC quedó instalado.\n\n"
            "1. Instala la app MousePC en tu teléfono Android.\n"
            "2. Conecta el teléfono a la misma WiFi que este PC.\n"
            "3. En la app, elige este PC e ingresa el PIN que verás en el icono de MousePC "
            "(clic derecho en el icono junto al reloj).\n\n"
            "La primera vez Windows preguntará por el firewall: permite el acceso en redes privadas.")
        ).grid(sticky="w", pady=(12, 0))
        ttk.Checkbutton(self.body, text="Abrir MousePC ahora", variable=self.start_now).grid(sticky="w", pady=(14, 0))
        self._buttons(("Finalizar", self._finish, True))

    def page_error(self, msg):
        self._clear()
        ttk.Label(self.body, text="No se pudo instalar", style="Title.TLabel").grid(sticky="w")
        ttk.Label(self.body, text=msg, wraplength=470, foreground="#b00020").grid(sticky="w", pady=(12, 0))
        self._buttons(("Cerrar", self.root.destroy, False), ("Reintentar", self.page_options, True))

    def _finish(self):
        if self.start_now.get():
            launch(self.dest.get().strip())
        self.root.destroy()

    def run(self):
        self.root.update_idletasks()
        w, h = self.root.winfo_width(), self.root.winfo_height()
        x = (self.root.winfo_screenwidth() - w) // 2
        y = (self.root.winfo_screenheight() - h) // 3
        self.root.geometry(f"+{x}+{y}")
        self.root.mainloop()


def gui_uninstall():
    root = tk.Tk()
    root.withdraw()
    if not messagebox.askyesno(f"Desinstalar {APP}", "¿Quieres desinstalar MousePC de este PC?"):
        return
    keep = messagebox.askyesno(f"Desinstalar {APP}", "¿Conservar tu configuración (PIN, velocidad) por si lo vuelves a instalar?")
    uninstall(remove_config=not keep)
    messagebox.showinfo(APP, "MousePC se desinstaló.")


def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:  # noqa: BLE001
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--uninstall", action="store_true")
    ap.add_argument("--silent", action="store_true")
    ap.add_argument("--dest", default=None)
    ap.add_argument("--no-desktop", action="store_true")
    ap.add_argument("--autostart", action="store_true")
    ap.add_argument("--no-shortcuts", action="store_true")
    ap.add_argument("--no-registry", action="store_true")
    ap.add_argument("--no-start", action="store_true")
    a = ap.parse_args()

    if a.uninstall:
        if a.silent:
            uninstall()
        else:
            gui_uninstall()
    elif a.silent:
        dest = a.dest or installed_location() or DEFAULT_DEST
        install(dest, desktop=not a.no_desktop, autostart=a.autostart,
                shortcuts=not a.no_shortcuts, registry=not a.no_registry)
        if not a.no_start:
            launch(dest)
    else:
        Wizard().run()


if __name__ == "__main__":
    main()
