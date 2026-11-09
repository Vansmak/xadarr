package com.arflix.tv.ui.house

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.arflix.tv.data.repository.HomeAssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

private const val TAG = "House3D"
private const val PANEL_PATH = "/neonplan3d"
private const val RENDER_WAIT_MS = 8_000L
// 25s felt slow (Joe, 2026-11-08: "needs to move through views quicker and sensibly").
private const val VIEW_EVERY_MS = 12_000L

/**
 * House 3D (Joe, 2026-11-08): Home Assistant's NeonPlan 3D panel full screen, for looking at --
 * the panel draws the live lights and the sun itself. Every [VIEW_EVERY_MS] it steps to the next
 * view (All floors, Downstairs, Upstairs) behind a short fade, and a small
 * corner shows the clock and a few states (locks, garage, doors, temperatures) from HA's REST API.
 *
 * Signs in with the token Xadarr already has: HA's frontend reads its login from localStorage
 * ("hassTokens"), so a one-line page on HA's origin stores the token there and moves on to the
 * panel. The same page hides HA's sidebar ("dockedSidebar"). Plain views, not Compose, so the
 * screensaver (House3dDream) can host it.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class House3dView(
    context: Context,
    private val ha: HomeAssistantRepository,
    private val scope: CoroutineScope,
) : FrameLayout(context) {

    private val web = WebView(context)
    private val cover = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val clock = label(34f)
    private val status = label(15f)
    private val message = label(20f).apply { gravity = Gravity.CENTER }
    private var jobs = mutableListOf<Job>()

    init {
        setBackgroundColor(Color.BLACK)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.isFocusable = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "page $url")
                if (url?.contains("/auth/authorize") == true) showMessage("Home Assistant didn't accept Xadarr's login")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showMessage("Can't reach Home Assistant")
            }
        }
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val corner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(status)
            setPadding(dp(32), 0, 0, dp(26))
        }
        addView(corner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun start() {
        jobs += scope.launch {
            val login = ha.webLogin()
            if (login == null) {
                showMessage("Set up Home Assistant in Xadarr's Settings first")
                return@launch
            }
            val (base, token) = login
            web.loadDataWithBaseURL("$base/", bootstrapPage(base, token), "text/html", "utf-8", null)
            delay(RENDER_WAIT_MS)
            tidy()
            delay(300)
            cover.animate().alpha(0f).setDuration(1_200).start()
            // Only floors where something is on (Joe, 2026-11-08: "just what's happening, can
            // ignore areas with nothing happening"); with nothing on it rests on the whole house.
            var shown = "All floors"
            var next = 1
            while (isActive) {
                tidy()
                delay(VIEW_EVERY_MS)
                val views = runCatching { JSONArray(js("xadarrViews()")) }.getOrNull() ?: continue
                if (views.length() == 0) continue
                val target = views.getString(next++ % views.length())
                if (target == shown) continue
                cover.animate().alpha(1f).setDuration(600).start()
                delay(650)
                val quoted = JSONObject.quote(target)
                if (js("xadarrGo($quoted)") == "\"retry\"") {
                    delay(400)
                    js("xadarrGo($quoted)")
                }
                // The room card can appear with the new view; hide it before the fade lifts.
                delay(400)
                tidy()
                cover.animate().alpha(0f).setDuration(900).start()
                shown = target
            }
        }
        jobs += scope.launch {
            while (isActive) {
                clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                delay(15_000)
            }
        }
        jobs += scope.launch {
            while (isActive) {
                status.text = ha.houseStatus().joinToString("   ·   ")
                delay(60_000)
            }
        }
    }

    private suspend fun js(expr: String): String = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript("$JS_HELPERS;$expr") { result ->
            Log.i(TAG, "$expr -> $result")
            if (cont.isActive) cont.resume(result ?: "null")
        }
    }

    /** The first-load popup ("Don't show again") and HA's menu button come and go; keep at it. */
    private fun tidy() {
        web.evaluateJavascript("$JS_HELPERS;xadarrTidy()") { Log.i(TAG, "tidy $it") }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        web.stopLoading()
        web.destroy()
    }

    private fun showMessage(text: String) {
        message.text = text
        cover.alpha = 1f
    }

    private fun label(sp: Float) = TextView(context).apply {
        setTextColor(Color.argb(210, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setShadowLayer(8f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bootstrapPage(base: String, token: String): String {
        val tokens = JSONObject()
            .put("access_token", token)
            .put("token_type", "Bearer")
            .put("expires_in", 1800)
            .put("hassUrl", base)
            .put("clientId", "$base/")
            .put("expires", System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000)
            .put("refresh_token", "")
        return "<html><body style=\"background:#000\"><script>" +
            "localStorage.setItem('hassTokens', ${JSONObject.quote(tokens.toString())});" +
            "localStorage.setItem('dockedSidebar', '\"always_hidden\"');" +
            "location.replace(${JSONObject.quote(base + PANEL_PATH)});" +
            "</script></body></html>"
    }

    private companion object {
        /**
         * Walks the page including shadow roots (HA is all web components). xadarrTidy clicks
         * "Don't show again" and hides the menu button. xadarrViews() lists All floors plus each floor
         * with a room that has something on (Downstairs first) -- "on" from NeonPlan's lit device
         * icons, their room from the entity's HA area; xadarrGo(name) clicks that view's
         * chip, or All floors and 'retry' when it isn't showing. Room close-ups were dropped: NeonPlan frames a room so near
         * it's mostly walls (Joe, 2026-11-08: "confusing, all the labels").
         * The panel's own header, chip row, floor thumbnails, buttons and room cards are hidden,
         * and so are the round device badges and room names: lights glow in the model itself, and TVs
         * and fans show as NeonPlan furniture (a TV model glows while on) once placed in its Editor
         * (Joe, 2026-11-08: the TV should show a screen, not an icon). The badges stay in the page
         * (hidden), which is how xadarrViews tells what's on (NeonPlan 3D v3.0.2 class names).
         */
        val JS_HELPERS = """
            function xadarrAll(){var out=[];(function walk(r){var els=r.querySelectorAll('*');for(var i=0;i<els.length;i++){out.push(els[i]);if(els[i].shadowRoot)walk(els[i].shadowRoot);}})(document);return out;}
            function xadarrText(e){return (e.textContent||'').replace(/\s+/g,' ').trim();}
            function xadarrApp(){var a=xadarrAll();for(var i=0;i<a.length;i++){if(a[i].classList&&a[i].classList.contains('fp3d-app'))return a[i];}return null;}
            function xadarrTidy(){var done=[];var all=xadarrAll();all.forEach(function(e){var t=xadarrText(e);
              if(/^don.?t show again$/i.test(t)&&e.getBoundingClientRect().width>0){e.click();done.push('popup');}
              var tag=e.tagName.toLowerCase();if(tag==='ha-menu-button'||tag==='ha-sidebar'){e.style.display='none';}});
              all.forEach(function(e){var root=e.shadowRoot;if(!root||!root.querySelector('[class*="fp3d"]')||root.querySelector('#xadarr-ambient'))return;
                var st=document.createElement('style');st.id='xadarr-ambient';
                st.textContent='.fp3d-header,.fp3d-nav,.fp3d-thumbs,.fp3d-find-btn,.fp3d-central-btn,.fp3d-walk-btn,.fp3d-eye,.fp3d-overlay,.fp3d-room-panel,.fp3d-rp,.fp3d-dev,.fp3d-pin:not(.fp3d-pin-floor){display:none!important}';
                root.appendChild(st);done.push('styled');});
              if(done.indexOf('styled')>=0)window.dispatchEvent(new Event('resize'));
              return done.join(',');}
            function xadarrChips(){var app=xadarrApp();if(!app)return [];return Array.prototype.slice.call(app.querySelectorAll('.fp3d-nav button.fp3d-chip')).filter(function(e){return !e.classList.contains('fp3d-nav-toggle')&&xadarrText(e);});}
            function xadarrViews(){var app=xadarrApp();var ha=document.querySelector('home-assistant');var hass=ha&&ha.hass;if(!app||!hass)return [];
              var all=xadarrAll(),pins=all.filter(function(e){return e.classList&&e.classList.contains('fp3d-pin')&&e.getAttribute('data-room');});
              var busyRooms={};all.forEach(function(e){if(!e.classList||!e.classList.contains('fp3d-dev-on'))return;var en=hass.entities[e.dataset.entity]||{};
                var area=en.area_id||(en.device_id&&hass.devices[en.device_id]?hass.devices[en.device_id].area_id:null);if(area)busyRooms[area]=1;});
              var busyFloors={};pins.forEach(function(e){if(busyRooms[e.getAttribute('data-room')])busyFloors[e.getAttribute('data-floor')]=1;});
              var floors=[];all.forEach(function(e){if(!e.classList||!e.classList.contains('fp3d-pin-floor'))return;var b=e.querySelector('b');if(b&&busyFloors[e.getAttribute('data-floor')])floors.push(xadarrText(b));});
              floors.sort(function(a,b){return /down/i.test(a)?-1:/down/i.test(b)?1:0;});
              return ['All floors'].concat(floors);}
            function xadarrGo(name){var l=xadarrChips();if(!l.length)return 'no chips';
              for(var k=0;k<l.length;k++){if(xadarrText(l[k])===name){l[k].click();return name;}}
              l[0].click();return 'retry';}
        """.trimIndent()
    }
}
