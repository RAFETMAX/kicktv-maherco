package com.maherco.kicktv

import android.webkit.JavascriptInterface

/** Receives the result of the in-page session probe. */
class ProbeBridge(private val onResult: (String) -> Unit) {

    @JavascriptInterface
    fun post(value: String) {
        onResult(value)
    }
}
