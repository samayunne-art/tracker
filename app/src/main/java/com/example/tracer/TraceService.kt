package com.example.tracer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ✏️ Vẽ  : lớp ảnh cho chạm xuyên qua, vẽ ở app bên dưới.
 * 🔍 Zoom: 2 ngón zoom/kéo/xoay trên lớp ảnh -> ảnh đổi ngay, rồi lặp lại cử chỉ đó lên app bên dưới.
 * ✋ Ảnh : chỉ kéo/zoom riêng ảnh để khớp lại.
 * Tăng âm lượng: đổi Vẽ <-> Zoom. Giảm âm lượng: ẩn/hiện ảnh.
 */
class TraceService : AccessibilityService() {

    companion object {
        var instance: TraceService? = null
        private val C_OFF = 0x33FFFFFF
        private val C_SEL = 0xFF2979FF.toInt()
        private val C_OK = 0xFF2E7D32.toInt()
    }

    private enum class Mode { DRAW, ZOOM, EDIT }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var dens = 1f

    private var imageRoot: FrameLayout? = null
    private var imageView: ImageView? = null
    private var imageLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var body: View? = null
    private var mini: View? = null
    private var hint: TextView? = null
    private var eyeChip: TextView? = null
    private var lockChip: TextView? = null
    private var syncChip: TextView? = null
    private val chips = HashMap<Mode, TextView>()

    private var mode = Mode.DRAW
    private var replaying = false
    private var imageHidden = false
    private var lockRot = false
    private var alphaPct = 50
    private var bmpW = 0
    private var bmpH = 0
    private val mat = Matrix()

    // bám theo zoom qua Shizuku (tuỳ chọn)
    private var mirror: TouchMirror? = null
    private var prevPinch: FloatArray? = null
    private var prevKey = 0
    private var gotFrame = false

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
        prefs = getSharedPreferences("trace_prefs", MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hide(); instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (imageRoot == null) return false
        val k = event.keyCode
        if (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (k == KeyEvent.KEYCODE_VOLUME_UP) setMode(if (mode == Mode.DRAW) Mode.ZOOM else Mode.DRAW)
            else toggleHidden()
        }
        return true
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

    // ---------- vị trí ảnh: nhớ lại, nạp lại ----------

    private fun fit() {
        val sz = screenSize()
        mat.setRectToRect(
            RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()),
            RectF(0f, 0f, sz[0].toFloat(), sz[1].toFloat()),
            Matrix.ScaleToFit.CENTER
        )
    }

    private fun saveState() {
        if (imageRoot == null) return
        val v = FloatArray(9)
        mat.getValues(v)
        val e = prefs.edit()
        e.putString("mat", v.joinToString(","))
        e.putLong("mat_img", prefs.getLong("img_id", 0L))
        e.putInt("mat_w", bmpW)
        e.putInt("mat_h", bmpH)
        e.putInt("alpha", alphaPct)
        e.putBoolean("lockRot", lockRot)
        val lp = panelLp
        if (lp != null) { e.putInt("px", lp.x); e.putInt("py", lp.y) }
        e.apply()
    }

    private fun loadSavedMatrix(): Boolean {
        val s = prefs.getString("mat", null) ?: return false
        if (prefs.getLong("mat_img", -1L) != prefs.getLong("img_id", 0L)) return false
        if (prefs.getInt("mat_w", 0) != bmpW || prefs.getInt("mat_h", 0) != bmpH) return false
        val parts = s.split(",")
        if (parts.size != 9) return false
        return try {
            mat.setValues(FloatArray(9) { parts[it].toFloat() })
            true
        } catch (e: Exception) { false }
    }

    /** Nạp lại ảnh (vd. vừa đổi sang nét viền), giữ nguyên vị trí */
    fun reloadImage() {
        val iv = imageView ?: return
        val bmp = BitmapFactory.decodeFile(File(filesDir, "trace.png").path) ?: return
        iv.setImageBitmap(bmp)
        if (bmp.width != bmpW || bmp.height != bmpH) {
            bmpW = bmp.width; bmpH = bmp.height
            fit()
        }
        iv.imageMatrix = mat
    }

