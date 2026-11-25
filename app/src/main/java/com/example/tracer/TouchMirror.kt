package com.example.tracer

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Đọc cảm ứng thô (getevent) qua Shizuku để biết khi nào 2 ngón đang zoom/kéo/xoay
 * ở app khác (vd ibisPaint). 1 ngón (đang vẽ) bị bỏ qua. Không lưu gì cả.
 */
class TouchMirror(
    private val onFrame: (Frame?) -> Unit,
    private val onDead: (String) -> Unit
) {
    /** 2 ngón đầu tiên, toạ độ 0..1 theo hướng tự nhiên của màn hình cảm ứng */
    class Frame(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val key: Int)

    companion object {
        /** null = sẵn sàng; ngược lại là lời nhắc cho người dùng */
        fun ready(): String? = try {
            if (!Shizuku.pingBinder()) {
                "Hãy cài Shizuku, mở nó và bấm Start"
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(100)
                "Hãy bấm Cho phép cho Trace trong hộp thoại Shizuku, rồi bấm lại nút này"
            } else null
        } catch (e: Throwable) {
            "Chưa kết nối được Shizuku"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var proc: Process? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            var err: String?
            try { err = loop() } catch (e: Throwable) { err = "Lỗi đọc cảm ứng: ${e.message}" }
            val was = running
            running = false
            proc?.destroy()
            val msg = err
            if (was && msg != null) main.post { onDead(msg) }
        }.start()
    }

    fun stop() {
        running = false
        proc?.destroy()
    }

    private fun sh(vararg cmd: String): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf(*cmd), null, null) as Process
    }

    private fun loop(): String? {
        // 1. tìm thiết bị màn hình cảm ứng + khoảng giá trị toạ độ
        val info = sh("getevent", "-pl")
        val text = info.inputStream.bufferedReader().readText()
        info.destroy()
        var dev: String? = null
        var maxX = 0f
        var maxY = 0f
        for (block in text.split("add device")) {
            val path = Regex("/dev/input/event\\d+").find(block)?.value ?: continue
            val mx = Regex("ABS_MT_POSITION_X\\s*:[^\\n]*?max (\\d+)").find(block)?.groupValues?.get(1)?.toFloatOrNull()
            val my = Regex("ABS_MT_POSITION_Y\\s*:[^\\n]*?max (\\d+)").find(block)?.groupValues?.get(1)?.toFloatOrNull()
            if (mx != null && my != null && mx > 0f && my > 0f && (dev == null || "INPUT_PROP_DIRECT" in block)) {
                dev = path; maxX = mx; maxY = my
            }
        }
        if (dev == null) return "Không tìm thấy màn hình cảm ứng"

        // 2. đọc luồng sự kiện (giao thức multi-touch "B")
        val p = sh("getevent", "-q", dev)
        proc = p
        val re = Regex("([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})\\s*$")
        val ids = IntArray(16) { -1 }
        val xs = FloatArray(16)
        val ys = FloatArray(16)
        var slot = 0
        var pinching = false
        p.inputStream.bufferedReader().use { rd ->
            while (running) {
                val line = rd.readLine() ?: break
                val mt = re.find(line) ?: continue
                val type = mt.groupValues[1].toInt(16)
                val code = mt.groupValues[2].toInt(16)
                val v = mt.groupValues[3].toLong(16).toInt()
                if (type == 3) {
                    when (code) {
                        0x2f -> slot = v.coerceIn(0, 15)          // ABS_MT_SLOT
                        0x39 -> ids[slot] = if (v < 0) -1 else v   // ABS_MT_TRACKING_ID
                        0x35 -> xs[slot] = v / maxX                // ABS_MT_POSITION_X
                        0x36 -> ys[slot] = v / maxY                // ABS_MT_POSITION_Y
                    }
                } else if (type == 0 && code == 0) {               // SYN_REPORT: hết một khung
                    var a = -1
                    var b = -1
                    for (i in 0 until 16) {
                        if (ids[i] >= 0) { if (a < 0) a = i else if (b < 0) b = i }
                    }
                    if (b >= 0) {
                        val f = Frame(xs[a], ys[a], xs[b], ys[b], ids[a] * 31 + ids[b])
                        pinching = true
                        main.post { onFrame(f) }
                    } else if (pinching) {
                        pinching = false
                        main.post { onFrame(null) }
                    }
                }
            }
        }
        return if (running) "Shizuku đã dừng — hãy bật lại đồng bộ" else null
    }
}
