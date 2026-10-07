package com.xennon.venue

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var settings: SoundSettings
    private lateinit var masterSwitch: Switch
    private lateinit var globalSwitch: Switch
    private lateinit var status: TextView
    private lateinit var demoButton: Button
    private lateinit var songButton: Button
    private val roomCards = LinkedHashMap<String, TextView>()

    private lateinit var mixRow: SliderRow
    private lateinit var positionRow: SliderRow
    private lateinit var bassRow: SliderRow
    private lateinit var widthRow: SliderRow
    private val eqRows = ArrayList<SliderRow>()

    // In-app preview (demo loop or a song from the phone), run through Venue's own engine.
    private var preview: PreviewPlayer? = null
    private var previewKind: String? = null
    private lateinit var meter: TextView
    private val meterTick = object : Runnable {
        override fun run() {
            val p = preview ?: return
            meter.text = when {
                !settings.enabled -> "Engine: bypassed (Effects off), you're hearing the original"
                p.processor == null -> "Engine: starting…"
                else -> "Engine: ${settings.room.emoji} ${settings.room.name} is changing the sound by %.0f%%"
                    .format(p.change * 100)
            }
            meter.postDelayed(this, 400)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" && masterSwitch.isChecked != settings.enabled) {
            masterSwitch.isChecked = settings.enabled
        }
        applyPreview()
    }
    private val serviceListener: () -> Unit = { runOnUiThread { refreshStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SoundSettings(this)
        setContentView(buildUi())
        android.util.Log.i(
            "VenueEffects",
            "v${packageManager.getPackageInfo(packageName, 0).versionName} on ${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android ${Build.VERSION.RELEASE}; effects=${settings.enabled}; phone effects: " +
                phoneEffects().replace("\n", " "),
        )
        syncControls()
        refreshStatus()
        if (settings.enabled && !EffectService.running) EffectService.start(this)
    }

    override fun onStart() {
        super.onStart()
        settings.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        EffectService.addListener(serviceListener)
        masterSwitch.isChecked = settings.enabled
        refreshStatus()
    }

    override fun onStop() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        EffectService.removeListener(serviceListener)
        super.onStop()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        column.addView(text("Venue EQ", 30f, WHITE, bold = true))
        column.addView(text("Move your music into a different room.", 15f, MUTED).withMargin(bottom = 16))

        // Master switch card
        val masterCard = card().apply { orientation = LinearLayout.VERTICAL }
        masterSwitch = Switch(this).apply {
            text = "Effects"
            textSize = 20f
            setTextColor(WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setOnCheckedChangeListener { _, on -> setEnabled(on) }
        }
        status = text("", 14f, MUTED).withMargin(top = 6)
        masterCard.addView(masterSwitch)
        masterCard.addView(status)
        column.addView(masterCard.withMargin(bottom = 20))

        // Rooms
        column.addView(section("Pick a room"))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ROOMS.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { room -> row.addView(roomCard(room), weighted()) }
            if (pair.size == 1) row.addView(View(this), weighted())
            grid.addView(row)
        }
        column.addView(grid.withMargin(bottom = 16))

        // Room controls
        column.addView(section("In the room"))
        mixRow = SliderRow("Room amount", 100, { "$it%" }) { settings.mix = it / 100f }
        positionRow = SliderRow("Where you stand", 100, ::positionLabel) { settings.position = it / 100f }
        column.addView(mixRow.view)
        column.addView(positionRow.view.withMargin(bottom = 16))

        // EQ
        column.addView(section("Equalizer"))
        EQ_FREQS.forEachIndexed { band, hz ->
            val label = if (hz >= 1000) "${hz / 1000f} kHz".replace(".0 ", " ") else "$hz Hz"
            val row = SliderRow(label, 240, { fmtDb((it - 120) / 10f) }) {
                settings.setEq(band, (it - 120) / 10f)
            }
            eqRows += row
            column.addView(row.view)
        }
        column.addView(button("Reset EQ to this room") {
            settings.selectRoom(settings.room)
            syncControls()
        }.withMargin(top = 4, bottom = 16))

        // Extras
        column.addView(section("Extras"))
        bassRow = SliderRow("Bass boost", 1000, { "${it / 10}%" }) { settings.bass = it }
        widthRow = SliderRow("3D width", 1000, { "${it / 10}%" }) { settings.width = it }
        column.addView(bassRow.view)
        column.addView(widthRow.view.withMargin(bottom = 16))

        // Preview
        column.addView(section("Try it here"))
        column.addView(
            text(
                "Plays through Venue's own sound engine, so it works on any phone, even on the " +
                    "speaker. Flip Effects on and off to compare.",
                14f, MUTED,
            )
        )
        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        demoButton = button("▶  Demo beat") { toggleDemo() }
        songButton = button("🎵  My song") { pickSong() }
        previewRow.addView(demoButton, weighted())
        previewRow.addView(songButton, weighted())
        column.addView(previewRow.withMargin(top = 8))
        meter = text("", 14f, ACCENT).withMargin(top = 6, bottom = 20)
        column.addView(meter)

        // Advanced
        column.addView(section("Advanced"))
        globalSwitch = Switch(this).apply {
            text = "Global mode (legacy)"
            textSize = 16f
            setTextColor(WHITE)
            isChecked = settings.globalMode
            setOnCheckedChangeListener { _, on -> settings.globalMode = on }
        }
        column.addView(globalSwitch)
        column.addView(
            text(
                "Turn this on if your music app never shows up above. It applies EQ, bass and " +
                    "width to all phone audio on devices that still allow it. Room reverb needs " +
                    "the app to attach normally.",
                13f, MUTED,
            ).withMargin(top = 4)
        )
        column.addView(
            text("Audio effects built into this phone:\n" + phoneEffects(), 13f, MUTED)
                .withMargin(top = 12)
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(column)
        }
        // targetSdk 35 draws edge-to-edge; keep content clear of the status/nav bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }
        return scroll
    }

    private fun roomCard(room: Room): TextView {
        val card = TextView(this).apply {
            text = "${room.emoji}  ${room.name}\n"
            append(room.tagline)
            textSize = 14f
            setTextColor(WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(84)
            setOnClickListener {
                settings.selectRoom(room)
                syncControls()
            }
        }
        roomCards[room.id] = card
        return card
    }

    /** Push the stored settings into every control. */
    private fun syncControls() {
        val current = settings.room.id
        roomCards.forEach { (id, card) ->
            val selected = id == current
            card.background = rounded(if (selected) CARD_SELECTED else CARD, if (selected) ACCENT else null)
            val room = roomById(id)
            card.text = "${room.emoji}  ${room.name}\n"
            card.append(room.tagline)
            card.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        mixRow.set((settings.mix * 100).roundToInt())
        positionRow.set((settings.position * 100).roundToInt())
        eqRows.forEachIndexed { band, row -> row.set((settings.eq(band) * 10).roundToInt() + 120) }
        bassRow.set(settings.bass)
        widthRow.set(settings.width)
        mixRow.view.alpha = if (settings.room.reverb == null) 0.4f else 1f
        positionRow.view.alpha = mixRow.view.alpha
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        status.text = when {
            !settings.enabled -> "Off. Turn it on, then press play in Spotify, YouTube Music, or any player."
            !EffectService.running -> "Starting…"
            else -> {
                val lines = EffectService.attached.map { (id, pkg) ->
                    val who = if (id == 0) "Global mode" else appLabel(pkg)
                    "$who:  ${EffectService.reports[id] ?: "…"}"
                }
                buildString {
                    if (EffectService.attached.keys.none { it != 0 }) {
                        append("Listening. Start playing music in any app and it will show up here.")
                    }
                    lines.forEach { append("\n").append(it) }
                    if (lines.isNotEmpty()) {
                        append("\n✓ working  ⚠ another audio app (e.g. Dolby) took over  ✗ phone doesn't support it")
                    }
                }.trim()
            }
        }
    }

    private fun setEnabled(on: Boolean) {
        if (settings.enabled != on) settings.enabled = on
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            if (!EffectService.running) EffectService.start(this)
        } else {
            EffectService.stop(this)
        }
        applyPreview()
        refreshStatus()
    }

    // ---------------------------------------------------------- Preview

    private fun toggleDemo() {
        if (previewKind == "demo") stopPreview() else startPreview("demo") { DemoSource() }
    }

    private fun pickSong() {
        if (previewKind == "song") {
            stopPreview()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
        startActivityForResult(intent, REQ_SONG)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_SONG && resultCode == RESULT_OK && uri != null) {
            startPreview("song") { FileSource(applicationContext, uri) }
        }
    }

    private fun startPreview(kind: String, open: () -> AudioSource) {
        stopPreview()
        val player = PreviewPlayer(open) { e ->
            runOnUiThread {
                if (previewKind == kind) stopPreview()
                Toast.makeText(this, "Couldn't play that: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        preview = player
        previewKind = kind
        player.start(previewSnapshot())
        meter.removeCallbacks(meterTick)
        meter.post(meterTick)
        if (kind == "demo") demoButton.text = "■  Stop" else songButton.text = "■  Stop"
    }

    private fun stopPreview() {
        preview?.release()
        preview = null
        previewKind = null
        if (::meter.isInitialized) {
            meter.removeCallbacks(meterTick)
            meter.text = ""
        }
        if (::demoButton.isInitialized) {
            demoButton.text = "▶  Demo beat"
            songButton.text = "🎵  My song"
        }
    }

    private fun previewSnapshot(): Snapshot? = if (settings.enabled) settings.snapshot() else null

    private fun applyPreview() {
        preview?.update(previewSnapshot())
    }

    private fun phoneEffects(): String = try {
        AudioEffect.queryEffects()
            .map { "${it.name} (${it.implementor})" }
            .distinct()
            .joinToString("\n") { "• $it" }
            .ifEmpty { "none reported" }
    } catch (e: Exception) {
        "couldn't list: ${e.message}"
    }

    // ---------------------------------------------------------- Helpers

    private inner class SliderRow(
        title: String,
        max: Int,
        private val fmt: (Int) -> String,
        onUser: (Int) -> Unit,
    ) {
        private val value = text("", 14f, ACCENT)
        private val bar = SeekBar(this@MainActivity).apply { this.max = max }
        val view: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(title, 15f, WHITE), weighted())
                addView(value)
            }
            addView(header)
            addView(bar)
            setPadding(0, dp(6), 0, dp(6))
        }

        init {
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = fmt(p)
                    if (fromUser) onUser(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        fun set(p: Int) {
            bar.progress = p
            value.text = fmt(p)
        }
    }

    private fun positionLabel(p: Int) = when {
        p < 20 -> "Front row"
        p < 45 -> "Close"
        p < 70 -> "Middle"
        p < 90 -> "Toward the back"
        else -> "Back wall"
    }

    private fun fmtDb(db: Float) = (if (db > 0) "+" else "") + "%.1f dB".format(db)

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(s: String) = text(s.uppercase(), 13f, PINK, bold = true).apply {
        letterSpacing = 0.12f
    }.withMargin(top = 8, bottom = 8)

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setTextColor(WHITE)
        background = rounded(CARD_SELECTED, null)
        setOnClickListener { onClick() }
    }

    private fun card() = LinearLayout(this).apply {
        background = rounded(CARD, null)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun rounded(fill: Int, stroke: Int?) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun <T : View> T.withMargin(top: Int = 0, bottom: Int = 0): T = apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(top), 0, dp(bottom)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val REQ_SONG = 1
        private val BG = Color.parseColor("#0E0B16")
        private val CARD = Color.parseColor("#1B1528")
        private val CARD_SELECTED = Color.parseColor("#2C2045")
        private val ACCENT = Color.parseColor("#B388FF")
        private val PINK = Color.parseColor("#FF80AB")
        private val WHITE = Color.parseColor("#F4F0FA")
        private val MUTED = Color.parseColor("#A89DBF")
    }
}
