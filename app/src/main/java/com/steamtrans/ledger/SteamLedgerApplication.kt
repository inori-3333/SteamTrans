package com.steamtrans.ledger

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/**
 * Keeps successfully downloaded item artwork for the lifetime of the app data.
 * HTTP expiry headers are deliberately ignored: artwork has no time-based TTL.
 */
class SteamLedgerApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .respectCacheHeaders(false)
        .diskCache {
            DiskCache.Builder()
                .directory(filesDir.resolve("item-artwork-cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()
}
