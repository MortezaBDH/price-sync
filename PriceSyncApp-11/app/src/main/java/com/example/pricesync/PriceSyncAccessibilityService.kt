package com.example.pricesync

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray

class PriceSyncAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PriceSync"
        private const val HEARTBEAT_MS = 4000L
        @Volatile var instance: PriceSyncAccessibilityService? = null
    }

    data class FieldConfig(
        val name: String,
        val sourceIndex: Int,
        val targetPlusIndex: Int,
        val targetMinusIndex: Int,
        val offset: Long,
        val clickStep: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var activeFieldSyncs = 0
    private val targetEventLog = ArrayDeque<String>()
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (Prefs.getAutoSyncEnabled(this@PriceSyncAccessibilityService) && activeFieldSyncs == 0) {
                syncAllFields()
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        Log.i(TAG, "سرویس فعال شد")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(heartbeat)
        try { textRecognizer.close() } catch (e: Exception) { }
        instance = null
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) logTargetEventIfRelevant(event)
        val sourcePkg = Prefs.getSourcePackage(this)
        if (sourcePkg.isBlank() || !Prefs.getAutoSyncEnabled(this)) return
        val evPkg = event?.packageName?.toString() ?: return
        if (evPkg == sourcePkg && activeFieldSyncs == 0) {
            syncAllFields()
        }
    }

    private fun logTargetEventIfRelevant(event: AccessibilityEvent) {
        val targetPkg = Prefs.getTargetPackage(this)
        if (targetPkg.isBlank() || event.packageName?.toString() != targetPkg) return
        val minDigits = Prefs.getMinDigits(this)
        val raw = (event.text?.joinToString(" ") ?: "") + " " + (event.contentDescription?.toString() ?: "")
        val digits = normalizeDigits(raw).filter { it.isDigit() }
        if (digits.length >= minDigits && raw.isNotBlank()) {
            val entry = "[${event.eventType}] $raw"
            synchronized(targetEventLog) {
                targetEventLog.addFirst(entry)
                while (targetEventLog.size > 25) targetEventLog.removeLast()
            }
        }
    }

    // ---------- API قابل فراخوانی از MainActivity ----------

    fun syncNowManual() {
        syncAllFields()
    }

    /** پیش‌نمایش (آسنکرون چون شامل OCR است). */
    fun getDebugSnapshot(callback: (String) -> Unit) {
        val sourcePkg = Prefs.getSourcePackage(this)
        val targetPkg = Prefs.getTargetPackage(this)
        val minDigits = Prefs.getMinDigits(this)

        val srcRoot = findRootForPackage(sourcePkg)
        val tgtRoot = findRootForPackage(targetPkg)

        val srcNums = ArrayList<Pair<AccessibilityNodeInfo, Long>>()
        collectNumberNodes(srcRoot, minDigits, srcNums)
        val tgtClicks = ArrayList<AccessibilityNodeInfo>()
        collectClickableNodes(tgtRoot, tgtClicks)

        val sb = StringBuilder()
        val blocked = isBlockedByPopup(srcRoot, tgtRoot)
        sb.append(if (blocked) "⛔ الان یه پاپ‌آپ/بنر معامله دیده می‌شود — سینک موقتاً متوقف می‌ماند.\n\n"
                   else "✅ پاپ‌آپ معامله دیده نمی‌شود.\n\n")
        sb.append("منبع (").append(sourcePkg).append(")")
        sb.append(if (srcRoot == null) " -> پیدا نشد!\n" else ":\n")
        srcNums.forEachIndexed { i, p -> sb.append("  [$i] ${p.second}\n") }

        sb.append("\nمقصد - دکمه‌های قابل کلیک:\n")
        tgtClicks.forEachIndexed { i, n ->
            val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.className?.toString()?.takeIf { it.isNotBlank() }
                ?: "(بدون متن)"
            sb.append("  [$i] $desc  (bounds=${boundsOf(n)})\n")
        }

        sb.append("\nمقادیر ردیابی‌شده (fallback، اگه OCR جواب نداد استفاده می‌شه):\n")
        try {
            val configs = parseFieldConfigs(Prefs.getFieldMapJson(this))
            for (f in configs) {
                val tv = Prefs.getTrackedValue(this, f.name)
                sb.append("  ${f.name}: ${tv?.toString() ?: "❗️کالیبره نشده"}\n")
            }
        } catch (e: Exception) {
            sb.append("  (خطا در تنظیمات فیلدها: ${e.message})\n")
        }

        if (tgtRoot == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sb.append("\n(OCR) در دسترس نیست (یا اپ مقصد پیدا نشد، یا اندروید قدیمی‌تر از ۱۱ است).\n")
            callback(sb.toString())
            return
        }

        sb.append("\n(OCR) در حال گرفتن اسکرین‌شات و خواندن اعداد از روی تصویر مقصد...\n")
        captureAndRecognizeTarget(tgtRoot) { ocrNums ->
            sb.append("\n(OCR) اعدادی که از روی تصویر مقصد خوانده شد (از بالا به پایین):\n")
            if (ocrNums.isEmpty()) {
                sb.append("  (هیچ عددی پیدا نشد)\n")
            } else {
                ocrNums.sortedBy { it.first.top }.forEach { (r, v) ->
                    sb.append("  $v  (bounds=${r.left},${r.top},${r.right},${r.bottom})\n")
                }
            }
            callback(sb.toString())
        }
    }

    // ---------- منطق اصلی همگام‌سازی (بر پایه OCR، هر دور یک اسکرین‌شات) ----------

    private fun syncAllFields() {
        if (activeFieldSyncs != 0) return
        activeFieldSyncs = 1
        runSyncTick(Prefs.getMaxClicks(this))
    }

    private fun runSyncTick(roundsLeft: Int) {
        if (roundsLeft <= 0) { activeFieldSyncs = 0; return }

        val configs = try {
            parseFieldConfigs(Prefs.getFieldMapJson(this))
        } catch (e: Exception) {
            Log.e(TAG, "فرمت JSON تنظیمات فیلدها اشتباه است: ${e.message}")
            activeFieldSyncs = 0
            return
        }
        if (configs.isEmpty()) { activeFieldSyncs = 0; return }

        val sourcePkg = Prefs.getSourcePackage(this)
        val targetPkg = Prefs.getTargetPackage(this)
        val minDigits = Prefs.getMinDigits(this)

        val srcRoot = findRootForPackage(sourcePkg)
        val tgtRoot = findRootForPackage(targetPkg)
        if (srcRoot == null || tgtRoot == null || isBlockedByPopup(srcRoot, tgtRoot)) {
            activeFieldSyncs = 0
            return
        }

        val srcNums = ArrayList<Pair<AccessibilityNodeInfo, Long>>()
        collectNumberNodes(srcRoot, minDigits, srcNums)
        val tgtClicks = ArrayList<AccessibilityNodeInfo>()
        collectClickableNodes(tgtRoot, tgtClicks)

        val useOcr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (!useOcr) {
            runTickLogic(configs, srcNums, tgtClicks, emptyList(), roundsLeft)
            return
        }
        captureAndRecognizeTarget(tgtRoot) { ocrNums ->
            runTickLogic(configs, srcNums, tgtClicks, ocrNums, roundsLeft)
        }
    }

    private fun runTickLogic(
        configs: List<FieldConfig>,
        srcNums: List<Pair<AccessibilityNodeInfo, Long>>,
        tgtClicks: List<AccessibilityNodeInfo>,
        ocrNums: List<Pair<Rect, Long>>,
        roundsLeft: Int
    ) {
        var clickedAny = false
        for (field in configs) {
            if (field.sourceIndex >= srcNums.size) continue
            if (field.targetPlusIndex !in tgtClicks.indices || field.targetMinusIndex !in tgtClicks.indices) continue

            val desired = srcNums[field.sourceIndex].second + field.offset

            val plusRect = Rect(); tgtClicks[field.targetPlusIndex].getBoundsInScreen(plusRect)
            val minusRect = Rect(); tgtClicks[field.targetMinusIndex].getBoundsInScreen(minusRect)
            val btnTopY = minOf(plusRect.top, minusRect.top)
            val btnCenterX = (plusRect.centerX() + minusRect.centerX()) / 2

val trackedVal = Prefs.getTrackedValue(this, field.name)
            val ocrVal = findNearestPriceAbove(ocrNums, btnTopY, btnCenterX)
            val current: Long = when {
                ocrVal != null && trackedVal == null -> ocrVal
                ocrVal != null && trackedVal != null && ocrVal in (trackedVal * 9 / 10)..(trackedVal * 11 / 10) -> ocrVal
                trackedVal != null -> trackedVal
                else -> continue
            }

            val step = if (field.clickStep > 0) field.clickStep else 1000L
            val diff = desired - current
            if (kotlin.math.abs(diff) * 2 < step) continue

            val btnIndex = if (diff > 0) field.targetPlusIndex else field.targetMinusIndex
            tgtClicks[btnIndex].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Prefs.setTrackedValue(this, field.name, current + (if (diff > 0) step else -step))
            clickedAny = true
            Log.i(TAG, "${field.name}: current=$current desired=$desired -> click[$btnIndex]")
        }

        if (clickedAny) {
            handler.postDelayed({ runSyncTick(roundsLeft - 1) }, Prefs.getClickDelayMs(this))
        } else {
            activeFieldSyncs = 0
        }
    }

    /** نزدیک‌ترین عدد OCR‌شده که «بالای» ردیف دکمه و هم‌ستونِ آن است. */
    private fun findNearestPriceAbove(ocr: List<Pair<Rect, Long>>, buttonTopY: Int, buttonCenterX: Int): Long? {
        var best: Long? = null
        var bestDist = Int.MAX_VALUE
        for ((rect, value) in ocr) {
            if (rect.bottom > buttonTopY + 20) continue
            val cx = (rect.left + rect.right) / 2
            if (kotlin.math.abs(cx - buttonCenterX) > 220) continue
            val dist = buttonTopY - rect.bottom
            if (dist in 0..300 && dist < bestDist) {
                bestDist = dist
                best = value
            }
        }
        return best
    }

    /** اسکرین‌شات از کل صفحه می‌گیرد، به محدوده‌ی پنجره‌ی مقصد کراپ می‌کند، و با OCR رقم‌ها را می‌خواند. */
    private fun captureAndRecognizeTarget(tgtRoot: AccessibilityNodeInfo, onResult: (List<Pair<Rect, Long>>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(emptyList())
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this), object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        hb.close()
                        if (hwBitmap == null) { onResult(emptyList()); return }
                        val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()

                        val winRect = Rect()
                        tgtRoot.getBoundsInScreen(winRect)
                        val safe = Rect(
                            winRect.left.coerceIn(0, softBitmap.width),
                            winRect.top.coerceIn(0, softBitmap.height),
                            winRect.right.coerceIn(0, softBitmap.width),
                            winRect.bottom.coerceIn(0, softBitmap.height)
                        )
                        if (safe.width() <= 0 || safe.height() <= 0) { onResult(emptyList()); return }

                        val cropped = Bitmap.createBitmap(softBitmap, safe.left, safe.top, safe.width(), safe.height())
                        val input = InputImage.fromBitmap(cropped, 0)
                        val minDigits = Prefs.getMinDigits(this@PriceSyncAccessibilityService)
                        textRecognizer.process(input)
                            .addOnSuccessListener { text ->
                                val out = ArrayList<Pair<Rect, Long>>()
                                for (block in text.textBlocks) {
                                    for (line in block.lines) {
                                        val digits = normalizeDigits(line.text).filter { it.isDigit() }
                                        if (digits.length in minDigits..10) {
                                            val v = digits.toLongOrNull() ?: continue
                                            val r = line.boundingBox ?: continue
                                            out.add(Rect(r.left + safe.left, r.top + safe.top, r.right + safe.left, r.bottom + safe.top) to v)
                                        }
                                    }
                                }
                                onResult(out)
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "OCR failed: ${e.message}")
                                onResult(emptyList())
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "screenshot processing error: ${e.message}")
                        onResult(emptyList())
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed: $errorCode")
                    onResult(emptyList())
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot call error: ${e.message}")
            onResult(emptyList())
        }
    }

    // ---------- توابع کمکی ----------

    private fun findRootForPackage(pkg: String): AccessibilityNodeInfo? {
        if (pkg.isBlank()) return null
        try {
            var best: AccessibilityNodeInfo? = null
            var bestArea = -1L
            var bestIsApp = false
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName != pkg) continue
                val rect = Rect()
                w.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height().toLong()
                val isApp = w.type == AccessibilityWindowInfo.TYPE_APPLICATION
                val better = when {
                    best == null -> true
                    isApp && !bestIsApp -> true
                    isApp == bestIsApp && area > bestArea -> true
                    else -> false
                }
                if (better) {
                    best = r
                    bestArea = area
                    bestIsApp = isApp
                }
            }
            return best
        } catch (e: Exception) {
            Log.e(TAG, "findRootForPackage error: ${e.message}")
        }
        return null
    }

    private fun isBlockedByPopup(srcRoot: AccessibilityNodeInfo?, tgtRoot: AccessibilityNodeInfo?): Boolean {
        val keywords = Prefs.getPauseKeywords(this)
        if (keywords.isEmpty()) return false
        return containsAnyText(srcRoot, keywords) || containsAnyText(tgtRoot, keywords)
    }

    private fun containsAnyText(root: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (root == null) return false
        val t = root.text?.toString() ?: root.contentDescription?.toString()
        if (!t.isNullOrBlank()) {
            for (k in keywords) {
                if (k.isNotEmpty() && t.contains(k)) return true
            }
        }
        for (i in 0 until root.childCount) {
            if (containsAnyText(root.getChild(i), keywords)) return true
        }
        return false
    }

    private fun collectNumberNodesSimple(
        root: AccessibilityNodeInfo?,
        minDigits: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Long>>
    ) {
        if (root == null) return
        val raw = root.text?.toString() ?: root.contentDescription?.toString()
        if (!raw.isNullOrBlank()) {
            val digits = normalizeDigits(raw).filter { it.isDigit() }
            if (digits.length >= minDigits) {
                digits.toLongOrNull()?.let { out.add(root to it) }
            }
        }
        for (i in 0 until root.childCount) {
            collectNumberNodesSimple(root.getChild(i), minDigits, out)
        }
    }

    private fun collectNumberNodesAggregate(
        root: AccessibilityNodeInfo?,
        minDigits: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Long>>
    ) {
        if (root == null) return
        val ownText = root.text?.toString() ?: root.contentDescription?.toString()
        if (ownText.isNullOrBlank() && root.childCount in 2..6) {
            val leaves = ArrayList<String>()
            if (collectPureDigitLeaves(root, leaves, 6)) {
                if (leaves.size in 2..4) {
                    val combined = leaves.joinToString("")
                    val digits = normalizeDigits(combined).filter { it.isDigit() }
                    if (digits.length in maxOf(minDigits, 8)..10) {
                        digits.toLongOrNull()?.let { out.add(root to it) }
                    }
                }
            }
        }
        for (i in 0 until root.childCount) {
            collectNumberNodesAggregate(root.getChild(i), minDigits, out)
        }
    }

    private fun collectPureDigitLeaves(node: AccessibilityNodeInfo, out: MutableList<String>, limit: Int): Boolean {
        if (out.size > limit) return false
        if (node.childCount == 0) {
            val t = node.text?.toString() ?: node.contentDescription?.toString()
            if (t.isNullOrBlank()) return true
            val norm = normalizeDigits(t).trim()
            if (!norm.all { it.isDigit() || it == ',' || it == ' ' }) return false
            if (norm.any { it.isDigit() }) out.add(t)
            return true
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (!collectPureDigitLeaves(c, out, limit)) return false
        }
        return true
    }

    private fun countTextNodes(root: AccessibilityNodeInfo?): Int {
        if (root == null) return 0
        var count = 0
        val t = root.text?.toString() ?: root.contentDescription?.toString()
        if (!t.isNullOrBlank()) count = 1
        for (i in 0 until root.childCount) {
            count += countTextNodes(root.getChild(i))
        }
        return count
    }

    private fun collectNumberNodes(
        root: AccessibilityNodeInfo?,
        minDigits: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Long>>
    ) {
        collectNumberNodesSimple(root, minDigits, out)
        if (out.isEmpty()) {
            collectNumberNodesAggregate(root, minDigits, out)
        }
    }

    private fun collectClickableNodes(
        root: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (root == null) return
        if (root.isClickable && root.childCount == 0) {
            out.add(root)
        }
        for (i in 0 until root.childCount) {
            collectClickableNodes(root.getChild(i), out)
        }
    }

    private fun normalizeDigits(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            sb.append(
                when (c) {
                    in '۰'..'۹' -> ('0' + (c - '۰'))
                    in '٠'..'٩' -> ('0' + (c - '٠'))
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun boundsOf(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        return "${r.left},${r.top},${r.right},${r.bottom}"
    }

    private fun parseFieldConfigs(json: String): List<FieldConfig> {
        val arr = JSONArray(json)
        val list = mutableListOf<FieldConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                FieldConfig(
                    name = o.optString("name", "field$i"),
                    sourceIndex = o.getInt("sourceIndex"),
                    targetPlusIndex = o.getInt("targetPlusIndex"),
                    targetMinusIndex = o.getInt("targetMinusIndex"),
                    offset = o.getLong("offset"),
                    clickStep = o.optLong("clickStep", 10000L)
                )
            )
        }
        return list
    }
}
