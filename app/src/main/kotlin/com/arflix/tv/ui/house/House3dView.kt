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
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private const val TAG = "House3D"
private const val PANEL_PATH = "/neonplan3d"
private const val RENDER_WAIT_MS = 8_000L
private const val VIEW_EVERY_MS = 25_000L

/**
 * House 3D (Joe, 2026-11-08): Home Assistant's NeonPlan 3D panel full screen, for looking at --
 * the panel draws the live lights and the sun itself. Every [VIEW_EVERY_MS] it steps to the next
 * view chip (All floors, Upstairs, Downstairs, then the rooms) behind a short fade, and a small
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
            var next = 1 // 0 is All floors, already showing
            while (isActive) {
                tidy()
                delay(VIEW_EVERY_MS)
                cover.animate().alpha(1f).setDuration(600).start()
                delay(650)
                step(next++)
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

    private fun step(i: Int, retried: Boolean = false) {
        web.evaluateJavascript("$JS_HELPERS;xadarrStep($i)") { result ->
            Log.i(TAG, "view $i -> $result")
            if (result == "\"retry\"" && !retried) {
                web.postDelayed({ step(i, retried = true) }, 400)
            } else {
                // The room card appears with the new view; hide it before the fade lifts.
                web.postDelayed({
                    tidy()
                    cover.animate().alpha(0f).setDuration(900).start()
                }, 400)
            }
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
         * "Don't show again" and hides the menu button; xadarrStep(i) clicks the i-th view
         * chip -- by name, from the full list seen with All floors up (a room's chips only list its
         * floor); when that chip isn't showing it clicks All floors and returns 'retry'. The panel's own
         * header, chip row, floor thumbnails, buttons and room cards are hidden so only the house and its live
         * labels show (NeonPlan 3D v3.0.2 class names).
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
                st.textContent='.fp3d-header,.fp3d-nav,.fp3d-thumbs,.fp3d-find-btn,.fp3d-central-btn,.fp3d-walk-btn,.fp3d-eye,.fp3d-overlay,.fp3d-room-panel,.fp3d-rp{display:none!important}';
                root.appendChild(st);done.push('styled');});
              if(done.indexOf('styled')>=0)window.dispatchEvent(new Event('resize'));
              return done.join(',');}
            function xadarrChips(){var app=xadarrApp();if(!app)return [];return Array.prototype.slice.call(app.querySelectorAll('.fp3d-nav button.fp3d-chip')).filter(function(e){return !e.classList.contains('fp3d-nav-toggle')&&xadarrText(e);});}
            function xadarrStep(i){var l=xadarrChips();if(!l.length)return 'no chips';
              if(!window.xadarrSeq||window.xadarrSeq.length<l.length)window.xadarrSeq=l.map(xadarrText);
              var want=window.xadarrSeq[i%window.xadarrSeq.length];
              for(var k=0;k<l.length;k++){if(xadarrText(l[k])===want){l[k].click();return want+' ('+(i%window.xadarrSeq.length+1)+'/'+window.xadarrSeq.length+')';}}
              l[0].click();return 'retry';}
        """.trimIndent()
    }
}
