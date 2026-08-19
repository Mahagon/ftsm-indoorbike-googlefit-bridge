package dev.frakw.ftmsbridge.update

import android.content.Context

internal class UpdatePreferences(context: Context) {
    private val values = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    var lastCheck: Long
        get() = values.getLong("last_check", 0)
        set(value) = values.edit().putLong("last_check", value).apply()

    fun dismiss(tag: String, at: Long) {
        values.edit().putString("dismissed_tag", tag).putLong("dismissed_at", at).apply()
    }

    fun isDismissed(tag: String, now: Long) = values.getString("dismissed_tag", null) == tag && isRecent(values.getLong("dismissed_at", 0), now)

    fun saveDownload(id: Long, release: ReleaseInfo, checksum: String) {
        values.edit()
            .putLong("download_id", id)
            .putString("download_tag", release.tag)
            .putLong("download_version", release.versionCode)
            .putString("download_title", release.title)
            .putString("download_notes", release.notes)
            .putString("download_name", release.apkName)
            .putString("download_apk_url", release.apkUrl)
            .putString("download_checksum_url", release.checksumUrl)
            .putString("download_checksum", checksum)
            .apply()
    }

    fun download(): SavedDownload? {
        val id = values.getLong("download_id", -1)
        val tag = values.getString("download_tag", null) ?: return null
        if (id < 0) return null
        return SavedDownload(
            id,
            ReleaseInfo(
                tag,
                values.getLong("download_version", 0),
                values.getString("download_title", tag).orEmpty(),
                values.getString("download_notes", "").orEmpty(),
                values.getString("download_name", null) ?: return null,
                values.getString("download_apk_url", "").orEmpty(),
                values.getString("download_checksum_url", "").orEmpty(),
            ),
            values.getString("download_checksum", null) ?: return null,
        )
    }

    fun clearDownload() {
        values.edit()
            .remove("download_id")
            .remove("download_tag")
            .remove("download_version")
            .remove("download_title")
            .remove("download_notes")
            .remove("download_name")
            .remove("download_apk_url")
            .remove("download_checksum_url")
            .remove("download_checksum")
            .apply()
    }

    companion object {
        const val CHECK_INTERVAL = 24 * 60 * 60 * 1_000L
    }
}

internal data class SavedDownload(val id: Long, val release: ReleaseInfo, val checksum: String)

internal fun isRecent(timestamp: Long, now: Long): Boolean = timestamp > 0 && now >= timestamp && now - timestamp < UpdatePreferences.CHECK_INTERVAL
