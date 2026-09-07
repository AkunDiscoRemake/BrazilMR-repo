package com.brazilmr

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brazilmr.ui.WorkspaceInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device/emulator smoke test. Camera grants and hardware accuracy are tested by the manual protocol. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test fun launcherCreatesNativeInputLayerAndClosesCleanly() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(containsInput(activity.window.decorView))
                assertFalse(activity.isFinishing)
            }
        }
    }
    private fun containsInput(view: View): Boolean {
        if(view is WorkspaceInputView) return true
        if(view is ViewGroup) for(i in 0 until view.childCount) if(containsInput(view.getChildAt(i))) return true
        return false
    }
}
