// Simple Android WebView app to host the PWA
// You can create this as a separate Android project

package com.example.pushu.demo

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class DemoWebViewActivity : AppCompatActivity() {
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val webView = WebView(this)
        setContentView(webView)
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                // Handle pushu:// deep links
                if (url?.startsWith("pushu://") == true) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, 
                            android.net.Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        // Handle error
                    }
                }
                return false
            }
        }
        
        // Load the PWA from device storage
        webView.loadUrl("file:///sdcard/pushu-demo/demo/index.html")
    }
}
