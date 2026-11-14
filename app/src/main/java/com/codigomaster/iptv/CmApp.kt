package com.codigomaster.iptv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Cargador de imágenes global afinado para TV Box / Fire Stick (poca RAM):
 *  - caché de memoria acotada según la RAM del equipo,
 *  - caché en disco grande (los pósters no se vuelven a descargar al volver a la pantalla),
 *  - ignora cabeceras no-cache de los servidores de pósters (si no, se re-descargan siempre).
 */
class CmApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowRam = am.isLowRamDevice || am.memoryClass <= 96
        return ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (lowRam) 0.15 else 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("posters")).maxSizeBytes(200L * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}
