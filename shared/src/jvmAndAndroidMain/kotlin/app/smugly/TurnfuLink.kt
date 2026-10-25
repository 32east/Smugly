package app.smugly

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class TurnfuLinkData(
    val host: String,
    val port: Int,
    val password: String,
    val workers: Int?,
    val mtu: Int?,
    val name: String?,
    val callHashes: List<String>,
    val token: String = ""
)

object TurnfuLink {
    const val SCHEME = "turnfu2"

    fun looksLike(text: String): Boolean = text.trim().startsWith("$SCHEME://")
    fun callHash(input: String): String? = TurnfuCall.hash(input)
    fun callUrl(input: String): String? = TurnfuCall.url(input)

    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(bytes)
    }

    private fun key(): ByteArray {
        val root = hmac("TURNFU2/link".toByteArray(), "turnfu invite seed".toByteArray())
        return hmac(root, "v2".toByteArray() + byteArrayOf(1))
    }

    private fun validate(data: TurnfuLinkData) {
        require(data.host.isNotEmpty() && data.host.none(Char::isWhitespace))
        require(data.host.lowercase() !in setOf("127.0.0.1", "0.0.0.0", "::1", "localhost"))
        require(data.port in 1..65535 && data.password.toByteArray().size in 1..128)
        require(data.workers == null || data.workers in 3..100)
        require(data.callHashes.size <= 6 && data.callHashes.all { it.length >= 16 && it.none(Char::isWhitespace) })
        require(data.token.isEmpty() || data.callHashes.isEmpty())
        require(data.mtu == null || data.mtu in 576..1500)
    }

    fun parse(text: String): TurnfuLinkData? = runCatching {
        require(looksLike(text) && text.length <= 128 * 1024)
        val blob = Base64.getUrlDecoder().decode(text.trim().substring(SCHEME.length + 3).filterNot(Char::isWhitespace))
        require(blob.size >= 29 && blob[0] == 2.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, blob, 1, 12))
        val reader = Reader(cipher.doFinal(blob, 13, blob.size - 13))
        var host = ""
        var port = 0
        var password = ""
        var workers: Int? = null
        var mtu: Int? = null
        var name: String? = null
        var token = ""
        val hashes = mutableListOf<String>()
        while (reader.hasMore()) {
            val tag = reader.byte()
            val value = reader.field()
            when (tag) {
                1 -> host = utf8(value)
                2 -> port = integer(value)
                3 -> password = utf8(value)
                4 -> { require(hashes.size < 6); hashes += utf8(value) }
                5 -> token = utf8(value)
                6 -> workers = integer(value)
                7 -> mtu = integer(value)
                8 -> name = utf8(value).takeIf { it.isNotBlank() }
            }
        }
        TurnfuLinkData(host, port, password, workers, mtu, name, hashes.distinct(), token).also(::validate)
    }.getOrNull()

    fun importLink(data: TurnfuLinkData): String {
        validate(data)
        val body = ByteArrayOutputStream()
        fun field(tag: Int, value: ByteArray) {
            body.write(tag)
            var size = value.size
            do {
                val next = size and 127
                size = size ushr 7
                body.write(next or if (size != 0) 128 else 0)
            } while (size != 0)
            body.write(value)
        }
        fun number(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
        field(1, data.host.toByteArray())
        field(2, number(data.port))
        field(3, data.password.toByteArray())
        data.callHashes.forEach { field(4, it.toByteArray()) }
        if (data.token.isNotEmpty()) field(5, data.token.toByteArray())
        data.workers?.let { field(6, number(it)) }
        data.mtu?.let { field(7, number(it)) }
        data.name?.takeIf(String::isNotBlank)?.let { field(8, it.toByteArray()) }
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, nonce))
        val blob = byteArrayOf(2) + nonce + cipher.doFinal(body.toByteArray())
        return "$SCHEME://" + Base64.getUrlEncoder().withoutPadding().encodeToString(blob)
    }

    fun applyTo(base: Config, data: TurnfuLinkData): Config = base.copy(
        protocol = Config.TunnelProtocol.TURNFU,
        turnfuHost = data.host,
        turnfuPort = data.port,
        turnfuPassword = data.password,
        turnfuWorkers = data.workers ?: base.turnfuWorkers,
        turnfuMtu = data.mtu ?: base.turnfuMtu,
        turnfuCalls = data.callHashes,
        turnfuToken = data.token
    )

    private fun integer(value: ByteArray): Int {
        require(value.size == 2)
        return ((value[0].toInt() and 255) shl 8) or (value[1].toInt() and 255)
    }

    private fun utf8(value: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value)).toString()

    private class Reader(val bytes: ByteArray) {
        private var at = 0
        fun hasMore() = at < bytes.size
        fun byte(): Int { require(hasMore()); return bytes[at++].toInt() and 255 }
        fun field(): ByteArray {
            var size = 0L
            var shift = 0
            while (true) {
                val b = byte()
                require(shift <= 56)
                size = size or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) {
                    require(shift == 0 || b != 0)
                    break
                }
                shift += 7
            }
            require(size <= bytes.size - at)
            val end = at + size.toInt()
            return bytes.copyOfRange(at, end).also { at = end }
        }
    }
}
