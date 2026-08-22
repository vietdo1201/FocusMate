package vn.edu.uit.tpkd.wear.cogload

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchAppSmokeTest {
    @Test
    fun applicationLoadsStandaloneRepository() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("vn.edu.uit.tpkd.wear.cogload", context.packageName)
        assertEquals("watch_rules_v2", WatchRuleEngine.RULE_VERSION)
        assertNotNull(StudySessionRepository(context).sessions())
    }
}
