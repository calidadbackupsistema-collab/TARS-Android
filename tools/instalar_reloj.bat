@echo off
setlocal
title Instalar TARS en el Galaxy Watch
cd /d "%~dp0"

echo ==============================================
echo   TARS para Galaxy Watch - instalador
echo ==============================================
echo.

if exist "platform-tools\adb.exe" goto tienes_adb
echo Descargando herramientas de Android, solo la primera vez...
curl -L -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip
if errorlevel 1 goto error
tar -xf platform-tools.zip
if errorlevel 1 goto error
del platform-tools.zip
:tienes_adb
set "ADB=%~dp0platform-tools\adb.exe"

echo Descargando la ultima version de TARS para el reloj...
if exist "tars-wear.apk" del "tars-wear.apk"
curl -L -o tars-wear.apk https://github.com/calidadbackupsistema-collab/TARS-Android/releases/latest/download/tars-wear.apk
if errorlevel 1 goto error

echo.
echo ---------------- EN EL RELOJ ----------------
echo 1. Ajustes - Acerca del reloj - Software: toca 7 veces "Version de software"
echo    para activar las Opciones de desarrollador.
echo 2. Ajustes - Opciones de desarrollador: activa "Depuracion ADB"
echo    y "Depuracion inalambrica". El reloj y esta PC deben estar en el mismo Wi-Fi.
echo 3. Dentro de "Depuracion inalambrica" toca "Vincular nuevo dispositivo".
echo ---------------------------------------------
echo.
set /p PAIR=IP:PUERTO que muestra la pantalla de vinculacion (ej. 192.168.1.50:37123):
set /p CODE=Codigo de vinculacion de 6 digitos:
"%ADB%" pair %PAIR% %CODE%
if errorlevel 1 goto error

echo.
echo Ahora regresa a la pantalla "Depuracion inalambrica" del reloj:
echo arriba aparece otra IP:PUERTO (es distinta a la de vinculacion).
set /p CONN=IP:PUERTO de conexion:
"%ADB%" connect %CONN%
if errorlevel 1 goto error
"%ADB%" -s %CONN% install -r tars-wear.apk
if errorlevel 1 goto error

echo.
echo Listo. Busca TARS en la lista de apps del reloj.
echo Recuerda instalar tambien tars-phone.apk en el celular.
pause
exit /b 0

:error
echo.
echo Algo fallo. Revisa los datos y vuelve a intentarlo.
pause
exit /b 1
