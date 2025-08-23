package com.example.pubsub.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pubsub.R
import com.example.pubsub.data.Configuration
import com.example.pubsub.data.ConfigurationManager
import com.example.pubsub.databinding.ActivityConfigurationEditorBinding
import com.example.pubsub.nostr.NostrFilter
import com.example.pubsub.ui.adapters.RelayUrlAdapter
import com.example.pubsub.ui.adapters.TextEntryAdapter
import com.example.pubsub.utils.NostrUtils
import com.example.pubsub.utils.UriBuilder

class ConfigurationEditorActivity : AppCompatActivity() {
    
    companion object {
        private const val EXTRA_CONFIGURATION_ID = "configuration_id"
        
        fun createIntent(context: Context, configurationId: String? = null): Intent {
            return Intent(context, ConfigurationEditorActivity::class.java).apply {
                configurationId?.let { putExtra(EXTRA_CONFIGURATION_ID, it) }
            }
        }
    }
    
    private lateinit var binding: ActivityConfigurationEditorBinding
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var relayUrlAdapter: RelayUrlAdapter
    private lateinit var authorsAdapter: TextEntryAdapter
    private lateinit var kindsAdapter: TextEntryAdapter
    private lateinit var pubkeyRefsAdapter: TextEntryAdapter
    private lateinit var eventRefsAdapter: TextEntryAdapter
    private lateinit var hashtagsAdapter: TextEntryAdapter
    
    private var configurationId: String? = null
    private var isEditMode = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigurationEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        configurationManager = ConfigurationManager(this)
        configurationId = intent.getStringExtra(EXTRA_CONFIGURATION_ID)
        isEditMode = configurationId != null
        
        setupToolbar()
        setupRelayUrlsRecyclerView()
        setupFilterRecyclerViews()
        setupTimestampFields()
        setupUI()
        
