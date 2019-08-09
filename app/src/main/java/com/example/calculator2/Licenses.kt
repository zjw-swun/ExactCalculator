package com.example.calculator2

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebView

class Licenses : Activity() {

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
