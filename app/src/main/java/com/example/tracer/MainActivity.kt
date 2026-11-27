package com.example.tracer

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var prefs: SharedPreferences
    private val worker = Executors.newSingleThreadExecutor()
    private var src: Bitmap? = null
    private var gen = 0

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pick(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("trace_prefs", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        fun p(v: Int) = (v * d).toInt()

        status = TextView(this)
        status.textSize = 16f
        preview = ImageView(this)
        preview.scaleType = ImageView.ScaleType.FIT_CENTER
        preview.setBackgroundColor(0xFFEEEEEE.toInt())

        fun btn(t: String, f: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { f() }
            return b
        }

        val lineCheck = CheckBox(this)
        lineCheck.text = "Chuyển thành nét viền (dễ trace hơn)"
        lineCheck.isChecked = prefs.getBoolean("lineart", false)
        lineCheck.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("lineart", on).apply()
            refresh(false)
        }

        val strengthLabel = TextView(this)
        strengthLabel.text = "Độ đậm nét (khi bật nét viền)"
        val strength = SeekBar(this)
        strength.max = 100
        strength.progress = prefs.getInt("strength", 50)
        strength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                prefs.edit().putInt("strength", strength.progress).apply()
                refresh(false)
            }
        })

        val colorRow = LinearLayout(this)
        colorRow.gravity = Gravity.CENTER_VERTICAL
        val colors = intArrayOf(Color.BLACK, 0xFFD32F2F.toInt(), 0xFF1976D2.toInt())
        val names = arrayOf("Đen", "Đỏ", "Xanh")
        for (i in colors.indices) {
            val c = TextView(this)
            c.text = names[i]
            c.setTextColor(Color.WHITE)
            c.setBackgroundColor(colors[i])
            c.setPadding(p(16), p(8), p(16), p(8))
            c.setOnClickListener {
                prefs.edit().putInt("color", colors[i]).apply()
                refresh(false)
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = p(8)
            colorRow.addView(c, lp)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(p(20), p(20), p(20), p(20))
        root.addView(status)
        root.addView(btn("1. Bật Trợ năng cho Trace") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2. Chọn ảnh để trace") { picker.launch("image/*") })
        val plp = LinearLayout.LayoutParams(-1, p(190))
        plp.topMargin = p(8)
        root.addView(preview, plp)
        root.addView(lineCheck)
        root.addView(strengthLabel)
        root.addView(strength)
        root.addView(colorRow)
        root.addView(btn("3. Hiện lớp ảnh") {
            val s = TraceService.instance
            if (s == null) toast("Hãy bật Trợ năng trước") else s.show()
        })
        root.addView(btn("Tắt lớp ảnh") { TraceService.instance?.hide() })
        root.addView(btn("Vẽ trong app (ảnh zoom theo canvas)") {
            if (File(filesDir, "trace.png").exists())
                startActivity(Intent(this, DrawActivity::class.java))
            else toast("Hãy chọn ảnh trước")
        })
        root.addView(btn("❓ Hướng dẫn") { startActivity(Intent(this, HelpActivity::class.java)) })

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)

        // nạp lại ảnh gốc của lần trước (để chỉnh nét viền được ngay)
        val srcFile = File(filesDir, "trace_src.png")
        if (srcFile.exists()) {
            worker.execute {
                val b = BitmapFactory.decodeFile(srcFile.path)
                runOnUiThread { if (b != null) { src = b; refresh(false) } }
            }
        }

        if (!prefs.getBoolean("seen_help", false)) {
            prefs.edit().putBoolean("seen_help", true).apply()
            startActivity(Intent(this, HelpActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TraceService.instance != null) "✅ Trợ năng đã bật" else "⚠️ Chưa bật Trợ năng"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun pick(uri: Uri) {
        worker.execute {
            val raw = loadScaled(uri)
            if (raw == null) {
                runOnUiThread { toast("Không đọc được ảnh") }
                return@execute
            }
            val fixed = fixOrientation(raw, uri)
            File(filesDir, "trace_src.png").outputStream().use { fixed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            prefs.edit().putLong("img_id", System.currentTimeMillis()).apply()
            runOnUiThread { src = fixed; refresh(true) }
        }
    }

    /** Tạo trace.png từ ảnh gốc (gốc hoặc nét viền), cập nhật xem trước và lớp phủ */
    private fun refresh(showOverlay: Boolean) {
        val s = src ?: return
        val v = ++gen
        val line = prefs.getBoolean("lineart", false)
        val strength = prefs.getInt("strength", 50)
        val color = prefs.getInt("color", Color.BLACK)
        worker.execute {
            if (v != gen) return@execute
            val out = if (line) LineArt.convert(s, strength, color) else s
            if (v != gen) return@execute
            File(filesDir, "trace.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            runOnUiThread {
                if (v != gen) return@runOnUiThread
                preview.setImageBitmap(out)
                val svc = TraceService.instance
                if (showOverlay) svc?.show() else svc?.reloadImage()
            }
        }
    }

    private fun loadScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Ảnh chụp từ camera hay lưu kèm thông tin xoay (EXIF): xoay lại cho đúng chiều */
    private fun fixOrientation(bmp: Bitmap, uri: Uri): Bitmap {
        var o = ExifInterface.ORIENTATION_NORMAL
        try {
            contentResolver.openInputStream(uri)?.use {
                o = ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) { }
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
