package eu.kanade.tachiyomi.extension.es.tenkaiscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

class ImageInterceptor(private val clientProvider: () -> OkHttpClient) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isScrambled = request.header("X-Falco-Scrambled") == "1"

        if (!isScrambled) {
            return chain.proceed(request)
        }

        val fragmentBase = request.header("X-Falco-Base") ?: ""
        val fragmentDir = request.header("X-Falco-Dir") ?: ""

        val manifestRequest = request.newBuilder()
            .removeHeader("X-Falco-Scrambled")
            .removeHeader("X-Falco-Base")
            .removeHeader("X-Falco-Dir")
            .build()

        val manifestResponse = chain.proceed(manifestRequest)
        if (!manifestResponse.isSuccessful) {
            return manifestResponse
        }

        val manifest = try {
            manifestResponse.parseAs<ManifestDto>()
        } catch (e: Exception) {
            throw IOException("Failed to parse FalcoScan manifest: ${e.message}", e)
        }

        if (manifest.pieces.isEmpty() || manifest.width <= 0 || manifest.height <= 0) {
            return manifestResponse
        }

        val result = Bitmap.createBitmap(manifest.width, manifest.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            val subClient = clientProvider()

            for (piece in manifest.pieces) {
                val piecePath = "projects/$fragmentDir${piece.file}"
                val encodedPath = Base64.encodeToString(piecePath.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val pieceUrl = fragmentBase.replace("PLACEHOLDER", encodedPath)

                val pieceRequest = Request.Builder()
                    .url(pieceUrl)
                    .headers(request.headers)
                    .removeHeader("X-Falco-Scrambled")
                    .removeHeader("X-Falco-Base")
                    .removeHeader("X-Falco-Dir")
                    .build()

                val pieceResponse = subClient.newCall(pieceRequest).execute()
                if (!pieceResponse.isSuccessful) {
                    pieceResponse.close()
                    throw IOException("Failed to download fragment ${piece.file}: HTTP ${pieceResponse.code}")
                }

                val pieceBitmap = BitmapFactory.decodeStream(pieceResponse.body.byteStream())
                pieceResponse.close()

                if (pieceBitmap != null) {
                    val left = piece.col * manifest.pieceWidth
                    val top = piece.row * manifest.pieceHeight
                    val dstRect = Rect(left, top, left + manifest.pieceWidth, top + manifest.pieceHeight)
                    canvas.drawBitmap(pieceBitmap, null, dstRect, null)
                    pieceBitmap.recycle()
                }
            }

            val buffer = Buffer()
            result.compress(Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())

            val mediaType = "image/jpeg".toMediaType()
            return manifestResponse.newBuilder()
                .body(buffer.asResponseBody(mediaType, buffer.size))
                .build()
        } finally {
            result.recycle()
        }
    }
}

@Serializable
data class ManifestDto(
    val width: Int,
    val height: Int,
    val pieceWidth: Int,
    val pieceHeight: Int,
    val cols: Int = 0,
    val rows: Int = 0,
    val pieces: List<PieceDto> = emptyList(),
)

@Serializable
data class PieceDto(
    val file: String,
    val row: Int,
    val col: Int,
)
