package dev.frakw.ftmsbridge

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupConfigurationTest {
    @Test
    fun `backup is enabled and restricted to encrypted database data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)

        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val includes = mutableListOf<Pair<String?, String?>>()
        var encryptedCloudBackup = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup" ->
                        encryptedCloudBackup =
                            parser.getAttributeValue(null, "disableIfNoEncryptionCapabilities") == "true"

                    "include" ->
                        includes +=
                            parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                }
            }
            parser.next()
        }
        parser.close()

        assertTrue(encryptedCloudBackup)
        assertEquals(listOf("database" to ".", "database" to "."), includes)
    }
}
