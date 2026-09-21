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

/**
 * سرویس Accessibility که همزمان دو اپ (منبع و مقصد) را در حالت
 * split-screen می‌خواند، و با کلیک شبیه‌سازی‌شده روی دکمه‌های +/-
 * اپ مقصد، اعداد آن را با فرمول source + offset همگام می‌کند.
 *
 * منطق کلیک: در هر مرحله فقط چک می‌کند مقدار فعلی کمتر یا بیشتر از
 * مقدار هدف است و یک کلیک + یا - می‌زند، سپس دوباره می‌خواند.
 * به این ترتیب لازم نیست بداند هر کلیک دقیقا چقدر عدد را تغییر می‌دهد.
 */
class PriceSyncAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PriceSync"
        @Volatile var instance: PriceSyncAccessibilityService? = null
    }

    data class FieldConfig(
        val name: String,
        val sourceIndex: Int,
        val targetValueIndex: Int,
        val targetPlusIndex: Int,
        val targetMinusIndex: Int,
        val offset: Long
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

        sb.append("\nمقصد (").append(targetPkg).append(") - اعداد")
        sb.append(if (tgtRoot == null) " -> پیدا نشد!\n" else ":\n")
        tgtNums.forEachIndexed { i, p -> sb.append("  [$i] ${p.second}\n") }

        sb.append("\nمقصد - دکمه‌های قابل کلیک:\n")
        tgtClicks.forEachIndexed { i, n ->
            val desc = n.contentDescription?.toString() ?: n.text?.toString() ?: n.className?.toString() ?: "?"
            sb.append("  [$i] $desc  (bounds=${boundsOf(n)})\n")
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
        val tgtNums = ArrayList<Pair<AccessibilityNodeInfo, Long>>()
        collectNumberNodes(tgtRoot, minDigits, tgtNums)
        val tgtClicks = ArrayList<AccessibilityNodeInfo>()
        collectClickableNodes(tgtRoot, tgtClicks)

        if (field.sourceIndex >= srcNums.size || field.targetValueIndex >= tgtNums.size) {
            Log.w(TAG, "ایندکس فیلد «${field.name}» خارج از محدوده است")
            finishOneField()
            return
        }

        val desired = srcNums[field.sourceIndex].second + field.offset
        val current = tgtNums[field.targetValueIndex].second

        if (desired == current) {
            finishOneField()
            return
        }

        val btnIndex = if (desired > current) field.targetPlusIndex else field.targetMinusIndex
        if (btnIndex < 0 || btnIndex >= tgtClicks.size) {
            Log.w(TAG, "ایندکس دکمه برای «${field.name}» خارج از محدوده است")
            finishOneField()
            return
        }

        tgtClicks[btnIndex].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "${field.name}: current=$current desired=$desired -> click[$btnIndex]")

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
                // اولویت با پنجره‌ی نوع TYPE_APPLICATION (نه یه اعلان کوچیک)،
                // و در مرحله‌ی بعد بزرگ‌ترین مساحت روی صفحه.
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

    private fun collectNumberNodes(
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
        } else if (root.childCount in 1..8) {
            // بعضی صفحات وب برای انیمیشن، رقم‌های یک عدد رو بین چند
            // span جدا می‌شکنن. اگه خود گره متن نداشت ولی چند فرزند
            // «ساده» (بدون نوه) داره، متن اون فرزندها رو می‌چسبونیم.
            var allLeafLike = true
            val combined = StringBuilder()
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                if (child.childCount > 0) allLeafLike = false
                val ct = child.text?.toString() ?: child.contentDescription?.toString()
                if (!ct.isNullOrBlank()) combined.append(ct)
            }
            if (allLeafLike && combined.isNotEmpty()) {
                val digits = normalizeDigits(combined.toString()).filter { it.isDigit() }
                if (digits.length >= minDigits) {
                    digits.toLongOrNull()?.let { out.add(root to it) }
                }
            }
        }
        for (i in 0 until root.childCount) {
            collectNumberNodes(root.getChild(i), minDigits, out)
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
                    targetValueIndex = o.getInt("targetValueIndex"),
                    targetPlusIndex = o.getInt("targetPlusIndex"),
                    targetMinusIndex = o.getInt("targetMinusIndex"),
                    offset = o.getLong("offset")
                )
            )
        }
        return list
    }
}
