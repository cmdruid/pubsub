package com.example.pubsub.ui.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.pubsub.databinding.ItemRelayUrlBinding

class RelayUrlAdapter : RecyclerView.Adapter<RelayUrlAdapter.RelayUrlViewHolder>() {
    
    private var relayUrls = mutableListOf<String>()
    
    fun setRelayUrls(urls: MutableList<String>) {
        relayUrls = urls
        notifyDataSetChanged()
    }
    
    fun getRelayUrls(): List<String> {
        return relayUrls.toList()
    }
    
    fun addRelayUrl(url: String) {
        relayUrls.add(url)
        notifyItemInserted(relayUrls.size - 1)
    }
    
    fun removeRelayUrl(position: Int) {
        if (position in 0 until relayUrls.size) {
            relayUrls.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, relayUrls.size - position)
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RelayUrlViewHolder {
        val binding = ItemRelayUrlBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RelayUrlViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: RelayUrlViewHolder, position: Int) {
        holder.bind(relayUrls[position], position)
    }
    
    override fun getItemCount(): Int = relayUrls.size
    
    inner class RelayUrlViewHolder(
        private val binding: ItemRelayUrlBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var textWatcher: TextWatcher? = null
        
        fun bind(url: String, position: Int) {
            binding.apply {
                // Remove previous text watcher to avoid conflicts
                textWatcher?.let { relayUrlEditText.removeTextChangedListener(it) }
                
                relayUrlEditText.setText(url)
                
                // Add new text watcher
                textWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val currentPosition = adapterPosition
                        if (currentPosition != RecyclerView.NO_POSITION && currentPosition < relayUrls.size) {
                            relayUrls[currentPosition] = s.toString()
                        }
                    }
                }
                relayUrlEditText.addTextChangedListener(textWatcher)
                
                deleteButton.setOnClickListener {
                    val currentPosition = adapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        removeRelayUrl(currentPosition)
                    }
                }
            }
        }
    }
}
