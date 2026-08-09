package com.example.firetvott

/**
 * Represents a single OTT service card on the dashboard.
 *
 * @param name        Display name shown on the card.
 * @param url         URL loaded inside WebViewActivity when the card is clicked.
 * @param colorHex    Fallback tile color (used since we don't ship real artwork per service).
 * @param useDesktopUA Some services (e.g. Airtel Xstream) serve a better DRM-capable
 *                      player to a desktop Chrome UA; others behave better as mobile Chrome.
 */
data class OttService(
    val name: String,
    val url: String,
    val colorHex: String = "#37474F",
    val useDesktopUA: Boolean = true
)
