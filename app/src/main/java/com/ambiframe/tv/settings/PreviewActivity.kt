package com.ambiframe.tv.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.ambiframe.tv.dream.ScreensaverSession

/**
 * "Preview screensaver": the real screensaver (art and Ambilight switching) in an activity, so it
 * can be tried without making AmbiFrame the system screensaver. Any key ends it, as in the dream.
 */
class PreviewActivity : ComponentActivity() {
    private lateinit var session: ScreensaverSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        session = ScreensaverSession(this, lifecycleScope)
        setContent { session.Content() }
    }

    override fun onStart() {
        super.onStart()
        session.start()
    }

    override fun onStop() {
        session.stop()
        super.onStop()
        // A preview that was left (Home, input switch) is over; don't resume it later.
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }
}