        if (isEditMode) {
            loadConfiguration()
        } else {
            // Add default relay and target URI for new configurations
            relayUrlAdapter.addRelayUrl("wss://relay.damus.io")
            binding.targetUriEditText.setText("https://njump.me")
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = if (isEditMode) getString(R.string.edit_subscription) else getString(R.string.new_subscription)
        }
    }
    
    private fun setupRelayUrlsRecyclerView() {
        relayUrlAdapter = RelayUrlAdapter()
        binding.relayUrlsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = relayUrlAdapter
        }
        
        binding.addRelayButton.setOnClickListener {
            relayUrlAdapter.addRelayUrl("")
        }
    }
    
    private fun setupFilterRecyclerViews() {
        // Authors adapter with public key validation
        authorsAdapter = TextEntryAdapter(
            hint = "Public key (hex or npub)",
            validator = TextEntryAdapter.createPublicKeyValidator()
        )
        binding.authorsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = authorsAdapter
        }
        
        // Event kinds adapter with integer validation
        kindsAdapter = TextEntryAdapter(
            hint = "Event kind (number)",
            validator = TextEntryAdapter.createEventKindValidator()
        )
        binding.kindsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = kindsAdapter
        }
        
        // Pubkey refs adapter with public key validation
        pubkeyRefsAdapter = TextEntryAdapter(
            hint = "Public key (hex or npub)",
            validator = TextEntryAdapter.createPublicKeyValidator()
        )
        binding.pubkeyRefsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = pubkeyRefsAdapter
        }
        
        // Event refs adapter
        eventRefsAdapter = TextEntryAdapter(hint = "Event ID (hex)")
        binding.eventRefsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = eventRefsAdapter
        }
        
        // Hashtags adapter with hashtag validation
        hashtagsAdapter = TextEntryAdapter(
            hint = "Hashtag (without #)",
            validator = TextEntryAdapter.createHashtagValidator()
        )
        binding.hashtagsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = hashtagsAdapter
        }
    }
    
    private fun setupTimestampFields() {
        // Since field timestamp display
        binding.sinceEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateTimestampDisplay(s.toString(), binding.sinceDateText)
            }
        })
        
        // Until field timestamp display
        binding.untilEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateTimestampDisplay(s.toString(), binding.untilDateText)
            }
        })
        
        // Now buttons
        binding.sinceNowButton.setOnClickListener {
            val now = NostrUtils.getCurrentTimestamp()
            binding.sinceEditText.setText(now.toString())
        }
        
        binding.untilNowButton.setOnClickListener {
            val now = NostrUtils.getCurrentTimestamp()
            binding.untilEditText.setText(now.toString())
        }
    }
    
    private fun updateTimestampDisplay(timestampStr: String, textView: android.widget.TextView) {
        if (NostrUtils.isValidTimestamp(timestampStr)) {
            val timestamp = timestampStr.toLong()
            textView.text = NostrUtils.formatTimestamp(timestamp)
            textView.visibility = View.VISIBLE
        } else {
            textView.visibility = View.GONE
        }
    }
    
    private fun setupUI() {
        binding.apply {
            // Add buttons for multi-entry fields
            addAuthorButton.setOnClickListener { authorsAdapter.addEntry() }
            addKindButton.setOnClickListener { kindsAdapter.addEntry() }
            addPubkeyRefButton.setOnClickListener { pubkeyRefsAdapter.addEntry() }
            addEventRefButton.setOnClickListener { eventRefsAdapter.addEntry() }
            addHashtagButton.setOnClickListener { hashtagsAdapter.addEntry() }
            
            saveButton.setOnClickListener {
                saveConfiguration()
            }
        }
    }
    
    private fun loadConfiguration() {
        val configurations = configurationManager.getConfigurations()
        val configuration = configurations.find { it.id == configurationId }
        
        if (configuration == null) {
            Toast.makeText(this, "Subscription not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        // Load basic configuration
        binding.nameEditText.setText(configuration.name)
        binding.targetUriEditText.setText(configuration.targetUri)
        

        
        // Load relay URLs
        relayUrlAdapter.setRelayUrls(configuration.relayUrls.toMutableList())
        
        // Load filter data into adapters
        val filter = configuration.filter
        authorsAdapter.setEntries(filter.authors ?: emptyList())
        kindsAdapter.setEntries(filter.kinds?.map { it.toString() } ?: emptyList())
        pubkeyRefsAdapter.setEntries(filter.pubkeyRefs ?: emptyList())
        eventRefsAdapter.setEntries(filter.eventRefs ?: emptyList())
        hashtagsAdapter.setEntries(filter.hashtags ?: emptyList())
        
        // Load simple fields
        binding.searchEditText.setText(filter.search ?: "")
        binding.sinceEditText.setText(filter.since?.toString() ?: "")
        binding.untilEditText.setText(filter.until?.toString() ?: "")
        binding.limitEditText.setText(filter.limit?.toString() ?: "")
    }
    
    private fun saveConfiguration() {
        val name = binding.nameEditText.text.toString().trim()
        val targetUri = binding.targetUriEditText.text.toString().trim()
        
        // Validate basic fields
        if (name.isBlank()) {
            binding.nameEditText.error = "Name is required"
            return
        }
        
        if (targetUri.isBlank()) {
            binding.targetUriEditText.error = "Target URI is required"
            return
        }
        
        if (!UriBuilder.isValidUri(targetUri)) {
            binding.targetUriEditText.error = "Invalid URI format"
            return
        }
        
        // Get relay URLs
        val relayUrls = relayUrlAdapter.getRelayUrls().filter { it.isNotBlank() }
        if (relayUrls.isEmpty()) {
            Toast.makeText(this, "At least one relay URL is required", Toast.LENGTH_LONG).show()
            return
        }
        
        // Build filter
        val filter = buildNostrFilter()
        if (filter.isEmpty()) {
            Toast.makeText(this, "At least one filter criteria is required", Toast.LENGTH_LONG).show()
            return
        }
        
        // Create or update configuration
        val configuration = Configuration(
            id = configurationId ?: "",
            name = name,
            relayUrls = relayUrls,
            filter = filter,
            targetUri = targetUri
        )
        
        if (isEditMode) {
            configurationManager.updateConfiguration(configuration)
            Toast.makeText(this, getString(R.string.subscription_updated), Toast.LENGTH_SHORT).show()
        } else {
            configurationManager.addConfiguration(configuration)
            Toast.makeText(this, getString(R.string.subscription_saved), Toast.LENGTH_SHORT).show()
        }
        
        finish()
    }
    
    private fun buildNostrFilter(): NostrFilter {
        // Get data from adapters
        val authors = authorsAdapter.getEntries().takeIf { it.isNotEmpty() }
        val kinds = kindsAdapter.getEntries().mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
        val pubkeyRefs = pubkeyRefsAdapter.getEntries().takeIf { it.isNotEmpty() }
        val eventRefs = eventRefsAdapter.getEntries().takeIf { it.isNotEmpty() }
        val hashtags = hashtagsAdapter.getEntries().takeIf { it.isNotEmpty() }
        
        // Get simple field values
        val search = binding.searchEditText.text.toString().trim().takeIf { it.isNotBlank() }
        val since = binding.sinceEditText.text.toString().trim().toLongOrNull()
        val until = binding.untilEditText.text.toString().trim().toLongOrNull()
        val limit = binding.limitEditText.text.toString().trim().toIntOrNull()
        
        return NostrFilter(
            authors = authors,
            kinds = kinds,
            pubkeyRefs = pubkeyRefs,
            eventRefs = eventRefs,
            hashtags = hashtags,
            search = search,
            since = since,
            until = until,
            limit = limit
        )
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
