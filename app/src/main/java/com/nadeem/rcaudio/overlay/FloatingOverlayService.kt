package com.nadeem.rcaudio.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nadeem.rcaudio.events.GameEventType
import com.nadeem.rcaudio.service.CommentaryService
import com.nadeem.rcaudio.service.KeepAlive

/**
 * Owns the floating buttons. Three small windows are drawn over other apps:
 *  - the control bar (top-left by default): handle, fold, P, T and R;
 *  - the event bar below it: handle and 0 / 1 / 2 / E;
 *  - the quick column (right edge, thumb reach): 4, 6 and W (with the wicket choices opening to its left).
 * Each window can be dragged by its handle. A tap goes straight to [CommentaryService];
 * nothing here reads or touches the game.
 */
class FloatingOverlayService : Service() {
    private class OverlayWindow(
        val view: LinearLayout,
        val params: WindowManager.LayoutParams,
        /** True when [WindowManager.LayoutParams.x] is measured from the right edge. */
        val fromEnd: Boolean,
        var addedAtMs: Long = 0L,
    )

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var menu: OverlayMenuState
    private var mainWindow: OverlayWindow? = null
    private var actionsWindow: OverlayWindow? = null
    private var quickWindow: OverlayWindow? = null
    private var actionButtonsRow: View? = null
    private var extrasRow: LinearLayout? = null
    private var tossRow: LinearLayout? = null
    private var wicketColumn: LinearLayout? = null
    private var dotRow: LinearLayout? = null
    private var foldToggle: TextView? = null
    private var expandedOnlyControls: List<View> = emptyList()

    private val windows: List<OverlayWindow>
        get() = listOfNotNull(mainWindow, actionsWindow, quickWindow)

    private val collapseExtras = Runnable {
        if (menu.closeExtrasIfTimedOut(SystemClock.elapsedRealtime())) {
            updateSubmenuViews()
        }
    }

