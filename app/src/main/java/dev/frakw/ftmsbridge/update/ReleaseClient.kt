package dev.frakw.ftmsbridge.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tag: String,
    val versionCode: Long,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
)

interface ReleaseClient {
    fun latest(): ReleaseInfo

    fun text(url: String): String
}

class GithubReleaseClient : ReleaseClient {
    override fun latest(): ReleaseInfo = parseRelease(text(LATEST_RELEASE_URL))

    override fun text(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "ftms-bridge-android-updater")
            val status = connection.responseCode
            if (status !in 200..299) throw UpdateException("GitHub returned HTTP $status")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Mahagon/ftsm-indoorbike-googlefit-bridge/releases/latest"
    }
}

internal fun parseRelease(json: String): ReleaseInfo {
    val value = JSONObject(json)
    if (value.optBoolean("draft") || value.optBoolean("prerelease")) {
        throw UpdateException("The latest release is not a stable release")
    }
    val tag = value.getString("tag_name")
    val versionCode = versionCode(tag)
    val apkName = "ftms-bridge-$tag.apk"
    val checksumName = "$apkName.sha256"
    val assets = value.getJSONArray("assets")
    val matching = (0 until assets.length()).map { assets.getJSONObject(it) }
    val apks = matching.filter { it.getString("name") == apkName }
    val checksums = matching.filter { it.getString("name") == checksumName }
    if (apks.size != 1 || checksums.size != 1) throw UpdateException("Release assets are missing or ambiguous")
    return ReleaseInfo(
        tag = tag,
        versionCode = versionCode,
        title = value.optString("name").ifBlank { tag },
        notes = value.optString("body"),
        apkName = apkName,
        apkUrl = apks.single().getString("browser_download_url"),
        checksumUrl = checksums.single().getString("browser_download_url"),
    )
}

internal fun versionCode(tag: String): Long {
    val match = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(tag)
        ?: throw UpdateException("Unsupported release tag: $tag")
    val (major, minor, patch) = match.destructured.toList().map(String::toLong)
    if (major > 2_100 || minor > 999 || patch > 999) throw UpdateException("Release version is outside Android limits")
    return major * 1_000_000 + minor * 1_000 + patch
}

class UpdateException(message: String) : Exception(message)
