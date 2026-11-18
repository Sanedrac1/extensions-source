package eu.kanade.tachiyomi.extension.es.aedex

import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import org.jsoup.nodes.Document
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Holds per-chapter HMAC signing keys and live-heartbeat state required by the blayvia.com reader.
 */
class LectorSession private constructor(
    val readerUrl: String,
    val origin: String,
    val xorByte: Int,
    private val h: String,
    private val clockOffset: Long,
    private val proofHeader: String,
    private val liveHeader: String,
    private val buildHeader: String,
    private val buildId: String,
    private val vivoSlug: String,
    private val hmacKey: ByteArray,
) {
    private var liveToken: String? = null
    private var liveExpiresAt: Long = 0L

    fun proofFor(scope: String, subject: String): String {
        val time = System.currentTimeMillis() + clockOffset
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
        }
        val sigHex = mac.doFinal("$scope.$subject.$time".toByteArray()).toHex().take(40)
        return "$time.$sigHex"
    }

    @Synchronized
    fun ensureLiveToken(chain: Interceptor.Chain, forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh && liveToken != null && now < liveExpiresAt) {
            return liveToken
        }
        val request = Request.Builder()
            .url("$origin/lector/$vivoSlug/")
            .post(LiveTokenRequest(h).toJsonRequestBody())
            .header(buildHeader, buildId)
            .header(proofHeader, proofFor("v", h))
            .fetch(Fetch.XHR.from(readerUrl))
            .build()
        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val data = response.parseAs<LiveTokenResponse>()
        val token = data.l ?: return null
        liveToken = token
        liveExpiresAt = System.currentTimeMillis() + maxOf(5_000L, data.ttl - 5_000L)
        return token
    }

    fun signPageRequest(request: Request, handle: String, token: String?): Request = request.newBuilder()
        .header(buildHeader, buildId)
        .header(proofHeader, proofFor("p", handle))
        .apply {
            if (token != null) header(liveHeader, token)
        }
        .build()

    companion object {
        private val LECTOR_REGEX = Regex("""window\.__LECTOR__\s*=\s*(\{.*?\});""")

        fun fromDocument(reader: Document): LectorSession? {
            val seed = reader.selectFirst("#bv-vault[data-bv-seed]")?.attr("data-bv-seed")
                ?.takeIf { it.isNotBlank() } ?: return null
            val configJson = reader.select("script:not([src])")
                .firstNotNullOfOrNull { LECTOR_REGEX.find(it.data())?.groupValues?.get(1) }
                ?: return null
            val config = configJson.parseAs<LectorConfigDto>()
            if (config.t.isBlank() || config.h.isBlank()) return null

            val digest = MessageDigest.getInstance("SHA-256")
            val keyHex = digest.digest("${config.t}:$seed".toByteArray()).toHex()
            val hmacKey = digest.digest("$keyHex:p".toByteArray())

            val readerUrl = reader.location()
            val parsed = readerUrl.toHttpUrl()
            val origin = "${parsed.scheme}://${parsed.host}" + if (parsed.port != 80 && parsed.port != 443) ":${parsed.port}" else ""
            val offset = if (config.now > 0L) config.now - System.currentTimeMillis() else 0L

            return LectorSession(
                readerUrl = readerUrl,
                origin = origin,
                xorByte = config.xorByte,
                h = config.h,
                clockOffset = offset,
                proofHeader = "bv-sig-${keyHex.substring(7, 15)}",
                liveHeader = "bv-live-${keyHex.substring(15, 23)}",
                buildHeader = "bv-build-${keyHex.substring(23, 31)}",
                buildId = keyHex.substring(31, 43),
                vivoSlug = keyHex.substring(43, 51),
                hmacKey = hmacKey,
            )
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
