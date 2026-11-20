package com.vrksignatures.sudhabot.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vrksignatures.sudhabot.R
import com.vrksignatures.sudhabot.databinding.ActivityMainBinding
import com.vrksignatures.sudhabot.memory.BotPreferences
import com.vrksignatures.sudhabot.memory.DailyLedgerMemory
import com.vrksignatures.sudhabot.memory.DiagnosticLogStore
import com.vrksignatures.sudhabot.memory.PermanentDistributorMemory
import com.vrksignatures.sudhabot.model.CustomerDemandRule
import com.vrksignatures.sudhabot.model.DemandRuleType
import com.vrksignatures.sudhabot.model.EmployeeChanceConfig
import com.vrksignatures.sudhabot.model.MilkFamily
import com.vrksignatures.sudhabot.model.MilkSku
import com.vrksignatures.sudhabot.model.PaymentMode
import com.vrksignatures.sudhabot.service.SudhaAccessibilityService
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BotPreferences
    private lateinit var tier1Memory: PermanentDistributorMemory
    private lateinit var tier2Memory: DailyLedgerMemory
    private lateinit var logs: DiagnosticLogStore

    private val demandRules = mutableListOf<CustomerDemandRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = BotPreferences(this)
        tier1Memory = PermanentDistributorMemory(this)
        tier2Memory = DailyLedgerMemory(this)
        logs = DiagnosticLogStore(this)

        // Strict Calendar Midnight (00:00) Auto-Reset on EVERY app open (never touches Tier 1)
        if (tier2Memory.checkAndResetAtMidnight()) {
            logs.info("MidnightReset", "App opened on new calendar date; Tier 2 Daily Memory auto-reset (Tier 1 preserved).")
        }

        loadSettingsIntoUi()
        bindInteractions()
    }

    override fun onResume() {
        super.onResume()
        tier2Memory.checkAndResetAtMidnight()
        refreshStatusDisplays()
    }

    private fun loadSettingsIntoUi() {
        binding.etTargetPackage.setText(prefs.targetAppPackage)
        binding.etExpectedRetailers.setText(prefs.expectedRetailersToday.toString())
        binding.etManualLastId.setText(prefs.manualLastHandledIdOverride.orEmpty())
        binding.swFastMode.isChecked = prefs.fastMode

        if (prefs.paymentMode == PaymentMode.AUTO_PAY_CASH) {
            binding.rbAutoPayCash.isChecked = true
        } else {
            binding.rbManualPayment.isChecked = true
        }

        // Load Customer Demand rules
        demandRules.clear()
        demandRules.addAll(prefs.getCustomerDemandRules())
        renderDemandRulesList()

        // Load Employee's Chance config
        val ec = prefs.getEmployeeChanceConfig()
        binding.swEmployeeChance.isChecked = ec.enabled
        binding.etExtraVolumeMl.setText(if (ec.totalExtraVolumeMl > 0) ec.totalExtraVolumeMl.toString() else "")
        binding.etTargetRetailersCount.setText(if (ec.targetRetailerCount > 0) ec.targetRetailerCount.toString() else "")
        binding.cbFamilyCow.isChecked = MilkFamily.COW.name in ec.eligibleFamilies
        binding.cbFamilySm.isChecked = MilkFamily.SM.name in ec.eligibleFamilies
        binding.cbFamilyTm.isChecked = MilkFamily.TM.name in ec.eligibleFamilies
        binding.cbFamilyFcm.isChecked = MilkFamily.FCM.name in ec.eligibleFamilies
        updateEmployeeChanceHeader()
    }

    private fun bindInteractions() {
        binding.btnSaveSettings.setOnClickListener {
            saveAllSettings()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            refreshStatusDisplays()
        }

        // Collapsible Customer Demand card
        binding.headerCustomerDemand.setOnClickListener {
            val visible = binding.containerCustomerDemand.visibility == View.VISIBLE
            binding.containerCustomerDemand.visibility = if (visible) View.GONE else View.VISIBLE
            binding.tvDemandToggleIcon.text = if (visible) "▼" else "▲"
        }

        binding.btnAddDemandRule.setOnClickListener {
            showAddDemandRuleDialog()
        }

        // Collapsible Employee's Chance card
        binding.headerEmployeeChance.setOnClickListener {
            val visible = binding.containerEmployeeChance.visibility == View.VISIBLE
            binding.containerEmployeeChance.visibility = if (visible) View.GONE else View.VISIBLE
            binding.tvEmployeeChanceToggleIcon.text = if (visible) "▼" else "▲"
        }
        binding.swEmployeeChance.setOnCheckedChangeListener { _, _ -> updateEmployeeChanceHeader() }

        // Permissions & Launch
        binding.btnAllowOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Section 4: Open Sudha Daily Orders & show floating panel in IDLE (NEVER auto-click Customers)
        binding.btnOpenSudha.setOnClickListener {
            saveAllSettings()
            SudhaAccessibilityService.instance?.showOverlay()
            val launchIntent = packageManager.getLaunchIntentForPackage(prefs.targetAppPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                Toast.makeText(
                    this,
                    "App '${prefs.targetAppPackage}' not installed on this device",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnShowOverlay.setOnClickListener {
            saveAllSettings()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Allow floating panel' first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!SudhaAccessibilityService.isRunning()) {
                Toast.makeText(this, "Enable SudhaAutoBot Accessibility Service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SudhaAccessibilityService.instance?.showOverlay()
        }

        // Separated Reset 1: Tier 2 Temporary Daily Memory ONLY
        binding.btnResetDailyLedger.setOnClickListener {
            tier2Memory.resetTodaysLedger()
            logs.info("Tier2Reset", "User tapped 'Reset today's ledger'. Tier 1 Permanent Blueprint preserved.")
            Toast.makeText(this, "Today's ledger cleared (Permanent Distributor History kept)", Toast.LENGTH_SHORT).show()
            refreshStatusDisplays()
        }

        // Separated Reset 2: Tier 1 Permanent Distributor Blueprint ONLY (Requires Confirmation Dialog)
        binding.btnResetDistributorHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Distributor History?")
                .setMessage(
                    "This clears the permanently learned Distributor Blueprint (${tier1Memory.memorizedDistributors.size} distributors, " +
                        "${tier1Memory.permanentBlacklistIds.size} blacklisted IDs). " +
                        "The bot will re-learn distributors on the next ▶ START. Today's retailer ledger is NOT affected."
                )
                .setPositiveButton("Reset Blueprint") { _, _ ->
                    tier1Memory.resetDistributorHistory()
                    logs.warn("Tier1Reset", "User confirmed 'Reset Distributor History'. Tier 1 Permanent Blueprint cleared.")
                    Toast.makeText(this, "Permanent Distributor History cleared", Toast.LENGTH_SHORT).show()
                    refreshStatusDisplays()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnViewDiagnosticLog.setOnClickListener {
            startActivity(Intent(this, DiagnosticLogActivity::class.java))
        }
    }

    private fun saveAllSettings() {
        prefs.targetAppPackage = binding.etTargetPackage.text.toString().trim()
        prefs.expectedRetailersToday = binding.etExpectedRetailers.text.toString().toIntOrNull() ?: 125
        prefs.manualLastHandledIdOverride = binding.etManualLastId.text.toString().trim()
        prefs.fastMode = binding.swFastMode.isChecked
        prefs.paymentMode = if (binding.rbAutoPayCash.isChecked) {
            PaymentMode.AUTO_PAY_CASH
        } else {
            PaymentMode.MANUAL_PAUSE
        }

        prefs.saveCustomerDemandRules(demandRules)

        val eligibleFamilies = mutableSetOf<String>()
        if (binding.cbFamilyCow.isChecked) eligibleFamilies.add(MilkFamily.COW.name)
        if (binding.cbFamilySm.isChecked) eligibleFamilies.add(MilkFamily.SM.name)
        if (binding.cbFamilyTm.isChecked) eligibleFamilies.add(MilkFamily.TM.name)
        if (binding.cbFamilyFcm.isChecked) eligibleFamilies.add(MilkFamily.FCM.name)
        if (eligibleFamilies.isEmpty()) {
            eligibleFamilies.add(MilkFamily.COW.name)
            eligibleFamilies.add(MilkFamily.SM.name)
        }

        val ecConfig = EmployeeChanceConfig(
            enabled = binding.swEmployeeChance.isChecked,
            totalExtraVolumeMl = binding.etExtraVolumeMl.text.toString().toIntOrNull() ?: 0,
            targetRetailerCount = binding.etTargetRetailersCount.text.toString().toIntOrNull() ?: 0,
            eligibleFamilies = eligibleFamilies
        )
        prefs.saveEmployeeChanceConfig(ecConfig)
        updateEmployeeChanceHeader()
    }

    private fun showAddDemandRuleDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_demand, null)
        val etId = dialogView.findViewById<EditText>(R.id.etDialogCustomerId)
        val rgType = dialogView.findViewById<RadioGroup>(R.id.rgRuleType)
        val rbExact = dialogView.findViewById<RadioButton>(R.id.rbExactPack)
        val spTarget = dialogView.findViewById<Spinner>(R.id.spSkuOrFamily)
        val etValue = dialogView.findViewById<EditText>(R.id.etDialogValue)

        val skuLabels = MilkSku.entries.map { it.exactLabel }
        val familyLabels = MilkFamily.entries.map { it.name }

        fun bindSpinner(items: List<String>) {
            spTarget.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        }
        bindSpinner(skuLabels)

        rgType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbExactPack) {
                bindSpinner(skuLabels)
            } else {
                bindSpinner(familyLabels)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Add Customer Demand Override")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val cid = etId.text.toString().trim().uppercase()
                val num = etValue.text.toString().trim().toIntOrNull()
                if (cid.isEmpty() || num == null) {
                    Toast.makeText(this, "Enter valid Customer ID and numeric value", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val selected = spTarget.selectedItem?.toString() ?: return@setPositiveButton
                val rule = if (rbExact.isChecked) {
                    CustomerDemandRule(
                        id = UUID.randomUUID().toString(),
                        customerId = cid,
                        ruleType = DemandRuleType.EXACT_PACK_OVERRIDE,
                        skuName = selected,
                        value = num.coerceAtLeast(0)
                    )
                } else {
                    CustomerDemandRule(
                        id = UUID.randomUUID().toString(),
                        customerId = cid,
                        ruleType = DemandRuleType.VOLUME_DELTA_ML,
                        familyName = selected,
                        value = num
                    )
                }
                demandRules.add(rule)
                prefs.saveCustomerDemandRules(demandRules)
                renderDemandRulesList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderDemandRulesList() {
        binding.tvDemandHeader.text = "Customer Demand Overrides (${demandRules.size})"
        binding.listDemandRules.removeAllViews()
        for (rule in demandRules) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_customer_demand, binding.listDemandRules, false)
            row.findViewById<TextView>(R.id.tvDemandSummary).text = rule.displaySummary()
            row.findViewById<View>(R.id.btnDeleteDemand).setOnClickListener {
                demandRules.removeAll { it.id == rule.id }
                prefs.saveCustomerDemandRules(demandRules)
                renderDemandRulesList()
            }
            binding.listDemandRules.addView(row)
        }
    }

    private fun updateEmployeeChanceHeader() {
        val status = if (binding.swEmployeeChance.isChecked) "ON" else "OFF"
        binding.tvEmployeeChanceHeader.text = "Employee's Chance ($status)"
    }

    private fun refreshStatusDisplays() {
        val overlayOk = if (Settings.canDrawOverlays(this)) "GRANTED" else "MISSING"
        val accOk = if (SudhaAccessibilityService.isRunning()) "RUNNING" else "OFF"
        binding.tvPermissionStatus.text = "Overlay: $overlayOk  |  Accessibility Service: $accOk"
        binding.tvDailyMemoryStatus.text = tier2Memory.statusSummary()
        binding.tvPermanentMemoryStatus.text = tier1Memory.statusSummary()
    }
}
