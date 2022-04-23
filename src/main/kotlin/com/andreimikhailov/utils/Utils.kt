package com.andreimikhailov.utils

import org.http4k.base64Decoded
import org.http4k.core.Credentials
import org.http4k.core.Request
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.cookie
import java.util.*
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey


fun Request.authCreds(): Credentials? = header("Authorization")
    ?.trim()
    ?.takeIf { it.startsWith("Basic") }
    ?.substringAfter("Basic")
    ?.trim()
    ?.toCredentials()

private fun String.toCredentials(): Credentials? = try {
    base64Decoded().split(":").let { Credentials(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) }
} catch (e: IllegalArgumentException) {
    null
}

fun decrypt(decryptor: Cipher, key: SecretKey, encrypted: String): String? {
    return try {
        String(decryptor.doFinal(Base64.getDecoder().decode(encrypted)))
    } catch(e: Exception) {
        when(e) {
            is IllegalStateException -> {
                println("-- Illegal State Exception of decryptor")
                decryptor.init(Cipher.DECRYPT_MODE, key)
                null
            }
            is IllegalBlockSizeException -> {
                println("-- Illegal Block Size Exception")
                decryptor.init(Cipher.DECRYPT_MODE, key)
                null
            }
            is BadPaddingException -> {
                println("-- Data does not decrypt. Is somebody trying to mess with the cookie?")
                decryptor.init(Cipher.DECRYPT_MODE, key)
                null
            }
            is AEADBadTagException -> {
                println("-- AEAD Bad Tag")
                decryptor.init(Cipher.DECRYPT_MODE, key)
                null
            }
            else -> {
                null
            }
        }
    }
}

fun getUserFromCookie(decryptor: Cipher, key: SecretKey, req: Request): String? {
    return req.cookie("user")?.let { return decrypt(decryptor, key, it.value) }
}

fun getCSRFTokenFromCookie(decryptor: Cipher, key: SecretKey, req: Request): String? {
    return req.cookie("csrf")?.let { return decrypt(decryptor, key, it.value) }
}
