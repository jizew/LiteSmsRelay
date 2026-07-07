package com.smsforward

import android.content.Context
import android.content.SharedPreferences

object AppConfig {

    private const val PREFS_NAME = "sms_forward_config"
    private const val KEY_ENABLED = "forward_enabled"
    private const val KEY_TARGET = "target_number"
    private const val KEY_KEEP_KEYWORD = "keep_keyword"
    private const val KEY_INCLUDE_SENDER = "include_sender"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = true
        set(_) {}

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun getTarget(ctx: Context): String =
        prefs(ctx).getString(KEY_TARGET, "") ?: ""

    fun setTarget(ctx: Context, number: String) =
        prefs(ctx).edit().putString(KEY_TARGET, number.trim()).apply()

    fun getKeyword(ctx: Context): String =
        prefs(ctx).getString(KEY_KEEP_KEYWORD, "") ?: ""

    fun setKeyword(ctx: Context, kw: String) =
        prefs(ctx).edit().putString(KEY_KEEP_KEYWORD, kw.trim()).apply()

    fun getKeywords(ctx: Context): List<String> =
        getKeyword(ctx).split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun isIncludeSender(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_INCLUDE_SENDER, true)

    fun setIncludeSender(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_INCLUDE_SENDER, on).apply()

    fun isReady(ctx: Context): Boolean {
        val target = getTarget(ctx)
        return target.isNotBlank() && isEnabled(ctx)
    }
}
