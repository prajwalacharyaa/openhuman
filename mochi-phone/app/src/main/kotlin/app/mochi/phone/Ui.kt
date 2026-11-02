package app.mochi.phone

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The look every screen shares, and the one layout rule that keeps it usable: the app draws edge to edge
 * (Android 15 does that anyway), so each screen pads itself for the status bar, the navigation bar and the
 * keyboard. Without that the status bar was white-on-white and the newest messages hid behind the keyboard.
 */
object Ui {
    val BG = Color.parseColor("#F6F5F2")
    val CARD = Color.WHITE
    val TEXT = Color.parseColor("#1D1D1F")
    val MUTED = Color.parseColor("#6B6B70")
    const val BLUE = "#3B6FD8"
    const val GREEN = "#2E9D5B"
    const val RED = "#B4472C"
    const val GREY = "#55555C"

    /** A screen: [root] holds everything; [right] is the header's right-hand slot (status, RAM, a button). */
    class Screen(val root: LinearLayout, val right: LinearLayout)

    fun dp(c: Context, value: Int) = (c.resources.displayMetrics.density * value).toInt()

    /**
     * Draw `root` edge to edge with dark status/navigation icons, padding it by `pad` plus the system bars and
     * the keyboard, then show it.
     */
    fun edgeToEdge(activity: Activity, root: View, pad: Int = dp(activity, 16)) {
        // the content first: the window's insets controller only exists once its decor view is built
        activity.setContentView(root)
        val window = activity.window
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        @Suppress("DEPRECATION") run { window.statusBarColor = BG; window.navigationBarColor = BG } // Android 12-14
        root.setBackgroundColor(BG)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(pad + bars.left, dp(activity, 8) + bars.top, pad + bars.right, dp(activity, 8) + bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    /** Set up `activity` as a Mochi screen with a header (a back arrow unless it's the home screen). */
    fun screen(activity: Activity, title: String, back: Boolean): Screen {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 52)
        }
        if (back) {
            header.addView(TextView(activity).apply {
                text = "‹"
                textSize = 30f
                setTextColor(TEXT)
                setPadding(0, 0, dp(activity, 16), dp(activity, 4))
                contentDescription = "Back"
                setOnClickListener { activity.finish() }
            })
        }
        header.addView(TextView(activity).apply {
            text = title
            textSize = if (back) 22f else 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(right)
        root.addView(header)
        edgeToEdge(activity, root)
        return Screen(root, right)
    }

    fun text(c: Context, value: String = "", size: Float = 15f, color: Int = TEXT) = TextView(c).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    fun button(c: Context, label: String, color: String, onClick: () -> Unit) = Button(c).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        stateListAnimator = null
        setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10))
        background = GradientDrawable().apply { cornerRadius = dp(c, 22).toFloat(); setColor(Color.parseColor(color)) }
        setOnClickListener { onClick() }
    }

    /** A white rounded card. */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
        background = GradientDrawable().apply { cornerRadius = dp(c, 16).toFloat(); setColor(CARD) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 12) }
    }

    fun matchWidth(c: Context, top: Int = 12) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, top) }

    /** Takes the rest of the screen's height (for the scrolling part). */
    fun fill() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

    fun gone(v: View, hidden: Boolean) { v.visibility = if (hidden) View.GONE else View.VISIBLE }
}
