package com.solanatappay.solana

import java.math.BigInteger

/**
 * Base58 encoding/decoding for Solana addresses and signatures.
 * Uses Bitcoin's Base58 alphabet.
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, c -> this[c.code] = index }
    }
    
    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        
        // Count leading zeros
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) {
            zeros++
        }
        
        // Convert to base58
        val encoded = ByteArray(input.size * 2)
        var outputStart = encoded.size
        var inputStart = zeros
        
        while (inputStart < input.size) {
            encoded[--outputStart] = ALPHABET[divmod(input, inputStart, 256, 58).toInt()].code.toByte()
            if (input[inputStart].toInt() == 0) {
                inputStart++
            }
        }
        
        // Add leading '1' for each leading zero
        while (outputStart < encoded.size && encoded[outputStart].toInt().toChar() == ALPHABET[0]) {
            outputStart++
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = ALPHABET[0].code.toByte()
        }
        
        return String(encoded, outputStart, encoded.size - outputStart, Charsets.UTF_8)
    }
    
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        
        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            if (digit < 0) throw IllegalArgumentException("Invalid Base58 character: $c")
            input58[i] = digit.toByte()
        }
        
        // Count leading zeros
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) {
            zeros++
        }
        
        // Convert from base58
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros
        
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256).toByte()
            if (input58[inputStart].toInt() == 0) {
                inputStart++
            }
        }
        
        // Skip leading zeros in output
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) {
            outputStart++
        }
        
        return ByteArray(zeros + decoded.size - outputStart).apply {
            System.arraycopy(decoded, outputStart, this, zeros, decoded.size - outputStart)
        }
    }
    
    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }
}

