package com.arflix.tv.util

import android.content.Context
import android.content.Intent

/**
 * Plain launch of the Plex app — no title-specific deep link. Three `plex://` URI candidates
 * (`play`, `preplay`, and the server machine identifier as host, all with
 * `/library/metadata/{ratingKey}`) were tested directly against a real device on 2026-08-09 and
 * all three landed on Plex's home screen, not the specific title. `dumpsys package
 * com.plexapp.android` confirms why: its manifest only claims specific known hosts
 * (`watch.plex.tv`, `link.plex.tv`, `links.plex.tv`, `l.plex.tv`, `marketing`) plus one catch-all
 * `plex://` filter with no host restriction that resolves to `SplashActivity` — which is exactly
 * what let the earlier `resolveActivity()` check silently report success while still landing on
 * Home, since Android intent resolution only checks the manifest's declared scheme/host/path, not
 * what the target Activity actually *does* with the URI once it's running. There's no publicly
 * documented format for a title-specific deep link, and decompiling the APK to find Plex's
 * internal (undocumented) routing was explicitly declined — see
 * [[project_dv_atmos_passthrough_2026-07-30]]. If Plex ever publishes a real scheme, or a
 * different one surfaces, this is the place to add it back.
 */
object PlexDeepLink {

    fun launchIntent(context: Context, serverId: String?, ratingKey: String?): Intent? =
        context.packageManager.getLaunchIntentForPackage("com.plexapp.android")
}
