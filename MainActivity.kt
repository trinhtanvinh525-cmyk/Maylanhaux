package com.nagakawa.ir

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

enum class AcMode(val label: String, val icon: String, val bits: Int, val hasTemp: Boolean) {
    AUTO("Tự động", "A", 0b000, true), COOL("Mát", "❄", 0b001, true),
    HEAT("Nóng", "☀", 0b100, true), DRY("Khô", "💧", 0b010, true), FAN("Quạt", "🌀", 0b110, false)
}

enum class FanSpeed(val label: String, val bits: Int) {
    AUTO("Tự động", 0b101), S1("1", 0b011), S2("2", 0b010), S3("3", 0b001)
}

enum class SwingDir(val label: String, val bits: Int) {
    UP("Lên", 0b001), MID("Giữa", 0b010), DOWN("Xuống", 0b011), SWING("Đảo gió", 0b000), OFF("Đứng yên", 0b111)
}

enum class ExtraFn(val raw: String, val verified: Boolean) {
    AUX_HEAT("Aux Heat", false), BODY_FEEL("Body Feel", false), DISPLAY("DISPLAY", true),
    ECO("ECO", true), HEALTH("Health", true), HUMIDIFICATION("Humidification", false),
    LR_WIND_MODE("LR_WIND_MODE", true), MUTE("MUTE", true), TURBO("TURBO", true)
}

class MainActivity : Activity() {
    private val irExecutor = Executors.newSingleThreadExecutor()
    private var irManager: ConsumerIrManager? = null

    private var power = true; private var temp = 26; private var mode = AcMode.COOL
    private var fan = FanSpeed.S3; private var swing = SwingDir.UP; private var sleep = false
    private var timerHours = 0; private var display = true; private var eco = false
    private var turbo = false; private var health = false; private var mute = false; private var lrWind = false
    private var lastAction = "Khởi tạo"; private var txStatus = "Chưa phát lệnh"; private var lastHex = "--"

    private lateinit var tvTemp: TextView; private lateinit var tvSubTemp: TextView
    private lateinit var tvModeVal: TextView; private lateinit var tvFanVal: TextView; private lateinit var tvSwingVal: TextView
    private lateinit var tvFlags: TextView; private lateinit var tvStatus: TextView
    private lateinit var btnPower: TextView; private lateinit var btnSwing: TextView
    private val modeViews = mutableMapOf<AcMode, Pair<TextView, TextView>>()
    private val auxViews = mutableMapOf<String, Pair<TextView, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        irManager = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        loadPrefs()

