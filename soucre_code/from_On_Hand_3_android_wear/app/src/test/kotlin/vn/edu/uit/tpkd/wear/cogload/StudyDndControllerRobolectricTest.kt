package vn.edu.uit.tpkd.wear.cogload

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudyDndControllerRobolectricTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        context.getSharedPreferences("focusmate_dnd_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        StudyDndController.disable(context)
        context.getSharedPreferences("focusmate_dnd_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun repeatedEnableAndDisableOwnAndReleaseDndExactlyOnce() {
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        assertTrue(StudyDndController.enable(context))
        assertTrue(StudyDndController.enable(context))
        assertTrue(StudyDndController.isOwned(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, manager.currentInterruptionFilter)

        StudyDndController.disable(context)
        StudyDndController.disable(context)
        assertFalse(StudyDndController.isOwned(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, manager.currentInterruptionFilter)
    }
}
