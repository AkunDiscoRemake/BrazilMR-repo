package com.brazilmr

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brazilmr.ui.SpatialInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test fun headsetStartsAndActuallyRendersGlFramesWithoutCameraPermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val rendered=CountDownLatch(1)
            scenario.onActivity { activity ->
                assertTrue(containsInput(activity.window.decorView))
                clickCameraFreeEntry(activity.window.decorView)
                activity.window.decorView.postDelayed({rendered.countDown()},2500)
            }
            assertTrue("UI thread stopped responding",rendered.await(12,TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue("GLES renderer did not produce frames",activity.hasRenderedFrameForDiagnostics())
            }
        }
    }
    private fun containsInput(view: View): Boolean {
        if(view is SpatialInputView)return true
        if(view is ViewGroup)for(i in 0 until view.childCount)if(containsInput(view.getChildAt(i)))return true
        return false
    }
    private fun clickCameraFreeEntry(view: View) {
        if(view is Button && view.text.toString().startsWith("Entrar sem câmera")){view.performClick();return}
        if(view is ViewGroup)for(i in 0 until view.childCount)clickCameraFreeEntry(view.getChildAt(i))
    }
}
