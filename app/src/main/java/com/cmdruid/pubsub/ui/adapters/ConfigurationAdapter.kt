package com.cmdruid.pubsub.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.databinding.ItemConfigurationBinding

class ConfigurationAdapter(
    private val onEditClick: (Configuration) -> Unit,
    private val onDeleteClick: (Configuration) -> Unit,
    private val onEnabledChanged: (Configuration, Boolean) -> Unit
) : RecyclerView.Adapter<ConfigurationAdapter.ConfigurationViewHolder>() {
    
    private var configurations = mutableListOf<Configuration>()
    
    fun setConfigurations(newConfigurations: List<Configuration>) {
        configurations.clear()
        configurations.addAll(newConfigurations)
        notifyDataSetChanged()
    }
    
    fun updateConfiguration(configuration: Configuration) {
        val index = configurations.indexOfFirst { it.id == configuration.id }
        if (index != -1) {
            configurations[index] = configuration
            notifyItemChanged(index)
        }
    }
    
    fun removeConfiguration(configurationId: String) {
        val index = configurations.indexOfFirst { it.id == configurationId }
        if (index != -1) {
            configurations.removeAt(index)
            notifyItemRemoved(index)
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigurationViewHolder {
        val binding = ItemConfigurationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConfigurationViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ConfigurationViewHolder, position: Int) {
        holder.bind(configurations[position])
    }
    
    override fun getItemCount(): Int = configurations.size
    
    inner class ConfigurationViewHolder(
        private val binding: ItemConfigurationBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(configuration: Configuration) {
            binding.apply {
                nameText.text = configuration.name
                summaryText.text = configuration.getSummary()
                targetUriText.text = configuration.targetUri
                
                enabledSwitch.isChecked = configuration.isEnabled
                enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked != configuration.isEnabled) {
                        onEnabledChanged(configuration, isChecked)
                    }
                }
                
                editButton.setOnClickListener {
                    onEditClick(configuration)
                }
                
                deleteButton.setOnClickListener {
                    onDeleteClick(configuration)
                }
            }
        }
    }
}
