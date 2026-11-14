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


## Versión 3.1.0 — Fluidez y compatibilidad TV / TV Box / Fire Stick

### Por qué iba lenta la búsqueda de películas
- El APK se compilaba en **debug** (Compose sin optimizar). Ahora el workflow genera **release** con R8.
- El encabezado y el detalle de serie aplicaban `blur` en tiempo real: eliminado.
- Los pósters se decodificaban a tamaño completo: ahora se decodifican al tamaño de la tarjeta (RGB565), con caché de memoria acotada y caché de disco de 200 MB.
- La búsqueda filtraba miles de títulos en el hilo principal en cada tecla: ahora espera 300 ms y filtra en segundo plano.
- La clasificación de la lista recreaba cientos de `Regex` por entrada: ahora se crean una sola vez (carga inicial más rápida).
- El efecto de foco del mando ya no recompone cada tarjeta (anillo dibujado en la fase de dibujo).

### Mando a distancia
- Riel lateral y botones de la barra superior con foco dorado visible.
- Texto ~15 % mayor en TV y márgenes seguros.
- Mantén OK pulsado (o pulsación larga) sobre una película, serie o canal para calificarlo con estrellas (en canales también añade/quita de Mi lista).
- Manifiesto sin requisitos de pantalla táctil, cámara, micrófono ni orientación vertical.

### Instalar en Fire Stick
Instala «Downloader» desde la tienda de Amazon, activa Ajustes › Mi Fire TV › Opciones para desarrolladores › Instalar apps desconocidas, y descarga el APK.

### Destacadas del inicio
- Altura adaptable: el contenido ya no se sale de la pantalla en TV.
- Los botones no cambian con la página, así el foco del mando no se pierde; flechas ‹ › para avanzar.
- La rotación automática se detiene mientras el mando está sobre las destacadas.

### Calificaciones
- **Público:** nota 0-10 de los votos de usuarios de TMDB (mínimo 10 votos). Requiere una clave gratuita de themoviedb.org, que se pega en Ajustes. Cada título se consulta una vez y se guarda en el equipo.
- **Tuya:** 1-5 estrellas (se muestra ×2 en escala 10 y tiene prioridad sobre la del público). Los canales no tienen base pública: solo cuentan tus estrellas.


## Versión 4.0.0 — Nueva interfaz horizontal
- Menú vertical a la izquierda: TV, DESTACAD., PELICULA, SERIES, KIDS, ANIME y EXPLORAR.
- **TV:** vista previa del canal en vivo en el centro (toca o pulsa OK para pantalla completa) y lista de canales a la derecha con flechas ▲▼.
- Barra superior: buscar, categorías, historial, mi lista, ajustes (campana), wifi (actualizar lista) y reloj.
- KIDS y ANIME agrupan canales, películas y series por categoría; EXPLORAR navega todas las categorías.
- La app queda en horizontal (móvil, tablet, Android TV y Fire TV). En pantallas muy estrechas se conserva el diseño anterior.

## Versión 4.1.0 — Más espacio para el vídeo y filtros nuevos
- Interfaz adaptativa: tamaños de texto, iconos y listas se ajustan a la altura de la pantalla (móvil, tablet y TV).
- Menú y lista de canales más estrechos: la vista previa del canal ocupa todo el espacio libre.
- Pantalla inmersiva (sin barras del sistema; desliza desde el borde para verlas).
- Botón de categoría sobre la vista previa para filtrar canales.
- El icono de filtro abre un panel con Tipo · Género · Año · País · Audio y cuadrícula de pósters con nota y etiqueta CC.


## Versión 4.2.0 — Portadas tipo cartelera y ficha de película/serie
- **Destacados, Películas, Series, Kids y Anime** comparten un nuevo diseño: banner doble con puntos (rota solo cada 7 s; desliza para cambiar), fila de 4 miniaturas apaisadas y filas de pósters por categoría con nota (azul) y etiqueta CC (naranja). Menú lateral con la opción activa en rojo.
- **Ficha de película / serie** (al tocar cualquier póster): título y nota, país | fecha | título original, géneros, director, actores, sinopsis con «Más», vista previa con botón de reproducir, botones Pantalla completa · Idioma · Resolución · Favorito y fila «Quizás te guste». En series añade temporadas y episodios.
- Idioma y Resolución abren el reproductor con el panel de audio / subtítulos / pantalla ya desplegado.
- Con la clave de TMDB (Ajustes) se rellenan sinopsis, director, reparto, país, fecha y fondos apaisados; sin clave se muestra la categoría y el póster recortado. Cada ficha se consulta una vez y se guarda en el equipo.
- «Continuar viendo» y el historial siguen reanudando directamente la reproducción.
