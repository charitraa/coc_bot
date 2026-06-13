package com.example.coc_upgrade

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

class CocBotAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "CocBot"
        const val ACTION_LOG = "com.cocbot.LOG"
        const val EXTRA_MESSAGE = "message"
        const val COC_PACKAGE = "com.supercell.clashofclans"

        // Intent actions from Flutter
        const val ACTION_START_BOT = "com.cocbot.START"
        const val ACTION_STOP_BOT  = "com.cocbot.STOP"

        var isRunning = false
        var freeBuilders = 0
        var upgradesStarted = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var botStep = BotStep.IDLE

    enum class BotStep {
        IDLE,
        WAIT_FOR_COC,
        CHECK_BUILDERS,
        TAP_BUILDER_BADGE,
        TAP_SUGGESTED_UPGRADE,
        TAP_UPGRADE_BUTTON,
        DONE
    }

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        log("✅ CoC Bot Accessibility Service connected")
    }

    // Receives ACTION_START_BOT / ACTION_STOP_BOT from MainActivity (Run Now / Stop button)
    // and from BotAlarmReceiver (scheduled daily run).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BOT -> startBot()
            ACTION_STOP_BOT -> stopBot()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        val pkg = event?.packageName?.toString() ?: return

        if (botStep == BotStep.WAIT_FOR_COC && pkg == COC_PACKAGE) {
            log("🏰 CoC is open!")
            botStep = BotStep.CHECK_BUILDERS
            handler.postDelayed({ checkFreeBuilders() }, 10000) // wait for game to fully load
        }
    }

    override fun onInterrupt() {
        log("⚠️ Service interrupted")
        isRunning = false
        botStep = BotStep.IDLE
    }

    // ─── Bot Flow ─────────────────────────────────────────────────────────────

    fun startBot() {
        if (isRunning) {
            log("⚠️ Bot already running")
            return
        }
        isRunning = true
        upgradesStarted = 0
        log("🚀 Bot started — opening CoC...")
        openCoc()
    }

    fun stopBot() {
        isRunning = false
        botStep = BotStep.IDLE
        log("⛔ Bot stopped")
    }

    private fun openCoc() {
        val intent = packageManager.getLaunchIntentForPackage(COC_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            botStep = BotStep.WAIT_FOR_COC
            log("📱 Launching Clash of Clans...")
        } else {
            log("❌ CoC not installed!")
            isRunning = false
        }
    }

    // ─── Builder Count (OCR) ──────────────────────────────────────────────────
    //
    // CoC renders its UI via GPU/SurfaceView, so the accessibility tree exposes
    // zero text (confirmed via dumpNodeTree: only FrameLayout/View, both
    // text='null'). The only reliable signal for "is a builder free" is the
    // always-visible "X/5" badge in the top HUD, so we screenshot it and run
    // on-device OCR (ML Kit) before tapping anything.

    private fun checkFreeBuilders() {
        if (!isRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("⚠️ Builder check needs Android 11+ — tapping without checking")
            botStep = BotStep.TAP_BUILDER_BADGE
            tapBuilderBadge()
            return
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val full = bitmapFromScreenshot(result)
                if (full == null) {
                    log("⚠️ Could not read screenshot buffer")
                    closeCoc()
                    return
                }

                // Save the full screen so we can see what CoC was showing if
                // OCR comes back empty (e.g. a popup covering the HUD).
                saveDebugBitmap("builder_check", full)

                // The "X/5" builder badge sits just right of the builder-avatar
                // icon in the top-center HUD. Crop a generous box around it.
                val left = (full.width * 0.47f).toInt()
                val right = (full.width * 0.57f).toInt()
                val bottom = (full.height * 0.09f).toInt()
                val crop = Bitmap.createBitmap(full, left, 0, right - left, bottom)
                full.recycle()

                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { visionText -> onBuilderCountRead(visionText.text) }
                    .addOnFailureListener { e ->
                        log("⚠️ OCR failed: ${e.message}")
                        closeCoc()
                    }
                    .addOnCompleteListener {
                        crop.recycle()
                        recognizer.close()
                    }
            }
            override fun onFailure(errorCode: Int) {
                log("⚠️ Screenshot failed (code $errorCode)")
                closeCoc()
            }
        })
    }

    private fun onBuilderCountRead(ocrText: String) {
        log("🔎 Builder badge OCR: '${ocrText.replace("\n", " ")}'")

        val match = Regex("""(\d+)\s*/\s*(\d+)""").find(ocrText)
        if (match == null) {
            log("⚠️ Could not read builder count from badge — skipping this run")
            closeCoc()
            return
        }

        val free = match.groupValues[1].toIntOrNull() ?: 0
        val max  = match.groupValues[2].toIntOrNull() ?: 0
        freeBuilders = free

        if (free == 0) {
            log("😴 No free builders ($free/$max) — skipping upgrades")
            closeCoc()
            return
        }

        log("🔨 Free builders: $free/$max — opening builder panel")
        botStep = BotStep.TAP_BUILDER_BADGE
        tapBuilderBadge()
    }

    // Tap the builder avatar/badge in the top HUD to open the Builders panel.
    private fun tapBuilderBadge() {
        if (!isRunning) return
        val display = resources.displayMetrics
        val x = display.widthPixels * 0.512f
        val y = display.heightPixels * 0.016f

        log("👆 Tapping builder badge at (${x.toInt()}, ${y.toInt()})")
        captureDebugScreenshot("builder_badge", x, y)
        performTap(x, y) {
            handler.postDelayed({ captureDebugScreenshot("builder_panel") }, 1500)
            handler.postDelayed({
                botStep = BotStep.TAP_SUGGESTED_UPGRADE
                tapFirstSuggestedUpgrade()
            }, 2000)
        }
    }

    // Tap the first item under "Suggested upgrades" in the Builders panel
    // (e.g. "Spring Trap x2"), calibrated at ~49.5% width / ~33.2% height.
    private fun tapFirstSuggestedUpgrade() {
        if (!isRunning) return
        val display = resources.displayMetrics
        val x = display.widthPixels * 0.495f
        val y = display.heightPixels * 0.332f

        log("👆 Tapping first suggested upgrade at (${x.toInt()}, ${y.toInt()})")
        captureDebugScreenshot("suggested_upgrade", x, y)
        performTap(x, y) {
            // Selecting the item reveals "Info"/"Upgrade" buttons at the
            // bottom of the panel — give the UI a moment to animate in, then
            // tap "Upgrade".
            handler.postDelayed({
                botStep = BotStep.TAP_UPGRADE_BUTTON
                tapUpgradeButton()
            }, 1500)
        }
    }

    // Tap the "Upgrade" button revealed at the bottom of the Builders panel
    // after selecting a suggested upgrade, calibrated at ~65.4% width /
    // ~84.7% height.
    private fun tapUpgradeButton() {
        if (!isRunning) return
        val display = resources.displayMetrics
        val x = display.widthPixels * 0.6537f
        val y = display.heightPixels * 0.8472f

        log("👆 Tapping Upgrade button at (${x.toInt()}, ${y.toInt()})")
        captureDebugScreenshot("upgrade_button", x, y)
        performTap(x, y) {
            upgradesStarted++
            handler.postDelayed({ captureDebugScreenshot("upgrade_result") }, 1500)
            handler.postDelayed({ closeCoc() }, 3500)
        }
    }

    private fun closeCoc() {
        log("🔒 Going home (minimizing CoC)")
        performGlobalAction(GLOBAL_ACTION_HOME)
        isRunning = false
        botStep = BotStep.DONE
        log("🎉 Bot complete! Started $upgradesStarted upgrade(s).")
        broadcastLog("BOT_DONE:$upgradesStarted")
    }

    // ─── Gesture / Screenshot helpers ────────────────────────────────────────

    private fun performTap(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                onDone?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                log("⚠️ Tap cancelled at ($x, $y)")
                onDone?.invoke()
            }
        }, handler)
    }

    // Converts a hardware-buffer screenshot result into a software ARGB_8888
    // bitmap we can crop/draw on/save.
    private fun bitmapFromScreenshot(result: ScreenshotResult): Bitmap? {
        val hwBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        result.hardwareBuffer.close()
        val bitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, true)
        hwBitmap?.recycle()
        return bitmap
    }

    // Saves a bitmap we already have in memory to the app's external files dir
    // (e.g. via `adb pull /sdcard/Android/data/com.example.coc_upgrade/files/`).
    private fun saveDebugBitmap(label: String, bitmap: Bitmap) {
        try {
            val file = File(getExternalFilesDir(null), "tap_${label}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            log("📸 Saved debug screenshot: ${file.absolutePath}")
        } catch (e: Exception) {
            log("⚠️ Debug screenshot error: ${e.message}")
        }
    }

    // Debug helper: takes a screenshot, optionally draws a red crosshair at a
    // planned tap point, and saves it to the app's external files dir so it can
    // be inspected (e.g. via `adb pull /sdcard/Android/data/com.example.coc_upgrade/files/`).
    private fun captureDebugScreenshot(label: String, tapX: Float? = null, tapY: Float? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("⚠️ Debug screenshot needs Android 11+ — skipping")
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val bitmap = bitmapFromScreenshot(result)
                    if (bitmap == null) {
                        log("⚠️ Debug screenshot: failed to read buffer")
                        return
                    }

                    if (tapX != null && tapY != null) {
                        val paint = Paint().apply {
                            color = Color.RED
                            style = Paint.Style.STROKE
                            strokeWidth = 6f
                        }
                        Canvas(bitmap).apply {
                            drawCircle(tapX, tapY, 30f, paint)
                            drawLine(tapX - 40, tapY, tapX + 40, tapY, paint)
                            drawLine(tapX, tapY - 40, tapX, tapY + 40, paint)
                        }
                    }

                    saveDebugBitmap(label, bitmap)
                    bitmap.recycle()
                } catch (e: Exception) {
                    log("⚠️ Debug screenshot error: ${e.message}")
                }
            }
            override fun onFailure(errorCode: Int) {
                log("⚠️ Debug screenshot failed (code $errorCode)")
            }
        })
    }

    // ─── Logging / Broadcast ─────────────────────────────────────────────────

    private fun log(msg: String) {
        Log.d(TAG, msg)
        broadcastLog(msg)
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_MESSAGE, msg)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
