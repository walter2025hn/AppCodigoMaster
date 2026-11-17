@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.codigomaster.iptv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * Motores de reproducción de Código Master:
 *  • ExoPlayer (Media3): HLS, DASH, SmoothStreaming, RTSP, MPEG-TS, MP4, MKV, decodificación por hardware.
 *  • VLC (libVLC): casi cualquier códec/contenedor/protocolo (RTMP, UDP, MKV/AVI exóticos, TS dañados…).
 * El reproductor arranca con ExoPlayer y salta solo a VLC (y viceversa) si el flujo falla.
 */
enum class EngineType(val label: String) { EXO("ExoPlayer"), VLC("VLC") }

enum class AspectMode(val label: String) {
    FIT("Normal"), FILL("Pantalla completa"), RATIO_16_9("16:9"), RATIO_4_3("4:3")
}

class PlayerState {
    var playing by mutableStateOf(true)
    var buffering by mutableStateOf(true)
    var ready by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var live by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var tracksVersion by mutableIntStateOf(0)
}

data class TrackOpt(val id: String, val label: String, val selected: Boolean)

abstract class Controller {
    val state = PlayerState()
    abstract val engine: EngineType
    abstract fun toggle()
    abstract fun seekBy(ms: Long)
    abstract fun seekTo(ms: Long)
    abstract fun poll()
    abstract fun audioOptions(): List<TrackOpt>
    abstract fun subtitleOptions(): List<TrackOpt>
    abstract fun videoOptions(): List<TrackOpt>
    abstract fun selectAudio(id: String)
    abstract fun selectSubtitle(id: String)
    abstract fun selectVideo(id: String)
    abstract fun setPictureEnhancement(enabled: Boolean)
    abstract fun release()

    @Composable
    abstract fun Content(modifier: Modifier, aspect: AspectMode)
}

private const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

private fun guessMime(url: String): String? {
    val lower = url.lowercase(Locale.ROOT)
    val path = lower.substringBefore('?').substringBefore('#')
    return when {
        lower.startsWith("rtsp://") -> MimeTypes.APPLICATION_RTSP
        path.endsWith(".m3u8") || lower.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".ism") || path.contains(".ism/") -> MimeTypes.APPLICATION_SS
        else -> null
    }
}

fun createController(
    engine: EngineType,
    context: Context,
    entry: Entry,
    live: Boolean,
    resumeMs: Long,
    onFatal: (String) -> Unit
): Controller? = try {
    when (engine) {
        EngineType.EXO -> ExoController(context, entry, live, resumeMs, onFatal)
        EngineType.VLC -> VlcController(context, entry, live, resumeMs, onFatal)
    }
} catch (t: Throwable) { null }

// ───────────────────────────── ExoPlayer (Media3) ─────────────────────────────

