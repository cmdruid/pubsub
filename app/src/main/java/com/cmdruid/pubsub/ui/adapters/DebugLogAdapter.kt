package com.cmdruid.pubsub.ui.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.logging.StructuredLogEntry
import com.cmdruid.pubsub.logging.LogType
import com.cmdruid.pubsub.logging.LogFilter
import java.text.SimpleDateFormat
import java.util.*

class DebugLogAdapter : RecyclerView.Adapter<DebugLogAdapter.DebugLogViewHolder>() {
    
    private var allLogs = mutableListOf<StructuredLogEntry>()
    private var filteredLogs = mutableListOf<StructuredLogEntry>()
    private var currentFilter = LogFilter.DEFAULT
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    fun setLogs(newLogs: List<StructuredLogEntry>) {
        allLogs.clear()
        allLogs.addAll(newLogs)
        
        // Apply filter and respect max logs limit
        filteredLogs.clear()
        val filtered = allLogs.filter { currentFilter.passes(it) }
        filteredLogs.addAll(filtered.take(currentFilter.maxLogs))
        notifyDataSetChanged()
    }
    
    fun addLog(log: StructuredLogEntry) {
        allLogs.add(0, log) // Add to beginning
        
        // Keep only last 1000 logs in memory (more than max display to allow filtering)
        if (allLogs.size > 1000) {
            allLogs.removeAt(allLogs.size - 1)
        }
        
        // Apply filter to ensure consistency
        applyFilter()
    }
    
    fun setFilter(filter: LogFilter) {
        currentFilter = filter
        applyFilter()
    }
    
    fun clearLogs() {
        allLogs.clear()
        filteredLogs.clear()
        notifyDataSetChanged()
    }
    
    private fun applyFilter() {
        val newFilteredLogs = allLogs.filter { currentFilter.passes(it) }.take(currentFilter.maxLogs)
        
        // Only update if the filtered list actually changed
        if (newFilteredLogs != filteredLogs) {
            filteredLogs.clear()
            filteredLogs.addAll(newFilteredLogs)
            notifyDataSetChanged()
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebugLogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debug_log, parent, false)
        return DebugLogViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DebugLogViewHolder, position: Int) {
        holder.bind(filteredLogs[position])
    }
    
    override fun getItemCount(): Int = filteredLogs.size
    
    inner class DebugLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val emojiRegex = Regex("^[🔋⚡🌐📡📨🔔🩺📋🖥️⚙️✅❌⏰🏓🆔🏥⏸️🧪🔄🛑🚀🧹📊🔍🔒🔓🌙📱🔌⚠️🗑️️]+\\s*")
        
        fun bind(entry: StructuredLogEntry) {
            // Fast timestamp formatting
            timestampText.text = dateFormat.format(Date(entry.timestamp))
            
            // Strip any emoji prefixes from the message to avoid double icons
            val cleanMessage = entry.message.replace(emojiRegex, "")
            
            // Pre-build the message string for better performance
            messageText.text = "${entry.type.icon} ${entry.domain.icon} $cleanMessage"
            
            // Set color directly without method call overhead
            messageText.setTextColor(getTypeColor(entry.type))
            
            // Set up click listener for copying to clipboard (only if not already set)
            if (!itemView.hasOnClickListeners()) {
                itemView.setOnClickListener {
                    val fullText = entry.toDisplayString()
                    copyToClipboard(itemView.context, fullText)
                    Toast.makeText(itemView.context, "Debug log copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        private fun getTypeColor(type: LogType): Int {
            return when (type) {
                LogType.ERROR -> 0xFFF44336.toInt() // Red
                LogType.WARN -> 0xFFFF9800.toInt()  // Orange
                LogType.INFO -> 0xFF2196F3.toInt()  // Blue
                LogType.DEBUG -> 0xFF9E9E9E.toInt() // Gray
                LogType.TRACE -> 0xFF607D8B.toInt() // Blue Gray
            }
        }
        
        private fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Debug Log", text)
            clipboard.setPrimaryClip(clip)
        }
    }
}
