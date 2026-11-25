<#
  Arma el paquete portable del servidor: MousePC-Servidor-Windows.zip

  Incluye el Python "embebido" oficial de python.org (firmado), tkinter y las
  librerías, así quien lo descarga no necesita instalar Python.

  Requiere un Python completo de la MISMA versión para copiar tkinter y usar pip:
    .\empaquetar.ps1 -Version 1.0.0 -Python "py -3.12"
#>
param(
    [string]$Version = "dev",
    [string]$PythonVersion = "3.12.10",
    [string]$Python = "python",
    [string]$Out = "$PSScriptRoot\dist"
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-Py { param([Parameter(ValueFromRemainingArguments)]$a) $exe, $pre = $Python -split ' ', 2; if ($pre) { & $exe $pre @a } else { & $exe @a }; if ($LASTEXITCODE) { throw "python falló: $a" } }

$found = Invoke-Py -c "import platform; print(platform.python_version())"
if ($found -ne $PythonVersion) { throw "Se necesita Python $PythonVersion para copiar tkinter (encontrado $found)" }
$base = Invoke-Py -c "import sys; print(sys.base_prefix)"

$pkg = Join-Path $Out 'MousePC'
$rt = Join-Path $pkg 'runtime'
if (Test-Path $pkg) { Remove-Item -Recurse -Force $pkg }
New-Item -ItemType Directory -Force $rt, (Join-Path $pkg 'app'), (Join-Path $Out 'cache') | Out-Null

# 1. Python embebido
$embed = Join-Path $Out "cache\python-$PythonVersion-embed-amd64.zip"
if (-not (Test-Path $embed)) {
    Write-Host "Descargando Python $PythonVersion embebido…"
    Invoke-WebRequest "https://www.python.org/ftp/python/$PythonVersion/python-$PythonVersion-embed-amd64.zip" -OutFile $embed
}
Expand-Archive $embed $rt
$tag = ($PythonVersion -split '\.')[0..1] -join ''
Set-Content (Join-Path $rt "python$tag._pth") "python$tag.zip`r`n.`r`nLib`r`nLib\site-packages" -NoNewline

# 2. tkinter (para la ventana de configuración y el asistente)
Copy-Item -Recurse (Join-Path $base 'Lib\tkinter') (Join-Path $rt 'Lib\tkinter')
Remove-Item -Recurse -Force (Join-Path $rt 'Lib\tkinter\test') -ErrorAction SilentlyContinue
foreach ($f in '_tkinter.pyd', 'tcl86t.dll', 'tk86t.dll', 'zlib1.dll') { Copy-Item (Join-Path $base "DLLs\$f") $rt }
Copy-Item -Recurse (Join-Path $base 'tcl') (Join-Path $rt 'tcl')

# 3. Librerías
Invoke-Py -m pip install --quiet --disable-pip-version-check --no-compile `
    --target (Join-Path $rt 'Lib\site-packages') `
    --only-binary=:all: --platform win_amd64 --python-version $PythonVersion --implementation cp `
    -r (Join-Path $PSScriptRoot 'requirements.txt')
Get-ChildItem $rt -Recurse -Directory -Filter __pycache__ | Remove-Item -Recurse -Force

# 4. Programa
foreach ($f in 'mousepc_server.py', 'instalador.py', 'mousepc.ico') { Copy-Item (Join-Path $PSScriptRoot $f) (Join-Path $pkg 'app') }
Set-Content (Join-Path $pkg 'app\VERSION') $Version -NoNewline

$check = 'if not exist "%~dp0runtime\pythonw.exe" (echo Primero extrae el ZIP: clic derecho - Extraer todo. & pause & exit /b 1)'
Set-Content (Join-Path $pkg 'Instalar MousePC.cmd') "@echo off`r`n$check`r`nstart `"`" `"%~dp0runtime\pythonw.exe`" `"%~dp0app\instalador.py`"`r`n"
Set-Content (Join-Path $pkg 'MousePC portable.cmd') "@echo off`r`n$check`r`nstart `"`" `"%~dp0runtime\pythonw.exe`" `"%~dp0app\mousepc_server.py`"`r`n"
Set-Content (Join-Path $pkg 'LEEME.txt') -Encoding utf8 @"
MousePC $Version - servidor para Windows
=========================================

Instalar (recomendado):
  Doble clic en "Instalar MousePC.cmd" y sigue el asistente.
  Se instala solo para tu usuario, sin permisos de administrador,
  y aparece en Configuración > Aplicaciones para desinstalarlo.

Sin instalar (portable):
  Doble clic en "MousePC portable.cmd". Puedes llevar esta carpeta en un USB.

Después:
  - Busca el icono de mouse junto al reloj (iconos ocultos, flecha ^).
  - Clic derecho en el icono: PIN, IP, activar/desactivar, configuración y salir.
  - Cuando Windows pregunte por el firewall, permite el acceso en redes privadas.
  - Si Windows muestra "Windows protegió tu PC", pulsa "Más información" y "Ejecutar de todas formas".

App para Android y más información: https://danielw121.github.io/mousepc/
"@

# 5. ZIP
$zip = Join-Path $Out 'MousePC-Servidor-Windows.zip'
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $pkg -DestinationPath $zip -CompressionLevel Optimal
Write-Host ("Listo: {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
