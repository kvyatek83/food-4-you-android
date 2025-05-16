package com.example.chabadfood

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var printer: StarPrinter
    private val printerScope = CoroutineScope(Dispatchers.IO)
    private val RECEIPT_WIDTH = 576 // Standard width for 3-inch receipt printers (in dots)
    private val FONT_SCALE = 1.6f
    private lateinit var webView: WebView
    private val TAG = "ReceiptPrinter"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupWebView()
        initializePrinter()
    }

    private fun setupWebView() {
        webView = findViewById<WebView>(R.id.webview)
        webView.webViewClient = WebViewClient()

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            domStorageEnabled = true
        }

        // Enable remote debugging
        WebView.setWebContentsDebuggingEnabled(true)

        // Add JavaScript interface for communication with Angular
        webView.addJavascriptInterface(WebAppInterface(), "AndroidPrinter")

        // Load the Angular app
        webView.loadUrl("http://192.168.68.58:12345")
    }

    private fun initializePrinter() {
        val settings = StarConnectionSettings(InterfaceType.Lan, "192.168.68.66", false)
        printer = StarPrinter(settings, applicationContext)
    }

    /**
     * Interface between JavaScript (Angular) and Android
     */
    inner class WebAppInterface {
        @JavascriptInterface
        fun printReceipt(receiptDataJson: String): String {
            Log.d(TAG, "Received print request from web app: $receiptDataJson")
            try {
                val receiptData = parseReceiptData(receiptDataJson)
                printerScope.launch {
                    val success = printReceiptFromData(receiptData)

                    // Notify the web app of the print result
                    withContext(Dispatchers.Main) {
                        val resultJson = JSONObject().apply {
                            put("success", success)
                            put("timestamp", System.currentTimeMillis())
                        }.toString()

                        webView.evaluateJavascript(
                            "window.angularPrintCallback($resultJson)",
                            null
                        )
                    }
                }
                return "{\"status\":\"processing\",\"message\":\"Print job submitted\"}"
            } catch (e: Exception) {
                Log.e(TAG, "Error processing print request", e)
                return "{\"status\":\"error\",\"message\":\"${e.message}\"}"
            }
        }

        @JavascriptInterface
        fun isPrinterAvailable(): String {
            return try {
                val isAvailable = runBlocking {
                    try {
                        printer.openAsync().await()
                        printer.closeAsync().await()
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Printer not available", e)
                        false
                    }
                }
                "{\"available\":$isAvailable}"
            } catch (e: Exception) {
                "{\"available\":false,\"error\":\"${e.message}\"}"
            }
        }
    }

    private fun parseReceiptData(jsonData: String): ReceiptData {
        return try {
            Gson().fromJson(jsonData, ReceiptData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing receipt data", e)
            // Return default receipt data if parsing fails
            createDefaultReceiptData()
        }
    }

    private suspend fun printReceiptFromData(receiptData: ReceiptData): Boolean {
        return try {
            // Create receipt bitmap from data
            val receiptBitmap = createReceiptBitmap(receiptData)

            // Connect to printer
            try {
                printer.openAsync().await()
                Log.i(TAG, "Connected to printer successfully")
            } catch (e: StarIO10CommunicationException) {
                Log.e(TAG, "Communication error: ${e.message}")
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Unknown error: ${e.message}")
                return false
            }

            // Print the receipt
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

                val result = printer.printAsync(commands).await()
                Log.i(TAG, "Print result: $result")
                true
            } catch (e: StarIO10UnprintableException) {
                when (e.errorCode) {
                    StarIO10ErrorCode.DeviceHasError -> {
                        Log.e(TAG, "Device error: ${e.message}")
                    }
                    StarIO10ErrorCode.PrinterHoldingPaper -> {
                        Log.e(TAG, "Printer holding paper: ${e.message}")
                    }
                    else -> {
                        Log.e(TAG, "Other printing error: ${e.message}")
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Print exception: ${e.message}")
                false
            } finally {
                printer.closeAsync().await()
                Log.i(TAG, "Connection closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in printReceiptFromData", e)
            false
        }
    }

    private fun createReceiptBitmap(receiptData: ReceiptData): Bitmap {
        // Create a list of receipt lines from our data model
        val receiptLines = mutableListOf<ReceiptLine>()

        // Add header
        if (receiptData.headerInfo != null) {
            with(receiptData.headerInfo) {
                storeName?.let {
                    receiptLines.add(ReceiptLine(it, 30f * FONT_SCALE, true, ReceiptLine.Alignment.CENTER))
                }
                storeAddress?.let {
                    receiptLines.add(ReceiptLine(it, 24f * FONT_SCALE, false, ReceiptLine.Alignment.CENTER))
                }
                storeCity?.let {
                    receiptLines.add(ReceiptLine(it, 24f * FONT_SCALE, false, ReceiptLine.Alignment.CENTER))
                }
                receiptLines.add(ReceiptLine("", 16f * FONT_SCALE)) // Blank line
            }
        }

        // Add date and reference info
        val dateStr = receiptData.date ?: SimpleDateFormat("MM/dd/yyyy    hh:mm aa", Locale.getDefault()).format(Date())
        receiptLines.add(ReceiptLine("Date: $dateStr", 22f * FONT_SCALE))

        if (receiptData.referenceNumber != null) {
            receiptLines.add(ReceiptLine("Ref: ${receiptData.referenceNumber}", 22f * FONT_SCALE))
        }

        receiptLines.add(ReceiptLine("--------------------------------", 22f * FONT_SCALE))
        receiptLines.add(ReceiptLine("", 16f * FONT_SCALE))

        // Add transaction type
        receiptData.transactionType?.let {
            receiptLines.add(ReceiptLine(it.uppercase(), 28f * FONT_SCALE, true))
            receiptLines.add(ReceiptLine("", 8f * FONT_SCALE))
        }

        // Add items if present
        if (receiptData.items != null && receiptData.items.isNotEmpty()) {
            // Add header for items
            receiptLines.add(ReceiptLine("Item         Description    Price", 22f * FONT_SCALE))

            // Add each item
            for (item in receiptData.items) {
                val itemText = formatItemLine(
                    item.sku ?: "",
                    item.description ?: "",
                    item.price?.toString() ?: ""
                )
                receiptLines.add(ReceiptLine(itemText, 22f * FONT_SCALE))
            }

            receiptLines.add(ReceiptLine("", 16f * FONT_SCALE))
        }

        // Add totals
        receiptData.subtotal?.let {
            receiptLines.add(ReceiptLine("Subtotal                  $it", 22f * FONT_SCALE))
        }

        receiptData.tax?.let {
            receiptLines.add(ReceiptLine("Tax                        $it", 22f * FONT_SCALE))
        }

        receiptLines.add(ReceiptLine("--------------------------------", 22f * FONT_SCALE))

        // Total amount with bold
        receiptData.total?.let {
            receiptLines.add(ReceiptLine("Total                    $it", 26f * FONT_SCALE, true))
        }

        receiptLines.add(ReceiptLine("--------------------------------", 22f * FONT_SCALE))
        receiptLines.add(ReceiptLine("", 16f * FONT_SCALE))

        // Payment information
        receiptData.paymentInfo?.let { payment ->
            payment.method?.let {
                receiptLines.add(ReceiptLine(it, 22f * FONT_SCALE))
            }

            payment.amount?.let {
                receiptLines.add(ReceiptLine(it, 22f * FONT_SCALE))
            }

            payment.cardNumber?.let {
                receiptLines.add(ReceiptLine(it, 22f * FONT_SCALE))
            }

            receiptLines.add(ReceiptLine("", 16f * FONT_SCALE))
        }

        // Footer with customizable message
        receiptData.footerInfo?.let { footer ->
            footer.refundPolicy?.let {
                receiptLines.add(ReceiptLine(it, 24f * FONT_SCALE, true, ReceiptLine.Alignment.CENTER, true))
            }

            footer.returnPolicy?.let {
                receiptLines.add(ReceiptLine(it, 22f * FONT_SCALE, false, ReceiptLine.Alignment.CENTER))
            }

            footer.additionalInfo?.let {
                receiptLines.add(ReceiptLine(it, 22f * FONT_SCALE, false, ReceiptLine.Alignment.CENTER))
            }

            receiptLines.add(ReceiptLine("", 16f * FONT_SCALE))

            footer.thankYouMessage?.let {
                receiptLines.add(ReceiptLine(it, 22f * FONT_SCALE, false, ReceiptLine.Alignment.CENTER))
            }
        }

        receiptLines.add(ReceiptLine("", 20f * FONT_SCALE)) // Extra space at bottom

        // Calculate receipt height based on text lines with additional padding for larger text
        var totalHeight = 0
        val textPaint = Paint()
        receiptLines.forEach { line ->
            textPaint.textSize = line.textSize
            val fontMetrics = textPaint.fontMetrics
            val lineHeight = fontMetrics.bottom - fontMetrics.top + fontMetrics.leading
            totalHeight += (lineHeight * 1.2).toInt()
        }

        // Create bitmap with calculated height
        val receiptBitmap = Bitmap.createBitmap(RECEIPT_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(receiptBitmap)

        // Fill with white background
        canvas.drawColor(Color.WHITE)

        // Draw each line
        var yPosition = 0f
        receiptLines.forEach { line ->
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = line.textSize
                isAntiAlias = true
                if (line.bold) {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                } else {
                    typeface = Typeface.DEFAULT
                }

                // For inverted text
                if (line.inverted) {
                    val bounds = Rect()
                    getTextBounds(line.text, 0, line.text.length, bounds)
                    val textWidth = measureText(line.text)
                    val textHeight = bounds.height()

                    val padding = textHeight * 0.3f

                    val rectLeft = when (line.alignment) {
                        ReceiptLine.Alignment.LEFT -> 0f
                        ReceiptLine.Alignment.CENTER -> (RECEIPT_WIDTH - textWidth) / 2 - padding
                        ReceiptLine.Alignment.RIGHT -> RECEIPT_WIDTH - textWidth - padding
                    }

                    // Draw black background for inverted text
                    val backgroundPaint = Paint().apply {
                        color = Color.BLACK
                    }
                    canvas.drawRect(rectLeft,
                        yPosition - textHeight + 5 - padding,
                        rectLeft + textWidth + padding * 2,
                        yPosition + 5 + padding,
                        backgroundPaint)
                    color = Color.WHITE  // Text will be white
                }
            }

            val fontMetrics = paint.fontMetrics
            yPosition += -fontMetrics.top // Move to baseline position

            // Calculate x position based on alignment
            val xPosition = when (line.alignment) {
                ReceiptLine.Alignment.LEFT -> 0f
                ReceiptLine.Alignment.CENTER -> RECEIPT_WIDTH / 2f - paint.measureText(line.text) / 2
                ReceiptLine.Alignment.RIGHT -> RECEIPT_WIDTH - paint.measureText(line.text)
            }

            canvas.drawText(line.text, xPosition, yPosition, paint)

            // Move to next line with additional spacing for larger text
            yPosition += (fontMetrics.bottom + fontMetrics.leading) * 1.2f
        }

        return receiptBitmap
    }

    private fun formatItemLine(sku: String, description: String, price: String): String {
        // Simple formatting to align the three fields
        val skuField = sku.padEnd(12).substring(0, 11)
        val descField = description.padEnd(15).substring(0, 14)
        return "$skuField $descField $price"
    }

    private fun createDefaultReceiptData(): ReceiptData {
        return ReceiptData(
            headerInfo = HeaderInfo(
                storeName = "Star Clothing Boutique",
                storeAddress = "123 Star Road",
                storeCity = "City, State 12345"
            ),
            date = SimpleDateFormat("MM/dd/yyyy    hh:mm aa", Locale.getDefault()).format(Date()),
            transactionType = "SALE",
            items = listOf(
                ItemInfo(
                    sku = "300678566",
                    description = "PLAIN T-SHIRT",
                    price = 10.99
                ),
                ItemInfo(
                    sku = "300692003",
                    description = "BLACK DENIM",
                    price = 29.99
                ),
                ItemInfo(
                    sku = "300651148",
                    description = "BLUE DENIM",
                    price = 29.99
                )
            ),
            subtotal = 70.97,
            tax = 0.0,
            total = 70.97,
            paymentInfo = PaymentInfo(
                method = "Charge",
                amount = "70.97",
                cardNumber = "Visa XXXX-XXXX-XXXX-0123"
            ),
            footerInfo = FooterInfo(
                refundPolicy = "Refunds and Exchanges",
                returnPolicy = "Within 30 days with receipt",
                additionalInfo = "And tags attached",
                thankYouMessage = "Thank you for shopping with us!"
            )
        )
    }

    // Class to represent a line of text in the receipt
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

// Data classes for receipt data
data class ReceiptData(
    val headerInfo: HeaderInfo? = null,
    val date: String? = null,
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

data class ItemInfo(
    val sku: String? = null,
    val description: String? = null,
    val quantity: Int? = null,
    val unitPrice: Double? = null,
    val price: Double? = null
)

data class PaymentInfo(
    val method: String? = null,
    val amount: String? = null,
    val cardNumber: String? = null,
    val authCode: String? = null
)

data class FooterInfo(
    val refundPolicy: String? = null,
    val returnPolicy: String? = null,
    val additionalInfo: String? = null,
    val thankYouMessage: String? = null
)

// Simple implementation of runBlocking since it's not included in Android standard library
private fun <T> runBlocking(block: suspend () -> T): T {
    var result: T? = null
    var exception: Throwable? = null
    val latch = java.util.concurrent.CountDownLatch(1)

    CoroutineScope(Dispatchers.IO).launch {
        try {
            result = block()
        } catch (e: Throwable) {
            exception = e
        } finally {
            latch.countDown()
        }
    }

    latch.await()
    exception?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
