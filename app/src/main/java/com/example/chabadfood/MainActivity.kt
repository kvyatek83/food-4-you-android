package com.example.chabadfood

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.airbnb.lottie.LottieAnimationView
import com.google.gson.Gson
import com.starmicronics.stario10.InterfaceType
import com.starmicronics.stario10.StarConnectionSettings
import com.starmicronics.stario10.StarIO10CommunicationException
import com.starmicronics.stario10.StarIO10ErrorCode
import com.starmicronics.stario10.StarIO10UnprintableException
import com.starmicronics.stario10.StarPrinter
import com.starmicronics.stario10.starxpandcommand.DocumentBuilder
import com.starmicronics.stario10.starxpandcommand.PrinterBuilder
import com.starmicronics.stario10.starxpandcommand.StarXpandCommandBuilder
import com.starmicronics.stario10.starxpandcommand.printer.CutType
import com.starmicronics.stario10.starxpandcommand.printer.ImageParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var printer: StarPrinter? = null
    private val printerScope = CoroutineScope(Dispatchers.IO)
    private val RECEIPT_WIDTH = 576
    private val FONT_SCALE = 1.6f
    private lateinit var webView: WebView
    private lateinit var animationView: LottieAnimationView
    private lateinit var animationContainer: View
    private lateinit var sharedPreferences: SharedPreferences
    private val TAG = "ReceiptPrinter"

    // Configuration properties
    private var printerIp: String = "192.168.68.51" // Default fallback
    private var printerEnabled: Boolean = true
    private var serverUrl: String = "https://food-4-u-chabad-antigua.work" // Default fallback
    private var webViewUrl: String = "https://food-4-u-chabad-antigua.work" // Will be fetched from server
    private var authToken: String? = null
    
    // Monitoring properties
    private var lastPrinterStatus: Boolean = false
    private var lastErrorMessage: String? = null
    private val statusCheckInterval = 8000L // 8 seconds
    private val configFetchInterval = 30000L // 30 seconds
    private val statusHandler = Handler(Looper.getMainLooper())
    private val configHandler = Handler(Looper.getMainLooper())

    // HTTP client for server communication
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Logging functionality
    private fun logToServer(level: String, message: String, meta: Map<String, Any> = emptyMap()) {
        if (authToken == null) {
            Log.d(TAG, "No auth token - logging locally: [$level] $message")
            return
        }

        printerScope.launch {
            try {
                val logData = JSONObject().apply {
                    put("source", "android")
                    put("level", level)
                    put("message", message)
                    put("meta", JSONObject().apply {
                        meta.forEach { (key, value) ->
                            put(key, value)
                        }
                        put("timestamp", System.currentTimeMillis())
                        put("deviceInfo", JSONObject().apply {
                            put("model", android.os.Build.MODEL)
                            put("manufacturer", android.os.Build.MANUFACTURER)
                            put("androidVersion", android.os.Build.VERSION.RELEASE)
                            put("appVersion", "1.0.0")
                        })
                    })
                }

                val requestBody = logData.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$serverUrl/api/logs/client-log")
                    .header("authorization", authToken!!)
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Failed to send log to server: ${response.code}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending log to server", e)
            }
        }
    }

    // Convenience logging methods
    private fun logError(message: String, error: Throwable? = null, meta: Map<String, Any> = emptyMap()) {
        val enrichedMeta = meta.toMutableMap()
        error?.let {
            enrichedMeta["error"] = mapOf(
                "message" to it.message,
                "type" to it.javaClass.simpleName,
                "stackTrace" to it.stackTraceToString()
            )
        }
        logToServer("error", message, enrichedMeta)
    }

    private fun logInfo(message: String, meta: Map<String, Any> = emptyMap()) {
        logToServer("info", message, meta)
    }

    private fun logWarn(message: String, meta: Map<String, Any> = emptyMap()) {
        logToServer("warn", message, meta)
    }

    private fun logDebug(message: String, meta: Map<String, Any> = emptyMap()) {
        logToServer("debug", message, meta)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure no splash screen and immediate content display
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("printer_config", MODE_PRIVATE)

        // Log application startup
        logInfo("Application starting", mapOf(
            "event" to "app_startup",
            "deviceModel" to android.os.Build.MODEL,
            "androidVersion" to android.os.Build.VERSION.RELEASE,
            "appVersion" to "1.0.0"
        ))

        // Clear old cached configuration to use new defaults
        clearOldConfiguration()
        
        loadLocalConfiguration()
        setupWebView()
        initializePrinter()

        // Start monitoring only if we have a token
        if (authToken != null) {
            logInfo("Starting periodic tasks - token available", mapOf(
                "event" to "periodic_tasks_start",
                "reason" to "token_available"
            ))
            startPeriodicTasks()
            fetchServerConfiguration()
        } else {
            logInfo("Skipping periodic tasks - no token", mapOf(
                "event" to "periodic_tasks_skip",
                "reason" to "no_token"
            ))
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed - handling without restart")
        
        // Handle configuration changes (like screen rotation) without restarting
        // The activity will continue running normally
    }

    private fun clearOldConfiguration() {
        // Clear old IP configurations to force use of new defaults
        val editor = sharedPreferences.edit()
        
        // Check if we have old server URLs and clear them
        val currentServerUrl = sharedPreferences.getString("server_url", null)
        val currentWebViewUrl = sharedPreferences.getString("webview_url", null)

        if (currentServerUrl != null && currentServerUrl.contains(" http://192.168.68.55:3311")) {
            Log.d(TAG, "Clearing old server URL: $currentServerUrl")
            editor.remove("server_url")
        }
        
        if (currentWebViewUrl != null && currentWebViewUrl.contains(" http://192.168.68.55:12345")) {
            Log.d(TAG, "Clearing old WebView URL: $currentWebViewUrl")
            editor.remove("webview_url")
        }
        
        editor.apply()
    }

    private fun loadLocalConfiguration() {
        printerIp = sharedPreferences.getString("printer_ip", "192.168.68.51") ?: "192.168.68.51"
        printerEnabled = sharedPreferences.getBoolean("printer_enabled", true)
        serverUrl = sharedPreferences.getString("server_url", "https://food-4-u-chabad-antigua.work") ?: "https://food-4-u-chabad-antigua.work"
        webViewUrl = sharedPreferences.getString("webview_url", "https://food-4-u-chabad-antigua.work") ?: "https://food-4-u-chabad-antigua.work"
        authToken = sharedPreferences.getString("auth_token", null)

        Log.d(TAG, "Loaded local config - IP: $printerIp, Enabled: $printerEnabled, Server: $serverUrl")
        Log.d(TAG, "Auth token available: ${authToken != null}")

        logInfo("Local configuration loaded", mapOf(
            "event" to "config_loaded",
            "printerIp" to printerIp,
            "printerEnabled" to printerEnabled,
            "serverUrl" to serverUrl,
            "webViewUrl" to webViewUrl,
            "hasAuthToken" to (authToken != null)
        ))
    }

    private fun saveLocalConfiguration() {
        sharedPreferences.edit().apply {
            putString("printer_ip", printerIp)
            putBoolean("printer_enabled", printerEnabled)
            putString("server_url", serverUrl)
            putString("webview_url", webViewUrl)
            putString("auth_token", authToken)
            apply()
        }
    }

    private fun onTokenReceived(token: String) {
        Log.d(TAG, "Token received from Angular")
        authToken = token
        saveLocalConfiguration()

        logInfo("Authentication token received", mapOf(
            "event" to "token_received",
            "hasToken" to true
        ))

        // Start monitoring and configuration fetching now that we have a token
        if (!isMonitoringActive()) {
            startPeriodicTasks()
        }
        fetchServerConfiguration()
    }

    private fun onTokenCleared() {
        Log.d(TAG, "Token cleared - user logged out")

        logInfo("Authentication token cleared", mapOf(
            "event" to "token_cleared",
            "reason" to "user_logout"
        ))

        authToken = null
        saveLocalConfiguration()

        // Stop monitoring when user logs out
        stopPeriodicTasks()
    }

    private fun isMonitoringActive(): Boolean {
        // Simple check to see if monitoring is already running
        return statusHandler.hasCallbacks { true }
    }

    private fun stopPeriodicTasks() {
        statusHandler.removeCallbacksAndMessages(null)
        configHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped periodic tasks")
    }

    private fun fetchServerConfiguration() {
        printerScope.launch {
            try {
                if (authToken == null) {
                    Log.d(TAG, "No auth token available - skipping config fetch")
                    return@launch
                }

                // First, test basic connectivity
                Log.d(TAG, "Fetching configuration from: $serverUrl")

                logDebug("Fetching server configuration", mapOf(
                    "serverUrl" to serverUrl,
                    "event" to "config_fetch_start"
                ))

                val request = Request.Builder()
                    .url("$serverUrl/api/traveler/printer-config")
                    .header("authorization", authToken!!)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Config response code: ${response.code}")
                    Log.d(TAG, "Config response body: $responseBody")

                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val config = JSONObject(responseBody)

                            // Update configuration from server
                            var configChanged = false
                            val oldPrinterIp = printerIp
                            val oldPrinterEnabled = printerEnabled

                            if (config.has("printerIp")) {
                                val newIp = config.getString("printerIp")
                                if (newIp != printerIp && newIp.isNotEmpty()) {
                                    printerIp = newIp
                                    configChanged = true
                                }
                            }

                            if (config.has("printerEnabled")) {
                                val newEnabled = config.getBoolean("printerEnabled")
                                if (newEnabled != printerEnabled) {
                                    printerEnabled = newEnabled
                                    configChanged = true
                                }
                            }

                            if (configChanged) {
                                saveLocalConfiguration()
                                withContext(Dispatchers.Main) {
                                    initializePrinter()
                                }
                                Log.d(TAG, "Configuration updated from server")

                                logInfo("Configuration updated from server", mapOf(
                                    "event" to "config_updated",
                                    "changes" to mapOf(
                                        "printerIp" to mapOf("old" to oldPrinterIp, "new" to printerIp),
                                        "printerEnabled" to mapOf("old" to oldPrinterEnabled, "new" to printerEnabled)
                                    )
                                ))
                            }
                        } catch (e: JSONException) {
                            Log.e(TAG, "Failed to parse config JSON. Response was: $responseBody", e)
                            logError("Failed to parse configuration JSON", e, mapOf(
                                "event" to "config_parse_error",
                                "responseBody" to (responseBody ?: "null")
                            ))
                        }
                    } else {
                        Log.w(TAG, "Config request failed with code: ${response.code}, body: $responseBody")
                        logWarn("Configuration request failed", mapOf(
                            "event" to "config_request_failed",
                            "statusCode" to response.code,
                            "responseBody" to (responseBody ?: "null")
                        ))

                        if (response.code == 401 || response.code == 403) {
                            // Token expired or invalid - notify Angular to re-authenticate
                            Log.w(TAG, "Token expired or invalid - clearing token")
                            logWarn("Authentication token expired", mapOf(
                                "event" to "token_expired",
                                "statusCode" to response.code
                            ))
                            withContext(Dispatchers.Main) {
                                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('androidTokenExpired'));", null)
                            }
                            onTokenCleared()
                        }
                    }
                }

                // Fetch WebView URL from environment (only if we still have a valid token)
                if (authToken != null) {
                    val envRequest = Request.Builder()
                        .url("$serverUrl/api/traveler/env")
                        .header("authorization", authToken!!)
                        .get()
                        .build()

                    httpClient.newCall(envRequest).execute().use { response ->
                        val responseBody = response.body?.string()
                        Log.d(TAG, "Env response code: ${response.code}")
                        Log.d(TAG, "Env response body: $responseBody")

                        if (response.isSuccessful && responseBody != null) {
                            try {
                                val env = JSONObject(responseBody)
                                if (env.has("webViewUrl")) {
                                    val newWebViewUrl = env.getString("webViewUrl")
                                    if (newWebViewUrl != webViewUrl && newWebViewUrl.isNotEmpty()) {
                                        val oldUrl = webViewUrl
                                        webViewUrl = newWebViewUrl
                                        saveLocalConfiguration()
                                        Log.d(TAG, "WebView URL updated: $webViewUrl")

                                        logInfo("WebView URL updated", mapOf(
                                            "event" to "webview_url_updated",
                                            "oldUrl" to oldUrl,
                                            "newUrl" to webViewUrl
                                        ))

                                        // Reload WebView with new URL
                                        withContext(Dispatchers.Main) {
                                            webView.loadUrl(webViewUrl)
                                        }
                                    }
                                }
                            } catch (e: JSONException) {
                                Log.e(TAG, "Failed to parse env JSON. Response was: $responseBody", e)
                                logError("Failed to parse environment JSON", e, mapOf(
                                    "event" to "env_parse_error",
                                    "responseBody" to (responseBody ?: "null")
                                ))
                            }
                        } else {
                            Log.w(TAG, "Env request failed with code: ${response.code}, body: $responseBody")
                            logWarn("Environment request failed", mapOf(
                                "event" to "env_request_failed",
                                "statusCode" to response.code
                            ))
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, e.message, e)
                logError("Failed to fetch server configuration", e, mapOf(
                    "event" to "config_fetch_error"
                ))
            }
        }
    }

    private fun startPeriodicTasks() {
        // Start printer status monitoring
        startPrinterStatusMonitoring()

        // Start periodic configuration fetching
        startConfigurationFetching()
    }

    private fun startPrinterStatusMonitoring() {
        val statusRunnable = object : Runnable {
            override fun run() {
                printerScope.launch {
                    checkPrinterStatusAndReport()
                }
                statusHandler.postDelayed(this, statusCheckInterval)
            }
        }
        statusHandler.post(statusRunnable)
    }

    private fun startConfigurationFetching() {
        val configRunnable = object : Runnable {
            override fun run() {
                fetchServerConfiguration()
                configHandler.postDelayed(this, configFetchInterval)
            }
        }
        configHandler.post(configRunnable)
    }

    private suspend fun checkPrinterStatusAndReport() {
        if (!printerEnabled) {
            reportStatusToServer(false, "Printer disabled")
            return
        }

        if (printer == null) {
            reportStatusToServer(false, "Printer not initialized")
            return
        }

        try {
            val isAvailable = try {
                printer!!.openAsync().await()
                printer!!.closeAsync().await()
                true
            } catch (e: StarIO10CommunicationException) {
                Log.w(TAG, "Printer communication error: ${e.message}")
                logWarn("Printer status check - communication error", mapOf(
                    "event" to "printer_status_check_comm_error",
                    "printerIp" to printerIp.toString(),
                    "error" to e.message.toString()
                ))
                false
            } catch (e: Exception) {
                Log.w(TAG, "Printer check error: ${e.message}")
                logWarn("Printer status check - general error", mapOf(
                    "event" to "printer_status_check_error",
                    "printerIp" to printerIp.toString(),
                    "error" to e.message.toString()
                ))
                false
            }

            val errorMessage = if (isAvailable) null else "Printer not responding"

            // Only report if status changed
            if (isAvailable != lastPrinterStatus || errorMessage != lastErrorMessage) {
                reportStatusToServer(isAvailable, errorMessage)
                lastPrinterStatus = isAvailable
                lastErrorMessage = errorMessage

                logInfo("Printer status changed", mapOf(
                    "event" to "printer_status_changed",
                    "printerIp" to printerIp.toString(),
                    "available" to isAvailable.toString(),
                    "errorMessage" to errorMessage.toString()
                ))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking printer status", e)
            val errorMsg = "Status check failed: ${e.message}"
            if (errorMsg != lastErrorMessage) {
                reportStatusToServer(false, errorMsg)
                lastErrorMessage = errorMsg

                logError("Printer status check failed", e, mapOf(
                    "event" to "printer_status_check_failed",
                    "printerIp" to printerIp
                ))
            }
        }
    }

    private suspend fun reportStatusToServer(available: Boolean, errorMessage: String?) {
        try {
            if (authToken == null) {
                Log.d(TAG, "No auth token available - skipping status report")
                return
            }

            val statusData = JSONObject().apply {
                put("printerAvailable", available)
                put("printerEnabled", printerEnabled)
                put("printerIp", printerIp)
                put("lastError", errorMessage)
                put("timestamp", System.currentTimeMillis())
            }

            val requestBody = statusData.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$serverUrl/api/traveler/printer-status")
                .header("authorization", authToken!!)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Status reported to server: available=$available, error=$errorMessage")
                } else {
                    Log.w(TAG, "Failed to report status to server: ${response.code}")
                    if (response.code == 401 || response.code == 403) {
                        // Token expired - notify Angular and clear token
                        Log.w(TAG, "Token expired during status report - clearing token")
                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('androidTokenExpired'));", null)
                        }
                        onTokenCleared()
                    } else {

                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reporting status to server", e)
        }
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webview)
        animationView = findViewById(R.id.opening_animation)
        animationContainer = findViewById(R.id.animation_container)

        // Add a fallback timeout in case animation doesn't start or complete
        Handler(Looper.getMainLooper()).postDelayed({
            if (animationContainer.visibility == View.VISIBLE) {
                Log.w(TAG, "Animation timeout - switching to WebView")
                animationContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(webViewUrl)
            }
        }, 5000) // 5 second timeout

        // Setup Lottie animation completion listener
        animationView.addAnimatorListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {
                Log.d(TAG, "Lottie animation started")
                logInfo("Opening animation started", mapOf(
                    "event" to "animation_started"
                ))
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                Log.d(TAG, "Lottie animation completed")
                logInfo("Opening animation completed", mapOf(
                    "event" to "animation_completed"
                ))
                
                // Hide animation and show WebView
                animationContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                
                // Load the WebView URL after animation completes
                logInfo("Loading WebView URL after animation", mapOf(
                    "event" to "webview_loading_post_animation",
                    "url" to webViewUrl
                ))
                webView.loadUrl(webViewUrl)
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                Log.d(TAG, "Lottie animation cancelled")
                // Fallback: show WebView anyway
                animationContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(webViewUrl)
            }

            override fun onAnimationRepeat(animation: android.animation.Animator) {
                // Not used for non-looping animation
            }
        })

        // Also try to manually start the animation if it's not auto-playing
        if (!animationView.isAnimating) {
            animationView.playAnimation()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView page loaded: $url")
                logInfo("WebView page loaded", mapOf(
                    "event" to "webview_page_loaded",
                    "url" to (url ?: "unknown")
                ))
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                logError("WebView error occurred", null, mapOf(
                    "event" to "webview_error",
                    "errorCode" to errorCode,
                    "description" to (description ?: "unknown"),
                    "failingUrl" to (failingUrl ?: "unknown")
                ))
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(WebAppInterface(), "AndroidPrinter")

        logInfo("WebView configured", mapOf(
            "event" to "webview_configured",
            "javascriptEnabled" to true,
            "debuggingEnabled" to true
        ))
    }

    private fun initializePrinter() {
        if (!printerEnabled) {
            Log.d(TAG, "Printer is disabled in configuration")
            logInfo("Printer initialization skipped - disabled in configuration", mapOf(
                "event" to "printer_init_skipped",
                "reason" to "disabled"
            ))
            printer = null
            return
        }

        try {
            val settings = StarConnectionSettings(InterfaceType.Lan, printerIp, false)
            printer = StarPrinter(settings, applicationContext)
            Log.d(TAG, "Printer initialized with IP: $printerIp")

            logInfo("Printer initialized successfully", mapOf(
                "event" to "printer_initialized",
                "printerIp" to printerIp,
                "interfaceType" to "LAN"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize printer", e)
            logError("Failed to initialize printer", e, mapOf(
                "event" to "printer_init_failed",
                "printerIp" to printerIp
            ))
            printer = null
        }
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun setAuthToken(token: String): String {
            Log.d(TAG, "Received auth token from Angular")
            try {
                // Validate token format (basic JWT check)
                val parts = token.split(".")
                if (parts.size != 3) {
                    return createErrorResponse("Invalid token format")
                }

                // Store token and start monitoring
                onTokenReceived(token)
                return createSuccessResponse("Token stored successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error storing auth token", e)
                return createErrorResponse("Failed to store token: ${e.message}")
            }
        }

        @JavascriptInterface
        fun clearAuthToken(): String {
            Log.d(TAG, "Clearing auth token - user logged out")
            onTokenCleared()
            return createSuccessResponse("Token cleared successfully")
        }

        @JavascriptInterface
        fun getAuthStatus(): String {
            return try {
                JSONObject().apply {
                    put("hasToken", authToken != null)
                    put("isMonitoring", isMonitoringActive())
                    put("timestamp", System.currentTimeMillis())
                }.toString()
            } catch (e: Exception) {
                createErrorResponse("Failed to get auth status: ${e.message}")
            }
        }

        @JavascriptInterface
        fun printReceipt(receiptDataJson: String): String {
            Log.d(TAG, "Print request received")

            logInfo("Print request received", mapOf(
                "event" to "print_request_received",
                "dataSize" to receiptDataJson.length
            ))

            if (authToken == null) {
                logWarn("Print request rejected - no authentication", mapOf(
                    "event" to "print_request_rejected",
                    "reason" to "no_auth_token"
                ))
                return createErrorResponse("Not authenticated - please log in first")
            }

            if (!printerEnabled) {
                logWarn("Print request rejected - printer disabled", mapOf(
                    "event" to "print_request_rejected",
                    "reason" to "printer_disabled"
                ))
                return createErrorResponse("Printer is disabled")
            }

            if (printer == null) {
                logError("Print request rejected - printer not initialized", null, mapOf(
                    "event" to "print_request_rejected",
                    "reason" to "printer_not_initialized"
                ))
                return createErrorResponse("Printer not initialized")
            }

            try {
                val receiptData = parseReceiptData(receiptDataJson)
                printerScope.launch {
                    val result = printReceiptFromData(receiptData)
                    // Report print result to server
                    reportPrintResultToServer(result)
                }

                logInfo("Print job submitted successfully", mapOf(
                    "event" to "print_job_submitted"
                ))
                return createSuccessResponse("Print job submitted")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing print request", e)
                logError("Error processing print request", e, mapOf(
                    "event" to "print_request_error"
                ))
                return createErrorResponse(e.message ?: "Unknown error")
            }
        }

        @JavascriptInterface
        fun testPrinter(): String {
            Log.d(TAG, "Test print requested")

            logInfo("Test print requested", mapOf(
                "event" to "test_print_requested"
            ))

            if (authToken == null) {
                logWarn("Test print rejected - no authentication", mapOf(
                    "event" to "test_print_rejected",
                    "reason" to "no_auth_token"
                ))
                return createErrorResponse("Not authenticated - please log in first")
            }

            if (!printerEnabled) {
                logWarn("Test print rejected - printer disabled", mapOf(
                    "event" to "test_print_rejected",
                    "reason" to "printer_disabled"
                ))
                return createErrorResponse("Printer is disabled")
            }

            if (printer == null) {
                logError("Test print rejected - printer not initialized", null, mapOf(
                    "event" to "test_print_rejected",
                    "reason" to "printer_not_initialized"
                ))
                return createErrorResponse("Printer not initialized")
            }

            printerScope.launch {
                val testData = createTestReceiptData()
                val result = printReceiptFromData(testData)
                reportPrintResultToServer(result, isTest = true)
            }

            logInfo("Test print job submitted", mapOf(
                "event" to "test_print_submitted"
            ))
            return createSuccessResponse("Test print initiated")
        }

        @JavascriptInterface
        fun getPrinterStatus(): String {
            return try {
                JSONObject().apply {
                    put("available", lastPrinterStatus)
                    put("enabled", printerEnabled)
                    put("ip", printerIp)
                    put("lastError", lastErrorMessage)
                    put("authenticated", authToken != null)
                    put("timestamp", System.currentTimeMillis())
                }.toString()
            } catch (e: Exception) {
                createErrorResponse("Failed to get status: ${e.message}")
            }
        }
    }

    private suspend fun reportPrintResultToServer(result: PrintResult, isTest: Boolean = false) {
        try {
            if (authToken == null) {
                Log.d(TAG, "No auth token available - skipping print result report")
                return
            }

            val printData = JSONObject().apply {
                put("success", result.success)
                put("message", result.message)
                put("errorCode", result.errorCode)
                put("isTest", isTest)
                put("timestamp", System.currentTimeMillis())
            }

            val requestBody = printData.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$serverUrl/api/traveler/print-result")
                .header("authorization", authToken!!)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Print result reported to server")
                } else {
                    Log.w(TAG, "Failed to report print result: ${response.code}")
                    if (response.code == 401 || response.code == 403) {
                        // Token expired - notify Angular and clear token
                        Log.w(TAG, "Token expired during print result report - clearing token")
                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('androidTokenExpired'));", null)
                        }
                        onTokenCleared()
                    } else {

                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error reporting print result to server", e)
        }
    }

    private fun createSuccessResponse(message: String): String {
        return JSONObject().apply {
            put("status", "success")
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    private fun createErrorResponse(message: String): String {
        return JSONObject().apply {
            put("status", "error")
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    private suspend fun printReceiptFromData(receiptData: ReceiptData): PrintResult {
        if (printer == null) {
            val result = PrintResult(false, "Printer not initialized", "NO_PRINTER")
            logError("Print failed - printer not initialized", null, mapOf(
                "event" to "print_failed",
                "errorCode" to "NO_PRINTER"
            ))
            return result
        }

        return try {
            logDebug("Starting print operation", mapOf(
                "event" to "print_operation_start",
                "printerIp" to printerIp
            ))

            val receiptBitmap = createReceiptBitmap(receiptData)

            try {
                printer!!.openAsync().await()
                Log.i(TAG, "Connected to printer successfully")

                logInfo("Printer connection established", mapOf(
                    "event" to "printer_connected",
                    "printerIp" to printerIp
                ))
            } catch (e: StarIO10CommunicationException) {
                Log.e(TAG, "Communication error: ${e.message}")
                val result = PrintResult(false, "Communication error: ${e.message}", "COMMUNICATION_ERROR")
                logError("Printer communication error", e, mapOf(
                    "event" to "printer_communication_error",
                    "printerIp" to printerIp,
                    "errorCode" to "COMMUNICATION_ERROR"
                ))
                return result
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                val result = PrintResult(false, "Connection error: ${e.message}", "CONNECTION_ERROR")
                logError("Printer connection error", e, mapOf(
                    "event" to "printer_connection_error",
                    "printerIp" to printerIp,
                    "errorCode" to "CONNECTION_ERROR"
                ))
                return result
            }

            try {
                val commands = StarXpandCommandBuilder().run {
                    addDocument(DocumentBuilder().addPrinter(
                        PrinterBuilder()
                            .actionPrintImage(ImageParameter(receiptBitmap, RECEIPT_WIDTH))
                            .actionFeedLine(3)
                            .actionCut(CutType.Partial)
                    ))
                    getCommands()
                }

                printer!!.printAsync(commands).await()
                Log.i(TAG, "Print completed successfully")

                val result = PrintResult(true, "Print completed successfully", null)
                logInfo("Print completed successfully", mapOf(
                    "event" to "print_completed",
                    "printerIp" to printerIp
                ))
                result

            } catch (e: StarIO10UnprintableException) {
                val errorMessage = when (e.errorCode) {
                    StarIO10ErrorCode.DeviceHasError -> "Device error: ${e.message}"
                    StarIO10ErrorCode.PrinterHoldingPaper -> "Printer holding paper: ${e.message}"
                    else -> "Printing error: ${e.message}"
                }
                Log.e(TAG, errorMessage)
                val result = PrintResult(false, errorMessage, e.errorCode.toString())
                logError("Print operation failed", e, mapOf(
                    "event" to "print_operation_failed",
                    "printerIp" to printerIp,
                    "errorCode" to e.errorCode.toString(),
                    "starErrorCode" to e.errorCode.name
                ))
                result

            } catch (e: Exception) {
                Log.e(TAG, "Print exception: ${e.message}")
                val result = PrintResult(false, "Print failed: ${e.message}", "PRINT_ERROR")
                logError("Print exception occurred", e, mapOf(
                    "event" to "print_exception",
                    "printerIp" to printerIp,
                    "errorCode" to "PRINT_ERROR"
                ))
                result

            } finally {
                try {
                    printer!!.closeAsync().await()
                    Log.i(TAG, "Connection closed")
                    logDebug("Printer connection closed", mapOf(
                        "event" to "printer_connection_closed",
                        "printerIp" to printerIp
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing connection", e)
                    logWarn("Error closing printer connection", mapOf(
                        "event" to "printer_connection_close_error",
                        "printerIp" to printerIp.toString(),
                        "error" to e.message.toString()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in printReceiptFromData", e)
            val result = PrintResult(false, "Unexpected error: ${e.message}", "UNEXPECTED_ERROR")
            logError("Unexpected print error", e, mapOf(
                "event" to "print_unexpected_error",
                "printerIp" to printerIp,
                "errorCode" to "UNEXPECTED_ERROR"
            ))
            result
        }
    }

    private fun createTestReceiptData(): ReceiptData {
        return ReceiptData(
            headerInfo = HeaderInfo(
                storeName = "Chabad House",
                storeAddress = "Test Print",
                storeCity = "System Check"
            ),
            date = SimpleDateFormat("MM/dd/yyyy hh:mm aa", Locale.getDefault()).format(Date()),
            transactionType = "TEST",
            total = 0.0,
            footerInfo = FooterInfo(
                thankYouMessage = "Printer test successful!"
            )
        )
    }

    private fun parseReceiptData(jsonData: String): ReceiptData {
        return try {
            Gson().fromJson(jsonData, ReceiptData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing receipt data", e)
            createDefaultReceiptData()
        }
    }

    private fun createReceiptBitmap(receiptData: ReceiptData): Bitmap {
        // Calculate dynamic height based on content
        val estimatedHeight = calculateReceiptHeight(receiptData)
        val bitmap = Bitmap.createBitmap(RECEIPT_WIDTH, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Paint configurations
        val headerPaint = Paint().apply {
            color = Color.BLACK
            textSize = 40f * FONT_SCALE // Even bigger header
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        val subHeaderPaint = Paint().apply {
            color = Color.BLACK
            textSize = 32f * FONT_SCALE // Bigger sub-header
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val datePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f * FONT_SCALE // Bigger date
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 26f * FONT_SCALE // Bigger bold text
            isAntiAlias = true
            isFakeBoldText = true
        }

        val normalPaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f * FONT_SCALE // Bigger normal text
            isAntiAlias = true
        }

        val centerPaint = Paint().apply {
            color = Color.BLACK
            textSize = 28f * FONT_SCALE // Bigger center text
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        canvas.drawColor(Color.WHITE)

        var yPosition = 60f // More top margin
        val lineHeight = 45f // More space between lines
        val centerX = RECEIPT_WIDTH / 2f
        val leftMargin = 20f
        val rightMargin = RECEIPT_WIDTH - 20f

        // Header - Chabad Antigua (centered, bigger)
        receiptData.headerInfo?.storeName?.let { storeName ->
            canvas.drawText(storeName, centerX, yPosition, headerPaint)
            yPosition += lineHeight + 20f // More space after header
        }

        // Sub-header - KOSHER FOOD FOR YOU (centered, bigger)
        receiptData.headerInfo?.storeAddress?.let { subHeader ->
            canvas.drawText(subHeader, centerX, yPosition, subHeaderPaint)
            yPosition += lineHeight + 15f // More space after sub-header
        }

        // Date in dd/mm/yyyy - hh:mm format (centered, under sub-header)
        receiptData.date?.let { date ->
            val formattedDate = formatDateForReceipt(date)
            canvas.drawText(formattedDate, centerX, yPosition, datePaint)
            yPosition += lineHeight + 20f // Space after date
        }

        // Customer name and order type (bold, with even spacing)
        yPosition += 20f // Extra space before customer info
        
        receiptData.customerInfo?.let { customerInfo ->
            val customerText = customerInfo.name ?: ""
            val orderTypeText = customerInfo.orderType ?: ""

            // Calculate text widths for proper spacing
            val customerWidth = boldPaint.measureText(customerText)
            val orderTypeWidth = boldPaint.measureText(orderTypeText)

            canvas.drawText(customerText, leftMargin, yPosition, boldPaint)
            canvas.drawText(orderTypeText, rightMargin - orderTypeWidth, yPosition, boldPaint)
            yPosition += lineHeight + 25f // More space after customer info
        }
        
        yPosition += 15f // Extra space after customer info

        // Items list with bullets - add space before items
        yPosition += 15f // Extra space before item list
        
        receiptData.items?.forEach { item ->
            yPosition = drawItemWithBullet(canvas, item, yPosition, leftMargin, rightMargin, normalPaint, lineHeight)
            yPosition += 15f // More space between items
        }
        
        yPosition += 20f // Extra space after item list

        // Total (bold, with even spacing)
        yPosition += 20f // Extra space before total
        
        val totalLabel = "TOTAL"
        val totalAmount = "${receiptData.total?.let { "%.2f".format(it) } ?: "0.00"} GTQ"

        val totalAmountWidth = boldPaint.measureText(totalAmount)

        canvas.drawText(totalLabel, leftMargin, yPosition, boldPaint)
        canvas.drawText(totalAmount, rightMargin - totalAmountWidth, yPosition, boldPaint)
        yPosition += lineHeight + 20f // Space after total
        
        yPosition += 15f // Extra space after total

        // Payment status (centered, bold)
        receiptData.paymentInfo?.status?.let { status ->
            canvas.drawText(status, centerX, yPosition, centerPaint)
            yPosition += lineHeight + 20f
        }

        // Bon Appétit (centered, bold) - Footer restored
        canvas.drawText("Bon Appétit", centerX, yPosition, centerPaint)
        yPosition += lineHeight + 40f // Space after Bon Appétit

        return bitmap
    }

    private fun drawItemWithBullet(
        canvas: Canvas,
        item: ItemInfo,
        startY: Float,
        leftMargin: Float,
        rightMargin: Float,
        paint: Paint,
        lineHeight: Float
    ): Float {
        var yPosition = startY
        val maxWidth = rightMargin - leftMargin
        val bulletWidth = 20f // Width for the bullet point
        val bulletIndent = 10f // Indentation for the bullet point

        // Get item name and add-ons
        val itemName = item.description ?: ""
        val addOns = item.selectedAddOns ?: emptyList()

        // Split item name and add-ons
        val parts = if (itemName.contains(" con ")) {
            val splitIndex = itemName.indexOf(" con ")
            val mainName = itemName.substring(0, splitIndex)
            val addOnsPart = itemName.substring(splitIndex + 5) // Remove " con "
            Pair(mainName, listOf(addOnsPart))
        } else {
            Pair(itemName, addOns)
        }

        val mainItemName = parts.first
        val itemAddOns = parts.second

        // Draw bullet point and main item name on the same line
        canvas.drawText("•", leftMargin + bulletIndent, yPosition, paint)
        yPosition = drawTextWithWrapping(canvas, mainItemName, leftMargin + bulletWidth + bulletIndent, yPosition, maxWidth - bulletWidth, paint, lineHeight)

        // Draw add-ons with indentation
        itemAddOns.forEach { addOn ->
            if (addOn.isNotEmpty()) {
                val addOnText = "    con $addOn"
                yPosition = drawTextWithWrapping(canvas, addOnText, leftMargin + bulletWidth + bulletIndent, yPosition, maxWidth - bulletWidth, paint, lineHeight)
            }
        }

        return yPosition
    }

    private fun drawTextWithWrapping(
        canvas: Canvas,
        text: String,
        leftMargin: Float,
        startY: Float,
        maxWidth: Float,
        paint: Paint,
        lineHeight: Float
    ): Float {
        var yPosition = startY
        val words = text.split(" ")
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val textWidth = paint.measureText(testLine)

            if (textWidth <= maxWidth) {
                currentLine = testLine
            } else {
                // Draw current line and start new line
                if (currentLine.isNotEmpty()) {
                    canvas.drawText(currentLine, leftMargin, yPosition, paint)
                    yPosition += lineHeight
                }
                currentLine = word
            }
        }

        // Draw remaining text
        if (currentLine.isNotEmpty()) {
            canvas.drawText(currentLine, leftMargin, yPosition, paint)
            yPosition += lineHeight
        }

        return yPosition
    }

    private fun formatDateForReceipt(dateString: String): String {
        return try {
            // Parse the Spanish date format and convert to dd/mm/yyyy - hh:mm
            val parts = dateString.split(" - ")
            if (parts.size == 2) {
                val datePart = parts[0] // "Domingo, 15 de Diciembre 2024"
                val timePart = parts[1] // "14:30"

                // Extract day, month, year from Spanish format
                val dateComponents = datePart.split(" ")
                if (dateComponents.size >= 5) {
                    val day = dateComponents[1].padStart(2, '0')
                    val monthName = dateComponents[3]
                    val year = dateComponents[4]

                    // Convert Spanish month to number
                    val monthNumber = when (monthName.lowercase()) {
                        "enero" -> "01"
                        "febrero" -> "02"
                        "marzo" -> "03"
                        "abril" -> "04"
                        "mayo" -> "05"
                        "junio" -> "06"
                        "julio" -> "07"
                        "agosto" -> "08"
                        "septiembre" -> "09"
                        "octubre" -> "10"
                        "noviembre" -> "11"
                        "diciembre" -> "12"
                        else -> "01"
                    }

                    "$day/$monthNumber/$year - $timePart"
                } else {
                    // Fallback: try to extract date from current date
                    val now = java.util.Date()
                    val formatter = java.text.SimpleDateFormat("dd/MM/yyyy - HH:mm", java.util.Locale.getDefault())
                    formatter.format(now)
                }
            } else {
                // Fallback: try to extract date from current date
                val now = java.util.Date()
                val formatter = java.text.SimpleDateFormat("dd/MM/yyyy - HH:mm", java.util.Locale.getDefault())
                formatter.format(now)
            }
        } catch (e: Exception) {
            // Ultimate fallback
            val now = java.util.Date()
            val formatter = java.text.SimpleDateFormat("dd/MM/yyyy - HH:mm", java.util.Locale.getDefault())
            formatter.format(now)
        }
    }

    private fun calculateReceiptHeight(receiptData: ReceiptData): Int {
        // Base height for header, footer, and spacing
        var height = 400 // Increased for extra spacing around items

        // Add height for each item (with bullet formatting and extra spacing)
        receiptData.items?.let { items ->
            height += items.size * 90 // Increased for better spacing
        }

        // Add space for margins and spacing
        height += 200 // Increased for extra spacing around item list

        return height
    }

    private fun createDefaultReceiptData(): ReceiptData {
        return ReceiptData(
            headerInfo = HeaderInfo(
                storeName = "Chabad House",
                storeAddress = "123 Main Street",
                storeCity = "City, State 12345"
            ),
            date = SimpleDateFormat("MM/dd/yyyy hh:mm aa", Locale.getDefault()).format(Date()),
            transactionType = "SALE",
            total = 0.0,
            footerInfo = FooterInfo(
                thankYouMessage = "Thank you for your visit!"
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacksAndMessages(null)
        configHandler.removeCallbacksAndMessages(null)

        logInfo("Application shutting down", mapOf(
            "event" to "app_shutdown"
        ))
    }

    private data class ReceiptLine(
        val text: String,
        val textSize: Float,
        val bold: Boolean = false,
        val alignment: Alignment = Alignment.LEFT,
        val inverted: Boolean = false
    ) {
        enum class Alignment { LEFT, CENTER, RIGHT }
    }
}

// Result class for better error handling
data class PrintResult(
    val success: Boolean,
    val message: String,
    val errorCode: String?
)

// Data classes for receipt data
data class ReceiptData(
    val headerInfo: HeaderInfo? = null,
    val date: String? = null,
    val customerInfo: CustomerInfo? = null,
    val referenceNumber: String? = null,
    val transactionType: String? = null,
    val items: List<ItemInfo>? = null,
    val subtotal: Double? = null,
    val tax: Double? = null,
    val total: Double? = null,
    val paymentInfo: PaymentInfo? = null,
    val footerInfo: FooterInfo? = null
)

data class HeaderInfo(
    val storeName: String? = null,
    val storeAddress: String? = null,
    val storeCity: String? = null,
    val logo: String? = null
)

data class CustomerInfo(
    val name: String? = null,
    val orderType: String? = null
)

data class ItemInfo(
    val sku: String? = null,
    val description: String? = null,
    val quantity: Int? = null,
    val unitPrice: Double? = null,
    val price: Double? = null,
    val selectedAddOns: List<String>? = null
)

data class PaymentInfo(
    val method: String? = null,
    val amount: String? = null,
    val cardNumber: String? = null,
    val authCode: String? = null,
    val status: String? = null
)

data class FooterInfo(
    val refundPolicy: String? = null,
    val returnPolicy: String? = null,
    val additionalInfo: String? = null,
    val thankYouMessage: String? = null
)