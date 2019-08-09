package com.example.calculator2

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebView

/**
 * This activity is launched when the user selects the "Open source licenses" menu option in the
 * [Calculator2] option menu, and just displays the file licenses.html from our assets in a
 * [WebView].
 */
class Licenses : Activity() {
    /**
     * Called when the activity is starting.
     *
     * @param savedInstanceState we do not override [onSaveInstanceState] so do not use this.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        webView.loadUrl(LICENSE_URL)

        setContentView(webView)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val LICENSE_URL = "file:///android_asset/licenses.html"
    }
}
