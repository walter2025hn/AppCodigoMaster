# Código Master IPTV 3.0.0

Aplicación Android IPTV con marca Codigo Master. Usa la lista M3U configurada en `MainActivity.kt` y conserva la última lista válida en caché local.

## Compatibilidad
- Android 5.0/API 21 o superior (teléfonos, Android TV y TV Box).
- Fire TV / Fire TV Stick basado en Android compatible con API 21 o superior. La instalación en Fire TV puede hacerse mediante sideload si no se distribuye en una tienda.
- Interfaz adaptable: navegación horizontal y controles enfocables para mando/D-pad; cuadrícula adaptable a pantallas grandes.
- Reproducción HLS (`.m3u8`) mediante Media3 HLS.

## Mejoras 1.3.0
- Catálogo de películas y series con cuadrículas adaptables al ancho disponible.
- Navegación horizontal para que las pestañas no se corten en pantallas pequeñas.
- Controles con foco para mando a distancia y navegación D-pad.
- Detección de dispositivos de TV en el manifiesto (`LEANBACK_LAUNCHER`) sin exigir hardware táctil.
- HLS habilitado para enlaces `.m3u8`.
- Filtros calculados y retenidos con `remember` para evitar trabajo repetido al escribir o cambiar de pantalla.
- La ruta `/movie/`, `/movies/`, `/vod/` y `/series/` y los grupos VOD tienen prioridad sobre metadatos genéricos de TV.
- Se quitó la regla peligrosa que convertía cualquier elemento con año en una película.
- Cache local: la app muestra la última lista guardada mientras consulta GitHub.

## Compilar APK desde GitHub
1. Sube el contenido de este proyecto a la raíz del repositorio.
2. Abre **Actions**.
3. Ejecuta **Build Android APK** (o espera al build tras un push).
4. Abre la ejecución completada y descarga el artefacto `CodigoMaster-IPTV-APK`.

El workflow usa JDK 17, Gradle 8.9 y Android SDK 35.

## Nota sobre clasificación
La clasificación usa rutas de proveedor, `group-title`, marcadores de episodios, metadatos y nombres conocidos. Las listas M3U no tienen un estándar universal para distinguir VOD de canales; si el proveedor etiqueta incorrectamente un elemento y no aporta una ruta o categoría fiable, puede requerir una regla adicional.

Usa únicamente listas y transmisiones para las que tengas autorización.


## Versión 2.0.0 — Rediseño visual y rendimiento
- Cabecera y navegación compactas para recuperar espacio vertical.
- Portada de inicio cinematográfica y carruseles con tarjetas más ligeras.
- Carga de imágenes con crossfade breve y caché Coil predeterminada.
- Claves estables en listas perezosas para reducir recomposiciones y saltos al desplazarse.
- Efecto de enfoque discreto para navegación con mando en Android TV/TV Box.
- Mantiene la clasificación de catálogo y reproducción Media3 de la versión anterior.


## Versión 3.0.0 — Interfaz profesional y reproductor de doble motor

### Nuevo diseño
- Identidad visual completa (negro profundo + dorado + azul eléctrico) con tema Material 3 propio.
- Navegación adaptable: barra inferior en móvil y riel lateral en tablet / Android TV / TV Box.
- Inicio con portada destacada (carrusel automático), «Continuar viendo», TV en vivo, películas, géneros y series.
- Filtros por categoría (chips), búsqueda global, «Mi lista» (favoritos) y detalle de serie con temporadas.
- Efectos de foco (escala + borde dorado) en todos los elementos para mando a distancia / D-pad.
- Ajustes: motor preferido y lista M3U personalizada.

### Reproducción
- **Motor 1 · ExoPlayer (Media3 1.5.1):** HLS, DASH, SmoothStreaming, RTSP, MPEG-TS, MP4, MKV; decodificación por hardware con fallback a software; extractor TS tolerante (NAL sin AUD / keyframes no IDR); reintentos de red; reconexión en directos.
- **Motor 2 · VLC (libVLC 3.6.0):** RTMP, UDP, códecs y contenedores poco comunes, TS dañados.
- **Cambio automático de motor:** si un enlace falla o se queda cargando 25 s, la app salta sola al otro motor.
- Controles propios: ±10 s, barra de progreso, pista de audio, subtítulos, relación de aspecto (ajustar / estirar / zoom), cambio manual de motor.
- Series: siguiente episodio automático. Películas y episodios: reanudar donde lo dejaste.
- Canales: cambiar de canal con ↑/↓ o CH+/CH− del mando; D-pad, teclas multimedia y gestos táctiles (doble toque ±10 s).
- Pantalla completa automática (horizontal en móvil) y pantalla siempre encendida.

### Notas
- Se incluyen solo las ABI `armeabi-v7a` y `arm64-v8a` (móviles, TV Box, Fire TV). El APK crece por libVLC (~25–35 MB).
- Cache de la lista en archivo local (más rápido con listas grandes).
