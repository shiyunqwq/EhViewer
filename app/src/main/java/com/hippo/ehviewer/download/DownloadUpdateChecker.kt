/*
 * Copyright 2026 EhViewer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.download

import android.content.Context
import android.content.SharedPreferences
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.client.EhRequestBuilder
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dispatcher
import okhttp3.coroutines.executeAsync
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks downloaded galleries for newer versions concurrently using lightweight HTML matching
 * on `<div id="gnd">`, and persists which ones are outdated so the download list can filter
 * by "has update" without re-checking every time.
 */
object DownloadUpdateChecker {
    private const val PREF_NAME = "download_update_checker"
    private const val KEY_OUTDATED_GIDS = "outdated_gids"
    private const val KEY_HAS_CHECKED = "has_checked"

    // High concurrency with a dedicated dispatcher (default OkHttp maxRequestsPerHost is 5)
    private const val CONCURRENCY = 10

    private val GND_MARKER = "id=\"gnd\""
    private val PATTERN_GALLERY_LINK = Regex("""/g/(\d+)/([0-9a-f]+)/""")

    private val checkOkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = CONCURRENCY * 2
            maxRequestsPerHost = CONCURRENCY
        }
        EhApplication.nonCacheOkHttpClient.newBuilder()
            .dispatcher(dispatcher)
            .build()
    }

    private val lock = Any()
    private var outdated: MutableSet<Long>? = null
    private var hasChecked = false

    class Result(
        val total: Int,
        val checked: Int,
        val found: Int,
        val failed: Int,
        /** oldGid -> (latestGid, latestToken) for galleries that have a newer version */
        val latestVersions: Map<Long, Pair<Long, String>> = emptyMap(),
    )

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context): MutableSet<Long> = synchronized(lock) {
        outdated ?: run {
            val p = prefs(context)
            hasChecked = p.getBoolean(KEY_HAS_CHECKED, false)
            val set = p.getStringSet(KEY_OUTDATED_GIDS, null)
                ?.mapNotNullTo(HashSet()) { it.toLongOrNull() }
                ?: HashSet()
            outdated = set
            set
        }
    }

    private fun save(context: Context) {
        val snapshot = synchronized(lock) { outdated?.map { it.toString() }?.toSet() ?: emptySet() }
        prefs(context).edit()
            .putStringSet(KEY_OUTDATED_GIDS, snapshot)
            .putBoolean(KEY_HAS_CHECKED, hasChecked)
            .apply()
    }

    /** Whether a check has ever been run (so there is a previous result to show). */
    fun hasResult(context: Context): Boolean {
        ensureLoaded(context)
        return synchronized(lock) { hasChecked }
    }

    /** Snapshot of gids known to have a newer version. */
    fun outdatedGids(context: Context): Set<Long> {
        val set = ensureLoaded(context)
        return synchronized(lock) { HashSet(set) }
    }

    /**
     * Check every gallery in [infos] concurrently. Results are saved incrementally, so a
     * cancelled check still keeps what has been found so far.
     */
    suspend fun check(
        context: Context,
        infos: List<GalleryInfo>,
        onProgress: suspend (checked: Int, total: Int, found: Int) -> Unit,
    ): Result {
        val set = ensureLoaded(context)
        val total = infos.size
        val checked = AtomicInteger(0)
        val found = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val rateLimited = AtomicBoolean(false)
        val latestVersions = ConcurrentHashMap<Long, Pair<Long, String>>()

        try {
            onProgress(0, total, 0)
            if (total > 0) {
                val semaphore = Semaphore(CONCURRENCY)
                val referer = EhUrl.referer
                coroutineScope {
                    for (info in infos) {
                        ensureActive()
                        launch(Dispatchers.IO) {
                            semaphore.withPermit {
                                ensureActive()
                                if (rateLimited.get()) {
                                    failed.incrementAndGet()
                                    val c = checked.incrementAndGet()
                                    onProgress(c, total, found.get())
                                    return@withPermit
                                }
                                val token = info.token
                                if (token.isNullOrEmpty()) {
                                    failed.incrementAndGet()
                                } else {
                                    val checkOutcome = checkSingle(info.gid, token, referer, rateLimited)
                                    when (checkOutcome) {
                                        is SingleOutcome.HasUpdate -> {
                                            synchronized(lock) { set.add(info.gid) }
                                            latestVersions[info.gid] = checkOutcome.latestGid to checkOutcome.latestToken
                                            found.incrementAndGet()
                                        }

                                        SingleOutcome.UpToDate -> {
                                            synchronized(lock) { set.remove(info.gid) }
                                        }

                                        SingleOutcome.Failed -> {
                                            failed.incrementAndGet()
                                        }
                                    }
                                }
                                val c = checked.incrementAndGet()
                                onProgress(c, total, found.get())
                            }
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) { hasChecked = true }
            save(context)
        }
        return Result(total, checked.get(), found.get(), failed.get(), latestVersions)
    }

    private sealed interface SingleOutcome {
        data class HasUpdate(val latestGid: Long, val latestToken: String) : SingleOutcome
        data object UpToDate : SingleOutcome
        data object Failed : SingleOutcome
    }

    private suspend fun checkSingle(
        gid: Long,
        token: String,
        referer: String,
        rateLimited: AtomicBoolean,
    ): SingleOutcome {
        val url = EhUrl.getGalleryDetailUrl(gid, token, 0, false)
        val request = EhRequestBuilder(url, referer).build()
        return runSuspendCatching {
            checkOkHttpClient.newCall(request).executeAsync().use { response ->
                if (response.code == 429) {
                    rateLimited.set(true)
                    return@use SingleOutcome.Failed
                }
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return@use SingleOutcome.Failed
                }
                if (body.contains("Your IP address has been temporarily banned")) {
                    rateLimited.set(true)
                    return@use SingleOutcome.Failed
                }
                val gndIndex = body.indexOf(GND_MARKER)
                if (gndIndex < 0) {
                    // Valid gallery page without <div id="gnd"> means no newer versions
                    return@use if (body.contains("id=\"gd1\"") || body.contains("id=\"gn\"")) {
                        SingleOutcome.UpToDate
                    } else {
                        SingleOutcome.Failed
                    }
                }
                val endIndex = body.indexOf("</div>", gndIndex)
                val gndSection = if (endIndex > gndIndex) {
                    body.substring(gndIndex, endIndex)
                } else {
                    body.substring(gndIndex)
                }
                var latest: Pair<Long, String>? = null
                for (match in PATTERN_GALLERY_LINK.findAll(gndSection)) {
                    val newGid = match.groupValues[1].toLongOrNull() ?: continue
                    val newToken = match.groupValues[2]
                    if (newGid != gid) {
                        latest = newGid to newToken
                    }
                }
                if (latest != null) {
                    SingleOutcome.HasUpdate(latest.first, latest.second)
                } else {
                    SingleOutcome.UpToDate
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            e.printStackTrace()
            SingleOutcome.Failed
        }
    }
}
