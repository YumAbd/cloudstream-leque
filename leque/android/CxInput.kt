package com.lagradost.cloudstream3.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.SearchView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

/**
 * Mouse and keyboard on Android TV (V5.0.23). The remote (D-pad) keeps working exactly as before:
 * these only add behaviour for a mouse / air mouse and a physical keyboard.
 *
 *  - Focus follows the mouse: pointing at a button or card focuses it, so it is highlighted like
 *    with the remote and the remote continues from there. Only fully visible views are focused, so
 *    a list never scrolls by itself under the pointer.
 *  - Moving the mouse in the player shows its controls (see [onMouseMove]).
 *  - Esc goes back, like the remote's Back button.
 *  - Typing a letter outside the player and text fields opens Search with it (not digits: some
 *    TV remotes have number buttons).
 */
object CxInput {
    /** Set by the player while it is open: called when the mouse moves (at most every 250 ms). */
    @Volatile var onMouseMove: (() -> Unit)? = null

    private val rect = Rect()
    private val location = IntArray(2)
    private var lastFocusMs = 0L
    private var lastMoveMs = 0L
    private val main = Handler(Looper.getMainLooper())

    private fun isMouse(event: MotionEvent) =
        event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    /** From Activity.dispatchGenericMotionEvent; never consumes the event. */
    fun onGenericMotion(activity: Activity, event: MotionEvent?) {
        if (event == null || event.actionMasked != MotionEvent.ACTION_HOVER_MOVE || !isMouse(event)) return
        if (!isLayout(TV)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveMs >= 250) {
            lastMoveMs = now
            try { onMouseMove?.invoke() } catch (_: Exception) {}
        }
        if (now - lastFocusMs < 60) return
        lastFocusMs = now
        // Never take focus away from a text field the user may be typing in.
        val current = activity.currentFocus
        if (current is EditText) return
        val root = activity.window?.decorView ?: return
        val target = focusableAt(root, event.rawX.toInt(), event.rawY.toInt()) ?: return
        if (target === current || target is EditText) return
        if (!target.getGlobalVisibleRect(rect) || rect.width() < target.width || rect.height() < target.height) return
        // Also leaves touch mode, so the focus highlight is shown.
        target.requestFocusFromTouch()
    }

    /** The deepest visible, enabled, focusable and clickable view under the point. */
    private fun focusableAt(view: View, x: Int, y: Int): View? {
        if (!view.isShown || view.alpha <= 0f) return null
        view.getLocationOnScreen(location)
        if (x < location[0] || y < location[1] || x >= location[0] + view.width || y >= location[1] + view.height) return null
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                focusableAt(view.getChildAt(i), x, y)?.let { return it }
            }
        }
        return if (view.isFocusable && view.isEnabled && (view.isClickable || view.isLongClickable)) view else null
    }

    /**
     * From Activity.dispatchKeyEvent, before the app's own handling. Returns true when the key was
     * used here. [search]: this activity has the Search page (MainActivity).
     */
    private val FOCUS_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS)

    /** True while [view] is still part of the window's view tree. */
    private fun isInside(view: View, root: View): Boolean {
        var p: Any? = view
        while (p != null) {
            if (p === root) return true
            p = (p as? View)?.parent ?: return false
        }
        return false
    }

    fun onKey(activity: Activity, event: KeyEvent?, search: Boolean): Boolean {
        if (event == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled)
                (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: activity.onBackPressed()
            return true
        }
        // A focused view taken off the screen (a list redrawn under the remote's focus) makes Android
        // crash on the next arrow key ("parameter must be a descendant of this view"): focus goes back
        // onto the screen instead.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in FOCUS_KEYS) {
            val focused = activity.currentFocus
            val root = activity.window?.decorView
            if (focused != null && root != null && !isInside(focused, root)) {
                try { focused.clearFocus() } catch (_: Exception) {}
                root.requestFocus(View.FOCUS_DOWN)
                return true
            }
        }
        if (!search || !isLayout(TV) || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        // The player uses letters as shortcuts while it is open.
        if (CommonActivity.keyEventListener != null) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        if (activity.currentFocus is EditText) return false
        val ch = event.unicodeChar.takeIf { it > 0 }?.toChar() ?: return false
        if (!Character.isLetter(ch)) return false
        openSearch(activity, ch.toString())
        return true
    }

    private fun openSearch(activity: Activity, text: String) {
        if (fillSearch(activity, text)) return
        activity.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId = R.id.navigation_search
        activity.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_search
        // The Search page needs a moment to appear.
        var tries = 0
        fun retry() {
            if (activity.isFinishing || activity.isDestroyed) return
            if (fillSearch(activity, text) || ++tries > 10) return
            main.postDelayed({ retry() }, 150)
        }
        main.postDelayed({ retry() }, 150)
    }

    /** Puts the typed text in the visible Search box (without searching yet) and focuses it. */
    private fun fillSearch(activity: Activity, text: String): Boolean {
        val box = activity.findViewById<SearchView>(R.id.main_search) ?: return false
        if (!box.isShown) return false
        box.setQuery(box.query.toString() + text, false)
        box.requestFocusFromTouch()
        box.isIconified = false
        return true
    }
}
