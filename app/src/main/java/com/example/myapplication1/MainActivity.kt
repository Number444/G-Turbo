package com.example.myapplication1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorLayout: LinearLayout
    private lateinit var btnRetry: Button
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var accessoryBar: LinearLayout
    private lateinit var btnTakePhoto: Button

    private var isSuccessfullyLoaded = false
    private val targetUrl = "https://gemini.google.com"
    private var keepSplash = true

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // 自动上传核心：记录当前拍摄的高清图路径
    private var currentPhotoUri: Uri? = null
    private var pendingUploadUri: Uri? = null

    // 1. 拍照完成后的逻辑：存入剪贴板 -> 模拟粘贴动作 -> 填入文字
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoUri != null) {
            // 1. 在后台读取图片并转换为 Base64
            Thread {
                try {
                    val inputStream = contentResolver.openInputStream(currentPhotoUri!!)
                    val bytes = inputStream?.readBytes()
                    val base64Image = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

                    // 2. 切回主线程注入 JS
                    runOnUiThread {
                        val injectJs = """
                            (function() {
                                const editor = document.querySelector('div[contenteditable="true"]') || document.querySelector('textarea');
                                if (editor) {
                                    editor.focus();
                                    
                                    // 模拟创建一个 DataTransfer 对象，这是最接近原生上传的注入方式
                                    const b64Data = "$base64Image";
                                    const byteCharacters = atob(b64Data);
                                    const byteArrays = [];
                                    for (let i = 0; i < byteCharacters.length; i++) {
                                        byteArrays.push(byteCharacters.charCodeAt(i));
                                    }
                                    const blob = new Blob([new Uint8Array(byteArrays)], {type: 'image/jpeg'});
                                    const file = new File([blob], "upload.jpg", {type: 'image/jpeg'});
                                    
                                    // 构造粘贴事件
                                    const dataTransfer = new DataTransfer();
                                    dataTransfer.items.add(file);
                                    const pasteEvent = new ClipboardEvent('paste', {
                                        clipboardData: dataTransfer,
                                        bubbles: true,
                                        cancelable: true
                                    });
                                    
                                    editor.dispatchEvent(pasteEvent);

                                    // 延迟输入文字
                                    setTimeout(() => {
                                        document.execCommand('insertText', false, '请根据拍摄的这张最新的照片解题');
                                    }, 1000);
                                }
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(injectJs, null)
                    }
                } catch (e: Exception) {
                    Log.e("G-Turbo", "Base64转换失败: ${e.message}")
                }
            }.start()
        }
    }
    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) launchCamera() else Toast.makeText(this, "相机权限被拒绝", Toast.LENGTH_SHORT).show()
    }

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
        // 开启全屏沉浸
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { keepSplash }
        Handler(Looper.getMainLooper()).postDelayed({ keepSplash = false }, 1000)

        setContentView(R.layout.activity_main)

        // 1. 强制设置沉浸式状态栏透明
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

// 2. 动态调整状态栏图标颜色
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

// 如果是白天模式，图标设为黑色；深色模式下，图标自动变白
        controller.isAppearanceLightStatusBars = !isDarkMode

        val mainContainer = findViewById<FrameLayout>(R.id.main_container)
        webView = findViewById(R.id.gemini_webview)
        errorLayout = findViewById(R.id.error_layout)
        btnRetry = findViewById(R.id.btn_retry)
        accessoryBar = findViewById(R.id.accessory_bar)
        btnTakePhoto = findViewById(R.id.btn_take_photo)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 键盘与避让逻辑：通过物理缩减 WebView 边界，彻底解决挡住输入框问题
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // 顶部用 Padding 留出状态栏，避免内容钻进去看不清
            mainContainer.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)

            if (::accessoryBar.isInitialized && ::webView.isInitialized) {
                accessoryBar.isVisible = isImeVisible
                val params = webView.layoutParams as FrameLayout.LayoutParams

                if (isImeVisible) {
                    val offset = (imeInsets.bottom - systemBars.bottom).toFloat()
                    accessoryBar.translationY = -offset

                    // 动态计算底部边距：键盘高度 + 工具栏高度
                    val barHeight = if (accessoryBar.height > 0) accessoryBar.height else 140
                    params.bottomMargin = imeInsets.bottom + barHeight - systemBars.bottom
                } else {
                    accessoryBar.translationY = 0f
                    params.bottomMargin = systemBars.bottom
                }
                webView.layoutParams = params
            }
            insets
        }

        setupWebView()

        // 绑定重试按钮
        btnRetry.setOnClickListener {
            showWebView()
            if (webView.url.isNullOrEmpty()) webView.loadUrl(targetUrl) else webView.reload()
        }

        // 绑定拍照按钮
        btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadWithStrictTimeout()
        }

        // 处理后退键
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun launchCamera() {
        try {
            // 在缓存目录生成唯一文件名
            val photoFile = File(cacheDir, "gemini_photo_${System.currentTimeMillis()}.jpg")
            currentPhotoUri = FileProvider.getUriForFile(this, "${packageName}.provider", photoFile)
            takePictureLauncher.launch(currentPhotoUri)
        } catch (e: Exception) {
            Log.e("G-Turbo", "Camera launch failed: ${e.message}")
            Toast.makeText(this, "文件提供器配置错误", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        //核心增加部分：允许 JS 模拟用户交互和弹窗
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, fp: ValueCallback<Array<Uri>>?, fcp: FileChooserParams?): Boolean {
                // 关键：如果 execCommand('paste') 触发了网页的文件选择请求，这里直接喂入图片
                if (pendingUploadUri != null) {
                    fp?.onReceiveValue(arrayOf(pendingUploadUri!!))
                    pendingUploadUri = null
                    return true
                }

                // 正常的选择文件请求（点击网页原生按钮时触发）
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
            override fun onPageFinished(view: WebView?, url: String?) {
                isSuccessfullyLoaded = true
                webView.evaluateJavascript("""
                    (function() { 
                        var h = document.querySelector('header'); if(h) h.style.display='none'; 
                        var style = document.createElement('style');
                        style.innerHTML = '* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }';
                        document.head.appendChild(style);
                    })();
                """.trimIndent(), null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showError()
            }
        }
    }

    private fun loadWithStrictTimeout() {
        isSuccessfullyLoaded = false
        webView.loadUrl(targetUrl)
    }

    private fun showError() {
        runOnUiThread {
            webView.isVisible = false
            errorLayout.isVisible = true
        }
    }

    private fun showWebView() {
        errorLayout.isVisible = false
        webView.isVisible = true
    }

    private fun isNetworkAvailable(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}