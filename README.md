# TARS para Android y Galaxy Watch

Versión nativa del asistente TARS. Usa la misma web app de
[Asistente-Personal](https://calidadbackupsistema-collab.github.io/Asistente-Personal/)
(diseño, cerebro, voz y sincronización con Firebase) y le agrega:

- **Escucha permanente sin internet:** dices «TARS» + el comando aunque la pantalla esté apagada
  (reconocimiento Vosk en español dentro del teléfono; el modelo de ~40 MB se descarga una vez).
- **Galaxy Watch:** toca el micrófono en el reloj, habla, y TARS responde desde el celular;
  la respuesta aparece y se lee en el reloj.

## Descargar

Cada vez que se sube un cambio a `main`, GitHub Actions compila y publica en
[Releases](../../releases/latest):

| Archivo | Para qué |
|---|---|
| `tars-phone.apk` | Instalar en el celular Samsung |
| `tars-wear.apk` | App del Galaxy Watch (Wear OS) |
| `instalar_reloj.bat` | Instalador para Windows que pasa la app al reloj |

### Celular
1. Abre `tars-phone.apk` desde el celular y acepta «Instalar apps desconocidas» la primera vez.
2. Al abrir TARS: permite el micrófono y las notificaciones.
3. Acepta «Mostrar sobre otras apps» y quita la optimización de batería para que TARS se abra cuando le hablas.

### Reloj
1. Descarga `instalar_reloj.bat` en tu PC con Windows y ábrelo.
2. Sigue las indicaciones (Depuración inalámbrica en el reloj, mismo Wi-Fi).

## Notas
- Ambos APK se firman con `keystore/tars.jks` (uso personal). Mantén el mismo archivo para poder actualizar sin desinstalar.
- El inicio de sesión dentro de la app es con correo y contraseña (Google no permite su ventana de acceso dentro de apps con WebView).
