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
        // 4 columns reads well on a 1080p/4K TV at 10-foot viewing distance.
        recyclerView.layoutManager = GridLayoutManager(this, 4)
        recyclerView.adapter = OttAdapter(OttConfig.services) { service ->
            val intent = Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, service.url)
                putExtra(WebViewActivity.EXTRA_NAME, service.name)
                putExtra(WebViewActivity.EXTRA_DESKTOP_UA, service.useDesktopUA)
            }
            startActivity(intent)
        }

        // Make sure the first card is focused immediately so the remote works with no extra clicks.
        recyclerView.post { recyclerView.requestFocus() }
    }
}
