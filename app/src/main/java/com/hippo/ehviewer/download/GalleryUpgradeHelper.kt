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

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.hippo.ehviewer.EhApplication.Companion.okHttpClient
import com.hippo.ehviewer.client.EhRequestBuilder
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.parser.GalleryDetailParser
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.readFromUniFile
import com.hippo.ehviewer.spider.saveToUniFile
import com.hippo.util.isAtLeastS
import com.hippo.util.runSuspendCatching
import com.hippo.util.withUIContext
import com.hippo.yorozuya.FileUtils
import kotlinx.coroutines.CancellationException
import okhttp3.coroutines.executeAsync

object GalleryUpgradeHelper {
    sealed class UpgradeResult {
        data object Success : UpgradeResult()
        data class ForegroundServiceNotAllowed(
            val intent: Intent,
            val galleryDetail: GalleryDetail,
            val label: String?,
            val time: Long,
        ) : UpgradeResult()

        data object Failed : UpgradeResult()
    }

    suspend fun upgradeGallery(
        context: Context,
        oldGid: Long,
        newGid: Long,
        newToken: String?,
    ): UpgradeResult {
        val oldInfo = withUIContext { DownloadManager.getDownloadInfo(oldGid) }
        val label = oldInfo?.label
        val time = oldInfo?.time ?: -1L
        val oldStartPage = SpiderDen.getGalleryDownloadDir(oldGid)
            ?.findFile(SpiderQueen.SPIDER_INFO_FILENAME)
            ?.let { readFromUniFile(it)?.startPage }
            ?: 0

        val url = EhUrl.getGalleryDetailUrl(
            newGid,
            newToken,
            0,
            false,
            SpiderQueen.GET_FULL_HASH,
        )
        val request = EhRequestBuilder(url, EhUrl.referer).build()
        var createdDir = false
        return runSuspendCatching {
            okHttpClient.newCall(request).executeAsync().use { response ->
                val body = response.body.string()
                val result = GalleryDetailParser.parse(body)
                val maxPageIndex = (result.pages - 1).coerceAtLeast(0)
                val spiderInfo = SpiderInfo(
                    result.gid,
                    result.token,
                    result.pages,
                    startPage = oldStartPage.coerceIn(0, maxPageIndex),
                    upgradeFrom = oldGid,
                )
                SpiderQueen.readPreviews(body, 0, spiderInfo)
                val dirName = FileUtils.sanitizeFilename("${result.gid}-${EhUtils.getSuitableTitle(result)}")
                val downloadDir = SpiderDen.perSafeDownloadDir(result.gid, dirName)
                    ?: return UpgradeResult.Failed
                createdDir = true
                val spiderInfoFile = downloadDir.createFile(SpiderQueen.SPIDER_INFO_FILENAME)
                    ?: run {
                        cleanupFailedUpgrade(result.gid)
                        return UpgradeResult.Failed
                    }
                spiderInfo.saveToUniFile(spiderInfoFile)

                val intent = Intent(context, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_START
                    putExtra(DownloadService.KEY_LABEL, label)
                    putExtra(DownloadService.KEY_GALLERY_INFO, result)
                    putExtra(DownloadService.KEY_TIME, time)
                }
                try {
                    ContextCompat.startForegroundService(context, intent)
                    UpgradeResult.Success
                } catch (e: Throwable) {
                    if (isAtLeastS && e is ForegroundServiceStartNotAllowedException) {
                        UpgradeResult.ForegroundServiceNotAllowed(intent, result, label, time)
                    } else {
                        e.printStackTrace()
                        cleanupFailedUpgrade(result.gid)
                        UpgradeResult.Failed
                    }
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            e.printStackTrace()
            if (createdDir) {
                cleanupFailedUpgrade(newGid)
            }
            UpgradeResult.Failed
        }
    }

    fun cleanupFailedUpgrade(newGid: Long) {
        runCatching {
            SpiderDen.getGalleryDownloadDir(newGid)?.takeIf { it.exists() }?.delete()
            DownloadManager.removeDownloadDirname(newGid)
        }
    }
}
