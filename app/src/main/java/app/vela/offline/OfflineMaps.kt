package app.vela.offline

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.json.JSONObject
import kotlin.math.max

/**
 * Offline basemap regions, via MapLibre Native's built-in offline store (the same
 * SDK we already render with). Downloading a region pulls the keyless basemap's
 * tiles/glyphs/sprites for a bounding box + zoom range into a local SQLite store;
 * the map then renders that area with no network. On-ethos: open tiles, no Google,
 * no backend. Routing + POI search still need network (offline routing/search are
 * a heavier follow-on).
 */
object OfflineMaps {

    /** Generous tile cap so a screen-sized area downloads; very large areas still
     *  hit it and report back so the user can zoom in. */
    private const val TILE_LIMIT = 50_000L

    /** How a tile download ended - the caller turns these into localized user text. */
    enum class DoneReason { SAVED, FAILED, TOO_LARGE }

    /** Esri World Imagery raster (same tiles the live satellite button serves):
     *  openly usable, z0–19 native. */
    const val ESRI_TILES = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** Kick off a tile download for [bounds]. Progress arrives as 0..100 through [onProgress]
     *  (state-driven card material, NOT flash-status spam - the old string callback re-flashed the
     *  heads-up banner on every tick, which stacked a second banner over the progress card, user
     *  2026-07-23), the created [OfflineRegion] is handed out through [onCreated] so the caller can
     *  CANCEL (STATE_INACTIVE + delete), and [onDone] fires exactly once with the outcome.
     *
     *  [satelliteTiles]: when non-null, the region ALSO packs that raster source (satellite
     *  imagery is a runtime-added source, so the plain style never contains it). The style is
     *  fetched, augmented, and handed to the packer as a temp file — any failure falls back to
     *  the plain style (vector-only), never a failed download. */
    fun download(
        context: Context,
        styleUrl: String,
        bounds: LatLngBounds,
        minZoom: Double,
        maxZoom: Double,
        name: String,
        satelliteTiles: String? = null,
        onCreated: (OfflineRegion) -> Unit = {},
        onProgress: (Int) -> Unit = {},
        onDone: (DoneReason) -> Unit,
    ) {
        if (satelliteTiles == null) {
            create(context, styleUrl, bounds, minZoom, maxZoom, name, onCreated, onProgress, onDone)
            return
        }
        // Style fetch off the main thread; creation back on it (MapLibre requires it).
        kotlin.concurrent.thread {
            val effective = runCatching { withSatellite(context, styleUrl, satelliteTiles) }.getOrNull() ?: styleUrl
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                create(context, effective, bounds, minZoom, maxZoom, name, onCreated, onProgress, onDone)
            }
        }
    }

    /** Style JSON with [tiles] injected as a raster source, materialized to a temp file.
     *  Packing reads sources, not layers, so no layer is added. Throws on any failure
     *  (the caller falls back to the plain style). */
    private fun withSatellite(context: Context, styleUrl: String, tiles: String): String {
        val json = when {
            styleUrl.startsWith("asset://") ->
                context.assets.open(styleUrl.removePrefix("asset://")).bufferedReader().readText()
            styleUrl.startsWith("file://") ->
                java.io.File(styleUrl.removePrefix("file://")).readText()
            styleUrl.startsWith("http") ->
                java.net.URL(styleUrl).openStream().bufferedReader().readText()
            else -> return styleUrl
        }
        val root = JSONObject(json)
        val sources = root.optJSONObject("sources") ?: JSONObject().also { root.put("sources", it) }
        sources.put(
            "vela-sat-offline",
            JSONObject()
                .put("type", "raster")
                .put("tiles", org.json.JSONArray().put(tiles))
                .put("tileSize", 256),
        )
        val f = java.io.File(context.cacheDir, "offline-style-sat.json")
        f.writeText(root.toString())
        return "file://" + f.absolutePath
    }

    private fun create(
        context: Context,
        styleUrl: String,
        bounds: LatLngBounds,
        minZoom: Double,
        maxZoom: Double,
        name: String,
        onCreated: (OfflineRegion) -> Unit,
        onProgress: (Int) -> Unit,
        onDone: (DoneReason) -> Unit,
    ) {
        val manager = OfflineManager.getInstance(context)
        manager.setOfflineMapboxTileCountLimit(TILE_LIMIT)
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            minZoom,
            maxZoom,
            context.resources.displayMetrics.density,
        )
        val metadata = JSONObject().put(KEY_NAME, name).toString().toByteArray()
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    onCreated(region)
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        // onStatusChanged keeps firing around completion; onDone must fire ONCE.
                        var done = false
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (done) return
                            if (status.isComplete) {
                                done = true
                                onDone(DoneReason.SAVED)
                            } else {
                                onProgress(
                                    (100.0 * status.completedResourceCount /
                                        max(1L, status.requiredResourceCount)).toInt(),
                                )
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            if (done) return
                            done = true
                            onDone(DoneReason.FAILED)
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            if (done) return
                            done = true
                            onDone(DoneReason.TOO_LARGE)
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    onDone(DoneReason.FAILED)
                }
            },
        )
    }

    fun list(context: Context, onResult: (List<OfflineRegion>) -> Unit) {
        OfflineManager.getInstance(context).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) = onResult(regions?.toList().orEmpty())
                override fun onError(error: String) = onResult(emptyList())
            },
        )
    }

    fun delete(region: OfflineRegion, onDone: () -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone()
            override fun onError(error: String) = onDone()
        })
    }

    /** Give the space back. MapLibre keeps saved areas and the browsing cache in ONE SQLite file,
     *  and deleting a region removes its rows, not the bytes: the file keeps its size until it is
     *  packed (VACUUM). A phone that had saved and deleted a few big areas showed 5 GB of "map
     *  data" with nothing left to delete (issue #601). Called after every area delete and by the
     *  delete-everything path; always completes (an error is reported as done). */
    fun packDatabase(context: Context, onDone: () -> Unit = {}) {
        runCatching {
            OfflineManager.getInstance(context).packDatabase(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = onDone()
                override fun onError(message: String) = onDone()
            })
        }.onFailure { onDone() }
    }

    /** Delete every saved area, then pack the database. */
    fun deleteAll(context: Context, onDone: () -> Unit) {
        list(context) { regions ->
            var left = regions.size
            if (left == 0) { packDatabase(context, onDone); return@list }
            regions.forEach { r -> delete(r) { if (--left == 0) packDatabase(context, onDone) } }
        }
    }

    fun nameOf(region: OfflineRegion): String =
        runCatching { JSONObject(String(region.metadata)).optString(KEY_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "Saved area"

    /** The saved area's tile bounds, so callers can re-fetch its offline data (POIs/addresses) for the
     *  same box. Null if the region isn't a tile-pyramid definition. */
    fun boundsOf(region: OfflineRegion): LatLngBounds? =
        (region.definition as? org.maplibre.android.offline.OfflineTilePyramidRegionDefinition)?.bounds

    private const val KEY_NAME = "name"
}
