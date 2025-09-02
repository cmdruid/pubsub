package com.cmdruid.pubsub.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

/**
 * Custom view for selecting single-letter tags using a dropdown spinner
 * Filters available letters: a-zA-Z excluding reserved tags (e, p, t)
 */
class TagSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : Spinner(context, attrs, defStyleAttr) {
    
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
    
    private var onTagSelectedListener: ((String) -> Unit)? = null
    
    init {
        // Ensure the spinner is properly interactive
        isClickable = true
        isFocusable = true
        setupPicker()
    }
    
    private fun setupPicker() {
        try {
            android.util.Log.d("TagSelectorView", "Setting up TagSelectorView as Spinner")
            
            // Create adapter for the spinner
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, AVAILABLE_LETTERS)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            setAdapter(adapter)
            
            // Set default selection to first available letter
            setSelection(0)
            
            // Add listener for selection changes
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position >= 0 && position < AVAILABLE_LETTERS.size) {
                        val selectedLetter = AVAILABLE_LETTERS[position]
                        android.util.Log.d("TagSelectorView", "Selected letter: $selectedLetter")
                        onTagSelectedListener?.invoke(selectedLetter)
                    }
                }
                
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    android.util.Log.d("TagSelectorView", "Nothing selected")
                }
            }
            
            android.util.Log.d("TagSelectorView", "TagSelectorView setup complete with ${AVAILABLE_LETTERS.size} letters")
            
            // Post a runnable to ensure the spinner is properly configured after layout
            post {
                android.util.Log.d("TagSelectorView", "Post-setup: isClickable=$isClickable, isFocusable=$isFocusable, adapter=${adapter?.count}")
            }
        } catch (e: Exception) {
            // Fallback if Spinner setup fails
            android.util.Log.e("TagSelectorView", "Failed to setup Spinner: ${e.message}", e)
        }
    }
    
    /**
     * Set the currently selected tag
     */
    fun setSelectedTag(tag: String) {
        val index = AVAILABLE_LETTERS.indexOf(tag)
        if (index >= 0) {
            setSelection(index)
        }
    }
    
    /**
     * Get the currently selected tag
     */
    fun getSelectedTag(): String {
        val position = selectedItemPosition
        return if (position >= 0 && position < AVAILABLE_LETTERS.size) {
            AVAILABLE_LETTERS[position]
        } else {
            AVAILABLE_LETTERS[0] // Default to first letter
        }
    }
    
    /**
     * Set listener for tag selection changes
     */
    fun setOnTagSelectedListener(listener: (String) -> Unit) {
        onTagSelectedListener = listener
    }
    
    /**
     * Get all available letters for reference
     */
    fun getAvailableLetters(): List<String> = AVAILABLE_LETTERS
}
