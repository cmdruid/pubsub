package com.cmdruid.pubsub.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.databinding.DialogLogFilterBinding
import com.cmdruid.pubsub.logging.LogFilter
import com.cmdruid.pubsub.logging.LogType
import com.cmdruid.pubsub.logging.LogDomain
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Dialog for configuring log filters
 */
class LogFilterDialog(
    context: Context,
    private val currentFilter: LogFilter,
    private val onFilterChanged: (LogFilter) -> Unit
) : AlertDialog(context) {

    private lateinit var binding: DialogLogFilterBinding
    private val typeSwitches = mutableMapOf<LogType, SwitchMaterial>()
    private val domainSwitches = mutableMapOf<LogDomain, SwitchMaterial>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = DialogLogFilterBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        
        setupUI()
        setupListeners()
        loadCurrentFilter()
    }

    private fun setupUI() {
        // Create switches for log types
        LogType.values().forEach { type ->
            val switch = createSwitch("${type.icon} ${type.displayName}")
            typeSwitches[type] = switch
            binding.logTypesContainer.addView(switch)
        }

        // Create switches for log domains
        LogDomain.values().forEach { domain ->
            val switch = createSwitch("${domain.icon} ${domain.displayName}")
            domainSwitches[domain] = switch
            binding.logDomainsContainer.addView(switch)
        }
    }

    private fun createSwitch(text: String): SwitchMaterial {
        val switch = SwitchMaterial(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 8, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        return switch
    }

    private fun setupListeners() {
        binding.maxLogsSlider.addOnChangeListener { _, value, _ ->
            binding.maxLogsValueText.text = "Current: ${value.toInt()} logs"
            // Apply filter in real-time as user moves slider
            applyFilter()
        }

        binding.resetButton.setOnClickListener {
            resetToDefault()
        }

        binding.closeButton.setOnClickListener {
            dismiss()
        }
        
        // Apply filters in real-time when switches change
        setupRealTimeFiltering()
    }

    private fun loadCurrentFilter() {
        // Set type switches
        typeSwitches.forEach { (type, switch) ->
            switch.isChecked = type in currentFilter.enabledTypes
        }

        // Set domain switches
        domainSwitches.forEach { (domain, switch) ->
            switch.isChecked = domain in currentFilter.enabledDomains
        }

        // Set max logs slider
        binding.maxLogsSlider.value = currentFilter.maxLogs.toFloat()
        binding.maxLogsValueText.text = "Current: ${currentFilter.maxLogs} logs"
    }

    private fun resetToDefault() {
        val defaultFilter = LogFilter.DEFAULT

        typeSwitches.forEach { (type, switch) ->
            switch.isChecked = type in defaultFilter.enabledTypes
        }

        domainSwitches.forEach { (domain, switch) ->
            switch.isChecked = domain in defaultFilter.enabledDomains
        }

        binding.maxLogsSlider.value = defaultFilter.maxLogs.toFloat()
        binding.maxLogsValueText.text = "Current: ${defaultFilter.maxLogs} logs"
    }
    
    private fun setupRealTimeFiltering() {
        // Add listeners to all type switches for real-time filtering
        typeSwitches.values.forEach { switch ->
            switch.setOnCheckedChangeListener { _, _ ->
                applyFilter()
            }
        }
        
        // Add listeners to all domain switches for real-time filtering
        domainSwitches.values.forEach { switch ->
            switch.setOnCheckedChangeListener { _, _ ->
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val enabledTypes = typeSwitches.filter { it.value.isChecked }.keys.toSet()
        val enabledDomains = domainSwitches.filter { it.value.isChecked }.keys.toSet()
        val maxLogs = binding.maxLogsSlider.value.toInt()

        val newFilter = LogFilter(
            enabledTypes = enabledTypes,
            enabledDomains = enabledDomains,
            maxLogs = maxLogs
        )

        onFilterChanged(newFilter)
    }
}
