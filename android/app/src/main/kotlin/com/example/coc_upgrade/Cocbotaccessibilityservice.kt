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

    // How many more suggested-upgrade-and-confirm cycles to run this session —
    // one per free builder, decremented after each confirmed upgrade.
    private var remainingUpgrades = 0

    enum class BotStep {
        IDLE,
        WAIT_FOR_COC,
        CHECK_BUILDERS,
        TAP_BUILDER_BADGE,
        TAP_SUGGESTED_UPGRADE,
        TAP_UPGRADE_BUTTON,
        TAP_CONFIRM_BUTTON,
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

        log("🔨 Free builders: $free/$max — starting $free upgrade(s)")
        remainingUpgrades = free
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
    // (e.g. "Spring Trap x2" / "Air Bomb").
    //
    // The "Suggested upgrades:" section's vertical position isn't fixed — it
    // shifts down by one row for every item listed under "Upgrades in
    // progress:" above it, which grows as the loop confirms more upgrades.
    // A hardcoded Y (the old ~33.2% height) only matched the very first
    // iteration's layout; on later iterations it landed on the "Suggested
    // upgrades:" header itself (non-interactive) instead of the first item.
    // So OCR the panel to find the "Suggested upgrades" header line and tap
    // directly below it — the X position stays fixed at ~49.5% width.
    private fun tapFirstSuggestedUpgrade() {
        if (!isRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            tapSuggestedUpgradeRow(resources.displayMetrics.heightPixels * 0.332f)
            return
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val full = bitmapFromScreenshot(result)
                if (full == null) {
                    log("⚠️ Could not read screenshot buffer — stopping")
                    closeCoc()
                    return
                }

                // Generous box around where the "Suggested upgrades:" list
                // sits. The bottom needs to be tall enough that the header
                // stays inside it even after "Upgrades in progress:" above
                // it has grown by up to ~5 rows (one per builder assigned
                // so far this run) — with 0 in-progress items the header
                // sits around ~25-33% height, so +5 rows (~8% each) can push
                // it down past 60%.
                val left = (full.width * 0.35f).toInt()
                val right = (full.width * 0.65f).toInt()
                val top = (full.height * 0.08f).toInt()
                val bottom = (full.height * 0.85f).toInt()
                val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                full.recycle()

                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { visionText ->
                        val lines = visionText.textBlocks
                            .flatMap { it.lines }
                            .mapNotNull { line -> line.boundingBox?.let { box -> line.text to box } }
                            .sortedBy { it.second.top }

                        for ((text, box) in lines) {
                            Log.d("CocBot", "🔎 suggested-upgrades line: '$text' top=${box.top} bottom=${box.bottom}")
                        }

                        // CoC's bold outlined font makes ML Kit emit multiple
                        // overlapping "lines" for the header text itself (fill +
                        // outline), each at slightly different Y. Taking the
                        // FIRST "Suggested" match's box as the boundary isn't
                        // reliable if a LATER duplicate has a larger bottom — so
                        // take the max bottom across ALL "Suggested" matches as
                        // the header's true lower edge, then pick the first line
                        // below that edge that isn't itself part of the header.
                        val headerLines = lines.filter { it.first.contains("Suggested", ignoreCase = true) }
                        val headerBottom = headerLines.maxOfOrNull { it.second.bottom }
                        // The first line below the header isn't always the first
                        // item's name — ML Kit sometimes picks up a stray
                        // "10H 43M"-style countdown ghost (OCR'd as "1OH 43M")
                        // sitting just under the header with nothing paired next
                        // to it. Skip any line that, after normalizing O/I/l back
                        // to 0/1, is made up entirely of digits/whitespace and
                        // duration-unit letters (d/h/m) — building/troop names
                        // always contain other letters and won't match this.
                        val durationLike = Regex("^[0-9OoIl\\sdDhHmM]+$")
                        val itemLine = headerBottom?.let { hb ->
                            lines.firstOrNull { (text, box) ->
                                val t = text.trim()
                                box.top >= hb && !t.contains("Suggested", ignoreCase = true) && !durationLike.matches(t)
                            }
                        }
                        if (itemLine == null) {
                            log("⚠️ Could not find a 'Suggested upgrades' item — stopping with $remainingUpgrades builder(s) unassigned")
                            closeCoc()
                        } else {
                            tapSuggestedUpgradeRow(top + itemLine.second.exactCenterY())
                        }
                    }
                    .addOnFailureListener { e ->
                        log("⚠️ OCR failed: ${e.message} — stopping")
                        closeCoc()
                    }
                    .addOnCompleteListener {
                        crop.recycle()
                        recognizer.close()
                    }
            }
            override fun onFailure(errorCode: Int) {
                log("⚠️ Screenshot failed (code $errorCode) — stopping")
                closeCoc()
            }
        })
    }

    // Taps the located "Suggested upgrades" row at the given Y
    // (screenshot-pixel coordinates), at a fixed X (~49.5% width).
    private fun tapSuggestedUpgradeRow(y: Float) {
        if (!isRunning) return
        val x = resources.displayMetrics.widthPixels * 0.495f

        log("👆 Tapping first suggested upgrade at (${x.toInt()}, ${y.toInt()})")
        captureDebugScreenshot("suggested_upgrade", x, y)
        performTap(x, y) {
            // Selecting the item reveals "Info"/"Upgrade" buttons at the
            // bottom of the panel — give the UI a moment to animate in, then
            // verify the "Upgrade" button actually appeared before tapping it.
            handler.postDelayed({ verifyUpgradeButtonAndProceed() }, 1500)
        }
    }

    // Locates the "Upgrade" button among the row of action buttons revealed
    // at the bottom of the panel after selecting a suggested upgrade, and
    // taps it.
    //
    // Most buildings show 3 action buttons (Info / Upgrade / Move), but some
    // (Town Hall, Laboratory, Clan Castle, Army Camp, etc.) show 4-6
    // (Rush Upgrade, Boost, Donate, Copy, ...), which shifts where "Upgrade"
    // sits in the row — a fixed X coordinate calibrated for the 3-button
    // case lands on the wrong button (or nothing) for those. Also, if
    // "Suggested upgrades" has fewer items than free builders, the fixed
    // tapFirstSuggestedUpgrade() coordinates can miss entirely — landing on
    // the "Other upgrades:" header, empty panel space, or even a building
    // behind the panel (which can swap the whole screen to that building's
    // info panel, which has its own button row). So OCR the whole
    // action-button row, find "Upgrade" by text, and tap exactly where it
    // is; if it's not there at all, stop the loop here instead of tapping
    // blind coordinates on the live board.
    private fun verifyUpgradeButtonAndProceed() {
        if (!isRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val display = resources.displayMetrics
            botStep = BotStep.TAP_UPGRADE_BUTTON
            tapUpgradeButton(display.widthPixels * 0.578f, display.heightPixels * 0.819f)
            return
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val full = bitmapFromScreenshot(result)
                if (full == null) {
                    log("⚠️ Could not read screenshot buffer — stopping")
                    closeCoc()
                    return
                }

                // The action-button row spans (close to) the full width —
                // buildings with more buttons squeeze them into the same
                // row rather than adding rows, so don't assume "Upgrade" is
                // in any particular column. Vertically it sits around
                // ~74%-90% height (the old 3-button calibration of ~81.9%
                // falls in the middle of this band).
                val top = (full.height * 0.74f).toInt()
                val bottom = (full.height * 0.90f).toInt()
                val crop = Bitmap.createBitmap(full, 0, top, full.width, bottom - top)
                full.recycle()

                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { visionText ->
                        val lines = visionText.textBlocks
                            .flatMap { it.lines }
                            .mapNotNull { line -> line.boundingBox?.let { box -> line.text to box } }

                        // Prefer an exact "Upgrade" label over a partial
                        // match like "Rush Upgrade" (a different, gem-cost
                        // button) or cost text mentioning "Upgrade".
                        val upgradeLine = lines.firstOrNull { it.first.trim().equals("Upgrade", ignoreCase = true) }
                            ?: lines.firstOrNull { it.first.contains("Upgrade", ignoreCase = true) }

                        if (upgradeLine == null) {
                            log("⚠️ No 'Upgrade' button found (ran out of suggested upgrades?) — stopping with $remainingUpgrades builder(s) unassigned")
                            saveDebugBitmap("verify_upgrade_region", crop)
                            closeCoc()
                        } else {
                            val box = upgradeLine.second
                            botStep = BotStep.TAP_UPGRADE_BUTTON
                            tapUpgradeButton(box.exactCenterX(), top + box.exactCenterY())
                        }
                    }
                    .addOnFailureListener { e ->
                        log("⚠️ OCR failed: ${e.message} — stopping")
                        closeCoc()
                    }
                    .addOnCompleteListener {
                        crop.recycle()
                        recognizer.close()
                    }
            }
            override fun onFailure(errorCode: Int) {
                log("⚠️ Screenshot failed (code $errorCode) — stopping")
                closeCoc()
            }
        })
    }

    // Taps the "Upgrade" button at the given screenshot-pixel coordinates,
    // located dynamically by verifyUpgradeButtonAndProceed() since its
    // position shifts with how many action buttons a building shows.
    private fun tapUpgradeButton(x: Float, y: Float) {
        if (!isRunning) return

        log("👆 Tapping Upgrade button at (${x.toInt()}, ${y.toInt()})")
        captureDebugScreenshot("upgrade_button", x, y)
        performTap(x, y) {
            // Tapping "Upgrade" opens a "Upgrade X to Level Y?" confirmation
            // dialog with a "Confirm" button — give it a moment to animate
            // in, then tap "Confirm".
            handler.postDelayed({
                botStep = BotStep.TAP_CONFIRM_BUTTON
                tapConfirmButton()
            }, 1500)
        }
    }

    // Tap the "Confirm" button on the "Upgrade X to Level Y?" dialog,
    // calibrated at ~75.7% width / ~85.7% height.
    private fun tapConfirmButton() {
        if (!isRunning) return
        val display = resources.displayMetrics
        val x = display.widthPixels * 0.757f
        val y = display.heightPixels * 0.857f

        log("👆 Tapping Confirm button at (${x.toInt()}, ${y.toInt()})")
        captureDebugScreenshot("confirm_button", x, y)
        performTap(x, y) {
            upgradesStarted++
            remainingUpgrades--
            log("✅ Started upgrade #$upgradesStarted ($remainingUpgrades builder(s) left to assign)")
            handler.postDelayed({ captureDebugScreenshot("confirm_result") }, 1500)

            if (remainingUpgrades > 0) {
                // Confirming closed the Builders panel and opened the
                // upgraded building's info panel — re-tap the builder badge
                // to bring the Builders panel back for the next builder.
                handler.postDelayed({
                    botStep = BotStep.TAP_BUILDER_BADGE
                    tapBuilderBadge()
                }, 2000)
            } else {
                handler.postDelayed({ closeCoc() }, 2000)
            }
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
