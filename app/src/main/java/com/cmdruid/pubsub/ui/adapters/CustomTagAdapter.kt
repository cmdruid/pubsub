package com.cmdruid.pubsub.ui.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
    
    fun addEntry(entry: HashtagEntry = HashtagEntry("a", "")) {
        // Default to first available letter if no tag specified
        val defaultEntry = if (entry.tag.isEmpty()) entry.copy(tag = "a") else entry
        entries.add(defaultEntry)
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
    
    companion object {
        // Available letters excluding reserved tags (e, p, t)
        private val AVAILABLE_LETTERS = buildList {
            // Add lowercase letters except e, p, t
            ('a'..'z').forEach { letter ->
                if (letter !in listOf('e', 'p', 't')) {
                    add(letter.toString())
                }
            }
            // Add uppercase letters except E, P, T
            ('A'..'Z').forEach { letter ->
                if (letter !in listOf('E', 'P', 'T')) {
                    add(letter.toString())
                }
            }
        }.sorted() // Sort alphabetically for better UX
    }
    
    inner class CustomTagViewHolder(
        private val binding: ItemCustomTagBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var valueTextWatcher: TextWatcher? = null
        
        fun bind(entry: HashtagEntry, position: Int) {
            binding.apply {
                // Remove previous text watcher to avoid conflicts
                valueTextWatcher?.let { valueEditText.removeTextChangedListener(it) }
                
                // Setup tag selector dropdown with custom styling
                val tagAdapter = ArrayAdapter(itemView.context, com.cmdruid.pubsub.R.layout.dropdown_tag_item, AVAILABLE_LETTERS)
                tagSelector.setAdapter(tagAdapter)
                tagSelector.dropDownHeight = 400 // Limit dropdown height to prevent overlap
                
                // Set current values
                android.util.Log.d("CustomTagAdapter", "Binding entry at position $position: tag=${entry.tag}, value=${entry.value}")
                val tagToSet = if (entry.tag.isNotEmpty()) entry.tag else "a"
                tagSelector.setText(tagToSet, false)
                valueEditText.setText(entry.value)
                
                // Validate and show errors
                updateValidation(entry)
                
                // Add tag selector listener
                tagSelector.setOnItemClickListener { _, _, tagPosition, _ ->
                    val selectedTag = AVAILABLE_LETTERS[tagPosition]
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION && currentPosition < entries.size) {
                        val currentEntry = entries[currentPosition]
                        entries[currentPosition] = currentEntry.copy(tag = selectedTag)
                        updateValidation(entries[currentPosition])
                        android.util.Log.d("CustomTagAdapter", "Tag changed to: $selectedTag")
                    }
                }
                
                // Add value text watcher
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
                // Tag validation is no longer needed since TagSelectorView only allows valid tags
                
                // Validate value - require value when tag is selected
                if (entry.value.isBlank()) {
                    valueInputLayout.error = "Value is required"
                } else {
                    valueInputLayout.error = null
                }
            }
        }
    }
}
