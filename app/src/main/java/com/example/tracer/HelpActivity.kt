package com.example.tracer

import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HelpActivity : AppCompatActivity() {

    private val steps = """
1. Bật Trợ năng: Cài đặt → Trợ năng → Trace overlay → Bật. Sau mỗi lần cài bản mới, hãy tắt rồi bật lại.

2. Chọn ảnh để trace. Có thể bật "nét viền" để ảnh thành các đường nét (dễ trace hơn) và chọn màu nét.

3. Bấm "Hiện lớp ảnh": ảnh mờ nằm trên mọi app, kèm bảng điều khiển nhỏ. Kéo bảng bằng nút ✥, thu nhỏ bằng nút —.

4. Trên bảng điều khiển:
• ✏️ Vẽ: chạm xuyên qua ảnh để vẽ ở app bên dưới.
• 🔍 Zoom: 2 ngón zoom/kéo/xoay, ảnh và app bên dưới cùng đổi.
• ✋ Ảnh: chỉ kéo/zoom riêng ảnh để khớp lại.
• 👁 ẩn/hiện ảnh · ⇋ lật ngang · ↻/🔒 khoá xoay · 🔗 bám theo zoom (cần Shizuku).
• Phím Tăng âm lượng: đổi Vẽ ↔ Zoom. Phím Giảm âm lượng: ẩn/hiện ảnh.

5. Quay video mà không có ảnh: dùng video quá trình vẽ của ibisPaint, hoặc quay "một ứng dụng" (Android 14+), hoặc ẩn ảnh trước khi quay.

6. "Vẽ trong app": canvas riêng, ảnh và nét vẽ luôn zoom cùng nhau. Có tẩy, hoàn tác/làm lại, xuất PNG nền trong suốt để nhập vào ibisPaint làm layer.
""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Cách dùng Trace"
        title.textSize = 22f
        title.setTypeface(title.typeface, Typeface.BOLD)
        box.addView(title)

        val body = TextView(this)
        body.text = steps
        body.textSize = 15f
        body.setLineSpacing(0f, 1.2f)
        body.setPadding(0, pad / 2, 0, pad / 2)
        box.addView(body)

        val ok = Button(this)
        ok.text = "Đã hiểu"
        ok.setOnClickListener { finish() }
        box.addView(ok)

        val sv = ScrollView(this)
        sv.addView(box)
        setContentView(sv)
    }
}
