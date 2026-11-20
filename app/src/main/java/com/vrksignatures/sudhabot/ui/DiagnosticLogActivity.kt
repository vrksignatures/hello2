package com.vrksignatures.sudhabot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vrksignatures.sudhabot.databinding.ActivityDiagnosticLogBinding
import com.vrksignatures.sudhabot.memory.DiagnosticLogStore

class DiagnosticLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticLogBinding
    private lateinit var logs: DiagnosticLogStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logs = DiagnosticLogStore(this)
        refreshLogView()

        binding.btnCopyLog.setOnClickListener {
            val text = logs.getAllFormatted()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SudhaAutoBot Diagnostics", text))
            Toast.makeText(this, "Diagnostic log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareLog.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SudhaAutoBot Diagnostic Event Log")
                putExtra(Intent.EXTRA_TEXT, logs.getAllFormatted())
            }
            startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Log"))
        }

        binding.btnClearLog.setOnClickListener {
            logs.clear()
            refreshLogView()
            Toast.makeText(this, "Diagnostic log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshLogView() {
        binding.tvDiagnosticLogContent.text = logs.getAllFormatted()
    }
}
