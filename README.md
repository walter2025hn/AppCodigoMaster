# Codigo Master IPTV — versión rediseñada

App Android nativa con Jetpack Compose y Media3.

## Mejoras
- Diseño oscuro premium azul/dorado con identidad Codigo Master.
- Navegación Inicio, TV, Películas y Series.
- Clasificación usando `group-title`, `tvg-id` y patrones de episodios SxxExx.
- Agrupa episodios como una sola serie (por ejemplo, Merlina); al tocarla se abre la lista de episodios.
- Reproduce los enlaces con ExoPlayer/Media3.
- Actualiza la M3U de GitHub al abrir y con el botón de refrescar; conserva caché si no hay conexión.

## GitHub Actions
Sube el contenido completo de esta carpeta a tu repositorio (reemplazando los archivos anteriores). El workflow `.github/workflows/android.yml` compila `app-debug.apk` y lo publica como artefacto.

## Lista
La URL se configura en `PLAYLIST_URL` dentro de `MainActivity.kt`. Usa únicamente listas y streams para los que tengas permiso de acceso y distribución. No incluyas credenciales privadas en un repositorio público.

## Nota sobre la clasificación
La clasificación depende de los metadatos de la M3U. Los nombres S01E01/S01E02 se agrupan automáticamente. Para clasificar de forma fiable todo el catálogo, conviene que `group-title` use categorías coherentes como `TV`, `Películas` y `Series`; elementos sin metadatos suficientes se conservan fuera de esas categorías en vez de etiquetarlos incorrectamente.
