# Prux ADB / Wireless Debugging

Esta versión elimina la dependencia de la app Shizuku y usa un cliente ADB dentro de Prux.

## Primer uso
1. Activa Opciones de desarrollador > Depuración inalámbrica.
2. Abre “Vincular dispositivo con código de vinculación”.
3. Prux detecta el servicio `_adb-tls-pairing._tcp` y muestra el puerto.
4. Introduce manualmente el código de 6 dígitos y pulsa EMPAREJAR.
5. Tras emparejarse, Prux intenta conectarse automáticamente al ADB TLS de Wireless Debugging.

## Después del primer emparejamiento
La identidad ADB de Prux se conserva en almacenamiento privado de la app. `ServerService` intenta reconectar mediante mDNS después de reinicios. No se lee la pantalla de Ajustes ni se usa AccessibilityService para capturar el código.

## Privilegios
El bridge expone solamente las operaciones que necesita el proyecto: AppOps/background, whitelist de idle y preparación de MediaProjection. No se ofrece una consola de shell arbitraria desde la UI.

## MediaProjection
La captura de pantalla continúa usando la API MediaProjection/VirtualDisplay del proyecto. La autorización de captura sigue sujeta a las protecciones de Android.

## Dependencias
Se usa `libadb-android` para el protocolo ADB/Wireless Debugging y `sun-security-android` para el certificado de identidad ADB. Estas dependencias se descargan desde JitPack al compilar.
