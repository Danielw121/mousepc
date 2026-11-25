# MousePC

Controla el mouse y el teclado de tu PC con Windows desde un teléfono Android, por WiFi:
como un **touchpad**, como un **air mouse con el giroscopio** o escribiendo con el teclado del teléfono.

**Descargas y guía:** https://danielw121.github.io/mousepc/

| | |
|---|---|
| 📱 **App Android** | [MousePC.apk](https://github.com/Danielw121/mousepc/releases/latest/download/MousePC.apk) (Android 8+) |
| 💻 **Programa Windows** | [MousePC-Servidor-Windows.zip](https://github.com/Danielw121/mousepc/releases/latest/download/MousePC-Servidor-Windows.zip) (Windows 10/11, no necesita Python) |

## Cómo se usa

1. En el PC: extrae el ZIP y abre **`Instalar MousePC.cmd`**. El asistente lo instala para tu usuario (sin administrador),
   crea accesos directos y lo registra en *Configuración → Aplicaciones*. ¿No quieres instalar? Usa `MousePC portable.cmd`.
2. Aparece un icono de mouse junto al reloj. Con clic derecho ves el **PIN**, la IP, lo activas/desactivas, lo configuras o lo cierras.
3. En el teléfono: instala `MousePC.apk`, conéctate a la misma WiFi, elige tu PC e ingresa el PIN.

La primera vez, Windows pregunta por el firewall: permite el acceso en **redes privadas**.

Si el mouse deja de responder en una ventana, es porque esa ventana corre **como administrador** (p. ej. el Administrador de tareas)
y Windows no deja controlarla: MousePC avisa en el PC y en el teléfono. Usa *clic derecho en el icono → Ejecutar como administrador*.

### Gestos

| Gesto | Acción |
|---|---|
| Deslizar 1 dedo | Mover el puntero (con aceleración) |
| Tocar con 1 / 2 / 3 dedos | Clic izquierdo / derecho / central |
| Deslizar 2 dedos | Scroll |
| Tocar, volver a tocar y deslizar | Arrastrar |
| Modo giroscopio | Mantén apretado y apunta moviendo el teléfono; toque corto = clic |

Además: barra de **volumen del PC** (y los botones de volumen del teléfono lo controlan), **sonido y vibración** al hacer clic,
y un **icono que sigue al dedo** (círculo, patita de gato, átomo, puntero grande o estrella) que se elige en Ajustes.

## Estructura

```
app_android/    App Android (Kotlin + Jetpack Compose)
servidor_pc/    Programa de Windows (Python): icono de bandeja, servidor UDP e instalador
  mousepc_server.py   servidor + icono en la bandeja + ventana de configuración
  instalador.py       asistente de instalación / desinstalación
  empaquetar.ps1      arma el ZIP portable con Python embebido
docs/           Página web (GitHub Pages)
.github/        Workflow que compila y publica cada versión
```

## Desarrollo

**Servidor** (Python 3.12):

```powershell
py -3 -m pip install -r servidor_pc\requirements.txt
py -3 servidor_pc\mousepc_server.py
```

**App** (JDK 17+ y Android SDK, o ábrela en Android Studio):

```powershell
cd app_android
.\gradlew.bat assembleRelease
```

**Paquete de Windows:**

```powershell
.\servidor_pc\empaquetar.ps1 -Version 1.0.0 -Python "py -3.12"
```

### Publicar una versión

```powershell
git tag v1.0.1
git push origin v1.0.1
```

GitHub Actions compila el APK firmado y el ZIP de Windows y crea la Release. La página de descargas siempre apunta a la última.
La firma del APK usa los secrets `MOUSEPC_KEYSTORE_BASE64` y `MOUSEPC_KEYSTORE_PASSWORD`.

## Protocolo

UDP en el puerto 5005, mensajes JSON:

| App → PC | Significado |
|---|---|
| `{"t":"disc"}` | Buscar PCs (broadcast) → `here` |
| `{"t":"hello","pin":"1234","name":"…"}` | Conectar → `ok` / `off` / `badpin` / `blocked` |
| `{"t":"ping"}` | Cada 2 s → `pong` |
| `{"t":"m","dx":1.5,"dy":-2}` | Mover (relativo) |
| `{"t":"c","b":"l"}`, `d`, `u` | Clic / apretar / soltar (`l`, `r`, `m`) |
| `{"t":"s","v":-12,"h":0}` | Scroll (120 = un paso de rueda) |
| `{"t":"txt","s":"hola"}`, `{"t":"key","k":"enter"}` | Teclado |
| `{"t":"bye"}` | Desconectar |

**Seguridad:** solo acepta teléfonos con el PIN correcto. Tras 5 PIN incorrectos bloquea esa IP 1 minuto.
Está pensado para la red de tu casa, no para exponerlo a internet.

## Licencia

[MIT](LICENSE)
