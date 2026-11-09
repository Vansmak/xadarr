package com.arflix.tv.ui.house

import android.service.dreams.DreamService
import android.view.KeyEvent
import com.arflix.tv.data.repository.HomeAssistantRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/**
 * The House 3D screensaver: pick "Xadarr House 3D" under the TV's Screen saver setting. Any key
 * wakes it (interactive only so the press is ours to swallow rather than reaching the app below).
 */
@AndroidEntryPoint
class House3dDream : DreamService() {
    @Inject lateinit var ha: HomeAssistantRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: House3dView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        view = House3dView(this, ha, scope).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }

    override fun onDreamingStopped() {
        view?.stop()
        view = null
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
