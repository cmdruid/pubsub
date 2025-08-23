package com.example.pubsub.ui.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.pubsub.R
import java.text.SimpleDateFormat
import java.util.*

class DebugLogAdapter : RecyclerView.Adapter<DebugLogAdapter.DebugLogViewHolder>() {
    
    private var logs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    fun setLogs(newLogs: List<String>) {
        logs.clear()
        logs.addAll(newLogs)
        notifyDataSetChanged()
    }
    
    fun addLog(log: String) {
        logs.add(0, log) // Add to beginning
        notifyItemInserted(0)
        
        // Keep only last 100 logs
        if (logs.size > 100) {
            logs.removeAt(logs.size - 1)
            notifyItemRemoved(logs.size)
        }
    }
    
    fun clearLogs() {
        logs.clear()
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DebugLogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_debug_log, parent, false)
        return DebugLogViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DebugLogViewHolder, position: Int) {
        holder.bind(logs[position])
    }
    
    override fun getItemCount(): Int = logs.size
    
    inner class DebugLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        
        fun bind(log: String) {
            // Parse timestamp and message
            val parts = log.split(": ", limit = 2)
            if (parts.size == 2) {
                try {
                    val timestamp = parts[0].toLong()
                    val message = parts[1]
                    val timeString = dateFormat.format(Date(timestamp))
                    
                    timestampText.text = timeString
                    messageText.text = message
                    
                    // Set up click listener for copying to clipboard
                    itemView.setOnClickListener {
                        val fullText = "[$timeString] $message"
                        copyToClipboard(itemView.context, fullText)
                        Toast.makeText(itemView.context, "Debug log copied", Toast.LENGTH_SHORT).show()
                    }
                    
                } catch (e: NumberFormatException) {
                    timestampText.text = ""
                    messageText.text = log
                    
                    itemView.setOnClickListener {
                        copyToClipboard(itemView.context, log)
                        Toast.makeText(itemView.context, "Debug log copied", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                timestampText.text = ""
                messageText.text = log
                
                itemView.setOnClickListener {
                    copyToClipboard(itemView.context, log)
                    Toast.makeText(itemView.context, "Debug log copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        private fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Debug Log", text)
            clipboard.setPrimaryClip(clip)
        }
    }
}