    /** Re-attaches a window if Android dropped it and pulls it back on screen after a rotation. */
    private val watchdog = object : Runnable {
        override fun run() {
            ensureOverlayAttached()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating buttons", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (intent?.action == ACTION_HIDE) {
            setEnabled(false)
            KeepAlive.cancelRevive(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW) setEnabled(true)
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted; stopping")
            setEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (mainWindow == null) {
            showBars()
            if (mainWindow == null) {
                // Android refused the window; do not loop on sticky restarts.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // A repeated start (app reopened, sticky restart, revive alarm) re-checks the windows.
        ensureOverlayAttached()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        startPlaybackServiceIfEnabled()
        return START_STICKY
    }

    /** Swiping the app out of Recents must not remove the buttons; ask Android to bring us back if it does. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; overlay keeps running")
        if (isEnabled(this)) KeepAlive.scheduleRevive(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Game rotated or the display size changed: a saved position may now be off screen.
        mainWindow?.view?.post { clampToScreen() }
        actionsWindow?.view?.post { clampToScreen() }
        quickWindow?.view?.post { clampToScreen() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        windows.forEach {
            try {
                windowManager.removeView(it.view)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Overlay view was already removed", exception)
            }
        }
        mainWindow = null
        actionsWindow = null
        quickWindow = null
        running = false
        // If we are being torn down while the user still wants the buttons, come back.
        if (isEnabled(this)) KeepAlive.scheduleRevive(this)
        super.onDestroy()
    }

    private fun startPlaybackServiceIfEnabled() {
        if (!CommentaryService.isEnabled(this)) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CommentaryService::class.java).setAction(CommentaryService.ACTION_START),
            )
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not start the playback service yet", exception)
        }
    }

    private fun ensureOverlayAttached() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission was revoked; stopping")
            setEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        windows.forEach { window ->
            // addView attaches asynchronously, so give a fresh window a moment before judging it missing.
            val settled = SystemClock.elapsedRealtime() - window.addedAtMs > ATTACH_GRACE_MS
            if (settled && !window.view.isAttachedToWindow) {
                try {
                    windowManager.addView(window.view, window.params)
                    window.addedAtMs = SystemClock.elapsedRealtime()
                    running = true
                    Log.i(TAG, "Overlay window was missing; re-attached")
                } catch (exception: IllegalStateException) {
                    Log.w(TAG, "Could not re-attach overlay yet", exception)
                } catch (exception: SecurityException) {
                    Log.w(TAG, "Could not re-attach overlay yet", exception)
                } catch (exception: WindowManager.BadTokenException) {
                    Log.w(TAG, "Could not re-attach overlay yet", exception)
                }
            }
        }
        clampToScreen()
    }

    /** Keeps every window fully on screen, including after the game switches between portrait and landscape. */
    private fun clampToScreen() {
        val bounds = windowManager.currentWindowMetrics.bounds
        windows.forEach { window ->
            val view = window.view
            val width = if (view.width > 0) view.width else dp(120)
            val height = if (view.height > 0) view.height else dp(40)
            val x = window.params.x.coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
            val y = window.params.y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
            if (x == window.params.x && y == window.params.y) return@forEach
            window.params.x = x
            window.params.y = y
            if (view.isAttachedToWindow) {
                try {
                    windowManager.updateViewLayout(view, window.params)
                } catch (exception: IllegalArgumentException) {
                    Log.w(TAG, "Overlay view was not attached while clamping", exception)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun newParams(fromEnd: Boolean, x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or (if (fromEnd) Gravity.END else Gravity.START)
        this.x = x
        this.y = y
    }

    private fun showBars() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        menu = OverlayMenuState(initiallyExpanded = prefs.getBoolean(KEY_EXPANDED, false))
        if (prefs.getInt(KEY_LAYOUT, 1) < LAYOUT_VERSION) {
            // New split layout: forget old positions so the separated windows use their defaults once.
            prefs.edit().remove(KEY_X).remove(KEY_Y)
                .remove(KEY_ACTION_X).remove(KEY_ACTION_Y)
                .remove(KEY_QUICK_X).remove(KEY_QUICK_Y)
                .putInt(KEY_LAYOUT, LAYOUT_VERSION).apply()
        }

        val main = buildMainWindow(prefs)
        val actions = buildActionsWindow(prefs)
        val quick = buildQuickWindow(prefs)
        mainWindow = main
        actionsWindow = actions
        quickWindow = quick
        updateExpandedViews()

        try {
            windowManager.addView(main.view, main.params)
            main.addedAtMs = SystemClock.elapsedRealtime()
            windowManager.addView(actions.view, actions.params)
            actions.addedAtMs = SystemClock.elapsedRealtime()
            windowManager.addView(quick.view, quick.params)
            quick.addedAtMs = SystemClock.elapsedRealtime()
            running = true
        } catch (exception: SecurityException) {
            Log.e(TAG, "Android refused the overlay window", exception)
            discardWindows()
        } catch (exception: WindowManager.BadTokenException) {
            Log.e(TAG, "Could not attach the overlay window", exception)
            discardWindows()
        }
    }

    private fun discardWindows() {
        windows.forEach {
            try {
                windowManager.removeView(it.view)
            } catch (_: IllegalArgumentException) {
                // Not attached; nothing to remove.
            }
        }
        mainWindow = null
        actionsWindow = null
        quickWindow = null
    }

    private fun submenuRow(gravity: Int, wide: Boolean, buttons: List<OverlayButton>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            this.gravity = gravity
            buttons.forEach { addView(buttonView(it, wide = wide)) }
        }

    /** Control bar: handle, fold, P, T and R. */
    private fun buildMainWindow(prefs: SharedPreferences): OverlayWindow {
        val params = newParams(fromEnd = false, x = prefs.getInt(KEY_X, dp(8)), y = prefs.getInt(KEY_Y, dp(4)))
        val window = OverlayWindow(barContainer(LinearLayout.HORIZONTAL), params, fromEnd = false)

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controlRow.addView(dragHandle(window, prefs, KEY_X, KEY_Y, size = dp(32), height = dp(32)))
        foldToggle = cell("", size = dp(32)).apply {
            contentDescription = "Expand event buttons"
            setOnClickListener { toggleExpanded(prefs) }
        }
        controlRow.addView(foldToggle)
        controlRow.addView(buttonView(OverlayButton.STOP))
        val tossButton = buttonView(OverlayButton.TOSS)
        val refreshmentButton = buttonView(OverlayButton.REFRESHMENT)
        controlRow.addView(tossButton)
        controlRow.addView(refreshmentButton)
        expandedOnlyControls = listOf(tossButton, refreshmentButton)
        window.view.addView(controlRow)
        return window
    }

    /** Event buttons and their menus live in a separate, independently movable window. */
    private fun buildActionsWindow(prefs: SharedPreferences): OverlayWindow {
        val params = newParams(
            fromEnd = false,
            x = prefs.getInt(KEY_ACTION_X, dp(8)),
            y = prefs.getInt(KEY_ACTION_Y, dp(40)),
        )
        val window = OverlayWindow(barContainer(LinearLayout.VERTICAL), params, fromEnd = false)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(dragHandle(window, prefs, KEY_ACTION_X, KEY_ACTION_Y, size = dp(24), height = dp(40)))
        OverlayButton.mainRow.forEach {
            actions.addView(buttonView(it, size = dp(48), height = dp(44)))
        }
        actionButtonsRow = actions

        extrasRow = submenuRow(Gravity.END, wide = true, buttons = OverlayButton.extrasRow)
        tossRow = submenuRow(Gravity.END, wide = true, buttons = OverlayButton.tossRow)
        dotRow = submenuRow(Gravity.CENTER, wide = true, buttons = OverlayButton.dotRow)

        window.view.apply {
            addView(actions)
            addView(extrasRow)
            addView(tossRow)
            addView(dotRow)
        }
        return window
    }

    /**
     * Quick column on the right edge, sized for a thumb: 4, 6, W. The wicket choices open to the
     * left of the column, so the column itself never moves or covers more of the game.
     */
    private fun buildQuickWindow(prefs: SharedPreferences): OverlayWindow {
        val params = newParams(
            fromEnd = false,
            x = prefs.getInt(KEY_QUICK_X, dp(152)),
            y = prefs.getInt(KEY_QUICK_Y, dp(120)),
        )
        val window = OverlayWindow(barContainer(LinearLayout.HORIZONTAL), params, fromEnd = false)

        val wicket = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            gravity = Gravity.CENTER
            OverlayButton.wicketRow.forEach { addView(buttonView(it, wide = true, height = dp(40))) }
        }
        wicketColumn = wicket

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(dragHandle(window, prefs, KEY_QUICK_X, KEY_QUICK_Y, size = dp(52), height = dp(20)))
        OverlayButton.quickRow.forEachIndexed { index, button ->
            if (index > 0) {
                column.addView(View(this), LinearLayout.LayoutParams(1, dp(5)))
            }
            column.addView(buttonView(button, size = dp(52), height = dp(48)))
        }

        window.view.addView(column)
        window.view.addView(wicket)
        return window
    }

    private fun barContainer(orientation: Int) = LinearLayout(this).apply {
        this.orientation = orientation
        // Frosted-glass panel: a very faint white sheen with a thin bright rim, so the game stays visible through it.
        background = glassDrawable(
            radius = dp(16),
            colors = intArrayOf(GLASS_PANEL_TOP, GLASS_PANEL_BOTTOM),
            strokeColor = GLASS_RIM,
        )
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }

    private fun glassDrawable(radius: Int, colors: IntArray, strokeColor: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }

    /** Each button gets its own barely-there glass tint so 4 / 6 / W / P stay recognisable at a glance. */
    private fun tintFor(button: OverlayButton): Int = when (button) {
        OverlayButton.FOUR -> GLASS_TINT_BLUE
        OverlayButton.SIX -> GLASS_TINT_GREEN
        OverlayButton.WICKET -> GLASS_TINT_RED
        OverlayButton.STOP -> GLASS_TINT_AMBER
        else -> GLASS_CELL
    }

    private fun dragHandle(
        window: OverlayWindow,
        prefs: SharedPreferences,
        keyX: String,
        keyY: String,
        size: Int,
        height: Int,
    ): View {
        val handle = cell("\u2261", size = size, height = height)
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = window.params.x
                    startY = window.params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    // A window anchored to the right edge moves the opposite way in x.
                    window.params.x = if (window.fromEnd) startX - dx else startX + dx
                    window.params.y = startY + dy
                    clampToScreen()
                    if (window.view.isAttachedToWindow) {
                        windowManager.updateViewLayout(window.view, window.params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt(keyX, window.params.x).putInt(keyY, window.params.y).apply()
                    true
                }
                else -> false
            }
        }
        return handle
    }

    private fun buttonView(
        button: OverlayButton,
        wide: Boolean = false,
        size: Int = if (wide) dp(58) else dp(36),
        height: Int = dp(32),
    ): View {
        val view = cell(button.label, size = size, height = height, tint = tintFor(button))
        view.contentDescription = when (button) {
            OverlayButton.WICKET -> "Wicket audio choices"
            OverlayButton.DOT -> "Dot ball audio choices"
            OverlayButton.EXTRAS -> "Extras audio choices"
            OverlayButton.TOSS -> "Toss audio choices"
            OverlayButton.REFRESHMENT -> "Play refreshment audio"
            OverlayButton.STOP -> "Stop audio"
            OverlayButton.RUN_OUT -> "Run out audio"
            OverlayButton.BOWLED_OUT -> "Bowled out audio"
            OverlayButton.CATCH_OUT -> "Catch out audio"
            OverlayButton.LBW_OUT -> "LBW out audio"
            OverlayButton.PITCHED_UP_DOT -> "Pitched up dot ball audio"
            OverlayButton.BOUNCER_DOT -> "Bouncer dot ball audio"
            else -> button.label
        }
        view.setOnClickListener { onButtonTapped(button, it) }
        return view
    }

    private fun toggleExpanded(prefs: SharedPreferences) {
        menu.setExpanded(!menu.expanded)
        prefs.edit().putBoolean(KEY_EXPANDED, menu.expanded).apply()
        handler.removeCallbacks(collapseExtras)
        updateExpandedViews()
        Log.i(TAG, "Overlay mode changed: ${if (menu.expanded) "expanded" else "collapsed"}")
    }

    private fun updateExpandedViews() {
        actionButtonsRow?.visibility = if (menu.expanded) View.VISIBLE else View.GONE
        expandedOnlyControls.forEach { it.visibility = if (menu.expanded) View.VISIBLE else View.GONE }
        updateSubmenuViews()
        foldToggle?.apply {
            text = if (menu.expanded) "\u2212" else "+"
            contentDescription = if (menu.expanded) "Collapse event buttons" else "Expand event buttons"
        }
        // Expanding changes the event window size; keep both windows fully on screen.
        mainWindow?.view?.post { clampToScreen() }
        actionsWindow?.view?.post { clampToScreen() }
    }

    private fun updateSubmenuViews() {
        extrasRow?.visibility = if (menu.expanded && menu.extrasOpen) View.VISIBLE else View.GONE
        tossRow?.visibility = if (menu.expanded && menu.tossOpen) View.VISIBLE else View.GONE
        dotRow?.visibility = if (menu.expanded && menu.dotOpen) View.VISIBLE else View.GONE
        // The wicket choices belong to the quick column, which works even when the event bar is folded.
        wicketColumn?.visibility = if (menu.wicketOpen) View.VISIBLE else View.GONE
        actionsWindow?.view?.post { clampToScreen() }
        quickWindow?.view?.post { clampToScreen() }
    }

    private fun onButtonTapped(button: OverlayButton, view: View) {
        if (button == OverlayButton.STOP) {
            if (!menu.acceptStopTap(SystemClock.elapsedRealtime())) return
            menu.closeExtras()
            handler.removeCallbacks(collapseExtras)
            updateExpandedViews()
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            flash(view)
            stopPlayback()
            return
        }
        if (!menu.expanded && !button.isQuick) return

        val type = menu.onTap(button, SystemClock.elapsedRealtime())
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        flash(view)

        handler.removeCallbacks(collapseExtras)
        updateSubmenuViews()
        if (menu.activeSubmenu != null) handler.postDelayed(collapseExtras, EXTRAS_TIMEOUT_MS)

        if (type != null) play(type)
    }

    private fun stopPlayback() {
        Log.i(TAG, "Overlay tap -> stop playback")
        ContextCompat.startForegroundService(
            this,
            Intent(this, CommentaryService::class.java).setAction(CommentaryService.ACTION_STOP_PLAYBACK),
        )
    }

    private fun play(type: GameEventType) {
        Log.i(TAG, "Overlay tap -> ${type.name}")
        ContextCompat.startForegroundService(
            this,
            Intent(this, CommentaryService::class.java)
                .setAction(CommentaryService.ACTION_PLAY_EVENT)
                .putExtra(CommentaryService.EXTRA_EVENT_ID, "overlay-${SystemClock.elapsedRealtimeNanos()}")
                .putExtra(CommentaryService.EXTRA_EVENT_TYPE, type.name)
                .putExtra(CommentaryService.EXTRA_EVENT_SOURCE, "OVERLAY"),
        )
    }

    private fun flash(view: View) {
        view.alpha = 0.35f
        view.postDelayed({ view.alpha = 1f }, 140)
    }

    private fun cell(text: String, size: Int, height: Int = dp(32), tint: Int = GLASS_CELL): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            // A soft shadow keeps white text readable over bright parts of the game.
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0xB3000000.toInt())
            textSize = if (text.length > 3) 11f else if (height > dp(40)) 18f else 13f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = glassDrawable(
                radius = dp(12),
                colors = intArrayOf(tint, tint),
                strokeColor = GLASS_CELL_RIM,
            )
            layoutParams = LinearLayout.LayoutParams(size, height).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private fun buildNotification(): Notification {
        val hide = PendingIntent.getService(
            this,
            3,
            Intent(this, FloatingOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Floating event buttons")
            .setContentText("4 / 6 / W beside the joystick; tap + at the top to expand the rest")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide buttons", hide)
            .build()
    }

    companion object {
        const val ACTION_SHOW = "com.nadeem.rcaudio.overlay.SHOW"
        const val ACTION_HIDE = "com.nadeem.rcaudio.overlay.HIDE"

        @Volatile
        var running: Boolean = false
            private set

        private const val CHANNEL_ID = "rc_overlay"
        private const val NOTIFICATION_ID = 24025
        private const val PREFS = "overlay_position"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_QUICK_X = "quick_x"
        private const val KEY_QUICK_Y = "quick_y"
        private const val KEY_ACTION_X = "action_x"
        private const val KEY_ACTION_Y = "action_y"
        private const val KEY_LAYOUT = "layout_version"
        private const val LAYOUT_VERSION = 4
        private const val KEY_EXPANDED = "expanded"
        private const val EXTRAS_TIMEOUT_MS = OverlayMenuState.DEFAULT_EXTRAS_TIMEOUT_MS
        private const val WATCHDOG_INTERVAL_MS = 4_000L
        private const val ATTACH_GRACE_MS = 1_500L
        private const val TAG = "RCOverlay"

        // Glass palette (ARGB). Alphas are deliberately tiny: the panels should look like frosted glass, not plastic.
        private const val GLASS_PANEL_TOP = 0x2EFFFFFF
        private const val GLASS_PANEL_BOTTOM = 0x0AFFFFFF
        private const val GLASS_RIM = 0x47FFFFFF
        private const val GLASS_CELL = 0x14FFFFFF
        private const val GLASS_CELL_RIM = 0x26FFFFFF
        private const val GLASS_TINT_BLUE = 0x3329B6F6
        private const val GLASS_TINT_GREEN = 0x3366BB6A
        private const val GLASS_TINT_RED = 0x33EF5350
        private const val GLASS_TINT_AMBER = 0x33FFCA28

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
    }
}
