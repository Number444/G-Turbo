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
import android.widget.TextView
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


    private lateinit var loadingSpinner: android.widget.ProgressBar
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
    private var shouldInsertText = true

    // 1. 拍照完成后的逻辑：存入剪贴板 -> 模拟粘贴动作 -> 填入文字
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoUri != null) {
            Thread {
                try {
                    val inputStream = contentResolver.openInputStream(currentPhotoUri!!)
                    val bytes = inputStream?.readBytes()
                    val base64Image = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

                    runOnUiThread {
                        // 构建提示词：使用了你要求的精准解题提示词
                        val proPrompt = """你现在是一名资深的学术导师。请精准识别我刚刚上传的这张最新图片（这是当前的解题目标）。请先扫描图片中的题目内容，然后按照以下逻辑进行：\n1. 【题目还原】：简要描述图片中的关键信息；\n2. 【核心思路】：说明解题所涉及的定理或公式；\n3. 【详细步骤】：给出清晰的推导或计算过程；\n4. 【最终答案】：加粗显示结论。"""

                        val injectJs = """
                            (function() {
                                const editor = document.querySelector('div[contenteditable="true"]') || document.querySelector('textarea');
                                if (editor) {
                                    editor.focus();
                                    const b64Data = "$base64Image";
                                    const byteCharacters = atob(b64Data);
                                    const byteArrays = [];
                                    for (let i = 0; i < byteCharacters.length; i++) {
                                        byteArrays.push(byteCharacters.charCodeAt(i));
                                    }
                                    const blob = new Blob([new Uint8Array(byteArrays)], {type: 'image/jpeg'});
                                    const file = new File([blob], "upload.jpg", {type: 'image/jpeg'});
                                    
                                    const dataTransfer = new DataTransfer();
                                    dataTransfer.items.add(file);
                                    const pasteEvent = new ClipboardEvent('paste', {
                                        clipboardData: dataTransfer,
                                        bubbles: true,
                                        cancelable: true
                                    });
                                    editor.dispatchEvent(pasteEvent);

                                    // 根据标志位判断是否输入文本
                                    if (${shouldInsertText}) {
                                        setTimeout(() => {
                                            document.execCommand('insertText', false, '$proPrompt');
                                        }, 1000);
                                    }
                                }
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(injectJs, null)
                    }
                } catch (e: Exception) {
                    Log.e("G-Turbo", "操作失败: ${e.message}")
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

    private val requestMicPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "麦克风已就绪", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要录音权限才能使用语音功能", Toast.LENGTH_SHORT).show()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            super.onLost(network)
            runOnUiThread { showError() }
        }
    }
    // 精准控制 Gemini 风格配色
    private fun applyThemeColors(isDarkMode: Boolean) {
        // 定义 Gemini 风格配色
        val bgColor = Color.parseColor(if (isDarkMode) "#131314" else "#FFFFFF") // 深灰黑或纯白
        val textColor = Color.parseColor(if (isDarkMode) "#E3E3E3" else "#1F1F1F")
        // 按钮色：晚上深灰，白天极浅灰
        val btnBgColor = Color.parseColor(if (isDarkMode) "#303134" else "#F1F3F4")

        // 1. 强制覆盖底色（解决断网黑/白屏）
        findViewById<FrameLayout>(R.id.main_container).setBackgroundColor(bgColor)
        errorLayout.setBackgroundColor(bgColor)
        accessoryBar.setBackgroundColor(bgColor)

        // 2. 覆盖重试按钮
        btnRetry.setTextColor(textColor)
        btnRetry.backgroundTintList = android.content.res.ColorStateList.valueOf(btnBgColor)

        // 3. 【同步】刷新新按钮和原按钮：靠左、椭圆纯色、无阴影
        val btnTakePhotoOnly = findViewById<Button>(R.id.btn_take_photo_only)
        btnTakePhotoOnly.setTextColor(textColor)
        btnTakePhotoOnly.backgroundTintList = android.content.res.ColorStateList.valueOf(btnBgColor)

        btnTakePhoto.setTextColor(textColor)
        btnTakePhoto.backgroundTintList = android.content.res.ColorStateList.valueOf(btnBgColor)

        // 4. 【关键】强制刷新状态栏图标颜色
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDarkMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // 开启全屏沉浸
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { keepSplash }
        Handler(Looper.getMainLooper()).postDelayed({ keepSplash = false }, 1000)

        setContentView(R.layout.activity_main)

        // 在需要的时候调用（比如检测到网页报错或初始化时）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        val mainContainer = findViewById<FrameLayout>(R.id.main_container)
        loadingSpinner = findViewById(R.id.loading_spinner)
        webView = findViewById(R.id.gemini_webview)
        errorLayout = findViewById(R.id.error_layout)
        btnRetry = findViewById(R.id.btn_retry)
        accessoryBar = findViewById(R.id.accessory_bar)
        btnTakePhoto = findViewById(R.id.btn_take_photo)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val btnTakePhotoOnly = findViewById<Button>(R.id.btn_take_photo_only)

        // 【关键点】首次启动时根据系统状态强制着色
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyThemeColors(isDarkMode)

        // 核心修改：加载前先判断网络，不给 WebView 等待 30s 的机会
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            if (isNetworkAvailable()) {
                loadingSpinner.isVisible = true // 只有有网才让 WebView 去跑
                webView.loadUrl(targetUrl)
            } else {
                showError() // 没网立刻显示重连，不等待
            }
        }

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
            errorLayout.isVisible = false
            loadingSpinner.isVisible = true // 显示圆环
            if (webView.url.isNullOrEmpty()) webView.loadUrl(targetUrl) else webView.reload()
            webView.isVisible = true
        }

        // 绑定拍题按钮
        btnTakePhoto.setOnClickListener {
            shouldInsertText = true // 设置为 需要 插入文字
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        // 拍照上传
        btnTakePhotoOnly.setOnClickListener {
            shouldInsertText = false // 设置为 不需要 插入文字
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
        webView.setBackgroundColor(Color.TRANSPARENT)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        webView.webChromeClient = object : WebChromeClient() {

            // 麦克风权限核心申请逻辑
            override fun onPermissionRequest(request: PermissionRequest) {
                // 在 8G3 这种高性能设备上，建议在 UI 线程处理
                runOnUiThread {
                    val resources = request.resources
                    for (res in resources) {
                        if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                            // 授权网页访问麦克风
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                            return@runOnUiThread
                        }
                    }
                }
            }
            override fun onShowFileChooser(view: WebView?, fp: ValueCallback<Array<Uri>>?, fcp: FileChooserParams?): Boolean {
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 只要开始跑，圆环必须转
                if (!errorLayout.isVisible) {
                    loadingSpinner.isVisible = true
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                // 只有当没有显示错误面板时，才隐藏转圈（代表加载成功）
                if (!errorLayout.isVisible) {
                    loadingSpinner.isVisible = false
                    webView.evaluateJavascript("""
                        (function() { 
                            var h = document.querySelector('header'); if(h) h.style.display='none'; 
                            var style = document.createElement('style');
                            style.innerHTML = '* { -webkit-tap-highlight-color: transparent !important; outline: none !important; }';
                            document.head.appendChild(style);
                        })();
                    """.trimIndent(), null)
                }
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
            loadingSpinner.isVisible = false // 断网报错，立刻停止转圈
            webView.isVisible = false        // 彻底隐藏 WebView，防止点出白色错误页
            errorLayout.isVisible = true     // 弹出重试面板
            // 在显示错误页的一瞬间，再次强制刷一遍当前主题的颜色
            val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            applyThemeColors(isDarkMode)
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
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 重新调用沉浸式初始化
        enableEdgeToEdge()

        // 从 newConfig 获取最新的模式
        val isDarkMode = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // 传入 applyThemeColors 处理
        applyThemeColors(isDarkMode)
    }
}