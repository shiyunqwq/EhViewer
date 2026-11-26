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
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Checks downloaded galleries for newer versions and remembers which ones are outdated,
 * so the download list can be filtered by "has update" without re-checking every time.
 */
object DownloadUpdateChecker {
    private const val PREF_NAME = "download_update_checker"
    private const val KEY_OUTDATED_GIDS = "outdated_gids"
    private const val KEY_HAS_CHECKED = "has_checked"

    // Same interval as the "check update" preference to avoid hammering the server
    private const val CHECK_INTERVAL_MS = 500L

    private val lock = Any()
    private var outdated: MutableSet<Long>? = null
    private var hasChecked = false

    class Result(val total: Int, val checked: Int, val found: Int, val failed: Int)

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
     * Check every gallery in [infos] one by one. Results are saved incrementally, so a
     * cancelled check still keeps what has been found so far.
     */
    suspend fun check(
        context: Context,
        infos: List<GalleryInfo>,
        onProgress: suspend (checked: Int, total: Int, found: Int) -> Unit,
    ): Result {
        val set = ensureLoaded(context)
        val total = infos.size
        var checked = 0
        var found = 0
        var failed = 0
        try {
            onProgress(0, total, 0)
            for ((index, info) in infos.withIndex()) {
                currentCoroutineContext().ensureActive()
                val token = info.token
                if (!token.isNullOrEmpty()) {
                    val url = EhUrl.getGalleryDetailUrl(info.gid, token, 0, false)
                    val detail = runSuspendCatching {
                        EhEngine.getGalleryDetail(url)
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        e.printStackTrace()
                        failed++
                    }.getOrNull()
                    if (detail != null) {
                        val hasNewer = detail.newerVersions.any { it.gid != info.gid }
                        synchronized(lock) {
                            if (hasNewer) set.add(info.gid) else set.remove(info.gid)
                        }
                        if (hasNewer) found++
                    }
                } else {
                    failed++
                }
                checked++
                onProgress(checked, total, found)
                if (index < total - 1) {
                    delay(CHECK_INTERVAL_MS)
                }
            }
        } finally {
            synchronized(lock) { hasChecked = true }
            save(context)
        }
        return Result(total, checked, found, failed)
    }
}
