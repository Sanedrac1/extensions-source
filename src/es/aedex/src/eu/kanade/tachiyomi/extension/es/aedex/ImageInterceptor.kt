package eu.kanade.tachiyomi.extension.es.aedex

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.multisrc.catharsis.TilePlan
import eu.kanade.tachiyomi.multisrc.catharsis.descramble
import keiyoushi.utils.hmacSha256
import keiyoushi.utils.parseAs
import keiyoushi.utils.sha256
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.xor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Clock

// Free-mode pages: XOR-masked, and shuffled over an 8x8 grid when the page has a scramble key
class ImageInterceptor : Interceptor {

    private val liveTokens = ConcurrentHashMap<String, LiveToken>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment ?: return chain.proceed(request)
        val segments = request.url.pathSegments
        val isLectorPage = segments.size >= 3 && segments[0] == "lector" && segments[1] == "p"
        val isFreePage = segments.getOrNull(3) == "free-pages"

        return when {
            isLectorPage -> interceptLectorPage(chain, request, segments[2], fragment)
            isFreePage -> interceptFreePage(chain, request, fragment)
            else -> chain.proceed(request)
        }
    }

    private fun interceptFreePage(chain: Interceptor.Chain, request: Request, fragment: String): Response {
        val pageXorKey = fragment.substringBefore(':').toIntOrNull()
        val scrambleKey = fragment.substringAfter(':')
        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        return processImage(response, pageXorKey, scrambleKey)
    }

    private fun interceptLectorPage(
        chain: Interceptor.Chain,
        request: Request,
        handle: String,
        fragment: String,
    ): Response {
        val (token, seed, sessionHandle, offsetRaw, xorRaw) = fragment.split(':')
        val clockOffset = offsetRaw.toLong()
        val fallbackXor = xorRaw.toInt()

        val key = "$token:$seed".sha256().toHexString()
        val hmacKey = "$key:p".sha256()
        val proofHeader = "bv-sig-${key.substring(7, 15)}"
        val liveHeader = "bv-live-${key.substring(15, 23)}"
        val buildHeader = "bv-build-${key.substring(23, 31)}"
        val buildId = key.substring(31, 43)
        val vivoSlug = key.substring(43, 51)

        fun proofFor(scope: String, subject: String): String {
            val now = Clock.System.now().toEpochMilliseconds() + clockOffset
            val sig = "$scope.$subject.$now".hmacSha256(hmacKey).toHexString().take(40)
            return "$now.$sig"
        }

        fun fetchLiveToken(): String {
            val now = Clock.System.now().toEpochMilliseconds()
            liveTokens[sessionHandle]?.takeIf { now < it.expiresAt }?.let { return it.token }
            synchronized(liveTokens) {
                val recheckNow = Clock.System.now().toEpochMilliseconds()
                liveTokens.entries.removeIf { it.value.expiresAt <= recheckNow }
                liveTokens[sessionHandle]?.let { return it.token }

                val liveUrl = request.url.newBuilder()
                    .encodedPath("/lector/$vivoSlug/")
                    .query(null)
                    .fragment(null)
                    .build()
                val liveReq = request.newBuilder()
                    .url(liveUrl)
                    .post(LiveTokenRequest(sessionHandle).toJsonRequestBody())
                    .header(buildHeader, buildId)
                    .header(proofHeader, proofFor("v", sessionHandle))
                    .build()
                return chain.proceed(liveReq).use { res ->
                    if (!res.isSuccessful) throw IOException("Failed to fetch reader live token: HTTP ${res.code}")
                    val data = res.parseAs<LiveTokenDto>()
                    if (data.token.isBlank()) throw IOException("Empty reader live token")
                    val expiry = Clock.System.now().toEpochMilliseconds() + maxOf(5_000L, data.ttl - 5_000L)
                    liveTokens[sessionHandle] = LiveToken(data.token, expiry)
                    data.token
                }
            }
        }

        val pageUrl = request.url.newBuilder().fragment(null).build()
        fun buildPageRequest(liveToken: String): Request = request.newBuilder()
            .url(pageUrl)
            .header(buildHeader, buildId)
            .header(proofHeader, proofFor("p", handle))
            .header(liveHeader, liveToken)
            .build()

        var response = chain.proceed(buildPageRequest(fetchLiveToken()))
        if (response.code == 403) {
            liveTokens.remove(sessionHandle)
            response.close()
            response = chain.proceed(buildPageRequest(fetchLiveToken()))
        }
        if (!response.isSuccessful) return response

        val pageXorKey = response.header("X-Page-Xor")?.toIntOrNull() ?: fallbackXor
        val scrambleKey = response.header("X-Page-Key").orEmpty()
        return processImage(response, pageXorKey, scrambleKey)
    }

    private fun processImage(response: Response, pageXorKey: Int?, scrambleKey: String): Response {
        // Plain, the page's XOR_KEY, then 0x43 for older chapters, like the site's reader
        val header = response.body.source().peek().readByteArray(HEADER_SIZE)
        val xorKey = listOfNotNull(0, pageXorKey, LEGACY_XOR_KEY).firstOrNull { isImageHeader(header.xor(it)) }
        if (xorKey == null) {
            response.close()
            throw IOException("Unknown page image format")
        }
        val plain = if (xorKey == 0) response else response.xor(xorKey)
        if (scrambleKey.isBlank()) return plain
        val image = plain.body.use { BitmapFactory.decodeStream(it.byteStream()) }
            ?: throw IOException("Undecodable page image")
        val result = descramble(image) { width, height -> tilePlan(mulberry32(scrambleKey.hashCode()), width, height) }
        image.recycle()

        val buffer = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())
        result.recycle()
        return response.newBuilder()
            .body(buffer.asResponseBody(MEDIA_TYPE, buffer.size))
            .build()
    }

    companion object {
        private const val LEGACY_XOR_KEY = 0x43
        private const val HEADER_SIZE = 12L
        private val MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}

private class LiveToken(val token: String, val expiresAt: Long)

// The reader's page shuffle generator
private fun mulberry32(seed: Int): () -> Double {
    var state = seed
    return {
        state += 0x6d2b79f5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
    }
}

// The reader's 8x8 plan drawn from [random]
private fun tilePlan(random: () -> Double, width: Int, height: Int): TilePlan {
    val cols = boundaries(random, width)
    val rows = boundaries(random, height)
    return TilePlan(cols, rows, permutation(random), permutation(random))
}

private fun boundaries(random: () -> Double, total: Int): IntArray {
    val bounds = IntArray(GRID + 1)
    bounds[GRID] = total
    val base = total / GRID
    var position = 0
    for (i in 0 until GRID - 1) {
        val max = total - position - (GRID - 1 - i) * MIN_TILE
        position += (base + ((random() * 0.7 - 0.35) * base).roundToInt()).coerceIn(MIN_TILE, maxOf(MIN_TILE, max))
        bounds[i + 1] = position
    }
    return bounds
}

private fun permutation(random: () -> Double): IntArray {
    val order = IntArray(GRID) { it }
    for (i in GRID - 1 downTo 1) {
        val j = (random() * (i + 1)).toInt()
        order[i] = order[j].also { order[j] = order[i] }
    }
    return order
}

private const val GRID = 8
private const val MIN_TILE = 16
