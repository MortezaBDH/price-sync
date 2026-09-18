package com.example.pricesync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var etSourcePkg: EditText
    private lateinit var etTargetPkg: EditText
    private lateinit var etMinDigits: EditText
    private lateinit var etClickDelay: EditText
    private lateinit var etMaxClicks: EditText
    private lateinit var etFieldMapJson: EditText
    private lateinit var switchAutoSync: Switch
    private lateinit var tvPreview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSourcePkg = findViewById(R.id.etSourcePkg)
        etTargetPkg = findViewById(R.id.etTargetPkg)
        etMinDigits = findViewById(R.id.etMinDigits)
        etClickDelay = findViewById(R.id.etClickDelay)
        etMaxClicks = findViewById(R.id.etMaxClicks)
        etFieldMapJson = findViewById(R.id.etFieldMapJson)
        switchAutoSync = findViewById(R.id.switchAutoSync)
        tvPreview = findViewById(R.id.tvPreview)

        loadPrefsIntoUi()

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveUiIntoPrefs()
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnRefreshPreview).setOnClickListener {
            saveUiIntoPrefs()
            val svc = PriceSyncAccessibilityService.instance
            tvPreview.text = svc?.getDebugSnapshot()
                ?: "سرویس Accessibility فعال نیست. اول آن را از دکمه بالا روشن کنید."
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            saveUiIntoPrefs()
            val svc = PriceSyncAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "سرویس فعال نیست", Toast.LENGTH_SHORT).show()
            } else {
                svc.syncNowManual()
                Toast.makeText(this, "همگام‌سازی شروع شد", Toast.LENGTH_SHORT).show()
            }
        }

        switchAutoSync.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            Prefs.setAutoSyncEnabled(this, isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        loadPrefsIntoUi()
    }

    private fun loadPrefsIntoUi() {
        etSourcePkg.setText(Prefs.getSourcePackage(this))
        etTargetPkg.setText(Prefs.getTargetPackage(this))
        etMinDigits.setText(Prefs.getMinDigits(this).toString())
        etClickDelay.setText(Prefs.getClickDelayMs(this).toString())
        etMaxClicks.setText(Prefs.getMaxClicks(this).toString())
        etFieldMapJson.setText(Prefs.getFieldMapJson(this))
        switchAutoSync.isChecked = Prefs.getAutoSyncEnabled(this)
    }

    private fun saveUiIntoPrefs() {
        Prefs.setSourcePackage(this, etSourcePkg.text.toString().trim())
        Prefs.setTargetPackage(this, etTargetPkg.text.toString().trim())
        Prefs.setMinDigits(this, etMinDigits.text.toString().toIntOrNull() ?: 6)
        Prefs.setClickDelayMs(this, etClickDelay.text.toString().toLongOrNull() ?: 300L)
        Prefs.setMaxClicks(this, etMaxClicks.text.toString().toIntOrNull() ?: 60)
        Prefs.setFieldMapJson(this, etFieldMapJson.text.toString())
    }
}
