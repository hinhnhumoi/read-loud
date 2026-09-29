package com.tung.readloud.data

import java.security.MessageDigest

object Hashing {
    fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
