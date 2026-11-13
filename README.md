# Codigo Master IPTV

Proyecto Android nativo (Kotlin + Jetpack Compose + Media3) con lista M3U remota configurada y actualización al abrir / botón manual.

## Compilar APK en GitHub
1. Crea un repositorio nuevo en GitHub.
2. Sube **todo el contenido** de esta carpeta (no solo el ZIP).
3. Abre la pestaña **Actions** y ejecuta `Build Android APK` (o espera al push).
4. Abre la ejecución terminada y descarga el artefacto `CodigoMaster-debug-apk`.
5. Dentro encontrarás `app-debug.apk`.

## Lista M3U
La URL está definida en `app/src/main/java/com/codigomaster/iptv/MainActivity.kt` como `PLAYLIST_URL`.
Puedes cambiarla ahí por otra URL pública autorizada. La app descarga la lista al abrir y conserva la última copia válida si no hay conexión. El botón de actualización permite volver a descargarla.

## Importante
- Este proyecto es una base inicial funcional, no una app publicada ni probada en todos los dispositivos.
- La lista M3U que configures debe contener streams que tengas derecho a usar y distribuir. Una app sin login no limita el número de usuarios; cualquier persona que tenga el APK puede intentar acceder a la lista.
- Si el repositorio o la lista son públicos, cualquier URL y credencial incluida dentro de la M3U puede ser vista por terceros. No publiques credenciales privadas. Usa una lista autorizada para compartir o un backend con acceso individual.
- La lista recibida contiene algunas entradas de ejemplo/placeholders y enlaces de streaming HTTP; revisa que el proveedor te autorice a redistribuirlos. El reproductor permite HTTP porque algunos enlaces de la lista lo usan.
- El APK de depuración generado por Actions sirve para instalar/pruebas. Para distribución amplia conviene preparar una versión release firmada con una clave privada guardada en GitHub Secrets.
