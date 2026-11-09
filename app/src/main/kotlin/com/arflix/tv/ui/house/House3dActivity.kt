package com.arflix.tv.ui.house

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.arflix.tv.data.repository.HomeAssistantRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** House 3D on demand -- the screensaver's view as a screen, for previewing it. Any key closes it. */
@AndroidEntryPoint
class House3dActivity : ComponentActivity() {
    @Inject lateinit var ha: HomeAssistantRepository

    private var view: House3dView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = House3dView(this, ha, lifecycleScope).also { setContentView(it); it.start() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }

    override fun onDestroy() {
        view?.stop()
        super.onDestroy()
    }
}
