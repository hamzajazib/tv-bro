package com.phlox.tvwebbrowser.send

import android.util.Base64
import java.security.SecureRandom

/**
 * The links of the page "Send to TV Bro" of the site: the QR code of the TV opens
 * `https://tvbro.phlox.dev/send/#<inbox>` (the fragment never goes to the site), the short address the
 * user types is `tvbro.phlox.dev/c/<code>`, a code that the database maps to the inbox for a while.
 * The page is never under /sync: the phone sync of 3.0 betas takes those links into the app.
 */
object SendLinks {
    const val SITE = "https://tvbro.phlox.dev"
    const val PAGE = "$SITE/send/"
    private const val CODE_PATH = "/c/"
    const val CODE_LENGTH = 8
    /** Crockford's base32: no I, L, O and U, which the page reads as 1, 1 and 0 */
    const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val random = SecureRandom()

    /** The link of the QR code */
    fun pageUrl(inbox: String): String = "$PAGE#$inbox"

    /** The short address to type, without the scheme: `tvbro.phlox.dev/c/K7Q2M9XP` */
    fun codeAddress(code: String): String = SITE.substringAfter("://") + CODE_PATH + code

    /** 128 random bits: whoever knows it can send to the TV, so it must not be guessed */
    fun newInbox(): String = randomToken()

    /** The key of the page of the TV in the local network */
    fun newKey(): String = randomToken()

    /** 40 random bits, see [CODE_ALPHABET] */
    fun newCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
