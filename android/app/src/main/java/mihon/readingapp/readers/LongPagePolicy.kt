package mihon.readingapp.readers

/** Geometry and allocation bounds; no artwork is cropped to make a long image fit. */
object LongPagePolicy {
    fun isLong(width: Int, height: Int) = width > 0 && height.toLong() >= width.toLong() * 3
    fun needsWidthFit(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int): Boolean =
        isLong(sourceWidth, sourceHeight) && viewWidth > 0 && viewHeight > 0 &&
            sourceHeight.toDouble() * viewWidth / sourceWidth > viewHeight * 1.5
    fun widthSample(sourceWidth: Int, viewWidth: Int): Int {
        require(sourceWidth > 0 && viewWidth > 0)
        val wanted = viewWidth.coerceAtMost(4096)
        var sample = 1
        while (sample <= (1 shl 20) && sourceWidth.toLong() / (sample.toLong() * 2) >= wanted) sample *= 2
        return sample
    }
    fun regionRows(sourceWidth: Int, sample: Int): Int {
        require(sourceWidth > 0 && sample > 0)
        val decodedWidth = ((sourceWidth.toLong() + sample - 1) / sample).coerceAtLeast(1)
        val rows = (4L * 1024 * 1024 / (decodedWidth * 4)).coerceIn(1, 2048)
        return (rows * sample).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun displayedHeight(sourceWidth: Int, sourceHeight: Int, viewWidth: Int): Int {
        require(sourceWidth > 0 && sourceHeight > 0 && viewWidth > 0)
        val height = sourceHeight.toLong() * viewWidth / sourceWidth
        require(height in 1..0x00ffffffL) { "This image exceeds Android's supported scrolling extent." }
        return height.toInt()
    }
}
