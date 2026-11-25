package com.example.tracer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            saveImage(uri)
            TraceService.instance?.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }
        root.addView(status)
        root.addView(btn("1. Bật Trợ năng cho Trace") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2. Chọn ảnh để trace") { picker.launch("image/*") })
        root.addView(btn("3. Hiện lớp ảnh") {
            val s = TraceService.instance
            if (s == null) Toast.makeText(this, "Hãy bật Trợ năng trước", Toast.LENGTH_SHORT).show()
            else s.show()
        })
        root.addView(btn("Tắt lớp ảnh") { TraceService.instance?.hide() })
        root.addView(btn("Vẽ trong app (ảnh zoom theo canvas)") {
            if (File(filesDir, "trace.png").exists())
                startActivity(Intent(this, DrawActivity::class.java))
            else
                Toast.makeText(this, "Hãy chọn ảnh trước", Toast.LENGTH_SHORT).show()
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TraceService.instance != null) "✅ Trợ năng đã bật" else "⚠️ Chưa bật Trợ năng"
    }

    private fun saveImage(uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return
        File(filesDir, "trace.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
