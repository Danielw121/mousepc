@echo off
rem Inicia MousePC sin ventana de consola (queda en los iconos ocultos)
cd /d "%~dp0"
start "" pyw -3 mousepc_server.py
