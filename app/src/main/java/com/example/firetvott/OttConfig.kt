package com.example.firetvott

/**
 * Single source of truth for which OTT cards appear on the dashboard.
 *
 * To add a new service, just add another OttService(...) entry to this list.
 * No layout or adapter changes are required.
 */
object OttConfig {

    val services = mutableListOf(
        OttService(
            name = "Airtel Xstream Play",
            url = "https://www.airtelxstream.in",
            colorHex = "#E40046",
            useDesktopUA = true
        ),
        OttService(
            name = "JioCinema",
            url = "https://www.jiocinema.com",
            colorHex = "#7C1DAB",
            useDesktopUA = true
        ),
        OttService(
            name = "Einthusan",
            url = "https://einthusan.tv",
            colorHex = "#1B5E20",
            useDesktopUA = false
        )
        // Add more like:
        // OttService(name = "Example", url = "https://example.com", colorHex = "#455A64")
    )
}
