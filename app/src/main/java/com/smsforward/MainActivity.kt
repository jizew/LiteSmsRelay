package com.smsforward

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 100
    }

    private lateinit var switchEnabled: Switch
    private lateinit var etTarget: EditText
    private lateinit var etKeyword: EditText
    private lateinit var btnSaveKeyword: Button
    private lateinit var switchSender: Switch
    private lateinit var btnBattery: Button
    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchEnabled = findViewById(R.id.switchEnabled)
        etTarget = findViewById(R.id.etTarget)
        etKeyword = findViewById(R.id.etKeyword)
        btnSaveKeyword = findViewById(R.id.btnSaveKeyword)
        switchSender = findViewById(R.id.switchSender)
        btnBattery = findViewById(R.id.btnBattery)
        txtStatus = findViewById(R.id.txtStatus)

        etTarget.inputType = InputType.TYPE_CLASS_PHONE

        loadConfig()
        requestPermissionsIfNeeded()
        startServiceIfReady()

        switchEnabled.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            AppConfig.setEnabled(this, on)
            updateStatus()
            if (on) startServiceIfReady() else SmsForwardService.stop(this)
        }

        switchSender.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            AppConfig.setIncludeSender(this, on)
        }

        btnBattery.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        etTarget.setOnFocusChangeListener { _: View, _: Boolean ->
            AppConfig.setTarget(this, etTarget.text.toString())
            updateStatus()
        }

        btnSaveKeyword.setOnClickListener {
            AppConfig.setKeyword(this, etKeyword.text.toString())
            Toast.makeText(this, R.string.toast_keyword_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadConfig()
        updateStatus()
        updateBatteryButton()
    }

    private fun loadConfig() {
        switchEnabled.isChecked = AppConfig.isEnabled(this)
        etTarget.setText(AppConfig.getTarget(this))
        etKeyword.setText(AppConfig.getKeyword(this))
        switchSender.isChecked = AppConfig.isIncludeSender(this)
    }

    private fun updateStatus() {
        val ready = AppConfig.isReady(this)
        txtStatus.text = if (ready) {
            getString(R.string.status_active, AppConfig.getTarget(this))
        } else {
            getString(R.string.status_inactive)
        }
    }

    private fun updateBatteryButton() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignored = pm.isIgnoringBatteryOptimizations(packageName)
        btnBattery.text = if (ignored) getString(R.string.battery_ok) else getString(R.string.battery_request)
        btnBattery.isEnabled = !ignored
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECEIVE_SMS)
        }
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_SMS)
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.SEND_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, R.string.toast_perms_granted, Toast.LENGTH_SHORT).show()
                startServiceIfReady()
            } else {
                Toast.makeText(this, R.string.toast_perms_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startServiceIfReady() {
        if (AppConfig.isReady(this)) {
            SmsForwardService.start(this)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
