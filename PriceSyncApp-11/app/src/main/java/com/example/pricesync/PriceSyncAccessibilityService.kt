package com.example.pricesync

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray

class PriceSyncAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PriceSync"
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "سرویس فعال شد")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val sourcePkg = Prefs.getSourcePackage(this)
        if (sourcePkg.isBlank() || !Prefs.getAutoSyncEnabled(this)) return
        val evPkg = event?.packageName?.toString() ?: return
        if (evPkg == sourcePkg && activeFieldSyncs == 0) {
            syncAllFields()
        }
    }

    // ---------- API قابل فراخوانی از MainActivity ----------

    fun syncNowManual() {
        syncAllFields()
    }

    /** یک اسکن فوری (بدون کلیک) برای کالیبراسیون ایندکس‌ها از داخل اپ. */
    fun getDebugSnapshot(): String {
        val sourcePkg = Prefs.getSourcePackage(this)
        val targetPkg = Prefs.getTargetPackage(this)
        val minDigits = Prefs.getMinDigits(this)

        val srcRoot = findRootForPackage(sourcePkg)
        val tgtRoot = findRootForPackage(targetPkg)

        val srcNums = ArrayList<Pair<AccessibilityNodeInfo, Long>>()
        collectNumberNodes(srcRoot, minDigits, srcNums)
        val tgtNums = ArrayList<Pair<AccessibilityNodeInfo, Long>>()
        collectNumberNodes(tgtRoot, minDigits, tgtNums)
        val tgtClicks = ArrayList<AccessibilityNodeInfo>()
        collectClickableNodes(tgtRoot, tgtClicks)

        val sb = StringBuilder()
        val blocked = isBlockedByPopup(srcRoot, tgtRoot)
        sb.append(if (blocked) "⛔ الان یه پاپ‌آپ/بنر معامله دیده می‌شود — سینک موقتاً متوقف می‌ماند.\n\n"
                   else "✅ پاپ‌آپ معامله دیده نمی‌شود.\n\n")
        sb.append("منبع (").append(sourcePkg).append(")")
        sb.append(if (srcRoot == null) " -> پیدا نشد! (اپ باز است؟ نام پکیج درست است؟)\n" else ":\n")
        srcNums.forEachIndexed { i, p -> sb.append("  [$i] ${p.second}\n") }
        sb.append("(تشخیصی) تعداد گره‌های دارای متن در منبع: ${countTextNodes(srcRoot)}\n")

        sb.append("\nمقصد (").append(targetPkg).append(") - اعداد")
        sb.append(if (tgtRoot == null) " -> پیدا نشد!\n" else ":\n")
        tgtNums.forEachIndexed { i, p -> sb.append("  [$i] ${p.second}\n") }

        val tgtTextNodeCount = countTextNodes(tgtRoot)
        sb.append("\n(تشخیصی) تعداد گره‌های دارای متن در مقصد: $tgtTextNodeCount\n")
        if (tgtRoot != null && tgtTextNodeCount < 15) {
            sb.append("⚠️ این عدد خیلی کمه؛ احتمالاً فایرفاکس متن واقعی این صفحه رو به Accessibility نمی‌ده (شاید رقم‌ها گرافیکی/فونت‌آیکون‌ان).\n")
        }

        sb.append("\nمقصد - دکمه‌های قابل کلیک:\n")
        tgtClicks.forEachIndexed { i, n ->
            val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: n.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.className?.toString()?.takeIf { it.isNotBlank() }
                ?: "(بدون متن)"
            sb.append("  [$i] $desc  (bounds=${boundsOf(n)})\n")
        }

        sb.append("\nمقادیر ردیابی‌شده‌ی مقصد (چیزی که خودِ اپ فکر می‌کند الان روی صفحه‌ست):\n")
        try {
            val configs = parseFieldConfigs(Prefs.getFieldMapJson(this))
            if (configs.isEmpty()) {
                sb.append("  (هیچ فیلدی تعریف نشده)\n")
            } else {
                for (f in configs) {
                    val tv = Prefs.getTrackedValue(this, f.name)
                    sb.append("  ${f.name}: ${tv?.toString() ?: "❗️کالیبره نشده"}\n")
                }
            }
        } catch (e: Exception) {
            sb.append("  (خطا در خواندن تنظیمات فیلدها: ${e.message})\n")
        }
        return sb.toString()
    }

    // ---------- منطق اصلی همگام‌سازی ----------

    private fun syncAllFields() {
        val configs = try {
            parseFieldConfigs(Prefs.getFieldMapJson(this))
        } catch (e: Exception) {
            Log.e(TAG, "فرمت JSON تنظیمات فیلدها اشتباه است: ${e.message}")
            return
        }
        val srcRoot = findRootForPackage(Prefs.getSourcePackage(this))
        val tgtRoot = findRootForPackage(Prefs.getTargetPackage(this))
        if (isBlockedByPopup(srcRoot, tgtRoot)) {
            Log.i(TAG, "پاپ‌آپ/بنر معامله دیده شد؛ این چرخه رد می‌شود")
            return
        }
        val maxAttempts = Prefs.getMaxClicks(this)
        activeFieldSyncs += configs.size
        for (field in configs) {
            syncFieldStep(field, maxAttempts)
        }
    }

    private fun syncFieldStep(field: FieldConfig, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            finishOneField()
            return
        }
        val sourcePkg = Prefs.getSourcePackage(this)
        val targetPkg = Prefs.getTargetPackage(this)
        val minDigits = Prefs.getMinDigits(this)
        val clickDelay = Prefs.getClickDelayMs(this)

        val srcRoot = findRootForPackage(sourcePkg)
        val tgtRoot = findRootForPackage(targetPkg)
        if (srcRoot == null || tgtRoot == null) {
            finishOneField()
            return
        }
        if (isBlockedByPopup(srcRoot, tgtRoot)) {
            Log.i(TAG, "پاپ‌آپ/بنر معامله وسط چرخه ظاهر شد؛ «${field.name}» متوقف شد")
            finishOneField()
            return
        }

        val srcNums = ArrayList<Pair<AccessibilityNodeInfo, Long>>()
        collectNumberNodes(srcRoot, minDigits, srcNums)
        if (field.sourceIndex >= srcNums.size) {
            Log.w(TAG, "ایندکس منبع برای «${field.name}» خارج از محدوده است")
            finishOneField()
            return
        }

        val desired = srcNums[field.sourceIndex].second + field.offset
        val current = Prefs.getTrackedValue(this, field.name)
        if (current == null) {
            Log.w(TAG, "«${field.name}» هنوز کالیبره نشده — مقدار فعلی مقصد را در تنظیمات وارد کنید")
            finishOneField()
            return
        }

        val diff = desired - current
        if (field.clickStep <= 0 || kotlin.math.abs(diff) * 2 < field.clickStep) {
            // به نزدیک‌ترین مقدار ممکن (با دقت نیم‌کلیک) رسیدیم
            finishOneField()
            return
        }

        val tgtClicks = ArrayList<AccessibilityNodeInfo>()
        collectClickableNodes(tgtRoot, tgtClicks)
        val btnIndex = if (diff > 0) field.targetPlusIndex else field.targetMinusIndex
        if (btnIndex < 0 || btnIndex >= tgtClicks.size) {
            Log.w(TAG, "ایندکس دکمه برای «${field.name}» خارج از محدوده است")
            finishOneField()
            return
        }

        tgtClicks[btnIndex].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val newTracked = current + (if (diff > 0) field.clickStep else -field.clickStep)
        Prefs.setTrackedValue(this, field.name, newTracked)
        Log.i(TAG, "${field.name}: tracked=$current -> $newTracked (desired=$desired) click[$btnIndex]")

        handler.postDelayed({ syncFieldStep(field, attemptsLeft - 1) }, clickDelay)
    }

    private fun finishOneField() {
        activeFieldSyncs = (activeFieldSyncs - 1).coerceAtLeast(0)
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

    /** آیا صفحه‌ی منبع یا مقصد الان یکی از پاپ‌آپ‌های «درخواست معامله» رو نشون می‌ده؟ */
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

    /** روش اصلی و امن: فقط متن خودِ هر گره را می‌خواند (همان چیزی که از اول درست کار می‌کرد). */
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

    /**
     * روش کمکی (فقط وقتی روش ساده هیچی پیدا نکند اجرا می‌شود): برای
     * صفحاتی که رقم‌های یک عدد را بین چند span (حتی در چند لایه‌ی
     * تودرتو) جدا می‌شکنند. خیلی سخت‌گیرانه است: اگر هر برگ داخل این
     * زیردرخت هر چیزی غیر از رقم/کاما/فاصله داشته باشد، یا تعداد
     * برگ‌ها زیاد باشد، کلاً رد می‌شود تا چیز نامربوطی قاطی نشود.
     */
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

    /** جمع‌آوری بازگشتی متن برگ‌ها؛ اگر هر برگ غیر رقم/کاما/فاصله داشت یا تعداد از حد گذشت، false برمی‌گرداند. */
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

    /** تشخیصی: چند گره در این زیردرخت اصلاً متن/contentDescription غیرخالی دارند؟ */
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
