package eu.kanade.tachiyomi.multisrc.catharsis

import android.graphics.Bitmap

// Tile boundaries, with the order the reader shuffled the columns and rows into
class TilePlan(val cols: IntArray, val rows: IntArray, val colOrder: IntArray, val rowOrder: IntArray)

// Undoes the reader's 8x8 row/column shuffle, planned for the image without gutters; shuffled tiles may sit [gutter] px apart
fun descramble(bitmap: Bitmap, gutter: Int = 0, plan: (width: Int, height: Int) -> TilePlan): Bitmap {
    // The reader drops a gutter that would leave less than 200 px
    val tileGutter = gutter.takeIf { minOf(bitmap.width, bitmap.height) - 2 * GRID * it >= MIN_GUTTERED_SIZE } ?: 0
    val outputWidth = bitmap.width - 2 * GRID * tileGutter
    val outputHeight = bitmap.height - 2 * GRID * tileGutter
    val tiles = plan(outputWidth, outputHeight)
    val srcCols = offsets(tiles.cols, tiles.colOrder)
    val srcRows = offsets(tiles.rows, tiles.rowOrder)

    // Copy pixels directly instead of Canvas/Paint.isFilterBitmap (which throws Stub! on Suwayomi)
    val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    var tilePixels = IntArray(0)
    for (row in 0 until GRID) {
        for (col in 0 until GRID) {
            val width = tiles.cols[col + 1] - tiles.cols[col]
            val height = tiles.rows[row + 1] - tiles.rows[row]
            if (width <= 0 || height <= 0) continue
            val count = width * height
            if (tilePixels.size < count) tilePixels = IntArray(count)
            val colPosition = tiles.colOrder.indexOf(col)
            val rowPosition = tiles.rowOrder.indexOf(row)
            // Each shuffled strip has a gutter on both sides
            val srcX = srcCols[colPosition] + tileGutter * (2 * colPosition + 1)
            val srcY = srcRows[rowPosition] + tileGutter * (2 * rowPosition + 1)
            bitmap.getPixels(tilePixels, 0, width, srcX, srcY, width, height)
            output.setPixels(tilePixels, 0, width, tiles.cols[col], tiles.rows[row], width, height)
        }
    }
    return output
}

private fun offsets(bounds: IntArray, order: IntArray): IntArray {
    val offsets = IntArray(GRID + 1)
    for (i in 0 until GRID) {
        offsets[i + 1] = offsets[i] + bounds[order[i] + 1] - bounds[order[i]]
    }
    return offsets
}

private const val GRID = 8
private const val MIN_GUTTERED_SIZE = 200
