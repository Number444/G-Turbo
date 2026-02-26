package com.example.myapplication1

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorLayout: LinearLayout
    private lateinit var btnRetry: Button
    private lateinit var connectivityManager: ConnectivityManager
    private var isSuccessfullyLoaded = false
    private val targetUrl = "https://gemini.google.com"
    private var keepSplash = true

    private var fileChooserCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileChooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileChooserCallback = null
    }

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        fileChooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileChooserCallback = null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            super.onLost(network)
            runOnUiThread { showError() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { keepSplash }
        Handler(Looper.getMainLooper()).postDelayed({ keepSplash = false }, 1000)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val mainContainer = findViewById<FrameLayout>(R.id.main_container)
        webView = findViewById(R.id.gemini_webview)
        errorLayout = findViewById(R.id.error_layout)
        btnRetry = findViewById(R.id.btn_retry)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            if (isNetworkAvailable()) {
                loadWithStrictTimeout()
            } else {
                showError()
            }
        }

        btnRetry.setOnClickListener {
            btnRetry.text = "连接中..."
            btnRetry.isEnabled = false
            showWebView()
            isSuccessfullyLoaded = false
            if (webView.url.isNullOrEmpty()) {
                webView.loadUrl(targetUrl)
            } else {
                webView.reload()
            }
            webView.postDelayed({ if (!isSuccessfullyLoaded) showError() }, 5000)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun loadWithStrictTimeout() {
        isSuccessfullyLoaded = false
        webView.loadUrl(targetUrl)
        webView.postDelayed({ if (!isSuccessfullyLoaded) showError() }, 5000)
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) isSuccessfullyLoaded = true
            }

            override fun onShowFileChooser(view: WebView?, fp: ValueCallback<Array<android.net.Uri>>?, fcp: FileChooserParams?): Boolean {
                fileChooserCallback = fp
                val isImageOnly = fcp?.acceptTypes?.any { it.contains("image") } == true
                if (isImageOnly) {
                    pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    pickFileLauncher.launch("*/*")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isSuccessfullyLoaded = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                isSuccessfullyLoaded = true
                webView.evaluateJavascript("(function() { var h = document.querySelector('header'); if(h) h.style.display='none'; })();", null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showError()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) showError()
            }
        }
    }

    private fun showError() {
        runOnUiThread {
            webView.stopLoading()
            webView.isVisible = false
            errorLayout.isVisible = true
            btnRetry.text = "重新连接"
            btnRetry.isEnabled = true
        }
    }

    private fun showWebView() {
        errorLayout.isVisible = false
        webView.isVisible = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun isNetworkAvailable(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}