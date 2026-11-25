package com.example.tracer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
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
import kotlin.math.atan2
import kotlin.math.hypot

class TraceService : AccessibilityService() {

    companion object {
        var instance: TraceService? = null
        private const val OFF = "🔗 Đồng bộ zoom: tắt"
        private const val ON = "🔗 Đồng bộ zoom: bật"
    }

    private lateinit var wm: WindowManager
    private var imageRoot: FrameLayout? = null
    private var imageView: ImageView? = null
    private var imageLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var syncChip: TextView? = null
    private var drawMode = true // true: chạm xuyên qua ảnh để vẽ ở app bên dưới
    private val mat = Matrix()

    private var mirror: TouchMirror? = null
    private var prevPinch: FloatArray? = null
    private var prevKey = 0

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
        attachGestures(root)

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
        imageRoot = root; imageView = iv; imageLp = lp; drawMode = true
        showPanel(iv)
    }

    fun hide() {
        stopMirror()
        imageRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        imageRoot = null; imageView = null; panel = null; syncChip = null
    }

    private fun stopMirror() {
        mirror?.stop(); mirror = null; prevPinch = null
        syncChip?.text = OFF
    }

    private fun setDrawMode(on: Boolean) {
        drawMode = on
        prevPinch = null
        val lp = imageLp ?: return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = if (on) lp.flags or f else lp.flags and f.inv()
        wm.updateViewLayout(imageRoot, lp)
    }

    // ---------- biến đổi ảnh (dùng chung cho kéo tay và đồng bộ) ----------

    /** [tâm x, tâm y, khoảng cách, góc độ] của 2 ngón */
    private fun pinchOf(x1: Float, y1: Float, x2: Float, y2: Float) = floatArrayOf(
        (x1 + x2) / 2f, (y1 + y2) / 2f, hypot(x2 - x1, y2 - y1),
        Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
    )

    /** Ảnh đi theo 2 ngón: các điểm dưới ngón tay luôn bám theo ngón (giống pinch của app vẽ) */
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

    // Chế độ chỉnh ảnh bằng tay: 1 ngón kéo, 2 ngón zoom + xoay
    private fun attachGestures(root: View) {
        var lx = 0f
        var ly = 0f
        var pc = floatArrayOf(0f, 0f, 0f, 0f)
        var needInit = false
        root.setOnTouchListener { _, e ->
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
            true
        }
    }

    // ---------- đồng bộ với zoom của app khác (qua TouchMirror) ----------

    @Suppress("DEPRECATION")
    private fun onMirrorFrame(f: TouchMirror.Frame?) {
        val root = imageRoot
        if (f == null || root == null || !drawMode) { prevPinch = null; return }
        val sz = screenSize()
        val w = sz[0].toFloat()
        val h = sz[1].toFloat()
        val rot = wm.defaultDisplay.rotation

        fun mx(fx: Float, fy: Float): Float = when (rot) {
            Surface.ROTATION_90 -> fy * w
            Surface.ROTATION_180 -> (1f - fx) * w
            Surface.ROTATION_270 -> (1f - fy) * w
            else -> fx * w
        }
        fun my(fx: Float, fy: Float): Float = when (rot) {
            Surface.ROTATION_90 -> (1f - fx) * h
            Surface.ROTATION_180 -> (1f - fy) * h
            Surface.ROTATION_270 -> fx * h
            else -> fy * h
        }

        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val n = pinchOf(
            mx(f.x1, f.y1) - loc[0], my(f.x1, f.y1) - loc[1],
            mx(f.x2, f.y2) - loc[0], my(f.x2, f.y2) - loc[1]
        )
        val pv = prevPinch
        if (pv != null && prevKey == f.key) applyPinch(pv, n)
        prevPinch = n
        prevKey = f.key
    }

    // ---------- bảng điều khiển nổi ----------

    private fun showPanel(iv: ImageView) {
        val d = resources.displayMetrics.density
        fun p(v: Int) = (v * d).toInt()
        fun chip(t: String, f: (TextView) -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 14f
            setPadding(p(10), p(6), p(10), p(6))
            background = GradientDrawable().apply { setColor(0x44FFFFFF); cornerRadius = p(12).toFloat() }
            setOnClickListener { f(this) }
        }

        val handle = TextView(this).apply {
            text = "✥"; setTextColor(Color.WHITE); textSize = 20f
            setPadding(p(10), p(4), p(14), p(4))
        }
        val modeBtn = chip("✋ Chỉnh ảnh") { b ->
            setDrawMode(!drawMode)
            b.text = if (drawMode) "✋ Chỉnh ảnh" else "✏️ Khoá để vẽ"
        }
        val closeBtn = chip("✕") { hide() }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(handle); addView(modeBtn); addView(closeBtn)
        }
        val seek = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { iv.alpha = maxOf(v, 3) / 100f }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val syncBtn = chip(OFF) { b ->
            if (mirror != null) {
                stopMirror()
            } else {
                val err = TouchMirror.ready()
                if (err != null) {
                    toast(err)
                } else {
                    mirror = TouchMirror(
                        onFrame = { onMirrorFrame(it) },
                        onDead = { msg -> toast(msg); stopMirror() }
                    ).also { it.start() }
                    b.text = ON
                }
            }
        }
        syncChip = syncBtn

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(10), p(8), p(10), p(8))
            background = GradientDrawable().apply { setColor(Color.argb(230, 34, 34, 34)); cornerRadius = p(16).toFloat() }
            addView(row)
            addView(seek, LinearLayout.LayoutParams(p(230), -2))
            addView(syncBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = p(6) })
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
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
