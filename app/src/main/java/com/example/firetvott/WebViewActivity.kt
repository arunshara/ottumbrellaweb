package com.example.firetvott

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
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
 * Hosts a single OTT web app inside a WebView, tuned for Fire TV:
 *  - Persistent login sessions (cookies + DOM/database storage survive app restarts)
 *  - Hardware-accelerated HTML5 video
 *  - Two remote-control modes:
 *      MODE_DPAD  -> standard focus-based navigation (works with pages that have visible focus states)
 *      MODE_MOUSE -> an on-screen cursor you glide with the D-pad and "click" with OK/Center
 *                    (needed for pages built for mouse/touch that ignore D-pad focus entirely)
 *  - Toggle between modes with the Play/Pause button (falls back to Menu button on some remotes)
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_DESKTOP_UA = "extra_desktop_ua"

        private const val MODE_DPAD = 0
        private const val MODE_MOUSE = 1

        private const val BASE_CURSOR_STEP = 18f   // px per key event at rest
        private const val MAX_CURSOR_STEP = 55f    // px per key event once "held"/repeating
        private const val CURSOR_SIZE_DP = 28

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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

    private val hideBadgeHandler = Handler(Looper.getMainLooper())
    private val hideBadgeRunnable = Runnable { modeBadge.visibility = View.GONE }

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

        // Start in D-pad mode; center the cursor for when the user switches to mouse mode.
        setMode(MODE_DPAD, announce = false)
        rootContainer.post {
            cursorX = rootContainer.width / 2f
            cursorY = rootContainer.height / 2f
            positionCursorView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
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

        // Chrome UA so services serve their full HTML5/Widevine DRM video player
        // instead of a stripped-down "unsupported browser" fallback page.
        settings.userAgentString = if (useDesktopUA) DESKTOP_UA else MOBILE_UA

        // Smooth video playback.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Persistent login sessions across app restarts / device reboots.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            // Keep all navigation inside the WebView instead of bouncing out to a browser/app chooser.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Full-screen playback support (most OTT players call this for their fullscreen video element).
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

            // Widevine DRM permission prompts (EME) - auto-grant so playback isn't blocked.
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Dual-mode remote input handling
    // ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Mode toggle: Play/Pause is the most reliable "extra" button on Fire TV remotes.
        // Menu button is offered as a fallback since some third-party remotes lack Play/Pause.
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MENU) {
            toggleMode()
            return true
        }

        if (currentMode == MODE_MOUSE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveCursor(-stepFor(event), 0f); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveCursor(stepFor(event), 0f); return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveCursor(0f, -stepFor(event)); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveCursor(0f, stepFor(event)); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    dispatchClickAtCursor(); return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (webView.canGoBack()) {
                        webView.goBack(); return true
                    }
                }
            }
        } else {
            // MODE_DPAD: let Android's normal focus/key handling drive the page where possible.
            if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Accelerate movement the longer a key is held (Android repeats onKeyDown with repeatCount). */
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

    /** Simulates a real touch (ACTION_DOWN then ACTION_UP) on the WebView at the cursor's position. */
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
        hideBadgeHandler.postDelayed(hideBadgeRunnable, 1500)
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
