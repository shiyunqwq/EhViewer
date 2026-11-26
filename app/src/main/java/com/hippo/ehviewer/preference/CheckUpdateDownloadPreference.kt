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
package com.hippo.ehviewer.preference

import android.content.Context
import android.content.DialogInterface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadUpdateChecker
import com.hippo.ehviewer.download.GalleryUpgradeHelper
import com.hippo.ehviewer.ui.SettingsActivity
import com.hippo.preference.DialogPreference
import com.hippo.util.withUIContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheckUpdateDownloadPreference(
    context: Context,
    attrs: AttributeSet? = null,
) : DialogPreference(context, attrs),
    CoroutineScope {
    override val coroutineContext = Dispatchers.IO + Job()

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.settings_download_check_update)
            .setMessage(R.string.settings_download_check_update_confirm)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int ->
                startCheckUpdate()
            }
            .setNegativeButton(android.R.string.cancel, null)
    }

    private fun startCheckUpdate() {
        if (singletonJob?.isActive == true) {
            showTip(GetText.getString(R.string.settings_download_check_update_running))
            return
        }
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.preference_dialog_check_update, null)
        val progressText = dialogView.findViewById<TextView>(R.id.progress_text)
        val progressDialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_download_check_update)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                singletonJob?.cancel()
            }
            .show()

        val appContext = context.applicationContext
        singletonJob = launch {
            var updatedCount = 0
            var failedCount = 0
            var total = 0
            var cancelled = false
            try {
                val downloadList = withUIContext {
                    DownloadManager.allDownloadInfoList.filter {
                        it.state != DownloadInfo.STATE_WAIT && it.state != DownloadInfo.STATE_DOWNLOAD
                    }
                }
                total = downloadList.size
                if (total == 0) {
                    return@launch
                }
                val checkResult = DownloadUpdateChecker.check(appContext, downloadList) { checked, totalCount, foundCount ->
                    withUIContext {
                        progressText.text = GetText.getString(
                            R.string.settings_download_check_update_progress,
                            checked,
                            totalCount,
                            foundCount,
                        )
                    }
                }
                failedCount += checkResult.failed

                val queuedGids = HashSet<Long>()
                for ((oldGid, latest) in checkResult.latestVersions) {
                    ensureActive()
                    val (latestGid, latestToken) = latest
                    if (latestGid != oldGid && !queuedGids.contains(latestGid)) {
                        val alreadyDownloaded = withUIContext {
                            DownloadManager.containDownloadInfo(latestGid)
                        }
                        if (!alreadyDownloaded) {
                            queuedGids.add(latestGid)
                            when (
                                val result = GalleryUpgradeHelper.upgradeGallery(
                                    appContext,
                                    oldGid,
                                    latestGid,
                                    latestToken,
                                )
                            ) {
                                is GalleryUpgradeHelper.UpgradeResult.Success -> {
                                    updatedCount++
                                }

                                is GalleryUpgradeHelper.UpgradeResult.ForegroundServiceNotAllowed -> {
                                    withUIContext {
                                        DownloadManager.startDownload(
                                            result.galleryDetail,
                                            result.label,
                                            result.time,
                                            oldGid,
                                        )
                                    }
                                    updatedCount++
                                }

                                is GalleryUpgradeHelper.UpgradeResult.Failed -> {
                                    queuedGids.remove(latestGid)
                                    failedCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    runCatching {
                        if (progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                    }
                    val msg = when {
                        total == 0 -> GetText.getString(R.string.settings_download_check_update_empty)
                        cancelled -> GetText.getString(R.string.settings_download_check_update_cancelled, updatedCount)
                        updatedCount > 0 -> GetText.getString(R.string.settings_download_check_update_found, updatedCount)
                        failedCount > 0 -> GetText.getString(R.string.settings_download_check_update_done_with_errors, failedCount)
                        else -> GetText.getString(R.string.settings_download_check_update_none)
                    }
                    showTip(msg)
                }
            }
        }
    }

    private fun showTip(msg: String) {
        (context as? SettingsActivity)?.showTip(msg, Snackbar.LENGTH_LONG)
    }
}

private var singletonJob: Job? = null
