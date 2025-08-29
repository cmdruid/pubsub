package com.cmdruid.pubsub.ui.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cmdruid.pubsub.databinding.ItemTextEntryBinding
import com.cmdruid.pubsub.utils.NostrUtils

class TextEntryAdapter(
    private val hint: String = "Enter value",
    private val validator: ((String) -> ValidationResult)? = null
) : RecyclerView.Adapter<TextEntryAdapter.TextEntryViewHolder>() {
    
    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val normalizedValue: String? = null
    )
    
    private var entries = mutableListOf<String>()
    
    fun setEntries(newEntries: List<String>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
    
    fun getEntries(): List<String> {
        return entries.filter { it.isNotBlank() }
    }
    
    fun addEntry(value: String = "") {
        entries.add(value)
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
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextEntryViewHolder {
        val binding = ItemTextEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TextEntryViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: TextEntryViewHolder, position: Int) {
        holder.bind(entries[position], position)
    }
    
    override fun getItemCount(): Int = entries.size
    
    inner class TextEntryViewHolder(
        private val binding: ItemTextEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var textWatcher: TextWatcher? = null
        
        fun bind(value: String, position: Int) {
            binding.apply {
                textInputLayout.hint = hint
                
                // Remove previous text watcher to avoid conflicts
                textWatcher?.let { textInputEditText.removeTextChangedListener(it) }
                
                textInputEditText.setText(value)
                
                // Add new text watcher
                textWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < entries.size) {
                            val inputText = s.toString()
                            
                            // Apply validation and normalization if provided
                            validator?.let { validate ->
                                val result = validate(inputText)
                                if (result.isValid && result.normalizedValue != null) {
                                    // Update the entry with normalized value
                                    entries[currentPosition] = result.normalizedValue
                                    // Update the text field if normalization changed the value
                                    if (result.normalizedValue != inputText) {
                                        textInputEditText.setText(result.normalizedValue)
                                        textInputEditText.setSelection(result.normalizedValue.length)
                                    }
                                    textInputLayout.error = null
                                } else {
                                    entries[currentPosition] = inputText
                                    textInputLayout.error = result.errorMessage
                                }
                            } ?: run {
                                entries[currentPosition] = inputText
                            }
                        }
                    }
                }
                textInputEditText.addTextChangedListener(textWatcher)
                
                deleteButton.setOnClickListener {
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        removeEntry(currentPosition)
                    }
                }
            }
        }
    }
    
    companion object {
        /**
         * Create a validator for public keys (accepts both hex and npub)
         */
        fun createPublicKeyValidator(): (String) -> ValidationResult {
            return { input ->
                val trimmed = input.trim()
                when {
                    trimmed.isBlank() -> ValidationResult(true, normalizedValue = trimmed)
                    NostrUtils.isValidNpub(trimmed) -> {
                        val hex = NostrUtils.npubToHex(trimmed)
                        if (hex != null) {
                            ValidationResult(true, normalizedValue = hex)
                        } else {
                            ValidationResult(false, "Invalid npub format")
                        }
                    }
                    NostrUtils.isValidHexPubkey(trimmed) -> ValidationResult(true, normalizedValue = trimmed)
                    else -> ValidationResult(false, "Invalid public key format (use hex or npub)")
                }
            }
        }
        
        /**
         * Create a validator for event kinds (integers)
         */
        fun createEventKindValidator(): (String) -> ValidationResult {
            return { input ->
                val trimmed = input.trim()
                when {
                    trimmed.isBlank() -> ValidationResult(true, normalizedValue = trimmed)
                    else -> {
                        val kind = trimmed.toIntOrNull()
                        if (kind != null && kind >= 0) {
                            ValidationResult(true, normalizedValue = kind.toString())
                        } else {
                            ValidationResult(false, "Must be a non-negative integer")
                        }
                    }
                }
            }
        }
        
        /**
         * Create a validator for hashtags (no # prefix) - DEPRECATED
         * Use CustomTagAdapter for new custom tag functionality with tag/value pairs
         */
        @Deprecated("Use CustomTagAdapter for new custom tag functionality")
        fun createHashtagValidator(): (String) -> ValidationResult {
            return { input ->
                val trimmed = input.trim()
                when {
                    trimmed.isBlank() -> ValidationResult(true, normalizedValue = trimmed)
                    trimmed.startsWith("#") -> ValidationResult(true, normalizedValue = trimmed.substring(1))
                    else -> ValidationResult(true, normalizedValue = trimmed)
                }
            }
        }
    }
}
