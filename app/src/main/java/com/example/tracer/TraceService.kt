package com.example.tracer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 3 chế độ:
 *  VẼ  : lớp ảnh cho chạm xuyên qua, vẽ ở app bên dưới như bình thường.
 *  ZOOM: 2 ngón zoom/kéo/xoay trên lớp ảnh -> ảnh đổi ngay, rồi Trace lặp lại đúng cử chỉ đó lên app bên dưới.
 *  ẢNH : chỉ kéo/zoom riêng ảnh (để khớp lại khi bị lệch).
 * Nút Tăng âm lượng: đổi nhanh VẼ <-> ZOOM.
 */
class TraceService : AccessibilityService() {

    companion object { var instance: TraceService? = null }

    private enum class Mode { DRAW, ZOOM, EDIT }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var dens = 1f
    private var imageRoot: FrameLayout? = null
    private var imageView: ImageView? = null
    private var imageLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var hint: TextView? = null
    private val chips = HashMap<Mode, TextView>()
    private var mode = Mode.DRAW
    private var replaying = false
    private val mat = Matrix()

    // trạng thái cử chỉ
    private var lx = 0f
    private var ly = 0f
    private var pc = FloatArray(4)
    private var needInit = false
    private var recA = -1
    private var recB = -1
    private var recT0 = 0L
    private val rec = ArrayList<FloatArray>() // [t, ax, ay, bx, by]

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hide(); instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (imageRoot != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                setMode(if (mode == Mode.DRAW) Mode.ZOOM else Mode.DRAW)
            }
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): IntArray {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            intArrayOf(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            intArrayOf(dm.widthPixels, dm.heightPixels)
        }
    }

    fun show() {
        hide()
        val file = File(filesDir, "trace.png")
        if (!file.exists()) { toast("Hãy chọn ảnh trước"); return }
        val bmp = BitmapFactory.decodeFile(file.path) ?: return

        val sz = screenSize()
        mat.setRectToRect(
            RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
            RectF(0f, 0f, sz[0].toFloat(), sz[1].toFloat()),
            Matrix.ScaleToFit.CENTER
        )
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setImageBitmap(bmp)
            imageMatrix = mat
            alpha = 0.5f
        }
        val root = FrameLayout(this).apply { addView(iv, FrameLayout.LayoutParams(-1, -1)) }
        root.setOnTouchListener { _, e -> onOverlayTouch(e) }

        val lp = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        imageRoot = root; imageView = iv; imageLp = lp
        mode = Mode.DRAW
        showPanel(iv)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        replaying = false
        imageRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        imageRoot = null; imageView = null; imageLp = null; panel = null; hint = null
        chips.clear(); rec.clear(); recA = -1; recB = -1
    }

    // ---------- chế độ ----------

    private fun chipBg(sel: Boolean) = GradientDrawable().apply {
        setColor(if (sel) 0xFF2979FF.toInt() else 0x33FFFFFF)
        cornerRadius = 12f * dens
    }

    private fun setMode(m: Mode) {
        mode = m
        recA = -1; recB = -1; rec.clear()
        updateTouchable()
        for ((k, v) in chips) v.background = chipBg(k == m)
        hint?.text = when (m) {
            Mode.DRAW -> "Chạm xuyên qua ảnh để vẽ"
            Mode.ZOOM -> "2 ngón zoom/kéo/xoay: ảnh và app bên dưới cùng đổi"
            Mode.EDIT -> "Kéo/zoom riêng ảnh để khớp lại với tranh"
        }
    }

    private fun updateTouchable() {
        val lp = imageLp ?: return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val touchable = mode != Mode.DRAW && !replaying
        val nf = if (touchable) lp.flags and f.inv() else lp.flags or f
        if (nf != lp.flags) {
            lp.flags = nf
            imageRoot?.let { wm.updateViewLayout(it, lp) }
        }
    }

    // ---------- biến đổi ảnh ----------

    /** [tâm x, tâm y, khoảng cách, góc độ] của 2 ngón */
    private fun pinchOf(x1: Float, y1: Float, x2: Float, y2: Float) = floatArrayOf(
        (x1 + x2) / 2f, (y1 + y2) / 2f, hypot(x2 - x1, y2 - y1),
        Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
    )

    private fun applyPinch(o: FloatArray, n: FloatArray) {
        val s = (if (o[2] > 0f) n[2] / o[2] else 1f).coerceIn(0.5f, 2f)
        var da = n[3] - o[3]
        if (da > 180f) da -= 360f
        if (da < -180f) da += 360f
        mat.postScale(s, s, o[0], o[1])
        mat.postRotate(da, o[0], o[1])
        mat.postTranslate(n[0] - o[0], n[1] - o[1])
        imageView?.imageMatrix = mat
    }

    // ---------- xử lý chạm trên lớp ảnh ----------

    private fun onOverlayTouch(e: MotionEvent): Boolean {
        if (replaying) return true
        if (mode == Mode.EDIT) editTouch(e) else if (mode == Mode.ZOOM) zoomTouch(e)
        return true
    }

    // Chế độ ẢNH: 1 ngón kéo, 2 ngón zoom + xoay (chỉ ảnh)
    private fun editTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; needInit = false }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> needInit = true
            MotionEvent.ACTION_MOVE ->
                if (e.pointerCount >= 2) {
                    val n = pinchOf(e.getX(0), e.getY(0), e.getX(1), e.getY(1))
                    if (!needInit) applyPinch(pc, n)
                    pc = n; needInit = false
                } else if (needInit) {
                    lx = e.x; ly = e.y; needInit = false
                } else {
                    mat.postTranslate(e.x - lx, e.y - ly)
                    imageView?.imageMatrix = mat
                    lx = e.x; ly = e.y
                }
        }
    }

    // Chế độ ZOOM: ảnh đổi ngay theo 2 ngón, đồng thời ghi lại đường đi của 2 ngón để lặp lại
    private fun zoomTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { recA = -1; recB = -1; rec.clear() }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && recA < 0) {
                    recA = e.getPointerId(0); recB = e.getPointerId(1)
                    recT0 = e.eventTime
                    rec.clear()
                    pc = pinchOf(e.getX(0), e.getY(0), e.getX(1), e.getY(1))
                    rec.add(floatArrayOf(0f, e.getX(0), e.getY(0), e.getX(1), e.getY(1)))
                }
            }
            MotionEvent.ACTION_MOVE -> if (recA >= 0) {
                val ia = e.findPointerIndex(recA)
                val ib = e.findPointerIndex(recB)
                if (ia >= 0 && ib >= 0) {
                    val n = pinchOf(e.getX(ia), e.getY(ia), e.getX(ib), e.getY(ib))
                    applyPinch(pc, n); pc = n
                    rec.add(floatArrayOf((e.eventTime - recT0).toFloat(), e.getX(ia), e.getY(ia), e.getX(ib), e.getY(ib)))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (recA >= 0 && (id == recA || id == recB)) {
                    finishRecording(true); recA = -1; recB = -1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (recA >= 0) {
                    finishRecording(e.actionMasked == MotionEvent.ACTION_UP); recA = -1; recB = -1
                }
            }
        }
    }

    private fun finishRecording(doReplay: Boolean) {
        val pts = ArrayList<FloatArray>(rec)
        rec.clear()
        if (!doReplay || pts.size < 3) return
        val first = pts.first()
        val last = pts.last()
        val f = pinchOf(first[1], first[2], first[3], first[4])
        val l = pinchOf(last[1], last[2], last[3], last[4])
        // chạm nhẹ 2 ngón (không di chuyển) thì bỏ qua, tránh vô tình kích hoạt "hoàn tác" ở app bên dưới
        if (hypot(l[0] - f[0], l[1] - f[1]) < 12f && abs(l[2] - f[2]) < 12f && abs(l[3] - f[3]) < 3f) return

        val step = maxOf(1, pts.size / 16)
        val sel = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.size - 1 }
        val loc = IntArray(2)
        imageRoot?.getLocationOnScreen(loc)
        fun c(v: Float) = if (v < 0f) 0f else v
        val pa = Path()
        val pb = Path()
        sel.forEachIndexed { i, p ->
            val ax = c(p[1] + loc[0]); val ay = c(p[2] + loc[1])
            val bx = c(p[3] + loc[0]); val by = c(p[4] + loc[1])
            if (i == 0) { pa.moveTo(ax, ay); pb.moveTo(bx, by) }
            else { pa.lineTo(ax, ay); pb.lineTo(bx, by) }
        }
        val dur = (last[0] - first[0]).toLong().coerceIn(150L, 800L)
        replay(listOf(pa, pb), dur)
    }

    /** Lặp lại cử chỉ 2 ngón lên app bên dưới (lớp ảnh tạm thời cho chạm xuyên qua) */
    private fun replay(paths: List<Path>, dur: Long) {
        replaying = true
        updateTouchable()
        handler.postDelayed({
            val b = GestureDescription.Builder()
            for (p in paths) b.addStroke(GestureDescription.StrokeDescription(p, 0L, dur))
            val ok = try {
                dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { finishReplay() }
                    override fun onCancelled(g: GestureDescription?) { finishReplay() }
                }, null)
            } catch (e: Exception) { false }
            if (!ok) finishReplay()
        }, 150L)
        handler.postDelayed({ if (replaying) finishReplay() }, dur + 2000L)
    }

    private fun finishReplay() {
        replaying = false
        updateTouchable()
    }

    // ---------- bảng điều khiển nổi ----------

    private fun showPanel(iv: ImageView) {
        dens = resources.displayMetrics.density
        fun p(v: Int) = (v * dens).toInt()
        fun chip(t: String, f: (TextView) -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 14f
            setPadding(p(10), p(6), p(10), p(6))
            background = chipBg(false)
            setOnClickListener { f(this) }
        }
        fun modeChip(m: Mode, label: String): TextView {
            val c = chip(label) { setMode(m) }
            chips[m] = c
            return c
        }

        val handle = TextView(this).apply {
            text = "✥"; setTextColor(Color.WHITE); textSize = 20f
            setPadding(p(8), p(4), p(10), p(4))
        }
        val closeBtn = chip("✕") { hide() }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            addView(modeChip(Mode.DRAW, "✏️ Vẽ"))
            addView(modeChip(Mode.ZOOM, "🔍 Zoom"))
            addView(modeChip(Mode.EDIT, "✋ Ảnh"))
            addView(closeBtn)
        }
        val seek = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { iv.alpha = maxOf(v, 3) / 100f }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val hintView = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt()); textSize = 11f
        }
        hint = hintView

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(10), p(8), p(10), p(8))
            background = GradientDrawable().apply { setColor(Color.argb(230, 34, 34, 34)); cornerRadius = p(16).toFloat() }
            addView(row)
            addView(seek, LinearLayout.LayoutParams(p(250), -2))
            addView(hintView)
        }
        val lp = WindowManager.LayoutParams(
            -2, -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = p(16); y = p(120) }

        var ox = 0f
        var oy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ox = lp.x - e.rawX; oy = lp.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX + ox).toInt(); lp.y = (e.rawY + oy).toInt()
                    wm.updateViewLayout(box, lp)
                }
            }
            true
        }
        wm.addView(box, lp); panel = box
        setMode(Mode.DRAW)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
