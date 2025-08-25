package com.cmdruid.pubsub.ui.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cmdruid.pubsub.databinding.ItemKeywordBinding

/**
 * Adapter for managing keyword entries for content-based event filtering
 * Supports real-time validation and sanitization of keywords
 */
class KeywordAdapter : RecyclerView.Adapter<KeywordAdapter.KeywordViewHolder>() {
    
    private var keywords = mutableListOf<String>()
    
    fun setKeywords(newKeywords: List<String>) {
        keywords.clear()
        keywords.addAll(newKeywords)
        notifyDataSetChanged()
    }
    
    /**
     * Get all valid keywords (sanitized)
     */
    fun getKeywords(): List<String> {
        return keywords
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length >= 2 && it.length <= 100 }
            .distinct()
    }
    
    fun addKeyword(keyword: String = "") {
        keywords.add(keyword)
        notifyItemInserted(keywords.size - 1)
    }
    
    fun removeKeyword(position: Int) {
        if (position in 0 until keywords.size) {
            keywords.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, keywords.size - position)
        }
    }
    
    fun clear() {
        keywords.clear()
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeywordViewHolder {
        val binding = ItemKeywordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return KeywordViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: KeywordViewHolder, position: Int) {
        holder.bind(keywords[position], position)
    }
    
    override fun getItemCount(): Int = keywords.size
    
    inner class KeywordViewHolder(
        private val binding: ItemKeywordBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var textWatcher: TextWatcher? = null
        
        fun bind(keyword: String, position: Int) {
            binding.apply {
                // Remove previous text watcher to avoid conflicts
                textWatcher?.let { keywordEditText.removeTextChangedListener(it) }
                
                // Set current value
                keywordEditText.setText(keyword)
                
                // Validate and show errors
                updateValidation(keyword)
                
                // Add text watcher
                textWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < keywords.size) {
                            val newKeyword = s.toString()
                            keywords[currentPosition] = newKeyword
                            updateValidation(newKeyword)
                        }
                    }
                }
                
                keywordEditText.addTextChangedListener(textWatcher)
                
                deleteButton.setOnClickListener {
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        removeKeyword(currentPosition)
                    }
                }
            }
        }
        
        private fun updateValidation(keyword: String) {
            binding.apply {
                val trimmedKeyword = keyword.trim()
                
                when {
                    keyword.isEmpty() -> {
                        keywordInputLayout.error = null
                    }
                    trimmedKeyword != keyword -> {
                        keywordInputLayout.error = "Leading/trailing spaces will be removed"
                    }
                    trimmedKeyword.length < 2 -> {
                        keywordInputLayout.error = "Keyword must be at least 2 characters"
                    }
                    trimmedKeyword.length > 100 -> {
                        keywordInputLayout.error = "Keyword too long (max 100 characters)"
                    }
                    trimmedKeyword.contains(Regex("\\s{2,}")) -> {
                        keywordInputLayout.error = "Multiple spaces will be normalized"
                    }
                    !trimmedKeyword.matches(Regex("[\\w\\s-]+")) -> {
                        keywordInputLayout.error = "Use only letters, numbers, spaces, and hyphens"
                    }
                    else -> {
                        keywordInputLayout.error = null
                    }
                }
            }
        }
    }
}
