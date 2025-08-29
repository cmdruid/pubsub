package com.cmdruid.pubsub.ui.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cmdruid.pubsub.databinding.ItemHashtagBinding

/**
 * Adapter for managing simple hashtags that use the "t" tag internally
 * This is specifically for the "Hash Tags" section which accepts single words only
 * (as opposed to CustomTagAdapter which handles arbitrary tag/value pairs)
 */
class HashtagsAdapter : RecyclerView.Adapter<HashtagsAdapter.HashtagsViewHolder>() {
    
    private var hashtags = mutableListOf<String>()
    
    fun setHashtags(newHashtags: List<String>) {
        hashtags.clear()
        hashtags.addAll(newHashtags)
        notifyDataSetChanged()
    }
    
    /**
     * Get all valid hashtag entries (single words only)
     */
    fun getHashtags(): List<String> {
        return hashtags.filter { it.trim().isNotBlank() && isValidHashtag(it.trim()) }
    }
    
    fun addHashtag(hashtag: String = "") {
        hashtags.add(hashtag)
        notifyItemInserted(hashtags.size - 1)
    }
    
    fun removeHashtag(position: Int) {
        if (position in 0 until hashtags.size) {
            hashtags.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, hashtags.size - position)
        }
    }
    
    fun clear() {
        hashtags.clear()
        notifyDataSetChanged()
    }
    
    private fun isValidHashtag(hashtag: String): Boolean {
        // Single word validation - no spaces, must be alphanumeric with hyphens/underscores allowed
        return hashtag.matches(Regex("[a-zA-Z0-9_-]+"))
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HashtagsViewHolder {
        val binding = ItemHashtagBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HashtagsViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: HashtagsViewHolder, position: Int) {
        holder.bind(hashtags[position], position)
    }
    
    override fun getItemCount(): Int = hashtags.size
    
    inner class HashtagsViewHolder(
        private val binding: ItemHashtagBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var textWatcher: TextWatcher? = null
        
        fun bind(hashtag: String, position: Int) {
            binding.apply {
                // Remove previous text watcher to avoid conflicts
                textWatcher?.let { hashtagEditText.removeTextChangedListener(it) }
                
                // Set current value
                hashtagEditText.setText(hashtag)
                
                // Validate and show errors
                updateValidation(hashtag)
                
                // Add text watcher
                textWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = bindingAdapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < hashtags.size) {
                            val newHashtag = s.toString()
                            hashtags[currentPosition] = newHashtag
                            updateValidation(newHashtag)
                        }
                    }
                }
                
                hashtagEditText.addTextChangedListener(textWatcher)
                
                deleteButton.setOnClickListener {
                    val currentPosition = bindingAdapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        removeHashtag(currentPosition)
                    }
                }
            }
        }
        
        private fun updateValidation(hashtag: String) {
            binding.apply {
                val trimmedHashtag = hashtag.trim()
                
                when {
                    hashtag.isEmpty() -> {
                        hashtagInputLayout.error = null
                    }
                    trimmedHashtag != hashtag -> {
                        hashtagInputLayout.error = "Leading/trailing spaces will be removed"
                    }
                    trimmedHashtag.contains(' ') -> {
                        hashtagInputLayout.error = "Hashtags must be single words (no spaces)"
                    }
                    trimmedHashtag.length < 2 -> {
                        hashtagInputLayout.error = "Hashtag must be at least 2 characters"
                    }
                    trimmedHashtag.length > 50 -> {
                        hashtagInputLayout.error = "Hashtag too long (max 50 characters)"
                    }
                    !isValidHashtag(trimmedHashtag) -> {
                        hashtagInputLayout.error = "Use only letters, numbers, hyphens, and underscores"
                    }
                    else -> {
                        hashtagInputLayout.error = null
                    }
                }
            }
        }
    }
}
