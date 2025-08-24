package com.cmdruid.pubsub.nostr

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Handles Nostr key pair operations including generation, validation, and conversion
 */
class KeyPair private constructor(
    val privateKey: ByteArray?,
    val publicKey: ByteArray
) {
    
    companion object {
        private const val TAG = "KeyPair"
        private const val KEY_SIZE = 32 // 256 bits
        
        /**
         * Generate a new random key pair
         */
        fun generate(): KeyPair {
            val random = SecureRandom()
            val privateKey = ByteArray(KEY_SIZE)
            random.nextBytes(privateKey)
            
            // For now, derive public key from private key (simplified approach)
            // In a full implementation, this would use proper EC cryptography
            val publicKey = derivePublicKey(privateKey)
            
            return KeyPair(privateKey, publicKey)
        }
        
        /**
         * Create key pair from hex private key
         */
        fun fromPrivateKeyHex(hex: String): KeyPair? {
            return try {
                if (!isValidHex(hex, 64)) return null
                
                val privateKey = hex.hexToByteArray()
                val publicKey = derivePublicKey(privateKey)
                
                KeyPair(privateKey, publicKey)
            } catch (e: Exception) {
                Log.w(TAG, "Error creating key pair from hex: $hex", e)
                null
            }
        }
        
        /**
         * Create read-only key pair from public key hex
         */
        fun fromPublicKeyHex(hex: String): KeyPair? {
            return try {
                if (!isValidHex(hex, 64)) return null
                
                val publicKey = hex.hexToByteArray()
                KeyPair(null, publicKey)
            } catch (e: Exception) {
                Log.w(TAG, "Error creating read-only key pair from hex: $hex", e)
                null
            }
        }
        
        /**
         * Create key pair from bech32 private key (nsec)
         */
        fun fromBech32PrivateKey(nsec: String): KeyPair? {
            // Implementation would use proper bech32 decoding
            // For now, simplified approach
            return null
        }
        
        /**
         * Create read-only key pair from bech32 public key (npub)
         */
        fun fromBech32PublicKey(npub: String): KeyPair? {
            // Implementation would use proper bech32 decoding
            // For now, simplified approach
            return null
        }
        
        private fun derivePublicKey(privateKey: ByteArray): ByteArray {
            // Simplified public key derivation
            // In a real implementation, this would use secp256k1 EC operations
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(privateKey)
        }
        
        private fun isValidHex(hex: String, expectedLength: Int): Boolean {
            return hex.length == expectedLength && 
                   hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
    }
    
    /**
     * Check if this key pair can sign (has private key)
     */
    fun canSign(): Boolean = privateKey != null
    
    /**
     * Get public key as hex string
     */
    fun getPublicKeyHex(): String {
        return publicKey.toHexString()
    }
    
    /**
     * Get private key as hex string (if available)
     */
    fun getPrivateKeyHex(): String? {
        return privateKey?.toHexString()
    }
    
    /**
     * Sign data with private key
     */
    fun sign(data: ByteArray): ByteArray? {
        if (privateKey == null) return null
        
        return try {
            // Simplified signing - in real implementation would use secp256k1
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(privateKey)
            digest.update(data)
            digest.digest()
        } catch (e: Exception) {
            Log.w(TAG, "Error signing data", e)
            null
        }
    }
    
    /**
     * Verify signature against data
     */
    fun verify(data: ByteArray, signature: ByteArray): Boolean {
        return try {
            // Simplified verification - in real implementation would use secp256k1
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(publicKey)
            digest.update(data)
            val expected = digest.digest()
            
            signature.contentEquals(expected)
        } catch (e: Exception) {
            Log.w(TAG, "Error verifying signature", e)
            false
        }
    }
    
    /**
     * Check if public key is valid format
     */
    fun isValidPublicKey(): Boolean {
        return publicKey.size == KEY_SIZE
    }
    
    /**
     * Clear sensitive data from memory
     */
    fun clear() {
        privateKey?.fill(0)
    }
}

/**
 * Extension function to convert ByteArray to hex string
 */
private fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * Extension function to convert hex string to ByteArray
 */
private fun String.hexToByteArray(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
