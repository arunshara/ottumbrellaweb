package com.example.firetvott

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts a single OTT web app inside a WebView, tuned for Fire TV.
 *
 * Key handling happens in dispatchKeyEvent(), not onKeyDown() — the WebView frequently
 * consumes D-pad/media keys itself before onKeyDown() would ever see them, which is why
 * dispatchKeyEvent() (called earlier, before the WebView gets a chance) is used instead.
 *
 * Also includes an external-player fallback: some OTT sites serve video the WebView's
 * hardware-accelerated renderer fails to draw (audio plays, no picture) even though
 * decoding succeeds. A small JS bridge detects that failure and hands the stream off to
 * an installed video app (VLC, MX Player, etc.) instead. This only works for non-DRM
 * streams — DRM-protected video can't be redirected this way since external players lack
 * the WebView's decryption keys.
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_DESKTOP_UA = "extra_desktop_ua"

        private const val MODE_DPAD = 0
        private const val MODE_MOUSE = 1

        private const val BASE_CURSOR_STEP = 18f
        private const val MAX_CURSOR_STEP = 55f
        private const val CURSOR_SIZE_DP = 28

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        // Auto-launch the external player the instant "audio but no picture" is detected,
        // instead of waiting for the user to press Fast-Forward. Set to false if you'd
        // rather it only ever trigger manually.
        private const val AUTO_FALLBACK_ON_BROKEN_VIDEO = true

        private val VIDEO_WATCHER_JS = """
            (function() {
              if (window.__nativePlayerBridgeInstalled) return;
              window.__nativePlayerBridgeInstalled = true;

              function trySrc(v) {
                try { return v.currentSrc || v.src || ''; } catch(e) { return ''; }
              }

              function watch(v) {
                if (v.__watched) return;
                v.__watched = true;
                var brokenReported = false;
                setInterval(function() {
                  if (!document.body.contains(v)) return;
                  var src = trySrc(v);
                  if (src && window.NativePlayer) {
                    try { window.NativePlayer.onVideoUrlDetected(src); } catch(e) {}
                  }
                  if (!brokenReported && !v.paused && v.currentTime > 2 && v.videoWidth === 0) {
                    brokenReported = true;
                    if (window.NativePlayer) {
                      try { window.NativePlayer.onVideoBroken(src); } catch(e) {}
                    }
                  }
                }, 1500);
              }

              function scan() {
                document.querySelectorAll('video').forEach(watch);
              }

              scan();
              var mo = new MutationObserver(scan);
              mo.observe(document.documentElement, { childList: true, subtree: true });
              setInterval(scan, 3000);
            })();
        """.trimIndent()
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var cursorView: ImageView
    private lateinit var modeBadge: TextView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var currentMode = MODE_DPAD
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorSizePx = 0

    private var centerDownTime = 0L
    private var longPressToggleFired = false
    private val LONG_PRESS_MS = 550L

    private var lastDetectedVideoUrl: String? = null

    private val hideBadgeHandler = Handler(Looper.getMainLooper())
    private val hideBadgeRunnable = Runnable { modeBadge.visibility = View.GONE }

    /** Exposed to page JavaScript as window.NativePlayer.* */
    private inner class NativePlayerBridge {
        @JavascriptInterface
        fun onVideoUrlDetected(url: String) {
            lastDetectedVideoUrl = url
        }

        @JavascriptInterface
        fun onVideoBroken(url: String) {
            lastDetectedVideoUrl = url
            runOnUiThread {
                if (AUTO_FALLBACK_ON_BROKEN_VIDEO) {
                    Toast.makeText(
                        this@WebViewActivity,
                        "No picture detected — opening in external player",
                        Toast.LENGTH_LONG
                    ).show()
                    launchExternalPlayer(url)
                } else {
                    showModeBadge("No picture? Press FF for external player")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        rootContainer = findViewById(R.id.webViewRoot)
        webView = findViewById(R.id.webView)
        cursorView = findViewById(R.id.cursorOverlay)
        modeBadge = findViewById(R.id.modeBadge)

        cursorSizePx = (CURSOR_SIZE_DP * resources.displayMetrics.density).toInt()

        val url = intent.getStringExtra(EXTRA_URL) ?: "https://www.airtelxstream.in"
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val desktopUA = intent.getBooleanExtra(EXTRA_DESKTOP_UA, true)
        title = name

        configureWebView(desktopUA)
        webView.loadUrl(url)

        setMode(MODE_DPAD, announce = false)
        rootContainer.post {
            cursorX = rootContainer.width / 2f
            cursorY = rootContainer.height / 2f
            positionCursorView()
        }

        showModeBadge("Play/Pause=Mouse Mode  |  FF=External Player")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureWebView(useDesktopUA: Boolean) {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = if (useDesktopUA) DESKTOP_UA else MOBILE_UA

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.addJavascriptInterface(NativePlayerBridge(), "NativePlayer")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                view.evaluateJavascript(VIDEO_WATCHER_JS, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                rootContainer.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                cursorView.bringToFront()
                modeBadge.bringToFront()
            }

            override fun onHideCustomView() {
                val cv = customView ?: return
                rootContainer.removeView(cv)
                customView = null
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Dual-mode remote input handling — intercepted BEFORE the WebView sees it
    // ---------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown) toggleMode()
            return true
        }

        // Fast-Forward = manually open the last-detected video stream in an external player.
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (isDown) launchExternalPlayer(null)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentMode == MODE_DPAD) {
                if (isDown && event.repeatCount == 0) {
                    centerDownTime = System.currentTimeMillis()
                    longPressToggleFired = false
                } else if (isDown && event.repeatCount > 0 && !longPressToggleFired) {
                    if (System.currentTimeMillis() - centerDownTime >= LONG_PRESS_MS) {
                        longPressToggleFired = true
                        toggleMode()
                        return true
                    }
                } else if (isUp) {
                    if (longPressToggleFired) {
                        longPressToggleFired = false
                        return true
                    }
                }
            } else {
                if (isDown && event.repeatCount == 0) {
                    dispatchClickAtCursor()
                }
                return true
            }
        }

        if (currentMode == MODE_MOUSE) {
            if (isDown) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-stepFor(event), 0f); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(stepFor(event), 0f); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(0f, -stepFor(event)); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(0f, stepFor(event)); return true }
                    KeyEvent.KEYCODE_BACK -> {
                        if (webView.canGoBack()) { webView.goBack(); return true }
                    }
                }
            } else if (isUp && keyCode in intArrayOf(
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
                )
            ) {
                return true
            }
        } else {
            if (keyCode == KeyEvent.KEYCODE_BACK && isDown && webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun stepFor(event: KeyEvent): Float {
        val accelerated = BASE_CURSOR_STEP + (event.repeatCount * 4f)
        return accelerated.coerceAtMost(MAX_CURSOR_STEP)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val maxX = (rootContainer.width - cursorSizePx).toFloat().coerceAtLeast(0f)
        val maxY = (rootContainer.height - cursorSizePx).toFloat().coerceAtLeast(0f)
        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)
        positionCursorView()
    }

    private fun positionCursorView() {
        val params = cursorView.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = cursorX.toInt()
        params.topMargin = cursorY.toInt()
        cursorView.layoutParams = params
    }

    private fun dispatchClickAtCursor() {
        val centerX = cursorX + cursorSizePx / 2f
        val centerY = cursorY + cursorSizePx / 2f
        val downTime = android.os.SystemClock.uptimeMillis()

        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, centerX, centerY, 0
        )
        val upEvent = MotionEvent.obtain(
            downTime, downTime + 50, MotionEvent.ACTION_UP, centerX, centerY, 0
        )
        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
    }

    private fun toggleMode() {
        setMode(if (currentMode == MODE_DPAD) MODE_MOUSE else MODE_DPAD, announce = true)
    }

    private fun setMode(mode: Int, announce: Boolean) {
        currentMode = mode
        val isMouse = mode == MODE_MOUSE
        cursorView.visibility = if (isMouse) View.VISIBLE else View.GONE

        val label = if (isMouse) "Mode: Mouse Pointer" else "Mode: D-Pad"
        if (announce) {
            showModeBadge(label)
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showModeBadge(text: String) {
        modeBadge.text = text
        modeBadge.visibility = View.VISIBLE
        modeBadge.bringToFront()
        hideBadgeHandler.removeCallbacks(hideBadgeRunnable)
        hideBadgeHandler.postDelayed(hideBadgeRunnable, 2500)
    }

    private fun launchExternalPlayer(urlOverride: String?) {
        val target = urlOverride ?: lastDetectedVideoUrl
        if (target.isNullOrBlank()) {
            Toast.makeText(this, "No video stream detected on this page yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(target), "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No video player app found — install VLC from the Amazon Appstore",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        hideBadgeHandler.removeCallbacks(hideBadgeRunnable)
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}