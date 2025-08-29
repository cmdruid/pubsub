package com.cmdruid.pubsub.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages import and export of subscription configurations
 */
class ImportExportManager(
    private val context: Context,
    private val configurationManager: ConfigurationManager
) {
    
    companion object {
        private const val TAG = "ImportExportManager"
        private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024 // 10MB limit
        private const val MAX_SUBSCRIPTIONS = 100 // Reasonable limit
    }
    
    private val gson = Gson()
    
    /**
     * Export current configurations to JSON
     */
    suspend fun exportConfigurations(outputUri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val configurations = configurationManager.getConfigurations()
            
            if (configurations.isEmpty()) {
                return@withContext ExportResult.Error("No subscriptions to export. Create some subscriptions first.")
            }
            
            // Get app version from package manager
            val appVersion = try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
            
            val exportData = ExportData.create(configurations, appVersion)
            val json = gson.toJson(exportData)
            
            // Write to file
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream)).use { writer ->
                    writer.write(json)
                    writer.flush()
                }
            } ?: return@withContext ExportResult.Error("Could not open file for writing")
            
            Log.d(TAG, "Exported ${configurations.size} subscriptions to ${outputUri.lastPathSegment}")
            
            ExportResult.Success(
                filename = outputUri.lastPathSegment ?: "backup.json",
                subscriptionCount = configurations.size
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            ExportResult.Error("Export failed: ${e.message}", e)
        }
    }
    
    /**
     * Import configurations from JSON file
     */
    suspend fun importConfigurations(
        inputUri: Uri, 
        importMode: ImportMode = ImportMode.ADD_NEW_ONLY
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            // Read and validate file
            val json = readJsonFromUri(inputUri)
            val validationResult = validateImportFile(json)
            
            if (validationResult is ValidationResult.Invalid) {
                return@withContext ImportResult.Error(
                    "Invalid file format:\n${validationResult.errors.joinToString("\n")}"
                )
            }
            
            // Parse export data
            val exportData = gson.fromJson(json, ExportData::class.java)
            val existingConfigurations = configurationManager.getConfigurations()
            
            // Handle duplicates based on import mode
            val result = when (importMode) {
                ImportMode.ADD_NEW_ONLY -> importAddNewOnly(exportData, existingConfigurations)
                ImportMode.REPLACE_ALL -> importReplaceAll(exportData)
                ImportMode.REPLACE_DUPLICATES -> importReplaceDuplicates(exportData, existingConfigurations)
            }
            
            Log.d(TAG, "Import completed: ${result}")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportResult.Error("Import failed: ${e.message}", e)
        }
    }
    
    /**
     * Validate import file without importing
     */
    suspend fun validateImportFile(inputUri: Uri): ValidationResult = withContext(Dispatchers.IO) {
        try {
            val json = readJsonFromUri(inputUri)
            validateImportFile(json)
        } catch (e: Exception) {
            ValidationResult.Invalid(listOf("Could not read file: ${e.message}"))
        }
    }
    
    /**
     * Get preview of what will be imported
     */
    suspend fun getImportPreview(inputUri: Uri): ImportPreview? = withContext(Dispatchers.IO) {
        try {
            val json = readJsonFromUri(inputUri)
            val validationResult = validateImportFile(json)
            
            if (validationResult is ValidationResult.Invalid) {
                return@withContext null
            }
            
            val exportData = gson.fromJson(json, ExportData::class.java)
            val existingConfigurations = configurationManager.getConfigurations()
            val existingNames = existingConfigurations.map { it.name.lowercase() }.toSet()
            
            val newSubscriptions = mutableListOf<String>()
            val duplicateSubscriptions = mutableListOf<String>()
            
            exportData.subscriptions.forEach { sub ->
                if (sub.name.lowercase() in existingNames) {
                    duplicateSubscriptions.add(sub.name)
                } else {
                    newSubscriptions.add(sub.name)
                }
            }
            
            ImportPreview(
                totalSubscriptions = exportData.subscriptions.size,
                newSubscriptions = newSubscriptions,
                duplicateSubscriptions = duplicateSubscriptions,
                version = exportData.version,
                date = exportData.date
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Preview generation failed", e)
            null
        }
    }
    
    /**
     * Generate filename for export
     */
    fun generateExportFilename(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
        val timestamp = dateFormat.format(Date())
        return "pubsub_backup_$timestamp.json"
    }
    
    // Private helper methods
    
    private fun readJsonFromUri(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Could not open file for reading")
        
        return inputStream.use { stream ->
            // Check file size
            val fileSize = stream.available()
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                throw Exception("File too large (${fileSize / 1024 / 1024}MB). Maximum size is ${MAX_FILE_SIZE_BYTES / 1024 / 1024}MB.")
            }
            
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
        }
    }
    
    private fun validateImportFile(json: String): ValidationResult {
        return try {
            val exportData = gson.fromJson(json, ExportData::class.java)
                ?: return ValidationResult.Invalid(listOf("Invalid JSON format"))
            
            val errors = mutableListOf<String>()
            
            // Check required fields
            if (exportData.version.isBlank()) {
                errors.add("Missing version field")
            }
            
            if (exportData.date.isBlank()) {
                errors.add("Missing date field")
            }
            
            // Check subscription count
            if (exportData.subscriptions.size > MAX_SUBSCRIPTIONS) {
                errors.add("Too many subscriptions (${exportData.subscriptions.size}). Maximum allowed: $MAX_SUBSCRIPTIONS")
            }
            
            // Validate each subscription
            exportData.subscriptions.forEachIndexed { index, subscription ->
                val subscriptionErrors = subscription.validate()
                subscriptionErrors.forEach { error ->
                    errors.add("Subscription ${index + 1} ('${subscription.name}'): $error")
                }
            }
            
            // Check for duplicate names within the import
            val names = exportData.subscriptions.map { it.name.lowercase() }
            val duplicateNames = names.groupBy { it }.filter { it.value.size > 1 }.keys
            duplicateNames.forEach { name ->
                errors.add("Duplicate subscription name in import file: '$name'")
            }
            
            if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
            
        } catch (e: JsonSyntaxException) {
            ValidationResult.Invalid(listOf("Invalid JSON format: ${e.message}"))
        } catch (e: Exception) {
            ValidationResult.Invalid(listOf("File validation error: ${e.message}"))
        }
    }
    
    private fun importAddNewOnly(
        exportData: ExportData, 
        existingConfigurations: List<Configuration>
    ): ImportResult {
        val existingNames = existingConfigurations.map { it.name.lowercase() }.toSet()
        val newSubscriptions = exportData.subscriptions.filter { 
            it.name.lowercase() !in existingNames 
        }
        
        var importedCount = 0
        newSubscriptions.forEach { exportableSubscription ->
            try {
                val configuration = exportableSubscription.toConfiguration()
                configurationManager.addConfiguration(configuration)
                importedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import subscription '${exportableSubscription.name}': ${e.message}")
            }
        }
        
        val duplicateCount = exportData.subscriptions.size - newSubscriptions.size
        
        return ImportResult.Success(
            importedCount = importedCount,
            duplicateCount = duplicateCount,
            skippedCount = duplicateCount
        )
    }
    
    private fun importReplaceAll(exportData: ExportData): ImportResult {
        // Clear all existing configurations
        val existingConfigurations = configurationManager.getConfigurations()
        existingConfigurations.forEach { config ->
            configurationManager.deleteConfiguration(config.id)
        }
        
        // Import all new configurations
        var importedCount = 0
        exportData.subscriptions.forEach { exportableSubscription ->
            try {
                val configuration = exportableSubscription.toConfiguration()
                configurationManager.addConfiguration(configuration)
                importedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import subscription '${exportableSubscription.name}': ${e.message}")
            }
        }
        
        return ImportResult.Success(
            importedCount = importedCount,
            duplicateCount = 0,
            skippedCount = 0
        )
    }
    
    private fun importReplaceDuplicates(
        exportData: ExportData,
        existingConfigurations: List<Configuration>
    ): ImportResult {
        val existingByName = existingConfigurations.associateBy { it.name.lowercase() }
        
        var importedCount = 0
        var replacedCount = 0
        
        exportData.subscriptions.forEach { exportableSubscription ->
            try {
                val existingConfig = existingByName[exportableSubscription.name.lowercase()]
                val newConfiguration = exportableSubscription.toConfiguration()
                
                if (existingConfig != null) {
                    // Replace existing configuration (preserve ID)
                    val updatedConfig = newConfiguration.copy(id = existingConfig.id)
                    configurationManager.updateConfiguration(updatedConfig)
                    replacedCount++
                } else {
                    // Add new configuration
                    configurationManager.addConfiguration(newConfiguration)
                }
                importedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import subscription '${exportableSubscription.name}': ${e.message}")
            }
        }
        
        return ImportResult.Success(
            importedCount = importedCount,
            duplicateCount = replacedCount,
            skippedCount = 0
        )
    }
}
