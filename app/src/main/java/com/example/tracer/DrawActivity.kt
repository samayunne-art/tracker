package com.example.tracer

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class DrawActivity : AppCompatActivity() {

    private lateinit var cv: TraceCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bmp = BitmapFactory.decodeFile(File(filesDir, "trace.png").path)
        if (bmp == null) {
            Toast.makeText(this, "Hãy chọn ảnh trước", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val d = resources.displayMetrics.density
        fun p(v: Int) = (v * d).toInt()
        val base = maxOf(bmp.width, bmp.height) / 500f

        cv = TraceCanvasView(this, bmp)
        cv.brushSize = maxOf(base, 1f)

        fun chipBg(sel: Boolean) = GradientDrawable().apply {
            setColor(if (sel) 0xFF2979FF.toInt() else 0x33FFFFFF)
            cornerRadius = p(12).toFloat()
        }
        fun chip(t: String, f: (TextView) -> Unit): TextView {
            val c = TextView(this)
            c.text = t; c.setTextColor(Color.WHITE); c.textSize = 18f
            c.gravity = Gravity.CENTER
            c.setPadding(p(9), p(8), p(9), p(8))
            c.background = chipBg(false)
            c.setOnClickListener { f(c) }
            return c
        }
        fun lp() = LinearLayout.LayoutParams(-2, -2).apply { setMargins(p(2), p(4), p(2), p(4)) }

        fun seekRow(label: String, max: Int, init: Int, on: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            val tv = TextView(this)
            tv.text = label; tv.setTextColor(Color.WHITE); tv.textSize = 13f; tv.minWidth = p(80)
            row.addView(tv)
            val sb = SeekBar(this)
            sb.max = max; sb.progress = init
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) = on(v)
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            row.addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
            return row
        }

        // hàng nút
        val eraserChip = chip("🧽") { b ->
            cv.erasing = !cv.erasing
            b.background = chipBg(cv.erasing)
        }
        val actions = LinearLayout(this)
        actions.gravity = Gravity.CENTER
        actions.addView(chip("↶") { cv.undo() }, lp())
        actions.addView(chip("↷") { cv.redo() }, lp())
        actions.addView(eraserChip, lp())
        actions.addView(chip("🗑") { cv.clear() }, lp())
        actions.addView(chip("👁") { cv.showRef = !cv.showRef }, lp())
        actions.addView(chip("⤢") { cv.resetView() }, lp())
        actions.addView(chip("💾") {
            AlertDialog.Builder(this)
                .setTitle("Lưu PNG")
                .setItems(arrayOf("Nền trong suốt (nhập vào ibisPaint làm layer)", "Nền trắng")) { _, i ->
                    savePng(i == 1)
                }
                .show()
        }, lp())

        // hàng màu
        val colors = listOf(Color.BLACK, 0xFFD32F2F.toInt(), 0xFF1976D2.toInt(),
            0xFF388E3C.toInt(), 0xFFF57C00.toInt(), Color.WHITE)
        val dots = ArrayList<GradientDrawable>()
        fun select(i: Int) {
            cv.brushColor = colors[i]
            cv.erasing = false
            eraserChip.background = chipBg(false)
            for (k in dots.indices) {
                dots[k].setStroke(p(if (k == i) 3 else 1), if (k == i) Color.YELLOW else Color.GRAY)
            }
        }
        val palette = LinearLayout(this)
        palette.gravity = Gravity.CENTER
        for (i in colors.indices) {
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(colors[i])
            dots.add(g)
            val dot = TextView(this)
            dot.background = g
            dot.setOnClickListener { select(i) }
            val dlp = LinearLayout.LayoutParams(p(32), p(32))
            dlp.setMargins(p(6), p(4), p(6), p(4))
            palette.addView(dot, dlp)
        }
        select(0)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(p(12), p(6), p(12), p(6))
        panel.setBackgroundColor(0xFF202020.toInt())
        panel.addView(seekRow("Độ mờ ảnh", 255, 128) { cv.refAlpha = it })
        panel.addView(seekRow("Nét vẽ", 39, 2) { cv.brushSize = maxOf(base, 1f) * (1 + it) / 3f })
        panel.addView(palette)
        panel.addView(actions)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF202020.toInt())
        root.addView(cv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            ins
        }
        setContentView(root)
    }

    private fun savePng(whiteBg: Boolean) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Lưu ảnh cần Android 10 trở lên", Toast.LENGTH_SHORT).show(); return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "trace_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Trace")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Không lưu được", Toast.LENGTH_SHORT).show(); return
        }
        contentResolver.openOutputStream(uri)?.use { cv.export(whiteBg).compress(Bitmap.CompressFormat.PNG, 100, it) }
        Toast.makeText(this, "Đã lưu vào Pictures/Trace", Toast.LENGTH_SHORT).show()
    }
}
