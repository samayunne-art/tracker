package com.example.tracer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Canvas vẽ: ảnh mẫu + nét vẽ dùng CHUNG một ma trận biến đổi,
 * nên zoom / kéo / xoay thì ảnh và nét vẽ luôn khớp nhau.
 * 1 ngón = vẽ. 2 ngón = zoom + kéo + xoay.
 */
class TraceCanvasView(ctx: Context, private val ref: Bitmap) : View(ctx) {

    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 128 }
    private val paperPaint = Paint().apply { color = Color.WHITE }

    var refAlpha = 128
        set(v) { field = v; refPaint.alpha = v; invalidate() }
    var showRef = true
        set(v) { field = v; invalidate() }
    var brushColor = Color.BLACK
    var brushSize = 4f // đơn vị theo pixel của ảnh (không phải pixel màn hình)

    private val page = RectF(0f, 0f, ref.width.toFloat(), ref.height.toFloat())
    private val m = Matrix()   // thế giới -> màn hình
    private val inv = Matrix()

    private class Stroke(val path: Path, val paint: Paint)
    private val strokes = ArrayList<Stroke>()
    private var cur: Stroke? = null
    private var pts = 0
    private var lastX = 0f
    private var lastY = 0f

    private var gesturing = false
    private var needInit = false
    private var gD = 0f
    private var gA = 0f
    private var gCx = 0f
    private var gCy = 0f
    private var fitted = false

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (!fitted && w > 0 && h > 0) { resetView(); fitted = true }
    }

    fun resetView() {
        m.setRectToRect(page, RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        invalidate()
    }

    fun undo() { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.size - 1); invalidate() } }
    fun clear() { strokes.clear(); invalidate() }

    fun export(): Bitmap {
        val bmp = Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(page, paperPaint)
        for (s in strokes) c.drawPath(s.path, s.paint)
        return bmp
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF3A3A3A.toInt())
        c.save()
        c.concat(m)
        c.clipRect(page)
        c.drawRect(page, paperPaint)
        if (showRef) c.drawBitmap(ref, 0f, 0f, refPaint)
        for (s in strokes) c.drawPath(s.path, s.paint)
        c.restore()
    }

    private fun toWorld(x: Float, y: Float): FloatArray {
        m.invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return p
    }

    private fun scaleNow(): Float {
        val v = FloatArray(9)
        m.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    private fun readGesture(e: MotionEvent) {
        val dx = e.getX(1) - e.getX(0)
        val dy = e.getY(1) - e.getY(0)
        gD = hypot(dx, dy)
        gA = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        gCx = (e.getX(0) + e.getX(1)) / 2f
        gCy = (e.getY(0) + e.getY(1)) / 2f
    }

    private fun doGesture(e: MotionEvent) {
        if (needInit) { readGesture(e); needInit = false; return }
        val oD = gD; val oA = gA; val oCx = gCx; val oCy = gCy
        readGesture(e)
        var s = if (oD > 0f) gD / oD else 1f
        val now = scaleNow() * s
        if (now < 0.05f || now > 40f) s = 1f
        var da = gA - oA
        if (da > 180f) da -= 360f
        if (da < -180f) da += 360f
        m.postScale(s, s, oCx, oCy)
        m.postRotate(da, oCx, oCy)
        m.postTranslate(gCx - oCx, gCy - oCy)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturing = false
                val p = toWorld(e.x, e.y)
                val path = Path().apply { moveTo(p[0], p[1]); lineTo(p[0] + 0.01f, p[1]) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = brushColor
                    strokeWidth = brushSize
                }
                val s = Stroke(path, paint)
                strokes.add(s); cur = s
                lastX = p[0]; lastY = p[1]; pts = 0
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!gesturing) {
                    val c = cur
                    if (c != null && pts < 5) strokes.remove(c) // chạm nhầm trước khi đặt ngón thứ 2
                    cur = null
                    gesturing = true
                }
                needInit = true
            }
            MotionEvent.ACTION_POINTER_UP -> needInit = true
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    if (gesturing) doGesture(e)
                } else {
                    val c = cur
                    if (c != null) {
                        val p = toWorld(e.x, e.y)
                        c.path.quadTo(lastX, lastY, (p[0] + lastX) / 2f, (p[1] + lastY) / 2f)
                        lastX = p[0]; lastY = p[1]; pts++
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cur?.path?.lineTo(lastX, lastY)
                cur = null; gesturing = false
                invalidate()
            }
        }
        return true
    }
}
