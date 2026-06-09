package com.arflix.tv

import android.content.Intent
import androidx.activity.ComponentActivity
import android.os.Bundle

/**
 * Minimal trampoline activity that registers as a HOME app when Launcher Mode is enabled.
 * Disabled by default (android:enabled="false" in manifest). Enabled/disabled at runtime
 * via PackageManager.setComponentEnabledSetting() when the user toggles Launcher Mode.
 * Immediately starts MainActivity and finishes itself.
 */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
        finish()
    }
}
