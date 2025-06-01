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
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
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
    private lateinit var sharedPreferences: SharedPreferences
    private val TAG = "ReceiptPrinter"

    // Configuration properties
    private var printerIp: String = "192.168.68.51" // Default fallback
    private var printerEnabled: Boolean = true
    private var serverUrl: String = "http://192.168.68.59:3311" // Default fallback
    private var webViewUrl: String = "http://192.168.68.59:12345" // Will be fetched from server
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("printer_config", MODE_PRIVATE)

        loadLocalConfiguration()
        setupWebView()
        initializePrinter()

        // Start monitoring only if we have a token
        if (authToken != null) {
            startPeriodicTasks()
            fetchServerConfiguration()
        }
    }

    private fun loadLocalConfiguration() {
        printerIp = sharedPreferences.getString("printer_ip", "192.168.68.51") ?: "192.168.68.51"
        printerEnabled = sharedPreferences.getBoolean("printer_enabled", true)
        serverUrl = sharedPreferences.getString("server_url", "http://192.168.68.59:3311") ?: "http://192.168.68.59:3311"
        webViewUrl = sharedPreferences.getString("webview_url", "http://192.168.68.59:12345") ?: "http://192.168.68.59:12345"
        authToken = sharedPreferences.getString("auth_token", null)

        Log.d(TAG, "Loaded local config - IP: $printerIp, Enabled: $printerEnabled, Server: $serverUrl")
        Log.d(TAG, "Auth token available: ${authToken != null}")
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

        // Start monitoring and configuration fetching now that we have a token
        if (!isMonitoringActive()) {
            startPeriodicTasks()
        }
        fetchServerConfiguration()
    }

    private fun onTokenCleared() {
        Log.d(TAG, "Token cleared - user logged out")
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
                            } else {

                            }
                        } catch (e: JSONException) {
                            Log.e(TAG, "Failed to parse config JSON. Response was: $responseBody", e)
                        }
                    } else {
                        Log.w(TAG, "Config request failed with code: ${response.code}, body: $responseBody")
                        if (response.code == 401 || response.code == 403) {
                            // Token expired or invalid - notify Angular to re-authenticate
                            Log.w(TAG, "Token expired or invalid - clearing token")
                            withContext(Dispatchers.Main) {
                                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('androidTokenExpired'));", null)
                            }
                            onTokenCleared()
                        } else {

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
                                        webViewUrl = newWebViewUrl
                                        saveLocalConfiguration()
                                        Log.d(TAG, "WebView URL updated: $webViewUrl")

                                        // Reload WebView with new URL
                                        withContext(Dispatchers.Main) {
                                            webView.loadUrl(webViewUrl)
                                        }
                                    } else {

                                    }
                                } else {

                                }
                            } catch (e: JSONException) {
                                Log.e(TAG, "Failed to parse env JSON. Response was: $responseBody", e)
                            }
                        } else {
                            Log.w(TAG, "Env request failed with code: ${response.code}, body: $responseBody")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch server configuration", e)
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
                false
            } catch (e: Exception) {
                Log.w(TAG, "Printer check error: ${e.message}")
                false
            }

            val errorMessage = if (isAvailable) null else "Printer not responding"

            // Only report if status changed
            if (isAvailable != lastPrinterStatus || errorMessage != lastErrorMessage) {
                reportStatusToServer(isAvailable, errorMessage)
                lastPrinterStatus = isAvailable
                lastErrorMessage = errorMessage
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking printer status", e)
            val errorMsg = "Status check failed: ${e.message}"
            if (errorMsg != lastErrorMessage) {
                reportStatusToServer(false, errorMsg)
                lastErrorMessage = errorMsg
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
        webView = findViewById<WebView>(R.id.webview)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "WebView page loaded: $url")
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

        // Load the WebView URL
        webView.loadUrl(webViewUrl)
    }

    private fun initializePrinter() {
        if (!printerEnabled) {
            Log.d(TAG, "Printer is disabled in configuration")
            printer = null
            return
        }

        try {
            val settings = StarConnectionSettings(InterfaceType.Lan, printerIp, false)
            printer = StarPrinter(settings, applicationContext)
            Log.d(TAG, "Printer initialized with IP: $printerIp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize printer", e)
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

            if (authToken == null) {
                return createErrorResponse("Not authenticated - please log in first")
            }

            if (!printerEnabled) {
                return createErrorResponse("Printer is disabled")
            }

            if (printer == null) {
                return createErrorResponse("Printer not initialized")
            }

            try {
                val receiptData = parseReceiptData(receiptDataJson)
                printerScope.launch {
                    val result = printReceiptFromData(receiptData)
                    // Report print result to server
                    reportPrintResultToServer(result)
                }
                return createSuccessResponse("Print job submitted")
            } catch (e: Exception) {
                Log.e(TAG, "Error processing print request", e)
                return createErrorResponse(e.message ?: "Unknown error")
            }
        }

        @JavascriptInterface
        fun testPrinter(): String {
            Log.d(TAG, "Test print requested")

            if (authToken == null) {
                return createErrorResponse("Not authenticated - please log in first")
            }

            if (!printerEnabled) {
                return createErrorResponse("Printer is disabled")
            }

            if (printer == null) {
                return createErrorResponse("Printer not initialized")
            }

            printerScope.launch {
                val testData = createTestReceiptData()
                val result = printReceiptFromData(testData)
                reportPrintResultToServer(result, isTest = true)
            }

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
            return PrintResult(false, "Printer not initialized", "NO_PRINTER")
        }

        return try {
            val receiptBitmap = createReceiptBitmap(receiptData)

            try {
                printer!!.openAsync().await()
                Log.i(TAG, "Connected to printer successfully")
            } catch (e: StarIO10CommunicationException) {
                Log.e(TAG, "Communication error: ${e.message}")
                return PrintResult(false, "Communication error: ${e.message}", "COMMUNICATION_ERROR")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                return PrintResult(false, "Connection error: ${e.message}", "CONNECTION_ERROR")
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
                PrintResult(true, "Print completed successfully", null)

            } catch (e: StarIO10UnprintableException) {
                val errorMessage = when (e.errorCode) {
                    StarIO10ErrorCode.DeviceHasError -> "Device error: ${e.message}"
                    StarIO10ErrorCode.PrinterHoldingPaper -> "Printer holding paper: ${e.message}"
                    else -> "Printing error: ${e.message}"
                }
                Log.e(TAG, errorMessage)
                PrintResult(false, errorMessage, e.errorCode.toString())

            } catch (e: Exception) {
                Log.e(TAG, "Print exception: ${e.message}")
                PrintResult(false, "Print failed: ${e.message}", "PRINT_ERROR")

            } finally {
                try {
                    printer!!.closeAsync().await()
                    Log.i(TAG, "Connection closed")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing connection", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in printReceiptFromData", e)
            PrintResult(false, "Unexpected error: ${e.message}", "UNEXPECTED_ERROR")
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
        val dividerSpacing = 20f

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
            yPosition += lineHeight + 25f // More space after date
        }

        // First divider line
        yPosition += dividerSpacing
        drawDividerLine(canvas, yPosition, leftMargin, rightMargin)
        yPosition += dividerSpacing

        // Customer name and order type (bold, with even spacing)
        receiptData.customerInfo?.let { customerInfo ->
            val customerText = customerInfo.name ?: ""
            val orderTypeText = customerInfo.orderType ?: ""

            // Calculate text widths for proper spacing
            val customerWidth = boldPaint.measureText(customerText)
            val orderTypeWidth = boldPaint.measureText(orderTypeText)

            canvas.drawText(customerText, leftMargin, yPosition, boldPaint)
            canvas.drawText(orderTypeText, rightMargin - orderTypeWidth, yPosition, boldPaint)
            yPosition += lineHeight + 15f
        }

        // Second divider line
        yPosition += dividerSpacing
        drawDividerLine(canvas, yPosition, leftMargin, rightMargin)
        yPosition += dividerSpacing

        // Items list with new formatting
        receiptData.items?.forEach { item ->
            yPosition = drawItemWithWrapping(canvas, item, yPosition, leftMargin, rightMargin, normalPaint, lineHeight)
            yPosition += 15f // Extra space between items
        }

        // Third divider line
        yPosition += dividerSpacing
        drawDividerLine(canvas, yPosition, leftMargin, rightMargin)
        yPosition += dividerSpacing

        // Total (bold, with even spacing)
        val totalLabel = "TOTAL"
        val totalAmount = "${receiptData.total?.let { "%.2f".format(it) } ?: "0.00"} GTQ"

        val totalAmountWidth = boldPaint.measureText(totalAmount)

        canvas.drawText(totalLabel, leftMargin, yPosition, boldPaint)
        canvas.drawText(totalAmount, rightMargin - totalAmountWidth, yPosition, boldPaint)
        yPosition += lineHeight + 20f

        // Fourth divider line
        yPosition += dividerSpacing
        drawDividerLine(canvas, yPosition, leftMargin, rightMargin)
        yPosition += dividerSpacing

        // Payment status (centered, bold)
        receiptData.paymentInfo?.status?.let { status ->
            canvas.drawText(status, centerX, yPosition, centerPaint)
            yPosition += lineHeight + 20f
        }

        // Bon Appétit (centered, bold)
        canvas.drawText("Bon Appétit", centerX, yPosition, centerPaint)
        yPosition += lineHeight + 60f // Much more space after Bon Appétit like header

        return bitmap
    }

    private fun drawItemWithWrapping(
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
        val addOnIndent = 40f // Indentation for "con" lines

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

        // Draw main item name with wrapping
        yPosition = drawTextWithWrapping(canvas, mainItemName, leftMargin, yPosition, maxWidth, paint, lineHeight)

        // Draw add-ons with indentation
        itemAddOns.forEach { addOn ->
            if (addOn.isNotEmpty()) {
                val addOnText = "    con $addOn"
                yPosition = drawTextWithWrapping(canvas, addOnText, leftMargin, yPosition, maxWidth, paint, lineHeight)
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

    private fun drawDividerLine(canvas: Canvas, y: Float, startX: Float, endX: Float) {
        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
        }
        canvas.drawLine(startX, y, endX, y, linePaint)
    }

    private fun calculateReceiptHeight(receiptData: ReceiptData): Int {
        // Base height for header, footer, and spacing (increased for better spacing)
        var height = 400 // Increased for more header spacing and bottom margin

        // Add height for each item (2 lines per item with more spacing)
        receiptData.items?.let { items ->
            height += items.size * 100 // 45 * 2 + 10 spacing per item
        }

        // Add extra space for dividers and margins (increased)
        height += 250

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