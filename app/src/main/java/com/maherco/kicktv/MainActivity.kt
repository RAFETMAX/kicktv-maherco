package com.maherco.kicktv

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val JS_PROBE = """
    (function () {
      try {
        var html = document.documentElement.innerHTML || "";
        var m = html.match(/"username"\s*:\s*"([A-Za-z0-9_.\-]{2,32})"/);
        return JSON.stringify({ path: location.pathname || "", user: m ? m[1] : "" });
      } catch (e) {
        return JSON.stringify({ path: "", user: "" });
      }
    })()
    """


    private lateinit var settings: SettingsStore
    private lateinit var audioManager: AudioManager
    private lateinit var player: ExoPlayer

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var webFallback: WebView
    private lateinit var overlayRoot: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelContent: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var txtChannel: TextView
    private lateinit var txtClock: TextView
    private lateinit var txtQuality: TextView
    private lateinit var txtLive: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var seekVolume: SeekBar

    private var txtStatusSession: TextView? = null
    private var txtStatusDelay: TextView? = null
    private var txtStatusQuality: TextView? = null
    private var sessionState: Int = 0
    private var probeRunning = false
    private var lastCatchUp: Long = 0L

    private val ui = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var panelVisible = false
    private var webReady = false
    private var retry = 0
    private var usingWeb = false
    private var qualityHeights: List<Int> = emptyList()
    private var lastVolume = 8

    private val hideRunnable = Runnable { hideControls() }

    private val driftRunnable = object : Runnable {
        override fun run() {
            maybeCatchUp()
            if (panelVisible) updateStatusCard()
            ui.postDelayed(this, 3000)
        }
    }
    private val clockRunnable = object : Runnable {
        override fun run() {
            txtClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            ui.postDelayed(this, 20000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        SessionStore.apply(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        bind()
        immersive()
        setupPlayer()
        setupControls()
        buildPanel()
        ui.post(clockRunnable)
        ui.post(driftRunnable)
        loadChannel()
        ui.postDelayed({ probeSession() }, 4000)
    }

    private fun bind() {
        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        webFallback = findViewById(R.id.web_fallback)
        overlayRoot = findViewById(R.id.overlay_root)
        panel = findViewById(R.id.panel_settings)
        panelContent = findViewById(R.id.panel_content)
        txtStatus = findViewById(R.id.txt_status)
        txtChannel = findViewById(R.id.txt_channel)
        txtClock = findViewById(R.id.txt_clock)
        txtQuality = findViewById(R.id.txt_quality)
        txtLive = findViewById(R.id.txt_live)
        progress = findViewById(R.id.progress)
        btnPlay = findViewById(R.id.btn_play)
        btnMute = findViewById(R.id.btn_mute)
        seekVolume = findViewById(R.id.seek_volume)
    }

    private fun immersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    // ------------------------------------------------------------------ player

    private fun setupPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 12000, 500, 1000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(KickApi.userAgent())
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(streamHeaders())

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .build()
        playerView.player = player
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        applyResizeMode()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> showBuffering(true)
                    Player.STATE_READY -> {
                        showBuffering(false)
                        txtStatus.visibility = View.GONE
                        retry = 0
                    }
                    Player.STATE_ENDED -> {
                        // live stream ended or connection dropped -> reconnect
                        ui.postDelayed({ loadChannel() }, 2000)
                    }
                }
                updatePlayIcon()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayIcon()

            override fun onTracksChanged(tracks: Tracks) = updateQualityList(tracks)

            override fun onPlayerError(error: PlaybackException) {
                showBuffering(false)
                retry++
                if (retry <= 3) {
                    txtStatus.text = getString(R.string.connecting)
                    txtStatus.visibility = View.VISIBLE
                    ui.postDelayed({ loadChannel() }, 2000L * retry)
                } else {
                    startWebFallback()
                }
            }
        })
    }

    private fun streamHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Referer" to "https://kick.com/",
            "Origin" to "https://kick.com"
        )
        val cookie = SessionStore.cookieHeader()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }

    private fun applyResizeMode() {
        playerView.resizeMode = when (settings.resizeMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun showBuffering(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadChannel() {
        if (settings.engine == 1) {
            startWebFallback()
            return
        }
        txtStatus.text = getString(R.string.connecting)
        txtStatus.visibility = View.VISIBLE
        showBuffering(true)
        val slug = settings.channelSlug
        Thread {
            val info = KickApi.fetch(slug)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (info?.playbackUrl != null) {
                    txtChannel.text = info.title
                    txtLive.visibility = if (info.isLive) View.VISIBLE else View.GONE
                    startPlayback(info.playbackUrl)
                } else if (info != null && !info.isLive) {
                    txtStatus.text = getString(R.string.offline)
                    txtStatus.visibility = View.VISIBLE
                    showBuffering(false)
                    startWebFallback()
                } else {
                    startWebFallback()
                }
            }
        }.start()
    }

    private fun startPlayback(url: String) {
        usingWeb = false
        webFallback.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val item = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(settings.latencyMs.toLong())
                    .setMinPlaybackSpeed(0.95f)
                    .setMaxPlaybackSpeed(1.10f)
                    .build()
            )
            .build()

        player.setMediaItem(item)
        applyQuality()
        player.prepare()
        player.playWhenReady = true
        player.volume = if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) 0f else 1f
        updatePlayIcon()
        scheduleHide()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        if (webReady) return
        webReady = true
        val s = webFallback.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webFallback, true)
        webFallback.webViewClient = WebViewClient()
    }

    private fun startWebFallback() {
        setupWeb()
        usingWeb = true
        showBuffering(false)
        try {
            player.stop()
            player.clearMediaItems()
        } catch (_: Exception) {
        }
        playerView.visibility = View.GONE
        webFallback.visibility = View.VISIBLE
        val url = "https://player.kick.com/${settings.channelSlug}?autoplay=true&muted=false"
        webFallback.loadUrl(url)
        txtStatus.visibility = View.GONE
    }

    private fun applyQuality() {
        val builder = player.trackSelectionParameters.buildUpon()
        val q = settings.quality
        if (q <= 0) {
            builder.clearVideoSizeConstraints()
        } else {
            builder.setMaxVideoSize(Int.MAX_VALUE, q)
            builder.setMaxVideoBitrate(Int.MAX_VALUE)
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun updateQualityList(tracks: Tracks) {
        val heights = sortedSetOf<Int>(compareByDescending { it })
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                if (f.height > 0) heights.add(f.height)
            }
        }
        val list = heights.toList()
        if (list != qualityHeights) {
            qualityHeights = list
            if (panelVisible) buildPanel()
        }
    }

    // ---------------------------------------------------------------- controls

    private fun setupControls() {
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.btn_live_edge).setOnClickListener {
            try {
                player.seekToDefaultPosition()
                player.play()
            } catch (_: Exception) {
            }
            showControls()
        }
        findViewById<ImageButton>(R.id.btn_reload).setOnClickListener { loadChannel() }
        findViewById<ImageButton>(R.id.btn_aspect).setOnClickListener {
            settings.resizeMode = (settings.resizeMode + 1) % 3
            applyResizeMode()
            buildPanel()
            showControls()
        }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { showPanel(true) }
        findViewById<ImageButton>(R.id.btn_close_panel).setOnClickListener { showPanel(false) }
        txtQuality.setOnClickListener { showPanel(true) }

        seekVolume.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val saved = settings.lastVolume
        if (saved in 0..seekVolume.max) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        }
        seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) setVolumeLevel(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = showControls()
            override fun onStopTrackingTouch(bar: SeekBar?) = showControls()
        })

        btnMute.setOnClickListener {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current > 0) {
                lastVolume = current
                setVolumeLevel(0)
            } else {
                setVolumeLevel(if (lastVolume > 0) lastVolume else seekVolume.max / 2)
            }
            showControls()
        }
        updateVolumeIcon()
        updateQualityLabel()
    }

    private fun setVolumeLevel(value: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        seekVolume.progress = value
        settings.lastVolume = value
        updateVolumeIcon()
    }

    private fun updateVolumeIcon() {
        val v = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        btnMute.setImageResource(if (v == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun togglePlay() {
        if (usingWeb) {
            Toast.makeText(this, R.string.engine_web, Toast.LENGTH_SHORT).show()
            showControls()
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.play()
        }
        showControls()
    }

    private fun updatePlayIcon() {
        val playing = if (usingWeb) false else player.isPlaying
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateQualityLabel() {
        txtQuality.text =
            if (settings.quality <= 0) getString(R.string.auto) else "${settings.quality}p"
    }

    // ------------------------------------------------------------- auto hiding

    private fun showControls() {
        if (!controlsVisible) {
            controlsVisible = true
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.animate().alpha(1f).setDuration(150).start()
        } else if (overlayRoot.visibility != View.VISIBLE) {
            overlayRoot.visibility = View.VISIBLE
            overlayRoot.alpha = 1f
        }
        scheduleHide()
    }

    private fun hideControls() {
        if (!controlsVisible || panelVisible) return
        controlsVisible = false
        overlayRoot.animate().alpha(0f).setDuration(220).withEndAction {
            if (!controlsVisible) overlayRoot.visibility = View.INVISIBLE
        }.start()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideRunnable)
        val delay = settings.autoHideMs
        if (delay > 0 && !panelVisible) ui.postDelayed(hideRunnable, delay.toLong())
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        showControls()
        return super.dispatchTouchEvent(ev)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        showControls()
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && panelVisible) {
                showPanel(false)
                return true
            }
            showControls()
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    togglePlay()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player.play(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player.pause(); return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            settings.lastVolume = seekVolume.progress
            updateVolumeIcon()
        }
        return super.dispatchKeyEvent(event)
    }

    // ----------------------------------------------------------- settings panel

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showPanel(show: Boolean) {
        panelVisible = show
        if (show) {
            buildPanel()
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(160).start()
            panel.translationX = dp(40).toFloat()
            panel.animate().translationX(0f).setDuration(160).start()
            showControls()
            ui.removeCallbacks(hideRunnable)
            if (panelContent.childCount > 0) panelContent.getChildAt(0).requestFocus()
        } else {
            panel.visibility = View.GONE
            scheduleHide()
        }
    }

    private fun addSection(title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.setTextColor(ContextCompat.getColor(this, R.color.accent))
        tv.textSize = 12.5f
        tv.letterSpacing = 0.06f
        tv.setPadding(0, dp(16), 0, dp(6))
        panelContent.addView(tv)
    }

    private fun addChipRow(options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit) {
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((label, value) in options) {
            val chip = TextView(this)
            chip.text = label
            chip.setTextColor(ContextCompat.getColorStateList(this, R.color.chip_text))
            chip.textSize = 13.5f
            chip.gravity = Gravity.CENTER
            chip.setBackgroundResource(R.drawable.chip_bg)
            chip.isSelected = value == current
            chip.isFocusable = true
            chip.setPadding(dp(15), dp(9), dp(15), dp(9))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            chip.layoutParams = lp
            chip.setOnClickListener { onPick(value) }
            row.addView(chip)
        }
        scroll.addView(row)
        val wrap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wrap.bottomMargin = dp(4)
        panelContent.addView(scroll, wrap)
    }

    private fun addActionRow(label: String, onClick: () -> Unit) {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tv.textSize = 14.5f
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setBackgroundResource(R.drawable.chip_bg)
        tv.isFocusable = true
        tv.setPadding(dp(16), dp(12), dp(16), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        panelContent.addView(tv)
    }

    private fun addInfoRow(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tv.textSize = 14f
        tv.setBackgroundResource(R.drawable.chip_normal)
        tv.setPadding(dp(16), dp(12), dp(16), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        tv.layoutParams = lp
        panelContent.addView(tv)
    }

    private fun addStatusCard() {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.bg_toast)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        card.layoutParams = lp

        txtStatusSession = TextView(this).apply {
            textSize = 14.5f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        txtStatusDelay = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        }
        txtStatusQuality = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(txtStatusSession)
        card.addView(txtStatusDelay)
        card.addView(txtStatusQuality)
        panelContent.addView(card)
        updateStatusCard()
    }

    private fun updateStatusCard() {
        val session = txtStatusSession ?: return
        val name = settings.kickUsername
        session.text = when {
            !SessionStore.hasSession() -> getString(R.string.session_missing)
            sessionState == 1 -> getString(R.string.session_ok) +
                (if (name.isNotBlank()) " · @$name" else "")
            sessionState == -1 -> getString(R.string.session_bad)
            sessionState == -2 -> getString(R.string.session_unknown)
            else -> getString(R.string.session_checking)
        }
        session.setTextColor(
            ContextCompat.getColor(
                this,
                when (sessionState) {
                    1 -> R.color.accent
                    -1 -> R.color.live_red
                    -2 -> R.color.text_secondary
                    else -> R.color.text_primary
                }
            )
        )
        txtStatusDelay?.text = getString(R.string.delay_value, delayText())
        txtStatusQuality?.text = getString(R.string.quality_value, qualityText())
    }

    private fun liveOffsetMs(): Long {
        if (usingWeb) return -1
        return try {
            val d = player.duration
            val p = player.currentPosition
            if (d <= 0 || p < 0) -1 else d - p
        } catch (_: Exception) {
            -1
        }
    }

    private fun delayText(): String {
        val offset = liveOffsetMs()
        return if (offset < 0) "—" else String.format(Locale.US, "%.1f ث", offset / 1000f)
    }

    private fun qualityText(): String {
        try {
            for (group in player.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until group.length) {
                    if (!group.isTrackSelected(i)) continue
                    val f = group.getTrackFormat(i)
                    if (f.height > 0) return "${f.height}p"
                }
            }
        } catch (_: Exception) {
        }
        return if (settings.quality <= 0) getString(R.string.auto) else "${settings.quality}p"
    }

    /** keeps the delay from drifting: catches up smoothly instead of hard seeking */
    private fun maybeCatchUp() {
        if (usingWeb) return
        val offset = liveOffsetMs()
        if (offset <= 0) return
        val now = System.currentTimeMillis()
        if (offset > settings.latencyMs + 6000L && now - lastCatchUp > 20000L) {
            lastCatchUp = now
            try {
                player.seekToDefaultPosition()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Ground-truth check: opens Kick's dashboard in a hidden WebView carrying the
     * bundled cookies. If Kick keeps the page on the dashboard, or exposes the
     * username, the session works. If it bounces to /login it does not.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun probeSession() {
        if (probeRunning) return
        probeRunning = true
        sessionState = 0
        updateStatusCard()

        val web = WebView(this)
        web.setBackgroundColor(0)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = KickApi.userAgent()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.postDelayed({
                    view.evaluateJavascript(JS_PROBE) { raw -> handleProbe(raw, web) }
                }, 2000)
            }
        }
        root.addView(web, FrameLayout.LayoutParams(1, 1))
        web.loadUrl("https://kick.com/dashboard")
        ui.postDelayed({
            if (probeRunning) {
                probeRunning = false
                sessionState = -2
                updateStatusCard()
                dropWebView(web)
            }
        }, 25000)
    }

    private fun handleProbe(raw: String?, web: WebView) {
        if (!probeRunning) return
        probeRunning = false
        var path = ""
        var user = ""
        try {
            val inner = JSONTokener(raw ?: "").nextValue()
            if (inner is String) {
                val o = JSONObject(inner)
                path = o.optString("path", "")
                user = o.optString("user", "")
            }
        } catch (_: Exception) {
        }
        val loggedIn = user.isNotBlank() ||
            (path.startsWith("/dashboard") && !path.contains("login"))
        if (user.isNotBlank() && user.length < 40) settings.kickUsername = user
        sessionState = if (loggedIn) 1 else -1
        updateStatusCard()
        dropWebView(web)
    }

    private fun dropWebView(web: WebView) {
        try {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.loadUrl("about:blank")
            web.destroy()
        } catch (_: Exception) {
        }
    }

    private fun addChannelEditor() {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val field = EditText(this)
        field.setText(settings.channelSlug)
        field.setSingleLine(true)
        field.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        field.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        field.hint = getString(R.string.channel_hint)
        field.textSize = 14f
        field.setBackgroundResource(R.drawable.bg_field)
        field.setPadding(dp(14), dp(10), dp(14), dp(10))
        val flp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        field.layoutParams = flp
        row.addView(field)

        val apply = TextView(this)
        apply.text = getString(R.string.apply)
        apply.setTextColor(ContextCompat.getColorStateList(this, R.color.chip_text))
        apply.textSize = 13.5f
        apply.gravity = Gravity.CENTER
        apply.setBackgroundResource(R.drawable.chip_bg)
        apply.isSelected = true
        apply.isFocusable = true
        apply.setPadding(dp(18), dp(10), dp(18), dp(10))
        val alp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        alp.marginStart = dp(8)
        apply.layoutParams = alp
        apply.setOnClickListener {
            settings.channelSlug = field.text.toString()
            field.setText(settings.channelSlug)
            retry = 0
            showPanel(false)
            loadChannel()
        }
        row.addView(apply)
        panelContent.addView(row)
    }

    private fun buildPanel() {
        panelContent.removeAllViews()

        addSection(getString(R.string.account))
        addStatusCard()
        addActionRow(getString(R.string.check_session)) { probeSession() }

        addSection(getString(R.string.quality))
        val options = mutableListOf(Pair(getString(R.string.auto), 0))
        val heights = if (qualityHeights.isEmpty()) listOf(1080, 720, 480, 360) else qualityHeights
        for (h in heights) options.add(Pair("${h}p", h))
        addChipRow(options, settings.quality) { value ->
            settings.quality = value
            applyQuality()
            updateQualityLabel()
            buildPanel()
        }

        addSection(getString(R.string.latency))
        addChipRow(
            listOf(
                Pair("2s · " + getString(R.string.latency_recommended), 2000),
                Pair("4s", 4000),
                Pair("6s", 6000),
                Pair("12s", 12000)
            ),
            settings.latencyMs
        ) { value ->
            settings.latencyMs = value
            buildPanel()
            if (settings.engine == 0) loadChannel()
        }

        addSection(getString(R.string.aspect))
        addChipRow(
            listOf(
                Pair(getString(R.string.aspect_fit), 0),
                Pair(getString(R.string.aspect_fill), 1),
                Pair(getString(R.string.aspect_zoom), 2)
            ),
            settings.resizeMode
        ) { value ->
            settings.resizeMode = value
            applyResizeMode()
            buildPanel()
        }

        addSection(getString(R.string.autohide))
        addChipRow(
            listOf(
                Pair(getString(R.string.seconds_2), 2500),
                Pair(getString(R.string.seconds_4), 4000),
                Pair(getString(R.string.seconds_8), 8000),
                Pair(getString(R.string.never), 0)
            ),
            settings.autoHideMs
        ) { value ->
            settings.autoHideMs = value
            buildPanel()
            scheduleHide()
        }

        addSection(getString(R.string.engine))
        addChipRow(
            listOf(
                Pair(getString(R.string.engine_native), 0),
                Pair(getString(R.string.engine_web), 1)
            ),
            settings.engine
        ) { value ->
            settings.engine = value
            buildPanel()
            loadChannel()
        }

        addSection(getString(R.string.channel))
        addChannelEditor()

        addSection(getString(R.string.reload))
        addActionRow(getString(R.string.reload)) {
            retry = 0
            loadChannel()
        }

        addSection("")
        val about = TextView(this)
        about.text = getString(R.string.about)
        about.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        about.textSize = 12f
        about.setPadding(0, dp(12), 0, dp(24))
        panelContent.addView(about)
    }

    // ------------------------------------------------------------------ system

    override fun onResume() {
        super.onResume()
        immersive()
        seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        updateVolumeIcon()
        if (panelVisible) buildPanel()
    }

    override fun onPause() {
        super.onPause()
        if (!usingWeb) player.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        ui.removeCallbacks(driftRunnable)
        try {
            player.release()
        } catch (_: Exception) {
        }
        try {
            if (webReady) {
                webFallback.loadUrl("about:blank")
                webFallback.destroy()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
