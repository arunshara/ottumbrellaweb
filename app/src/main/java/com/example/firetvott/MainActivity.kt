package com.example.firetvott

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recyclerView = findViewById<RecyclerView>(R.id.ottGrid)
        recyclerView.layoutManager = GridLayoutManager(this, 4)
        recyclerView.adapter = OttAdapter(OttConfig.services) { service ->
            val intent = Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, service.url)
                putExtra(WebViewActivity.EXTRA_NAME, service.name)
                putExtra(WebViewActivity.EXTRA_DESKTOP_UA, service.useDesktopUA)
            }
            startActivity(intent)
        }

        // Avoid focus loss when items are recycled/rebound during scroll/layout passes —
        // a common cause of "D-pad stops responding" on TV RecyclerViews.
        recyclerView.preserveFocusAfterLayout = true
        recyclerView.itemAnimator = null
        recyclerView.isFocusable = true
        recyclerView.descendantFocusability = RecyclerView.FOCUS_AFTER_DESCENDANTS

        // A single post{} can run before the adapter has actually laid out any child
        // views, silently leaving nothing focused (remote appears "dead"). Waiting for
        // the layout pass to finish guarantees a focusable child exists first.
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (recyclerView.childCount > 0) {
                    recyclerView.getChildAt(0)?.requestFocus()
                    recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val recyclerView = findViewById<RecyclerView>(R.id.ottGrid)
        if (recyclerView.focusedChild == null && recyclerView.childCount > 0) {
            recyclerView.getChildAt(0)?.requestFocus()
        }
    }
}