class ExoController(
    context: Context,
    private val entry: Entry,
    private val live: Boolean,
    private val resumeMs: Long,
    private val onFatal: (String) -> Unit
) : Controller() {
    override val engine = EngineType.EXO

    private val handler = Handler(Looper.getMainLooper())
    private val attempts: List<String?> = guessMime(entry.url)?.let { listOf<String?>(it) } ?: listOf(null, MimeTypes.APPLICATION_M3U8)
    private var attempt = 0
    private var retries = 0
    private var firstStart = true
    private var released = false
    private var lastPos = 0L

    private val dataSourceFactory = DefaultDataSource.Factory(
        context,
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
    )
    private val errorPolicy = DefaultLoadErrorHandlingPolicy(6)
    private val defaultFactory: DefaultMediaSourceFactory

    val exo: ExoPlayer

    init {
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        defaultFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
        defaultFactory.setLoadErrorHandlingPolicy(errorPolicy)

        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val selector = DefaultTrackSelector(context)
        selector.setParameters(
            selector.buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
        )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (live) 15_000 else 30_000,
                if (live) 50_000 else 90_000,
                2_500, 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(defaultFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.buffering = playbackState == Player.STATE_BUFFERING
                state.ended = playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) { state.ready = true; retries = 0 }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { state.playing = playWhenReady }

            override fun onTracksChanged(tracks: Tracks) { state.tracksVersion++ }

            override fun onPlayerError(error: PlaybackException) {
                if (released) return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition(); exo.prepare(); return
                }
                val isNetwork = error.errorCode in 2000..2999
                retryOrFail(error.errorCodeName, isNetwork)
            }
        })
        // Inicializa la cadena de efectos antes de preparar el primer video.
        exo.setVideoEffects(emptyList())
        load(resume = false)
    }

    private fun retryOrFail(reason: String, isNetwork: Boolean) {
        if (released) return
        if (isNetwork && retries < 2) {
            retries++
            handler.postDelayed({ if (!released) load(resume = true) }, 1500L * retries)
        } else if (attempt < attempts.lastIndex) {
            attempt++; retries = 0
            load(resume = false)
        } else onFatal("ExoPlayer: $reason")
    }

    private fun buildSource(item: MediaItem, mime: String?): MediaSource =
        if (mime == MimeTypes.APPLICATION_M3U8) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setExtractorFactory(
                    DefaultHlsExtractorFactory(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                        true
                    )
                )
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(item)
        } else defaultFactory.createMediaSource(item)

    private fun load(resume: Boolean) {
        val mime = attempts[attempt]
        val item = MediaItem.Builder().setUri(entry.url).apply { if (mime != null) setMimeType(mime) }.build()
        val start = when {
            live -> C.TIME_UNSET
            resume && lastPos > 0 -> lastPos
            firstStart && resumeMs > 10_000 -> resumeMs
            else -> C.TIME_UNSET
        }
        firstStart = false
        exo.setMediaSource(buildSource(item, mime), start)
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun toggle() {
        if (exo.playbackState == Player.STATE_ENDED) { exo.seekTo(0); exo.playWhenReady = true }
        else exo.playWhenReady = !exo.playWhenReady
    }

    override fun seekBy(ms: Long) {
        val target = (exo.currentPosition + ms).coerceAtLeast(0L)
        val d = exo.duration
        exo.seekTo(if (d != C.TIME_UNSET && d > 0) target.coerceAtMost(d) else target)
    }

    override fun seekTo(ms: Long) = exo.seekTo(ms)

    override fun poll() {
        state.position = exo.currentPosition.coerceAtLeast(0L)
        val d = exo.duration
        state.duration = if (d == C.TIME_UNSET) 0L else d
        state.live = live || exo.isCurrentMediaItemLive
        lastPos = state.position
    }

    private fun options(type: Int): List<TrackOpt> {
        val out = mutableListOf<TrackOpt>()
        exo.currentTracks.groups.forEachIndexed { gi, group ->
            if (group.type == type) {
                for (ti in 0 until group.length) {
                    if (!group.isTrackSupported(ti)) continue
                    val f = group.getTrackFormat(ti)
                    val lang = f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
                    val base = f.label ?: lang ?: "Pista ${out.size + 1}"
                    val extra = if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) " · ${f.channelCount} can." else ""
                    out += TrackOpt("$gi:$ti", base + extra, group.isTrackSelected(ti))
                }
            }
        }
        return out
    }

    override fun audioOptions(): List<TrackOpt> = options(C.TRACK_TYPE_AUDIO)

    override fun videoOptions(): List<TrackOpt> = options(C.TRACK_TYPE_VIDEO).mapIndexed { index, opt ->
        val (gi, ti) = opt.id.split(":").map { it.toInt() }
        val format = exo.currentTracks.groups.getOrNull(gi)?.getTrackFormat(ti)
        val resolution = format?.height?.takeIf { it > 0 }?.let { "${it}p" }
        opt.copy(label = resolution ?: opt.label.ifBlank { "Calidad ${index + 1}" })
    }

    override fun subtitleOptions(): List<TrackOpt> {
        val subs = options(C.TRACK_TYPE_TEXT)
        if (subs.isEmpty()) return emptyList()
        val none = subs.none { it.selected } || exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        return listOf(TrackOpt("off", "Desactivados", none)) + subs.map { if (none) it.copy(selected = false) else it }
    }

    private fun select(type: Int, id: String) {
        val params = exo.trackSelectionParameters.buildUpon()
        if (id == "off") {
            params.setTrackTypeDisabled(type, true)
        } else {
            val (gi, ti) = id.split(":").map { it.toInt() }
            val group = exo.currentTracks.groups.getOrNull(gi) ?: return
            params.setTrackTypeDisabled(type, false)
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti))
        }
        exo.trackSelectionParameters = params.build()
    }

    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)
    override fun selectSubtitle(id: String) = select(C.TRACK_TYPE_TEXT, id)
    override fun selectVideo(id: String) = select(C.TRACK_TYPE_VIDEO, id)

    override fun setPictureEnhancement(enabled: Boolean) {
        if (released) return
        exo.setVideoEffects(
            if (enabled) listOf(
                Brightness(0.015f),
                Contrast(0.08f),
                HslAdjustment.Builder().adjustSaturation(7f).build()
            ) else emptyList()
        )
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        exo.release()
    }

    @Composable
    override fun Content(modifier: Modifier, aspect: AspectMode) {
        BoxWithConstraints(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
            val ratio = when (aspect) {
                AspectMode.RATIO_16_9 -> 16f / 9f
                AspectMode.RATIO_4_3 -> 4f / 3f
                else -> null
            }
            val playerModifier = if (ratio == null) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().aspectRatio(ratio).heightIn(max = maxHeight)
            AndroidView(
                modifier = playerModifier,
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exo
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.resizeMode = when (aspect) {
                        AspectMode.FIT, AspectMode.RATIO_16_9, AspectMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                }
            )
        }
    }
}

// ───────────────────────────── VLC (libVLC) ─────────────────────────────

