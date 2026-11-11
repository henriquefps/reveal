package dev.hfps.reveal.pipeline

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the instrumented runner works in this module and the device can host the GPU pipeline (OpenGL ES 3.0+). */
@RunWith(AndroidJUnit4::class)
class PipelineSmokeTest {
    @Test
    fun deviceSupportsOpenGlEs3() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        assertThat(activityManager.deviceConfigurationInfo.reqGlEsVersion).isAtLeast(GLES_3_0)
    }

    private companion object {
        const val GLES_3_0 = 0x30000
    }
}
