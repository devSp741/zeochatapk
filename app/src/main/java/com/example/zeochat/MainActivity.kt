package com.example.zeochat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import com.onesignal.OneSignal
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.onesignal.user.subscriptions.IPushSubscriptionObserver
import com.onesignal.user.subscriptions.PushSubscriptionChangedState
import androidx.activity.OnBackPressedCallback

import android.webkit.ValueCallback
import android.net.Uri
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var layoutError: RelativeLayout
    private lateinit var btnRetry: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var isError = false
    private var isPageFinished = false

    // Variable to hold the callback for file upload
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ActivityResultLauncher for handling file picker result
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            var results: Array<Uri>? = null
            if (data != null) {
                val dataString = data.dataString
                val clipData = data.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i ->
                        clipData.getItemAt(i).uri
                    }
                } else if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check if OneSignal App ID is already saved
        val sharedPref = getSharedPreferences("ZeoChatPrefs", MODE_PRIVATE)
        val savedAppId = sharedPref.getString("onesignal_app_id", "")

        if (!savedAppId.isNullOrEmpty()) {
            setupOneSignalObserver()
        }

        webView = findViewById(R.id.webView)
        layoutError = findViewById(R.id.layoutError)
        btnRetry = findViewById(R.id.btnRetry)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isError = false
                isPageFinished = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    isError = true
                    showErrorScreen()
                    swipeRefresh.isRefreshing = false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                isPageFinished = true
                if (!isError) {
                    showWebView()
                    injectOneSignalId()
                    syncOneSignalInit()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            // Handle file upload requests
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel existing callback if any
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*" // Accept all file types, can be specific like "image/*"
                
                //Allow multiple file selection if supported
                 if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                
                // Specific MIME types from the web page input
                val mimeTypes = fileChooserParams?.acceptTypes
                if (mimeTypes != null && mimeTypes.isNotEmpty()) {
                     intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }

                try {
                    filePickerLauncher.launch(Intent.createChooser(intent, "Select File"))
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }
        }

        checkPermissions()

        val url = getString(R.string.website_url)
        webView.loadUrl(url)

        btnRetry.setOnClickListener {
            showWebView()
            webView.reload()
        }

        swipeRefresh.setOnRefreshListener {
            webView.clearCache(true)
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupOneSignalObserver() {
        // Request OneSignal Notification Permission
        lifecycleScope.launch {
            OneSignal.Notifications.requestPermission(true)
        }

        // Observer to handle OneSignal ID changes/availability
        OneSignal.User.pushSubscription.addObserver(object : IPushSubscriptionObserver {
            override fun onPushSubscriptionChange(state: PushSubscriptionChangedState) {
                if (state.current.id != null) {
                    runOnUiThread {
                        injectOneSignalId()
                    }
                }
            }
        })
    }

    private fun showErrorScreen() {
        webView.visibility = View.GONE
        layoutError.visibility = View.VISIBLE
    }

    private fun showWebView() {
        layoutError.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }



    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.READ_EXTERNAL_STORAGE, // For older Android versions
             // For Android 13+ (Tiramisu)
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
             Manifest.permission.READ_MEDIA_AUDIO
        )

        val listPermissionsNeeded = ArrayList<String>()
        for (permission in permissions) {
             // Basic check, robust implementation would check SDK version
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(permission)
            }
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun injectOneSignalId() {
        try {
            val userId = OneSignal.User.pushSubscription.id
            if (!userId.isNullOrEmpty() && ::webView.isInitialized && isPageFinished && !isError) {
                val script = "javascript:try{saveAndroidOneSignalUserId('$userId');}catch(e){console.log('OneSignal Sync Error: ' + e);}"
                webView.evaluateJavascript(script, null)
            }
        } catch (e: Exception) {
            // OneSignal might not be initialized yet
        }
    }

    private fun syncOneSignalInit() {
        if (::webView.isInitialized && isPageFinished && !isError) {
            // Fetch onesignal_app_id from the webpage JS variable
            webView.evaluateJavascript("javascript:onesignal_app_id") { value ->
                if (value != null && value != "null" && value != "undefined") {
                    // Value comes back as a string with quotes, e.g., "\"123...\""
                    val appId = value.replace("\"", "")
                    
                    val sharedPref = applicationContext.getSharedPreferences("ZeoChatPrefs", MODE_PRIVATE)
                    val savedAppId = sharedPref.getString("onesignal_app_id", "")

                    if (appId.isNotEmpty() && appId != savedAppId) {
                        // Save new ID
                        with(sharedPref.edit()) {
                            putString("onesignal_app_id", appId)
                            apply()
                        }
                        // Re-init OneSignal with new ID
                        OneSignal.initWithContext(applicationContext, appId)
                        
                        // Setup observer now that we are initialized
                        setupOneSignalObserver()
                        // Try to inject ID immediately if available
                        injectOneSignalId()
                    }
                }
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
    }
}
