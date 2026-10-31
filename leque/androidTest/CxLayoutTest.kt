package com.lagradost.cloudstream3

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.databinding.AccountEditDialogBinding
import com.lagradost.cloudstream3.databinding.PlayerCustomLayoutBinding
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player binds the TV controls (player_custom_layout_tv) with the phone binding class, so a
 * view added to the phone layout only crashes every TV device when a video starts (V5.0.14).
 * Inflates and binds the layouts CloudXtream changes, on a real Android runtime.
 */
@RunWith(AndroidJUnit4::class)
class CxLayoutTest {
    private fun onMain(block: (android.content.Context) -> Unit) {
        var error: Throwable? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            try {
                block(ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme))
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
    }

    private fun inflate(context: android.content.Context, layout: Int): View =
        LayoutInflater.from(context).inflate(layout, FrameLayout(context), false)

    @Test fun playerControlsBindOnPhoneAndTv() = onMain { context ->
        for (layout in intArrayOf(R.layout.player_custom_layout, R.layout.player_custom_layout_tv)) {
            val binding = PlayerCustomLayoutBinding.bind(inflate(context, layout))
            assertNotNull(binding.root.findViewById<View>(R.id.player_cx_more_btt))
        }
    }

    @Test fun profileEditDialogBinds() = onMain { context ->
        assertNotNull(AccountEditDialogBinding.bind(inflate(context, R.layout.account_edit_dialog)).cxKidsProfileCheckbox)
    }
}
