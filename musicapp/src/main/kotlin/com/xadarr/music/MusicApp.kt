package com.xadarr.music

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import com.arflix.tv.music.PhoneSpeaker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // On a phone, the phone itself is one of the speakers (see PhoneSpeaker). TVs remote-control only.
        val tv = getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        if (!tv) PhoneSpeaker.install(this)
    }
}