class VlcController(
    context: Context,
    private val entry: Entry,
    private val live: Boolean,
    private val resumeMs: Long,
    private val onFatal: (String) -> Unit
) : Controller() {
    override val engine = EngineType.VLC

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--http-reconnect",
            "--network-caching=2000",
            "--live-caching=2000",
            "--file-caching=1500",
            "--audio-time-stretch"
        )
    )
    private val mp = MediaPlayer(libVlc)
    private var released = false
    private var attached = false
    private var resumed = false
    private var aspect = AspectMode.FIT

    init {
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    state.playing = true; state.ready = true; state.buffering = false; state.ended = false
                    if (!resumed && !live && resumeMs > 10_000) { resumed = true; mp.setTime(resumeMs) }
                    applyAspect(aspect)
                    state.tracksVersion++
                }
                MediaPlayer.Event.Paused -> state.playing = false
                MediaPlayer.Event.Stopped -> state.playing = false
                MediaPlayer.Event.EndReached -> state.ended = true
                MediaPlayer.Event.EncounteredError -> if (!released) onFatal("VLC: error de reproducción")
                MediaPlayer.Event.Buffering -> state.buffering = ev.buffering < 100f && !state.ended
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> state.tracksVersion++
                else -> Unit
            }
        }
    }

    fun attach(layout: VLCVideoLayout) {
        if (attached || released) return
        attached = true
        mp.attachViews(layout, null, true, false)
        val media = Media(libVlc, Uri.parse(entry.url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=2000")
        mp.setMedia(media)
        media.release()
        mp.play()
    }

    fun applyAspect(mode: AspectMode) {
        aspect = mode
        if (released || !attached) return
        try {
            when (mode) {
                AspectMode.RATIO_16_9 -> { mp.setAspectRatio("16:9"); mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT) }
                AspectMode.RATIO_4_3 -> { mp.setAspectRatio("4:3"); mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT) }
                AspectMode.FIT -> { mp.setAspectRatio(null); mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT) }
                AspectMode.FILL -> { mp.setAspectRatio(null); mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL) }
            }
        } catch (_: Throwable) { }
    }

    override fun toggle() {
        if (released) return
        if (mp.isPlaying) { mp.pause(); state.playing = false } else { mp.play(); state.playing = true }
    }

    override fun seekBy(ms: Long) {
        if (released || !mp.isSeekable) return
        mp.setTime((mp.getTime() + ms).coerceAtLeast(0L))
    }

    override fun seekTo(ms: Long) {
        if (released || !mp.isSeekable) return
        mp.setTime(ms)
    }

    override fun poll() {
        if (released) return
        state.position = mp.getTime().coerceAtLeast(0L)
        val len = mp.getLength()
        state.duration = if (len > 0) len else 0L
        state.live = live || len <= 0L
    }

    override fun audioOptions(): List<TrackOpt> {
        if (released) return emptyList()
        val tracks = mp.getAudioTracks() ?: return emptyList()
        val current = mp.getAudioTrack()
        return tracks.filter { it.id >= 0 }.map { TrackOpt(it.id.toString(), it.name ?: "Audio ${it.id}", it.id == current) }
    }

    override fun videoOptions(): List<TrackOpt> = emptyList()

    override fun selectVideo(id: String) = Unit

    // libVLC Android no expone controles de ajuste de imagen en esta API.
    // La mejora GPU se aplica en ExoPlayer; VLC mantiene la imagen original.
    override fun setPictureEnhancement(enabled: Boolean) = Unit

    override fun subtitleOptions(): List<TrackOpt> {
        if (released) return emptyList()
        val tracks = mp.getSpuTracks() ?: return emptyList()
        val real = tracks.filter { it.id >= 0 }
        if (real.isEmpty()) return emptyList()
        val current = mp.getSpuTrack()
        return listOf(TrackOpt("off", "Desactivados", current < 0)) +
            real.map { TrackOpt(it.id.toString(), it.name ?: "Subtítulo ${it.id}", it.id == current) }
    }

    override fun selectAudio(id: String) {
        id.toIntOrNull()?.let { mp.setAudioTrack(it) }
        state.tracksVersion++
    }

    override fun selectSubtitle(id: String) {
        mp.setSpuTrack(if (id == "off") -1 else id.toIntOrNull() ?: -1)
        state.tracksVersion++
    }

    override fun release() {
        if (released) return
        released = true
        val player = mp
        val lib = libVlc
        try { player.setEventListener(null) } catch (_: Throwable) { }
        try { player.detachViews() } catch (_: Throwable) { }
        // stop()/release() de libVLC pueden bloquear con redes lentas: se hacen fuera del hilo principal.
        Thread {
            try { player.stop() } catch (_: Throwable) { }
            try { player.release() } catch (_: Throwable) { }
            try { lib.release() } catch (_: Throwable) { }
        }.start()
    }

    @Composable
    override fun Content(modifier: Modifier, aspect: AspectMode) {
        AndroidView(
            modifier = modifier,
            factory = { ctx -> VLCVideoLayout(ctx).also { attach(it) } },
            update = { applyAspect(aspect) }
        )
    }
}
