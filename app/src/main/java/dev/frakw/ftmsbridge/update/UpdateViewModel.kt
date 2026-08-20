package dev.frakw.ftmsbridge.update

import android.app.Application
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.frakw.ftmsbridge.BuildConfig
import dev.frakw.ftmsbridge.MainActivity
import dev.frakw.ftmsbridge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Clock

enum class UpdateStatus { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, UP_TO_DATE, ERROR }

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val release: ReleaseInfo? = null,
    val progress: Int? = null,
    val message: String? = null,
)

class UpdateViewModel(
    application: Application,
    private val client: ReleaseClient = GithubReleaseClient(),
    private val clock: Clock = Clock.systemUTC(),
) : AndroidViewModel(application) {
    private val preferences = UpdatePreferences(application)
    private val downloads = application.getSystemService(DownloadManager::class.java)
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private var polling: Job? = null

    init {
        restoreDownload()
    }

    fun automaticCheck() {
        val now = clock.millis()
        if (!shouldAutomaticCheck(BuildConfig.DEBUG, mutableState.value.status, preferences.lastCheck, now)) return
        check(manual = false)
    }

    fun manualCheck() {
        if (!BuildConfig.DEBUG) check(manual = true)
    }

    fun dismiss() {
        mutableState.value.release?.let { preferences.dismiss(it.tag, clock.millis()) }
        mutableState.value = UpdateUiState()
    }

    fun startDownload() {
        val release = mutableState.value.release ?: return
        if (mutableState.value.status !in setOf(UpdateStatus.AVAILABLE, UpdateStatus.ERROR)) return
        mutableState.value = UpdateUiState(UpdateStatus.DOWNLOADING, release, progress = 0)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val checksum = parseChecksum(client.text(release.checksumUrl), release.apkName)
                val target = targetFile(release.apkName)
                if (target.exists() && !target.delete()) throw UpdateException(getApplication<Application>().getString(R.string.update_replace_failed))
                target.parentFile?.mkdirs()
                val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                    .setTitle(getApplication<Application>().getString(R.string.update_download_title, release.tag))
                    .setDescription(getApplication<Application>().getString(R.string.downloading_update))
                    .setMimeType(APK_MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(
                        getApplication(),
                        Environment.DIRECTORY_DOWNLOADS,
                        "updates/${release.apkName}",
                    )
                val id = downloads.enqueue(request)
                preferences.saveDownload(id, release, checksum)
                poll(SavedDownload(id, release, checksum))
            } catch (error: Exception) {
                fail(release, error)
            }
        }
    }

    fun readyFile(): File? = mutableState.value
        .takeIf { it.status == UpdateStatus.READY }
        ?.release
        ?.let { targetFile(it.apkName) }
        ?.takeIf(File::exists)

    private fun check(manual: Boolean) {
        if (mutableState.value.status == UpdateStatus.CHECKING) return
        if (manual) mutableState.value = UpdateUiState(UpdateStatus.CHECKING)
        preferences.lastCheck = clock.millis()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val release = client.latest()
                val newer = release.versionCode > BuildConfig.VERSION_CODE
                val dismissed = preferences.isDismissed(release.tag, clock.millis())
                mutableState.value = when {
                    newer && (manual || !dismissed) -> UpdateUiState(UpdateStatus.AVAILABLE, release)
                    manual && !newer -> UpdateUiState(UpdateStatus.UP_TO_DATE, message = getApplication<Application>().getString(R.string.latest_version))
                    else -> UpdateUiState()
                }
            } catch (error: Exception) {
                mutableState.value = if (manual) {
                    UpdateUiState(UpdateStatus.ERROR, message = error.message ?: getApplication<Application>().getString(R.string.update_check_failed))
                } else {
                    UpdateUiState()
                }
            }
        }
    }

    private fun restoreDownload() {
        val saved = preferences.download() ?: return
        if (saved.release.versionCode <= BuildConfig.VERSION_CODE) {
            targetFile(saved.release.apkName).delete()
            preferences.clearDownload()
            return
        }
        mutableState.value = UpdateUiState(UpdateStatus.DOWNLOADING, saved.release)
        viewModelScope.launch(Dispatchers.IO) { poll(saved) }
    }

    private suspend fun poll(saved: SavedDownload) {
        polling?.cancel()
        polling = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val snapshot = query(saved.id)
                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        verify(saved)
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        preferences.clearDownload()
                        fail(saved.release, UpdateException(getApplication<Application>().getString(R.string.update_download_failed, snapshot.reason)))
                        return@launch
                    }

                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_RUNNING,
                    -> mutableState.value = UpdateUiState(UpdateStatus.DOWNLOADING, saved.release, snapshot.progress)

                    else -> {
                        preferences.clearDownload()
                        fail(saved.release, UpdateException(getApplication<Application>().getString(R.string.update_download_unavailable)))
                        return@launch
                    }
                }
                delay(1_000)
            }
        }
        polling?.join()
    }

    private fun query(id: Long): DownloadSnapshot {
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return DownloadSnapshot(0, null, 0)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else null
            return DownloadSnapshot(status, progress, reason)
        }
    }

    private suspend fun verify(saved: SavedDownload) = withContext(Dispatchers.IO) {
        try {
            val file = targetFile(saved.release.apkName)
            val application = getApplication<Application>()
            if (!file.isFile) throw UpdateException(application.getString(R.string.update_apk_missing))
            if (!sha256(file).equals(saved.checksum, ignoreCase = true)) throw UpdateException(application.getString(R.string.update_checksum_mismatch))
            val packageInfo = application.packageManager.getPackageArchiveInfo(file.path, 0)
                ?: throw UpdateException(application.getString(R.string.update_invalid_apk))
            if (packageInfo.packageName != BuildConfig.APPLICATION_ID) throw UpdateException(application.getString(R.string.update_wrong_package))
            if (packageInfo.longVersionCode <= BuildConfig.VERSION_CODE) throw UpdateException(application.getString(R.string.update_not_newer))
            ApkSignatureVerifier(application.packageManager, BuildConfig.APPLICATION_ID).verify(file)
            mutableState.value = UpdateUiState(UpdateStatus.READY, saved.release, progress = 100)
            notifyReady(saved.release)
        } catch (error: Exception) {
            targetFile(saved.release.apkName).delete()
            preferences.clearDownload()
            fail(saved.release, error)
        }
    }

    private fun fail(release: ReleaseInfo?, error: Exception) {
        mutableState.value = UpdateUiState(
            UpdateStatus.ERROR,
            release,
            message = error.message ?: "Update failed",
        )
    }

    private fun notifyReady(release: ReleaseInfo) {
        val application = getApplication<Application>()
        val manager = application.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL, application.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            application,
            0,
            Intent().setClass(application, MainActivity::class.java).setPackage(application.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            UPDATE_NOTIFICATION_ID,
            Notification.Builder(application, UPDATE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(application.getString(R.string.update_ready_title, release.tag))
                .setContentText(application.getString(R.string.update_ready_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun targetFile(name: String): File = File(
        getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        "updates/$name",
    )

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val UPDATE_CHANNEL = "updates"
        private const val UPDATE_NOTIFICATION_ID = 304
    }
}

private data class DownloadSnapshot(val status: Int, val progress: Int?, val reason: Int)

internal fun shouldAutomaticCheck(
    debug: Boolean,
    status: UpdateStatus,
    lastCheck: Long,
    now: Long,
): Boolean = !debug && status == UpdateStatus.IDLE && !isRecent(lastCheck, now)

internal fun parseChecksum(value: String, apkName: String): String {
    val match = Regex("^([0-9a-fA-F]{64})(?:\\s+\\*?(.+))?$").matchEntire(value.trim())
        ?: throw UpdateException("Release checksum is malformed")
    val namedFile = match.groupValues[2]
    if (namedFile.isNotEmpty() && namedFile != apkName) throw UpdateException("Release checksum names a different file")
    return match.groupValues[1].lowercase()
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