        val rootScroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#071126"), Color.parseColor("#0E2954"), Color.parseColor("#1F4E79"), Color.parseColor("#09152E"))
            )
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(36))
        }
        rootScroll.addView(main)

        // Header
        main.addView(label("Nagakawa Air Conditioner", 20f, true, "#FFFFFF"))
        main.addView(label("Profile: AUX 13-Byte • Liquid Glass UI", 11f, false, "#99D6EAFF"))

        // Nhiệt độ lớn trung tâm
        tvTemp = label("26°C", 74f, false, "#FFFFFF").apply { setPadding(0, dp(14), 0, 0) }
        tvSubTemp = label("Nhiệt độ cài đặt (Dự kiến)", 13f, false, "#B3D6EAFF")
        tvFlags = label("", 11f, true, "#5CE1E6").apply { setPadding(dp(8), dp(8), dp(8), dp(14)) }
        main.addView(tvTemp); main.addView(tvSubTemp); main.addView(tvFlags)

        // Bảng thông tin nhanh (Liquid Glass Card)
        val quickCard = row().apply {
            background = glassBg(24f, false)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        tvModeVal = label("Mát", 16f, true, "#FFFFFF")
        tvFanVal = label("3", 16f, true, "#FFFFFF")
        tvSwingVal = label("Lên", 16f, true, "#FFFFFF")
        quickCard.addView(infoCol(tvModeVal, "Chế độ"), weight(1f))
        quickCard.addView(infoCol(tvFanVal, "Tốc độ quạt"), weight(1f))
        quickCard.addView(infoCol(tvSwingVal, "Hướng"), weight(1f))
        main.addView(quickCard)
        space(main, 14)

        // Nút Nguồn & Xoay (Liquid Pill)
        val rowPS = row()
        btnPower = pillBtn("⏻  Nguồn") { power = !power; send(if (power) "Nguồn: Đang bật" else "Nguồn: Đang tắt") }
        btnSwing = pillBtn("⇄  Xoay") {
            swing = if (swing == SwingDir.SWING) SwingDir.UP else SwingDir.SWING
            send("Hướng gió: ${swing.label}")
        }
        rowPS.addView(btnPower, weight(1f, 6)); rowPS.addView(btnSwing, weight(1f, 6))
        main.addView(rowPS)
        space(main, 14)

        // Thanh điều chỉnh nhiệt độ ( − | Nhiệt độ | + )
        val tempBar = row().apply {
            background = glassBg(32f, false)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        tempBar.addView(label("−", 28f, true, "#FFFFFF").apply {
            setPadding(dp(22), dp(6), dp(22), dp(6))
            setOnClickListener { changeTemp(-1) }
        })
        tempBar.addView(label("Nhiệt độ", 16f, true, "#E6FFFFFF"), weight(1f))
        tempBar.addView(label("+", 26f, true, "#FFFFFF").apply {
            setPadding(dp(22), dp(6), dp(22), dp(6))
            setOnClickListener { changeTemp(1) }
        })
        main.addView(tempBar)
        space(main, 14)

        // Khu vực 5 chế độ hoạt động (Nút tròn Liquid Glass)
        val modeCard = row().apply {
            background = glassBg(26f, false)
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        AcMode.entries.forEach { m ->
            val (col, circle, txt) = circleItem(m.icon, m.label) {
                mode = m
                if (m != AcMode.COOL) eco = false
                if (m != AcMode.COOL && m != AcMode.HEAT) turbo = false
                send("Chế độ: ${m.label}")
            }
            modeViews[m] = circle to txt
            modeCard.addView(col, weight(1f))
        }
        main.addView(modeCard)
        space(main, 14)

        // Khu vực 5 chức năng bổ sung
        val auxCard = row().apply {
            background = glassBg(26f, false)
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        val auxItems = listOf(
             Triple("SLEEP", "🌙", "Ngủ") to { sleep = !sleep; send(if (sleep) "Ngủ: Đang bật" else "Ngủ: Đang tắt") },
            Triple("TIMER", "⏱", "Hẹn giờ") to { showTimerDialog() },
            Triple("DIR", "⇅", "Hướng") to {
                swing = SwingDir.entries[(swing.ordinal + 1) % SwingDir.entries.size]
                send("Hướng: ${swing.label}")
            },
            Triple("FAN", "⚙", "Tốc độ quạt") to {
                fan = FanSpeed.entries[(fan.ordinal + 1) % FanSpeed.entries.size]
                send("Quạt: ${fan.label}")
            },
            Triple("MORE", "⊞", "Thêm") to { showLiquidMoreSheet() }
        )
        auxItems.forEach { (info, action) ->
            val (col, circle, txt) = circleItem(info.second, info.third, action)
            auxViews[info.first] = circle to txt
            auxCard.addView(col, weight(1f))
        }
        main.addView(auxCard)
        space(main, 16)

        // Bảng 3 tầng trạng thái (Liquid Glass)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBg(22f, false)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        tvStatus = label("", 11.5f, false, "#E0F2FE").apply { gravity = Gravity.START }
        statusCard.addView(tvStatus)
        space(statusCard, 10)

        val rowSync = row()
        rowSync.addView(pillBtn("↻ Phát đồng bộ lại") { send("Đồng bộ lại trạng thái dự kiến") }, weight(1f, 4))
        rowSync.addView(pillBtn("⟲ Đặt lại mặc định") {
            power = true; temp = 26; mode = AcMode.COOL; fan = FanSpeed.S3; swing = SwingDir.UP
            sleep = false; timerHours = 0; display = true; eco = false; turbo = false
            health = false; mute = false; lrWind = false; lastAction = "Mặc định"; txStatus = "Đã đặt lại"
            savePrefs(); refreshUi()
        }, weight(1f, 4))
        statusCard.addView(rowSync)
        main.addView(statusCard)

        setContentView(rootScroll)
        refreshUi()
    }

    private fun showLiquidMoreSheet() {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#EE102542"), Color.parseColor("#F50A1428"))
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#665CE1E6"))
            }
            setPadding(dp(20), dp(22), dp(20), dp(20))
        }
        box.addView(label("Chức năng điều khiển khác", 18f, true, "#FFFFFF"))
        box.addView(label("Nhấn để bật/tắt • Các mục chưa xác minh được khóa an toàn", 11f, false, "#99D6EAFF"))
        space(box, 14)

        ExtraFn.entries.chunked(2).forEach { pair ->
            val r = row()
            pair.forEach { fn ->
                val isOn = when (fn) {
                    ExtraFn.DISPLAY -> display; ExtraFn.ECO -> eco; ExtraFn.HEALTH -> health
                    ExtraFn.LR_WIND_MODE -> lrWind; ExtraFn.MUTE -> mute; ExtraFn.TURBO -> turbo
                    else -> false
                }
                val sub = if (!fn.verified) "Chưa xác minh" else if (isOn) "Đang bật" else "Đang tắt"
                val btn = label("${fn.raw}\n($sub)", 13f, true, if (!fn.verified) "#77FFFFFF" else "#FFFFFF").apply {
                    background = glassBg(26f, fn.verified && isOn)
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                    setOnClickListener {
                        if (!fn.verified) {
                            txStatus = "Đã chặn IR: ${fn.raw} chưa xác minh"
                            toast("${fn.raw}: Chưa xác minh mã IR trên Nagakawa")
                            refreshUi(); dlg.dismiss(); return@setOnClickListener
                        }
                        when (fn) {
                            ExtraFn.DISPLAY -> { display = !display; send("DISPLAY: ${if (display) "Đang bật" else "Đang tắt"}", true) }
                            ExtraFn.ECO -> if (mode != AcMode.COOL) toast("ECO chỉ hỗ trợ ở chế độ Mát") else { eco = !eco; if (eco) turbo = false; send("ECO: ${if (eco) "Đang bật" else "Đang tắt"}") }
                            ExtraFn.TURBO -> if (mode != AcMode.COOL && mode != AcMode.HEAT) toast("TURBO chỉ hỗ trợ ở Mát/Nóng") else { turbo = !turbo; if (turbo) eco = false; send("TURBO: ${if (turbo) "Đang bật" else "Đang tắt"}") }
                            ExtraFn.HEALTH -> { health = !health; send("Health: ${if (health) "Đang bật" else "Đang tắt"}") }
                            ExtraFn.LR_WIND_MODE -> { lrWind = !lrWind; send("LR_WIND_MODE: ${if (lrWind) "Đang bật" else "Đang tắt"}") }
                            ExtraFn.MUTE -> { mute = !mute; send("MUTE: ${if (mute) "Đang bật" else "Đang tắt"}") }
                            else -> {}
                        }
                        dlg.dismiss()
                    }
                }
                r.addView(btn, weight(1f, 6))
            }
            if (pair.size == 1) r.addView(Space(this), weight(1f, 6))
            box.addView(r)
        }
        space(box, 10)
        box.addView(pillBtn("Đóng bảng chức năng") { dlg.dismiss() })
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dlg.show()
    }

    private fun showTimerDialog() {
        val opts = arrayOf("Tắt hẹn giờ (0h)", "1 giờ", "2 giờ", "4 giờ", "8 giờ", "12 giờ")
        val vals = intArrayOf(0, 1, 2, 4, 8, 12)
        AlertDialog.Builder(this).setTitle("Bộ hẹn giờ (Timer)").setItems(opts) { _, i ->
            timerHours = vals[i]
            send(if (timerHours == 0) "Hẹn giờ: Đang tắt" else "Hẹn giờ: ${timerHours}h")
        }.show()
    }

    private fun changeTemp(d: Int) {
        if (!power) { toast("Hãy bật Nguồn trước"); return }
        if (!mode.hasTemp) { toast("Chế độ ${mode.label} không chỉnh nhiệt độ"); return }
        if (temp + d !in 16..30) { toast("Giới hạn 16°C – 30°C"); return }
        temp += d; send("Nhiệt độ: ${temp}°C")
    }

    private fun send(action: String, toggleDisp: Boolean = false) {
        lastAction = action; savePrefs()
        val (pulses, hex) = encodeAux13Byte(toggleDisp)
        lastHex = hex; refreshUi()
        irExecutor.execute {
            val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val okHw = packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR) &&
                runCatching { irManager?.hasIrEmitter() == true }.getOrDefault(false)
            val okFreq = runCatching { irManager?.carrierFrequencies?.any { 38000 in it.minFrequency..it.maxFrequency } == true }.getOrDefault(false)
            val msg = if (!okHw || irManager == null) "[$t] LỖI: Máy không có cổng hồng ngoại IR"
            else if (!okFreq) "[$t] LỖI: Không hỗ trợ tần số 38kHz"
            else runCatching { irManager!!.transmit(38000, pulses); "[$t] Đã gửi ($action) – Chưa xác nhận" }
                .getOrElse { "[$t] Lỗi phát IR: ${it.message}" }
            runOnUiThread { txStatus = msg; refreshUi() }
        }
    }

    private fun encodeAux13Byte(toggleDisp: Boolean): Pair<IntArray, String> {
        val f = IntArray(13)
        f[0] = 0xC3
        f[1] = (((temp.coerceIn(16, 30) - 8) and 0x1F) shl 3) or (swing.bits and 0x07)
        f[2] = (if (lrWind) 0b000 else 0b111) shl 5
        f[4] = (fan.bits and 0x07) shl 5
        f[5] = (if (turbo && (mode == AcMode.COOL || mode == AcMode.HEAT)) 1 shl 6 else 0) or (if (mute) 1 shl 7 else 0)
        f[6] = ((mode.bits and 0x07) shl 5) or (if (sleep) 1 shl 2 else 0)
        f[7] = timerHours.coerceIn(0, 24)
        f[8] = if (f[7] > 0) (1 shl 3) else 0
        f[9] = (if (power) 1 shl 5 else 0) or (if (eco && mode == AcMode.COOL) 1 shl 4 else 0) or (if (health) 1 shl 2 else 0)
        f[11] = if (toggleDisp) 0x05 else 0x00
        f[12] = (0..11).sumOf { f[it] } and 0xFF
        val pulses = IntArray(2 + 13 * 16 + 2)
        var i = 0; pulses[i++] = 9000; pulses[i++] = 4500
        for (b in f) for (bit in 0 until 8) { pulses[i++] = 560; pulses[i++] = if (((b shr bit) and 1) == 1) 1690 else 560 }
        pulses[i++] = 560; pulses[i] = 10000
        return pulses to f.joinToString(" ") { "%02X".format(it) }
    }

    private fun refreshUi() {
        tvTemp.text = if (!power) "TẮT" else if (!mode.hasTemp) "--" else "${temp}°C"
        tvModeVal.text = mode.label; tvFanVal.text = fan.label; tvSwingVal.text = swing.label

        val fl = mutableListOf(if (power) "Nguồn: Đang bật" else "Nguồn: Đang tắt")
        if (eco) fl.add("ECO: Đang bật"); if (turbo) fl.add("TURBO: Đang bật"); if (sleep) fl.add("Ngủ: Đang bật")
        if (timerHours > 0) fl.add("Hẹn giờ: ${timerHours}h"); if (health) fl.add("Health: Đang bật")
        if (mute) fl.add("MUTE: Đang bật"); if (lrWind) fl.add("LR_WIND: Đang bật")
        fl.add(if (display) "DISPLAY: Bật" else "DISPLAY: Tắt")
        tvFlags.text = fl.joinToString("  •  ")

        btnPower.text = if (power) "⏻  Nguồn: Đang bật" else "⏻  Nguồn: Đang tắt"
        btnPower.background = glassBg(30f, power, if (power) "#00C853" else "#D32F2F")

        val swOn = swing == SwingDir.SWING
        btnSwing.text = if (swOn) "⇄  Xoay: Đang bật" else "⇄  Xoay (${swing.label})"
        btnSwing.background = glassBg(30f, swOn)

        modeViews.forEach { (m, pair) -> pair.first.background = circleGlassBg(m == mode) }
        auxViews["SLEEP"]?.first?.background = circleGlassBg(sleep)
        auxViews["TIMER"]?.first?.background = circleGlassBg(timerHours > 0)
        auxViews["DIR"]?.first?.background = circleGlassBg(swOn)
        auxViews["FAN"]?.first?.background = circleGlassBg(fan != FanSpeed.AUTO)
        auxViews["MORE"]?.first?.background = circleGlassBg(eco || turbo || health || mute || lrWind)

        val hasIr = runCatching { irManager?.hasIrEmitter() == true }.getOrDefault(false)
        tvStatus.text = "1. Trạng thái dự kiến: $lastAction\n" +
            "2. Lệnh đã gửi: $txStatus\n" +
            "3. Xác nhận từ điều hòa: Chưa xác nhận (IR 1 chiều - Có thể lệch nếu dùng điều khiển rời)\n" +
            "• Phần cứng IR: ${if (hasIr) "Sẵn sàng (38kHz)" else "Không có mắt IR"}  |  HEX: $lastHex"
    }

    private fun glassBg(radiusDp: Float, active: Boolean, customGlow: String? = null) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (customGlow != null) intArrayOf(Color.parseColor(customGlow), Color.parseColor("#26FFFFFF"))
        else if (active) intArrayOf(Color.parseColor("#CC0099FF"), Color.parseColor("#990052CC"))
        else intArrayOf(Color.parseColor("#33FFFFFF"), Color.parseColor("#14FFFFFF"))
    ).apply {
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), Color.parseColor(if (active || customGlow != null) "#CCFFFFFF" else "#4DFFFFFF"))
    }

    private fun circleGlassBg(active: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (active) intArrayOf(Color.parseColor("#FF00B4DB"), Color.parseColor("#FF0066FF"))
        else intArrayOf(Color.parseColor("#38FFFFFF"), Color.parseColor("#18FFFFFF"))
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(1), Color.parseColor(if (active) "#FFFFFFFF" else "#55FFFFFF"))
    }

    private fun circleItem(sym: String, txt: String, onClick: () -> Unit): Triple<LinearLayout, TextView, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { onClick() } }
        val circle = label(sym, 20f, true, "#FFFFFF").apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            background = circleGlassBg(false)
        }
        val title = label(txt, 11f, false, "#E6FFFFFF").apply { setPadding(0, dp(6), 0, 0) }
        col.addView(circle); col.addView(title)
        return Triple(col, circle, title)
    }

    private fun infoCol(valTv: TextView, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(valTv); addView(label(title, 12f, false, "#99D6EAFF"))
    }

    private fun pillBtn(txt: String, onClick: () -> Unit) = label(txt, 14f, true, "#FFFFFF").apply {
        background = glassBg(28f, false)
        setPadding(dp(14), dp(16), dp(14), dp(16))
        setOnClickListener { onClick() }
    }

    private fun label(txt: String, size: Float, bold: Boolean, hex: String) = TextView(this).apply {
        text = txt; textSize = size; gravity = Gravity.CENTER; setTextColor(Color.parseColor(hex))
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun savePrefs() = getSharedPreferences("ac", MODE_PRIVATE).edit()
        .putBoolean("p", power).putInt("t", temp).putString("m", mode.name).putString("f", fan.name)
        .putString("s", swing.name).putBoolean("sl", sleep).putInt("tm", timerHours).putBoolean("d", display)
        .putBoolean("e", eco).putBoolean("tb", turbo).putBoolean("h", health).putBoolean("mt", mute).putBoolean("lr", lrWind).apply()

    private fun loadPrefs() = getSharedPreferences("ac", MODE_PRIVATE).let { p ->
        power = p.getBoolean("p", true); temp = p.getInt("t", 26).coerceIn(16, 30)
        mode = runCatching { AcMode.valueOf(p.getString("m", "COOL")!!) }.getOrDefault(AcMode.COOL)
        fan = runCatching { FanSpeed.valueOf(p.getString("f", "S3")!!) }.getOrDefault(FanSpeed.S3)
        swing = runCatching { SwingDir.valueOf(p.getString("s", "UP")!!) }.getOrDefault(SwingDir.UP)
        sleep = p.getBoolean("sl", false); timerHours = p.getInt("tm", 0); display = p.getBoolean("d", true)
        eco = p.getBoolean("e", false); turbo = p.getBoolean("tb", false); health = p.getBoolean("h", false)
        mute = p.getBoolean("mt", false); lrWind = p.getBoolean("lr", false)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weight(w: Float, mDp: Int = 2) = LinearLayout.LayoutParams(0, WRAP_CONTENT, w).apply { val m = dp(mDp); setMargins(m, m, m, m) }
    private fun space(p: LinearLayout, d: Int) = p.addView(Space(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(d)))
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
