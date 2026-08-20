package dev.frakw.ftmsbridge

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.frakw.ftmsbridge.update.ApkSignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun installedApkHasAVerifiableSigningIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ApkSignatureVerifier(context.packageManager, context.packageName).verify(File(context.applicationInfo.sourceDir))
    }
}
