package com.example.pricesync

import android.content.Context

/**
 * تمام تنظیمات در SharedPreferences ذخیره می‌شود تا از داخل خود اپ
 * (بدون نیاز به کامپایل دوباره) قابل تغییر باشد.
 */
object Prefs {
    private const val FILE = "price_sync_prefs"

    // نمونه اولیه — این اعداد فقط مثال هستند، حتما با دکمه «پیش‌نمایش» در اپ کالیبره کنید.
    const val DEFAULT_FIELD_MAP = """[
  {"name":"نقد فردا - خرید از ما","sourceIndex":1,"targetValueIndex":0,"targetPlusIndex":1,"targetMinusIndex":0,"offset":50000},
  {"name":"نقد فردا - فروش به ما","sourceIndex":0,"targetValueIndex":1,"targetPlusIndex":3,"targetMinusIndex":2,"offset":-50000},
  {"name":"نقد پس‌فردا - خرید از ما","sourceIndex":3,"targetValueIndex":2,"targetPlusIndex":5,"targetMinusIndex":4,"offset":50000},
  {"name":"نقد پس‌فردا - فروش به ما","sourceIndex":2,"targetValueIndex":3,"targetPlusIndex":7,"targetMinusIndex":6,"offset":-50000}
]"""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getSourcePackage(ctx: Context): String = prefs(ctx).getString("source_package", "") ?: ""
    fun setSourcePackage(ctx: Context, v: String) { prefs(ctx).edit().putString("source_package", v).apply() }

    fun getTargetPackage(ctx: Context): String = prefs(ctx).getString("target_package", "") ?: ""
    fun setTargetPackage(ctx: Context, v: String) { prefs(ctx).edit().putString("target_package", v).apply() }

    fun getAutoSyncEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_sync", false)
    fun setAutoSyncEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto_sync", v).apply() }

    fun getClickDelayMs(ctx: Context): Long = prefs(ctx).getLong("click_delay_ms", 300L)
    fun setClickDelayMs(ctx: Context, v: Long) { prefs(ctx).edit().putLong("click_delay_ms", v).apply() }

    fun getMaxClicks(ctx: Context): Int = prefs(ctx).getInt("max_clicks", 60)
    fun setMaxClicks(ctx: Context, v: Int) { prefs(ctx).edit().putInt("max_clicks", v).apply() }

    fun getMinDigits(ctx: Context): Int = prefs(ctx).getInt("min_digits", 6)
    fun setMinDigits(ctx: Context, v: Int) { prefs(ctx).edit().putInt("min_digits", v).apply() }

    fun getFieldMapJson(ctx: Context): String = prefs(ctx).getString("field_map", DEFAULT_FIELD_MAP) ?: DEFAULT_FIELD_MAP
    fun setFieldMapJson(ctx: Context, v: String) { prefs(ctx).edit().putString("field_map", v).apply() }
}
