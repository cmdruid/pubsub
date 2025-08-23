package com.cmdruid.pubsub.utils

import android.util.Log
import java.security.MessageDigest
import java.util.*

/**
 * Utility functions for Nostr protocol operations
 */
object NostrUtils {
    
    private const val TAG = "NostrUtils"
    
    // Bech32 alphabet for encoding/decoding
    private val BECH32_ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    
    // Bech32 constants
    private const val BECH32_CONST = 1
    private val BECH32_GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    
    /**
     * Check if a string is a valid npub (Nostr public key in bech32 format)
     */
    fun isValidNpub(npub: String): Boolean {
        return npub.startsWith("npub1") && npub.length == 63
    }
    

    
    /**
     * Check if a string is a valid hex public key
     */
    fun isValidHexPubkey(pubkey: String): Boolean {
        return pubkey.length == 64 && pubkey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
    
    /**
     * Decode npub to hex public key
     * Simplified implementation - in production you'd want a full bech32 library
     */
    fun npubToHex(npub: String): String? {
        return try {
            if (!isValidNpub(npub)) return null
            
            // Use proper bech32 decoding
            val decoded = decodeBech32(npub) ?: return null
            if (decoded.first != "npub" || decoded.second.size != 32) return null
            
            decoded.second.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding npub: $npub", e)
            null
        }
    }
    
    /**
     * Encode hex public key to npub
     */
    fun hexToNpub(hex: String): String? {
        return try {
            if (!isValidHexPubkey(hex)) return null
            
            // Convert hex to bytes
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (bytes.size != 32) return null
            
            // Use standard bech32 encoding with npub HRP
            bech32Encode("npub", bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Error encoding hex to npub: $hex", e)
            null
        }
    }
    
    /**
     * Encode hex event ID to note (NIP-19) - simpler than nevent, no TLV needed
     */
    fun hexToNote(hex: String): String? {
        return try {
            if (hex.length != 64 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                return null
            }
            
            // Convert hex to bytes
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (bytes.size != 32) return null
            
            // Use simple bech32 encoding for note (no TLV structure needed)
            bech32Encode("note", bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Error encoding hex to note: $hex", e)
            null
        }
    }
    

    
    /**
     * Decode note to hex event ID
     */
    fun noteToHex(note: String): String? {
        return try {
            if (!isValidNote(note)) return null
            
            // Use proper bech32 decoding
            val decoded = decodeBech32(note) ?: return null
            if (decoded.first != "note" || decoded.second.size != 32) return null
            
            decoded.second.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding note: $note", e)
            null
        }
    }
    
    /**
     * Check if a string is a valid note (Nostr event ID in bech32 format)
     */
    fun isValidNote(note: String): Boolean {
        return note.startsWith("note1") && note.length >= 50 && note.length <= 100
    }
    

    
    /**
     * Normalize a public key - if it's an npub, convert to hex, otherwise return as-is if valid hex
     */
    fun normalizePublicKey(input: String): String? {
        val trimmed = input.trim()
        return when {
            isValidNpub(trimmed) -> npubToHex(trimmed)
            isValidHexPubkey(trimmed) -> trimmed
            else -> null
        }
    }
    
    /**
     * Get current Unix timestamp
     */
    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis() / 1000
    }
    
    /**
     * Format Unix timestamp to readable date/time
     */
    fun formatTimestamp(timestamp: Long): String {
        return try {
            val date = Date(timestamp * 1000)
            java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            "Invalid timestamp"
        }
    }
    
    /**
     * Validate if a string is a valid Unix timestamp
     */
    fun isValidTimestamp(timestampStr: String): Boolean {
        return try {
            val timestamp = timestampStr.toLong()
            timestamp > 0 && timestamp < 4000000000L // Reasonable range
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Proper bech32 decoding with checksum verification
     */
    private fun decodeBech32(bech32String: String): Pair<String, ByteArray>? {
        return try {
            val pos = bech32String.lastIndexOf('1')
            if (pos < 1 || pos + 7 > bech32String.length || pos + 1 + 6 > bech32String.length) {
                return null
            }
            
            val hrp = bech32String.substring(0, pos)
            val data = bech32String.substring(pos + 1)
            
            // Convert characters to values
            val values = data.map { char ->
                val index = BECH32_ALPHABET.indexOf(char.lowercaseChar())
                if (index == -1) return null
                index
            }.toIntArray()
            
            // Verify checksum
            if (!bech32VerifyChecksum(hrp, values)) {
                return null
            }
            
            // Remove checksum (last 6 characters)
            val dataValues = values.sliceArray(0 until values.size - 6)
            
            // Convert from 5-bit to 8-bit
            val decoded = convertBits(dataValues.map { it.toByte() }.toByteArray(), 5, 8, false)
                ?: return null
            
            Pair(hrp, decoded.map { it.toByte() }.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "Error in bech32 decoding", e)
            null
        }
    }
    
    /**
     * Verify bech32 checksum
     */
    private fun bech32VerifyChecksum(hrp: String, data: IntArray): Boolean {
        val hrpExpanded = hrpExpand(hrp)
        val combined = hrpExpanded + data
        val polymod = bech32Polymod(combined)
        return polymod == BECH32_CONST
    }
    
    /**
     * Calculate bech32 polymod for checksum verification
     */
    private fun bech32Polymod(values: IntArray): Int {
        var chk = 1
        for (value in values) {
            val top = chk shr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            for (i in 0..4) {
                chk = chk xor if ((top shr i) and 1 != 0) BECH32_GENERATOR[i] else 0
            }
        }
        return chk
    }
    
    /**
     * Legacy simplified decoding method (kept for compatibility)
     */
    private fun decodeBech32Data(data: String): ByteArray? {
        val decoded = decodeBech32("1$data") ?: return null
        return decoded.second
    }
    
        /**
     * Standard bech32 encoding function
     */
    private fun bech32Encode(hrp: String, data: ByteArray): String? {
        return try {
            // Convert 8-bit data to 5-bit groups
            val fiveBitData = convertBits(data, 8, 5, true) ?: return null
            
            // Create the data part with checksum
            val values = fiveBitData.toMutableList()
            val hrpExpanded = hrpExpand(hrp)
            val checksum = bech32CreateChecksum(hrpExpanded + values.toIntArray() + IntArray(6) { 0 })
            values.addAll(checksum.toList())
            
            // Build the final bech32 string: hrp + "1" + data + checksum
            val result = hrp + "1" + values.map { BECH32_ALPHABET[it] }.joinToString("")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Error in bech32 encoding", e)
            null
        }
    }
    
    /**
     * Legacy encoding method for compatibility
     */
    private fun encodeBech32(hrp: String, data: ByteArray): String {
        return bech32Encode(hrp, data) ?: ""
    }
    
    /**
     * Convert between bit groups
     */
    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            if (value < 0 || value shr fromBits != 0) return null
            
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }
        
        if (pad) {
            if (bits > 0) {
                result.add((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        
        return result.toIntArray()
    }
    
    /**
     * Expand HRP for checksum calculation
     */
    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            result[i] = hrp[i].code shr 5
        }
        result[hrp.length] = 0
        for (i in hrp.indices) {
            result[i + hrp.length + 1] = hrp[i].code and 31
        }
        return result
    }
    
    /**
     * Calculate bech32 checksum
     */
    private fun bech32CreateChecksum(values: IntArray): IntArray {
        var chk = 1  // Always start with 1
        for (value in values) {
            val top = chk shr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            for (i in 0..4) {
                chk = chk xor if ((top shr i) and 1 != 0) BECH32_GENERATOR[i] else 0
            }
        }
        // XOR with BECH32_CONST at the end
        chk = chk xor BECH32_CONST
        return IntArray(6) { (chk shr (5 * (5 - it))) and 31 }
    }
    
    /**
     * Legacy simplified encoding method (kept for compatibility)
     */
    private fun encodeBech32Data(data: ByteArray): String {
        return encodeBech32("", data)
    }
}
