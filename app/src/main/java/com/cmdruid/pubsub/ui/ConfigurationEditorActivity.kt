package com.cmdruid.pubsub.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.cmdruid.pubsub.R
import com.cmdruid.pubsub.data.Configuration
import com.cmdruid.pubsub.data.ConfigurationManager
import com.cmdruid.pubsub.data.SettingsManager
import com.cmdruid.pubsub.service.PubSubService
import com.cmdruid.pubsub.databinding.ActivityConfigurationEditorBinding
import com.cmdruid.pubsub.nostr.NostrFilter
import com.cmdruid.pubsub.ui.adapters.RelayUrlAdapter
import com.cmdruid.pubsub.ui.adapters.TextEntryAdapter
import com.cmdruid.pubsub.ui.adapters.CustomTagAdapter
import com.cmdruid.pubsub.ui.adapters.HashtagsAdapter
import com.cmdruid.pubsub.ui.adapters.KeywordAdapter
import com.cmdruid.pubsub.data.HashtagEntry
import com.cmdruid.pubsub.data.KeywordFilter
import com.cmdruid.pubsub.utils.NostrUtils
import com.cmdruid.pubsub.utils.UriBuilder

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
    private lateinit var settingsManager: SettingsManager
    private lateinit var relayUrlAdapter: RelayUrlAdapter
    private lateinit var authorsAdapter: TextEntryAdapter
    private lateinit var kindsAdapter: TextEntryAdapter
    private lateinit var pubkeyRefsAdapter: TextEntryAdapter
    private lateinit var eventRefsAdapter: TextEntryAdapter
    private lateinit var hashtagsAdapter: HashtagsAdapter
    private lateinit var customTagsAdapter: CustomTagAdapter
    private lateinit var keywordsAdapter: KeywordAdapter
    
    private var configurationId: String? = null
    private var isEditMode = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigurationEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        configurationManager = ConfigurationManager(this)
        settingsManager = SettingsManager(this)
        configurationId = intent.getStringExtra(EXTRA_CONFIGURATION_ID)
        isEditMode = configurationId != null
        
        setupToolbar()
        setupRelayUrlsRecyclerView()
        setupFilterRecyclerViews()
        setupKeywordsRecyclerView()
        setupUI()
        
        if (isEditMode) {
            loadConfiguration()
        } else {
            // Add default relay and target URI from settings for new configurations
            relayUrlAdapter.addRelayUrl(settingsManager.getDefaultRelayServer())
            binding.targetUriEditText.setText(settingsManager.getDefaultEventViewer())
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
        
        // Hashtags adapter - for simple hashtag entries using "t" tag
        hashtagsAdapter = HashtagsAdapter()
        binding.hashtagsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = hashtagsAdapter
        }
        
        // Custom tags adapter - for advanced tag/value pairs
        customTagsAdapter = CustomTagAdapter()
        binding.customTagsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = customTagsAdapter
        }
    }
    
    private fun setupKeywordsRecyclerView() {
        keywordsAdapter = KeywordAdapter()
        binding.keywordsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ConfigurationEditorActivity)
            adapter = keywordsAdapter
        }
        
        // Set up visibility toggle based on content
        updateKeywordVisibility()
    }
    

    
    private fun setupUI() {
        binding.apply {
            // Add buttons for multi-entry fields
            addAuthorButton.setOnClickListener { authorsAdapter.addEntry() }
            addKindButton.setOnClickListener { kindsAdapter.addEntry() }
            addPubkeyRefButton.setOnClickListener { pubkeyRefsAdapter.addEntry() }
            addEventRefButton.setOnClickListener { eventRefsAdapter.addEntry() }
            addHashtagsButton.setOnClickListener { hashtagsAdapter.addHashtag() }
            addCustomTagButton.setOnClickListener { customTagsAdapter.addEntry() }
            addKeywordButton.setOnClickListener { 
                keywordsAdapter.addKeyword()
                updateKeywordVisibility()
            }
            
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
        
        // Separate hashtag entries into hashtags (t tag) and custom tags (other tags)
        val allHashtagEntries = filter.hashtagEntries ?: emptyList()
        val hashtagValues = allHashtagEntries.filter { it.tag.lowercase() == "t" }.map { it.value }
        val customTagEntries = allHashtagEntries.filter { it.tag.lowercase() != "t" }
        
        hashtagsAdapter.setHashtags(hashtagValues)
        customTagsAdapter.setEntries(customTagEntries)
        
        // Load keyword filter
        keywordsAdapter.setKeywords(configuration.keywordFilter?.keywords ?: emptyList())
        updateKeywordVisibility()
        
        // No simple fields to load (limit field removed)
    }
    
    private fun saveConfiguration() {
        val name = binding.nameEditText.text.toString().trim()
        val rawTargetUri = binding.targetUriEditText.text.toString().trim()
        
        // Validate basic fields
        if (name.isBlank()) {
            binding.nameEditText.error = "Name is required"
            return
        }
        
        if (rawTargetUri.isBlank()) {
            binding.targetUriEditText.error = "Target URI is required"
            return
        }
        
        if (!UriBuilder.isValidUri(rawTargetUri)) {
            binding.targetUriEditText.error = "Invalid URI format"
            return
        }
        
        // Normalize the target URI to handle trailing slashes consistently
        val targetUri = UriBuilder.normalizeTargetUri(rawTargetUri)
        
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
        
        // Build keyword filter
        val keywordFilter = buildKeywordFilter()
        
        // Create or update configuration
        val configuration = if (isEditMode) {
            // For edit mode, use the existing configurationId
            Configuration(
                id = configurationId ?: throw IllegalStateException("Configuration ID should not be null in edit mode"),
                name = name,
                relayUrls = relayUrls,
                filter = filter,
                targetUri = targetUri,
                keywordFilter = keywordFilter
            )
        } else {
            // For new configurations, generate a new ID
            Configuration(
                name = name,
                relayUrls = relayUrls,
                filter = filter,
                targetUri = targetUri,
                keywordFilter = keywordFilter
            )
        }
        
        if (isEditMode) {
            configurationManager.updateConfiguration(configuration)
            Toast.makeText(this, getString(R.string.subscription_updated), Toast.LENGTH_SHORT).show()
        } else {
            configurationManager.addConfiguration(configuration)
            Toast.makeText(this, getString(R.string.subscription_saved), Toast.LENGTH_SHORT).show()
        }
        
        // Notify service to sync configurations if it's running
        if (configurationManager.isServiceRunning) {
            syncServiceConfigurations()
        }
        
        finish()
    }
    
    /**
     * Sync service configurations with current enabled configurations
     */
    private fun syncServiceConfigurations() {
        try {
            val serviceIntent = Intent(this, PubSubService::class.java).apply {
                action = "SYNC_CONFIGURATIONS"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Silently handle errors - this is not critical for the UI flow
        }
    }
    
    private fun buildNostrFilter(): NostrFilter {
        // Get data from adapters
        val authors = authorsAdapter.getEntries().takeIf { it.isNotEmpty() }
        val kinds = kindsAdapter.getEntries().mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
        val pubkeyRefs = pubkeyRefsAdapter.getEntries().takeIf { it.isNotEmpty() }
        val eventRefs = eventRefsAdapter.getEntries().takeIf { it.isNotEmpty() }
        
        // Combine hashtags and custom tags into a single hashtagEntries list
        val allHashtagEntries = mutableListOf<HashtagEntry>()
        
        // Add hashtags as "t" tag entries
        hashtagsAdapter.getHashtags().forEach { hashtag ->
            allHashtagEntries.add(HashtagEntry("t", hashtag))
        }
        
        // Add custom tag entries
        allHashtagEntries.addAll(customTagsAdapter.getEntries())
        
        val hashtagEntries = allHashtagEntries.takeIf { it.isNotEmpty() }
        
        return NostrFilter(
            authors = authors,
            kinds = kinds,
            pubkeyRefs = pubkeyRefs,
            eventRefs = eventRefs,
            hashtagEntries = hashtagEntries,
            search = null, // Removed text search
            since = null, // Will be auto-updated by service
            until = null, // Removed time range
            limit = null // Removed limit field
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
    
    private fun buildKeywordFilter(): KeywordFilter? {
        val keywords = keywordsAdapter.getKeywords()
        return if (keywords.isNotEmpty()) {
            KeywordFilter.from(keywords)
        } else {
            null
        }
    }
    
    private fun updateKeywordVisibility() {
        binding.apply {
            if (keywordsAdapter.itemCount == 0) {
                keywordsRecyclerView.visibility = View.GONE
                noKeywordsText.visibility = View.VISIBLE
            } else {
                keywordsRecyclerView.visibility = View.VISIBLE
                noKeywordsText.visibility = View.GONE
            }
        }
    }
}
