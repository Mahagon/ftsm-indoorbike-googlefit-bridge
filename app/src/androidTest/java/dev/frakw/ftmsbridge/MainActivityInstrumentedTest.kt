package dev.frakw.ftmsbridge

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.frakw.ftmsbridge.update.ApkSignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test
    fun mainActivityLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            }
        }
    }

    @Test
    fun mainActivitySupportsPortraitAndLandscape() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ).forEach { orientation ->
                scenario.onActivity { it.requestedOrientation = orientation }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        }
    }

    @Test
    fun privacyActivityLaunches() {
        ActivityScenario.launch(PrivacyActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            }
        }
    }

    @Test
    fun healthPermissionUsageOpensProtectedPrivacyActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = ComponentName(context.packageName, "${context.packageName}.ViewPermissionUsageActivity")
        val info = context.packageManager.getActivityInfo(alias, PackageManager.ComponentInfoFlags.of(0))
        assertEquals("${context.packageName}.PrivacyActivity", info.targetActivity)
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", info.permission)

        val intent = Intent(Intent.ACTION_VIEW_PERMISSION_USAGE)
            .addCategory("android.intent.category.HEALTH_PERMISSIONS")
            .setPackage(context.packageName)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        assertTrue(matches.any { it.activityInfo.name == alias.className })
    }

    @Test
    fun installedApkHasAVerifiableSigningIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ApkSignatureVerifier(context.packageManager, context.packageName).verify(File(context.applicationInfo.sourceDir))
    }
}
