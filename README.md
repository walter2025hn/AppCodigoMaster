# Codigo Master IPTV 1.3.0

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
