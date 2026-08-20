package dev.frakw.ftmsbridge.update

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal class ApkSignatureVerifier(
    private val packageManager: PackageManager,
    private val installedPackageName: String,
) {
    fun verify(file: File) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = packageManager.getPackageInfo(installedPackageName, flags).signingInfo
            ?: throw UpdateException("The installed app signing identity is unavailable")
        val archive = packageManager.getPackageArchiveInfo(file.path, flags)
            ?: throw UpdateException("Downloaded file is not a valid APK")
        val downloaded = if (Build.VERSION.SDK_INT >= 36) {
            PackageManager.getVerifiedSigningInfo(file.path, SigningInfo.VERSION_SIGNING_BLOCK_V2)
        } else {
            archive.signingInfo ?: throw UpdateException("Downloaded APK signing identity is unavailable")
        }
        if (!signingIdentityMatches(signingIdentity(installed), signingIdentity(downloaded))) {
            throw UpdateException("Downloaded APK is not signed by this app's signing identity")
        }
    }
}

internal data class SigningIdentity(
    val multipleSigners: Boolean,
    val current: Set<String>,
    val history: Set<String>,
)

internal fun signingIdentityMatches(
    installed: SigningIdentity,
    downloaded: SigningIdentity,
): Boolean {
    if (installed.current.isEmpty() || downloaded.current.isEmpty()) return false
    if (installed.multipleSigners || downloaded.multipleSigners) {
        return installed.multipleSigners && downloaded.multipleSigners && installed.current == downloaded.current
    }
    val installedSigner = installed.current.singleOrNull() ?: return false
    return downloaded.current.size == 1 && installedSigner in downloaded.history
}

private fun signingIdentity(info: SigningInfo): SigningIdentity {
    val current = info.apkContentsSigners.orEmpty().mapTo(mutableSetOf(), Signature::fingerprint)
    val history = if (info.hasMultipleSigners()) {
        current
    } else {
        info.signingCertificateHistory.orEmpty().mapTo(mutableSetOf(), Signature::fingerprint).ifEmpty { current }
    }
    return SigningIdentity(info.hasMultipleSigners(), current, history)
}

private fun Signature.fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
