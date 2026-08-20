package dev.frakw.ftmsbridge

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `german resources use informal localized workout wording`() {
        val german = localizedContext(Locale.GERMAN)
        assertEquals("Heimtrainer", german.getString(R.string.indoor_bike))
        assertEquals("Jetzt beenden", german.getString(R.string.finish_now))
        assertEquals("In Health Connect gespeichert", german.getString(R.string.saved_health_connect))
    }

    @Test
    fun `unsupported locale falls back to english`() {
        val french = localizedContext(Locale.FRENCH)
        assertEquals("Exercise bike", french.getString(R.string.indoor_bike))
        assertEquals("Finish now", french.getString(R.string.finish_now))
    }

    private fun localizedContext(locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}
