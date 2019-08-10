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
     * Called when the activity is starting. First we call our super's implementation of [onCreate].
     * We initialize our `val webView` with a new instance then call its `loadUrl` method to have
     * it load the URL `LICENSE_URL` ("file:///android_asset/licenses.html"). Finally we set our
     * content view to `webView`.
     *
     * @param savedInstanceState we do not override [onSaveInstanceState] so do not use this.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        webView.loadUrl(LICENSE_URL)

        setContentView(webView)
    }

    /**
     * This hook is called whenever an item in your options menu is selected. If the identifier of
     * the [item] selected is android.R.id.home we call the [onBackPressed] method to have the
     * system finish this activity, and then return *true* to consume the event. If the identifier
     * is not android.R.id.home we return the value returned by our super's implementation of
     * `onOptionsItemSelected` to the caller.
     *
     * @param item The menu item that was selected.
     * @return Return false to allow normal menu processing to proceed, true to consume it here.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Our static constants.
     */
    companion object {
        /**
         * The URL for the file in our assets for the open source licence html.
         */
        private const val LICENSE_URL = "file:///android_asset/licenses.html"
    }
}
