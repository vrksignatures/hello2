package com.vrksignatures.sudhabot.memory

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DiagnosticLogStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()

    fun log(level: String, tag: String, message: String) {
        val timestamp = LocalDateTime.now().format(TIME_FORMATTER)
        val line = "[$timestamp] [$level] [$tag] $message"
        synchronized(lock) {
            val entries = readEntriesInternal().toMutableList()
            entries.add(line)
            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            prefs.edit().putString(KEY_LOGS, gson.toJson(entries)).apply()
        }
    }

    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun audit(tag: String, message: String) = log("AUDIT", tag, message)
    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun alarm(tag: String, message: String) = log("ALARM", tag, message)

    fun getAllFormatted(): String {
        synchronized(lock) {
            val entries = readEntriesInternal()
            return if (entries.isEmpty()) {
                "No diagnostic events recorded yet."
            } else {
                entries.joinToString("\n")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_LOGS).apply()
        }
    }

    private fun readEntriesInternal(): List<String> {
        val raw = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "sudha_diagnostic_logs"
        private const val KEY_LOGS = "entries_json"
        private const val MAX_ENTRIES = 600
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
