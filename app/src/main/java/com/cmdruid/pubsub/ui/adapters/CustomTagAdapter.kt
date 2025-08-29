package com.cmdruid.pubsub.ui.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cmdruid.pubsub.databinding.ItemCustomTagBinding
import com.cmdruid.pubsub.data.HashtagEntry

/**
 * Adapter for managing custom tag entries with separate tag and value fields
 * This is for the "Custom Tags" section which allows arbitrary single-letter tags
 * (as opposed to HashtagsAdapter which handles simple hashtags using the "t" tag)
 * 
 * According to NIP-01, tags should be single letters (a-z, A-Z)
 * Simplified UX: Users can add multiple entries with the same tag,
 * and they will be automatically consolidated during serialization.
 */
class CustomTagAdapter : RecyclerView.Adapter<CustomTagAdapter.CustomTagViewHolder>() {
    
    private var entries = mutableListOf<HashtagEntry>()
    
    fun setEntries(newEntries: List<HashtagEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
    
    /**
     * Get all valid entries. Duplicates will be consolidated by the serializer.
     */
    fun getEntries(): List<HashtagEntry> {
        return entries.filter { it.isValid() }
    }
    
    fun addEntry(entry: HashtagEntry = HashtagEntry("", "")) {
        entries.add(entry)
        notifyItemInserted(entries.size - 1)
    }
    
    fun removeEntry(position: Int) {
        if (position in 0 until entries.size) {
            entries.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, entries.size - position)
        }
    }
    
    fun clear() {
        entries.clear()
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CustomTagViewHolder {
        val binding = ItemCustomTagBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CustomTagViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: CustomTagViewHolder, position: Int) {
        holder.bind(entries[position], position)
    }
    
    override fun getItemCount(): Int = entries.size
    
    inner class CustomTagViewHolder(
        private val binding: ItemCustomTagBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var tagTextWatcher: TextWatcher? = null
        private var valueTextWatcher: TextWatcher? = null
        
        fun bind(entry: HashtagEntry, position: Int) {
            binding.apply {
                // Remove previous text watchers to avoid conflicts
                tagTextWatcher?.let { tagEditText.removeTextChangedListener(it) }
                valueTextWatcher?.let { valueEditText.removeTextChangedListener(it) }
                
                // Set current values
                tagEditText.setText(entry.tag)
                valueEditText.setText(entry.value)
                
                // Validate and show errors
                updateValidation(entry)
                
                // Add text watchers
                tagTextWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < entries.size) {
                            val tag = s.toString()
                            val currentEntry = entries[currentPosition]
                            entries[currentPosition] = currentEntry.copy(tag = tag)
                            updateValidation(entries[currentPosition])
                        }
                    }
                }
                
                valueTextWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < entries.size) {
                            val value = s.toString()
                            val currentEntry = entries[currentPosition]
                            entries[currentPosition] = currentEntry.copy(value = value)
                            updateValidation(entries[currentPosition])
                        }
                    }
                }
                
                tagEditText.addTextChangedListener(tagTextWatcher)
                valueEditText.addTextChangedListener(valueTextWatcher)
                
                deleteButton.setOnClickListener {
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        removeEntry(currentPosition)
                    }
                }
            }
        }
        
        private fun updateValidation(entry: HashtagEntry) {
            binding.apply {
                // Validate tag
                when {
                    entry.tag.isEmpty() -> {
                        tagInputLayout.error = null
                    }
                    entry.tag.length != 1 -> {
                        tagInputLayout.error = "Tag must be a single letter"
                    }
                    !entry.tag.matches(Regex("[a-zA-Z]")) -> {
                        tagInputLayout.error = "Tag must be a letter (a-z, A-Z)"
                    }
                    entry.tag.lowercase() in listOf("e", "p", "t") -> {
                        tagInputLayout.error = "Tags 'e', 'p', and 't' are reserved for dedicated sections"
                    }
                    else -> {
                        tagInputLayout.error = null
                    }
                }
                
                // Validate value
                if (entry.tag.isNotEmpty() && entry.value.isBlank()) {
                    valueInputLayout.error = "Value is required"
                } else {
                    valueInputLayout.error = null
                }
            }
        }
    }
}
