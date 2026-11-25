package com.example.tracer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
import kotlin.math.atan2
import kotlin.math.hypot

class TraceService : AccessibilityService() {

    companion object { var instance: TraceService? = null }

    private lateinit var wm: WindowManager
    private var imageRoot: FrameLayout? = null
    private var imageLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var drawMode = true // true: chạm xuyên qua ảnh để vẽ ở app bên dưới

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

    fun show() {
        hide()
        val file = File(filesDir, "trace.png")
        if (!file.exists()) { toast("Hãy chọn ảnh trước"); return }
        val bmp = BitmapFactory.decodeFile(file.path) ?: return

        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.5f
        }
        val root = FrameLayout(this).apply { addView(iv, FrameLayout.LayoutParams(-1, -1)) }
        attachGestures(root, iv)

        val lp = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        imageRoot = root; imageLp = lp; drawMode = true
        showPanel(iv)
    }

    fun hide() {
        imageRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        imageRoot = null; panel = null
    }

    private fun setDrawMode(on: Boolean) {
        drawMode = on
        val lp = imageLp ?: return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = if (on) lp.flags or f else lp.flags and f.inv()
        wm.updateViewLayout(imageRoot, lp)
    }

    // 1 ngón: kéo ảnh. 2 ngón: phóng to/thu nhỏ + xoay.
    private fun attachGestures(root: View, iv: View) {
        var lx = 0f; var ly = 0f; var ld = 0f; var la = 0f; var resync = false
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; resync = false }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { ld = dist(e); la = angle(e) }
                MotionEvent.ACTION_POINTER_UP -> resync = true
                MotionEvent.ACTION_MOVE ->
                    if (e.pointerCount >= 2) {
                        val d = dist(e); val a = angle(e)
                        if (ld > 0f) { val s = d / ld; iv.scaleX *= s; iv.scaleY *= s }
                        var da = a - la
                        if (da > 180) da -= 360
                        if (da < -180) da += 360
                        iv.rotation += da
                        ld = d; la = a
                    } else if (resync) {
                        lx = e.x; ly = e.y; resync = false
                    } else {
                        iv.translationX += e.x - lx; iv.translationY += e.y - ly
                        lx = e.x; ly = e.y
                    }
            }
            true
        }
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun angle(e: MotionEvent) = Math.toDegrees(
        atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())
    ).toFloat()

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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(10), p(8), p(10), p(8))
            background = GradientDrawable().apply { setColor(Color.argb(230, 34, 34, 34)); cornerRadius = p(16).toFloat() }
            addView(row)
            addView(seek, LinearLayout.LayoutParams(p(230), -2))
        }
        val lp = WindowManager.LayoutParams(
            -2, -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = p(16); y = p(120) }

        var ox = 0f; var oy = 0f
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
