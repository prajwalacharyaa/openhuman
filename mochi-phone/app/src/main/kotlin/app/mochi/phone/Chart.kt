package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import app.mochi.phone.ui.MochiTheme
import org.json.JSONObject

/**
 * Samir's chart popup. `mochi-phone chart NABIL --range 6m …` sends what to show (range, lines, levels, trend
 * lines, candle marks, a note); the popup opens over whatever screen is on, or changes in place if it's already
 * open ("now show a year"). December closes it with ×; Samir can close it too (`mochi-phone chart close`).
 */
object ChartBus {
    /** What the popup shows; null = closed. Changed on the main thread only. */
    val spec = mutableStateOf<JSONObject?>(null)
    private val main = Handler(Looper.getMainLooper())

    fun show(c: Context, args: JSONObject): JSONObject {
        val symbol = args.optString("symbol").trim().uppercase()
        if (symbol.isEmpty()) return fail("which stock?")
        val s = JSONObject(args.toString()).put("symbol", symbol).put("seq", System.nanoTime())
        val wasOpen = ChartActivity.onView
        main.post { spec.value = s }
        if (wasOpen) return ok("the chart on the phone now shows $symbol" +
            (args.optString("range").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ", drawing it in now")
        return AppNav.show(c, Intent(c, ChartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            "chart:$symbol".hashCode(), "Samir's chart of $symbol: tap to see it", "Samir's chart of $symbol")
    }

    fun close(): JSONObject {
        main.post { spec.value = null }
        return ok("the chart popup is closed")
    }

    private fun ok(result: String) = JSONObject().put("ok", true).put("result", result)
    private fun fail(error: String) = JSONObject().put("ok", false).put("error", error)
}

class ChartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MochiTheme {
                val s = ChartBus.spec.value
                LaunchedEffect(s == null) { if (s == null) finish() }
                if (s != null) ChartPopup(s) { ChartBus.spec.value = null }
            }
        }
    }

    override fun onStart() { super.onStart(); onView = true }
    override fun onStop() { super.onStop(); onView = false }

    companion object {
        /** The popup is on screen: a new chart changes it in place instead of opening another. */
        @Volatile var onView = false
    }
}