    fun show() {
        hide()
        val file = File(filesDir, "trace.png")
        if (!file.exists()) { toast("Hãy chọn ảnh trước"); return }
        val bmp = BitmapFactory.decodeFile(file.path) ?: return
        bmpW = bmp.width; bmpH = bmp.height
        if (!loadSavedMatrix()) fit()
        alphaPct = prefs.getInt("alpha", 50)
        lockRot = prefs.getBoolean("lockRot", false)
        imageHidden = false

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.MATRIX
        iv.setImageBitmap(bmp)
        iv.imageMatrix = mat
        iv.alpha = alphaPct / 100f

        val root = FrameLayout(this)
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
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
        saveState()
        stopMirror()
        handler.removeCallbacksAndMessages(null)
        replaying = false
        imageRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        imageRoot = null; imageView = null; imageLp = null
        panel = null; panelLp = null; body = null; mini = null; hint = null
        eyeChip = null; lockChip = null; syncChip = null
        chips.clear(); rec.clear(); recA = -1; recB = -1
    }

    // ---------- chế độ, hiện/ẩn, lật ----------

    private fun chipBgC(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 12f * dens
    }

    private fun chipBg(sel: Boolean) = chipBgC(if (sel) C_SEL else C_OFF)

    private fun setMode(m: Mode) {
        mode = m
        recA = -1; recB = -1; rec.clear()
        updateTouchable()
        for ((k, v) in chips) v.background = chipBg(k == m)
        hint?.text = when {
            mirror != null && m == Mode.DRAW && gotFrame -> "✓ Đã nhận cử chỉ: ảnh bám theo zoom 2 ngón"
            mirror != null && m == Mode.DRAW -> "Zoom 2 ngón ở app bên dưới, ảnh sẽ bám theo"
            m == Mode.DRAW -> "✏️ Vẽ: chạm xuyên qua ảnh"
            m == Mode.ZOOM -> "🔍 Zoom: 2 ngón, ảnh và app bên dưới cùng đổi"
            else -> "✋ Ảnh: kéo/zoom riêng ảnh để khớp lại"
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

    private fun toggleHidden() {
        imageHidden = !imageHidden
        imageView?.visibility = if (imageHidden) View.INVISIBLE else View.VISIBLE
        eyeChip?.background = chipBg(imageHidden)
    }

    private fun flipH() {
        val r = RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat())
        mat.mapRect(r)
        mat.postScale(-1f, 1f, r.centerX(), r.centerY())
        imageView?.imageMatrix = mat
        saveState()
    }

    private fun setMini(on: Boolean) {
        val padX = (10 * dens).toInt()
        val padY = (8 * dens).toInt()
        body?.visibility = if (on) View.GONE else View.VISIBLE
        mini?.visibility = if (on) View.VISIBLE else View.GONE
        val pn = panel ?: return
        if (on) {
            pn.background = null
            pn.setPadding(0, 0, 0, 0)
        } else {
            pn.background = GradientDrawable().apply {
                setColor(Color.argb(230, 34, 34, 34))
                cornerRadius = 16f * dens
            }
            pn.setPadding(padX, padY, padX, padY)
        }
    }

    // ---------- bám theo zoom của app khác (Shizuku) ----------

    private fun toggleSync() {
        if (mirror != null) { stopMirror(); setMode(mode); return }
        val err = TouchMirror.ready()
        if (err != null) { toast(err); return }
        gotFrame = false
        mirror = TouchMirror(
            onFrame = { onMirrorFrame(it) },
            onDead = { msg -> toast(msg); stopMirror(); setMode(mode) }
        ).also { it.start() }
        syncChip?.background = chipBg(true)
        setMode(mode)
    }

    private fun stopMirror() {
        mirror?.stop(); mirror = null; prevPinch = null; gotFrame = false
        syncChip?.background = chipBg(false)
    }

    @Suppress("DEPRECATION")
    private fun onMirrorFrame(f: TouchMirror.Frame?) {
        val root = imageRoot
        if (f == null || root == null || mode != Mode.DRAW || replaying) {
            if (prevPinch != null) { prevPinch = null; saveState() }
            return
        }
        if (!gotFrame) {
            gotFrame = true
            syncChip?.background = chipBgC(C_OK)
            setMode(mode)
        }
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

    // ---------- biến đổi ảnh ----------

    /** [tâm x, tâm y, khoảng cách, góc độ] của 2 ngón */
    private fun pinchOf(x1: Float, y1: Float, x2: Float, y2: Float) = floatArrayOf(
        (x1 + x2) / 2f, (y1 + y2) / 2f, hypot(x2 - x1, y2 - y1),
        Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
    )

    private fun applyPinch(o: FloatArray, n: FloatArray) {
        val s = (if (o[2] > 0f) n[2] / o[2] else 1f).coerceIn(0.2f, 5f)
        var da = if (lockRot) 0f else n[3] - o[3]
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveState()
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
        saveState()
        val pts = ArrayList<FloatArray>(rec)
        rec.clear()
        if (!doReplay || pts.size < 3) return
        val first = pts.first()
        val last = pts.last()
        val f = pinchOf(first[1], first[2], first[3], first[4])
        val l = pinchOf(last[1], last[2], last[3], last[4])
        // chạm nhẹ 2 ngón (không di chuyển) thì bỏ qua, tránh vô tình kích hoạt "hoàn tác" ở app bên dưới
        if (hypot(l[0] - f[0], l[1] - f[1]) < 12f && abs(l[2] - f[2]) < 12f && abs(l[3] - f[3]) < 3f) return

        val a0 = atan2((first[4] - first[2]).toDouble(), (first[3] - first[1]).toDouble())
        fun fix(p: FloatArray): FloatArray {
            if (!lockRot) return p
            val cx = (p[1] + p[3]) / 2f
            val cy = (p[2] + p[4]) / 2f
            val r = hypot(p[3] - p[1], p[4] - p[2]) / 2f
            val dx = (kotlin.math.cos(a0) * r).toFloat()
            val dy = (kotlin.math.sin(a0) * r).toFloat()
            return floatArrayOf(p[0], cx - dx, cy - dy, cx + dx, cy + dy)
        }
        val step = maxOf(1, pts.size / 16)
        val sel = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.size - 1 }
        val loc = IntArray(2)
        imageRoot?.getLocationOnScreen(loc)
        fun c(v: Float) = if (v < 0f) 0f else v
        val pa = Path()
        val pb = Path()
        sel.forEachIndexed { i, raw ->
            val p = fix(raw)
            val ax = c(p[1] + loc[0]); val ay = c(p[2] + loc[1])
            val bx = c(p[3] + loc[0]); val by = c(p[4] + loc[1])
            if (i == 0) { pa.moveTo(ax, ay); pb.moveTo(bx, by) }
            else { pa.lineTo(ax, ay); pb.lineTo(bx, by) }
        }
        val dur = (last[0] - first[0]).toLong().coerceIn(100L, 800L)
        replay(listOf(pa, pb), dur)
    }

    /** Lặp lại cử chỉ 2 ngón lên app bên dưới (lớp ảnh tạm thời cho chạm xuyên qua) */
    private fun replay(paths: List<Path>, dur: Long) {
        replaying = true
        hint?.text = "⏳ Đang đồng bộ, đợi chút rồi hãy zoom tiếp"
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
        }, 80L)
        handler.postDelayed({ if (replaying) finishReplay() }, dur + 2000L)
    }

    private fun finishReplay() {
        replaying = false
        setMode(mode)
    }

    // ---------- bảng điều khiển nổi ----------

    /** Kéo bảng; nếu chỉ chạm (không kéo) thì gọi onTap */
    private fun attachDrag(v: View, onTap: (() -> Unit)?) {
        var ox = 0f
        var oy = 0f
        var sx = 0f
        var sy = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            val lp = panelLp
            if (lp != null) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        ox = lp.x - e.rawX; oy = lp.y - e.rawY
                        sx = e.rawX; sy = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - sx) > 10 * dens || abs(e.rawY - sy) > 10 * dens) moved = true
                        if (moved) {
                            lp.x = (e.rawX + ox).toInt(); lp.y = (e.rawY + oy).toInt()
                            panel?.let { wm.updateViewLayout(it, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) saveState() else onTap?.invoke()
                    }
                }
            }
            true
        }
    }

    private fun showPanel(iv: ImageView) {
        dens = resources.displayMetrics.density
        fun p(v: Int) = (v * dens).toInt()
        fun gap() = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = p(4) }
        fun chip(t: String, f: (TextView) -> Unit): TextView {
            val c = TextView(this)
            c.text = t; c.setTextColor(Color.WHITE); c.textSize = 15f
            c.gravity = Gravity.CENTER
            c.setPadding(p(9), p(6), p(9), p(6))
            c.background = chipBg(false)
            c.setOnClickListener { f(c) }
            return c
        }
        fun modeChip(m: Mode, label: String): TextView {
            val c = chip(label) { setMode(m) }
            chips[m] = c
            return c
        }

        // hàng 1: kéo, 3 chế độ, thu nhỏ, đóng
        val handle = TextView(this)
        handle.text = "✥"; handle.setTextColor(Color.WHITE); handle.textSize = 20f
        handle.setPadding(p(8), p(2), p(10), p(2))
        attachDrag(handle, null)
        val row1 = LinearLayout(this)
        row1.gravity = Gravity.CENTER_VERTICAL
        row1.addView(handle)
        row1.addView(modeChip(Mode.DRAW, "✏️"), gap())
        row1.addView(modeChip(Mode.ZOOM, "🔍"), gap())
        row1.addView(modeChip(Mode.EDIT, "✋"), gap())
        row1.addView(chip("—") { setMini(true) }, gap())
        row1.addView(chip("✕") { hide() })

        // thanh độ mờ
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = alphaPct
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                alphaPct = v
                iv.alpha = maxOf(v, 3) / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { saveState() }
        })

        // hàng 2: ẩn/hiện, lật, khoá xoay, bám theo
        val eye = chip("👁") { toggleHidden() }
        eyeChip = eye
        val flip = chip("⇋") { flipH() }
        val lock = chip(if (lockRot) "🔒" else "↻") { b ->
            lockRot = !lockRot
            b.text = if (lockRot) "🔒" else "↻"
            b.background = chipBg(lockRot)
            saveState()
        }
        lock.background = chipBg(lockRot)
        lockChip = lock
        val sync = chip("🔗") { toggleSync() }
        syncChip = sync
        val row2 = LinearLayout(this)
        row2.gravity = Gravity.CENTER_VERTICAL
        row2.addView(eye, gap())
        row2.addView(flip, gap())
        row2.addView(lock, gap())
        row2.addView(sync)

        val hintView = TextView(this)
        hintView.setTextColor(0xCCFFFFFF.toInt())
        hintView.textSize = 11f
        hint = hintView

        val bodyView = LinearLayout(this)
        bodyView.orientation = LinearLayout.VERTICAL
        bodyView.addView(row1)
        bodyView.addView(seek, LinearLayout.LayoutParams(p(240), -2))
        val r2lp = LinearLayout.LayoutParams(-2, -2)
        r2lp.topMargin = p(4); r2lp.bottomMargin = p(2)
        bodyView.addView(row2, r2lp)
        bodyView.addView(hintView)

        // nút tròn nhỏ khi thu nhỏ bảng
        val miniView = TextView(this)
        miniView.text = "◐"; miniView.setTextColor(Color.WHITE); miniView.textSize = 20f
        miniView.gravity = Gravity.CENTER
        miniView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(210, 34, 34, 34))
        }
        miniView.visibility = View.GONE
        attachDrag(miniView) { setMini(false) }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(bodyView)
        box.addView(miniView, LinearLayout.LayoutParams(p(44), p(44)))
        body = bodyView; mini = miniView; panel = box

        val sz = screenSize()
        val lp = WindowManager.LayoutParams(
            -2, -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt("px", p(16)).coerceIn(0, maxOf(0, sz[0] - p(60)))
        lp.y = prefs.getInt("py", p(120)).coerceIn(0, maxOf(0, sz[1] - p(60)))
        panelLp = lp
        wm.addView(box, lp)
        setMini(false)
        setMode(Mode.DRAW)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
