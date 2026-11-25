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

        fun chip(t: String, f: (TextView) -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 18f
            gravity = Gravity.CENTER
            setPadding(p(14), p(8), p(14), p(8))
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = p(12).toFloat() }
            setOnClickListener { f(this) }
        }

        fun seekRow(label: String, max: Int, init: Int, on: (Int) -> Unit) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label; setTextColor(Color.WHITE); textSize = 13f; minWidth = p(80)
            })
            addView(SeekBar(context).apply {
                this.max = max; progress = init
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) = on(v)
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // hàng nút
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(-2, -2).apply { setMargins(p(4), p(4), p(4), p(4)) }
            addView(chip("↶") { cv.undo() }, lp)
            addView(chip("🗑") { cv.clear() }, lp)
            addView(chip("👁") { cv.showRef = !cv.showRef }, lp)
            addView(chip("⤢") { cv.resetView() }, lp)
            addView(chip("💾") { savePng() }, lp)
        }

        // hàng màu
        val colors = listOf(Color.BLACK, 0xFFD32F2F.toInt(), 0xFF1976D2.toInt(),
            0xFF388E3C.toInt(), 0xFFF57C00.toInt(), Color.WHITE)
        val dots = ArrayList<GradientDrawable>()
        fun select(i: Int) {
            cv.brushColor = colors[i]
            dots.forEachIndexed { k, g -> g.setStroke(p(if (k == i) 3 else 1), if (k == i) Color.YELLOW else Color.GRAY) }
        }
        val palette = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            colors.forEachIndexed { i, col ->
                val g = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
                dots.add(g)
                addView(TextView(context).apply { background = g; setOnClickListener { select(i) } },
                    LinearLayout.LayoutParams(p(32), p(32)).apply { setMargins(p(6), p(4), p(6), p(4)) })
            }
        }
        select(0)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(12), p(6), p(12), p(6))
            setBackgroundColor(0xFF202020.toInt())
            addView(seekRow("Độ mờ ảnh", 255, 128) { cv.refAlpha = it })
            addView(seekRow("Nét vẽ", 39, 2) { cv.brushSize = maxOf(base, 1f) * (1 + it) / 3f })
            addView(palette)
            addView(actions)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202020.toInt())
            addView(cv, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(panel, LinearLayout.LayoutParams(-1, -2))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            ins
        }
        setContentView(root)
    }

    private fun savePng() {
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
        contentResolver.openOutputStream(uri)?.use { cv.export().compress(Bitmap.CompressFormat.PNG, 100, it) }
        Toast.makeText(this, "Đã lưu vào Pictures/Trace", Toast.LENGTH_SHORT).show()
    }
}
