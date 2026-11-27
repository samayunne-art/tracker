package com.example.tracer

import android.graphics.Bitmap

object LineArt {

    /** Đổi ảnh thành nét viền trên nền trong suốt. strength 0..100 = độ đậm nét. */
    fun convert(src: Bitmap, strength: Int, color: Int): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val gray = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val a = c ushr 24
            val l = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
            gray[i] = (l * a + 255 * (255 - a)) / 255 // điểm trong suốt coi như nền trắng
        }

        val blur = IntArray(n) { 255 - gray[it] }
        val r = maxOf(2, maxOf(w, h) / 250)
        boxBlur(blur, w, h, r)
        boxBlur(blur, w, h, r)

        val gain = 1.0f + strength / 25f // 1..5
        val rgb = color and 0x00FFFFFF
        val out = px // dùng lại mảng cũ để đỡ tốn bộ nhớ
        for (i in 0 until n) {
            val b = blur[i]
            val g = gray[i]
            val dodge = if (b >= 255) 255 else minOf(255, g * 255 / (255 - b))
            val line = 255 - dodge // 0 = nền, 255 = nét đậm
            val a = ((line - 6) * gain).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or rgb
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int) {
        val t = IntArray(a.size)
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -r..r) sum += a[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                t[row + x] = sum / div
                sum += a[row + minOf(w - 1, x + r + 1)] - a[row + maxOf(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (k in -r..r) sum += t[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = sum / div
                sum += t[minOf(h - 1, y + r + 1) * w + x] - t[maxOf(0, y - r) * w + x]
            }
        }
    }
}
