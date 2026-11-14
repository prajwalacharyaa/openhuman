package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.WhileVisible
import app.mochi.phone.ui.io
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Opening and closing the book view from the server ("show me the book": mochi-phone book show). */
object BookView {
    val open = mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())

    fun show(c: Context): JSONObject {
        main.post { open.value = true }
        return AppNav.show(c, Intent(c, BookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            "book-view".hashCode(), "The book Mochi is reading: tap to see it", "the book being read")
    }

    fun close(): JSONObject {
        main.post { open.value = false }
        return JSONObject().put("ok", true).put("result", "the book view is closed")
    }
}

/**
 * The part of the book being read aloud right now, its words highlighted as the voice says them. What is read comes
 * from the server (mochi-books read writes it); how far the voice has got comes from the call's own live transcript
 * of what it is saying, counted in words. Close with the button or "close the book".
 */
class BookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BookView.open.value = true
        setContent {
            MochiTheme {
                val open by BookView.open
                LaunchedEffect(open) { if (!open) finish() }
                BookScreen { BookView.open.value = false }
            }
        }
    }
}

private fun wordsOf(text: String) = text.split(Regex("\\s+")).filter { it.isNotBlank() }

@Composable
private fun BookScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf<JSONObject?>(null) }
    var spoken by remember { mutableStateOf(0) }
    val list = rememberLazyListState()
    WhileVisible {
        var tick = 0
        while (true) {
            if (tick % 3 == 0) io(scope, { Doing.get(ctx, "/books/reading") }) { r ->
                if (r.optDouble("at") != reading?.optDouble("at")) { reading = r; spoken = 0 }
            }
            // how far the voice has got in this part: the words of what it is saying now
            val line = Call.said.lastOrNull { it.role == "agent" }?.text ?: ""
            spoken = maxOf(spoken, wordsOf(line).size)
            tick++
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            RoundButton(Icons2.Close, "Close the book", c.field, c.ink, 44.dp) { onClose() }
            Column(Modifier.weight(1f)) {
                val r = reading
                Text(r?.optString("title") ?: "The book", style = Type.title, color = c.ink, maxLines = 1)
                if (r != null) Text(r.optString("chapter_title") +
                    (r.optInt("page").takeIf { it > 0 }?.let { " · page $it" } ?: ""), style = Type.small, color = c.muted, maxLines = 1)
            }
        }
        val r = reading
        val paras = r?.optJSONArray("paras")
        if (paras == null || paras.length() == 0) {
            Text("Nothing is being read right now. Ask Mochi to read from one of your books.", style = Type.body,
                color = c.muted, modifier = Modifier.padding(Space.xl))
        } else {
            var before = 0
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.xl, vertical = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.l)) {
                for (i in 0 until paras.length()) {
                    val words = wordsOf(paras.optString(i))
                    val start = before
                    before += words.size
                    item(key = "$i-${r.optDouble("at")}") {
                        val text = buildAnnotatedString {
                            words.forEachIndexed { k, w ->
                                val read = start + k < spoken
                                withStyle(SpanStyle(color = if (read) c.ink else c.muted,
                                    background = if (read) c.amberBg else androidx.compose.ui.graphics.Color.Transparent)) {
                                    append(w)
                                }
                                append(" ")
                            }
                        }
                        Text(text, style = Type.body.copy(fontSize = 18.sp, lineHeight = 28.sp))
                    }
                }
            }
            // keep the line being read on screen
            LaunchedEffect(spoken) {
                var count = 0
                for (i in 0 until paras.length()) {
                    count += wordsOf(paras.optString(i)).size
                    if (count >= spoken) { runCatching { list.animateScrollToItem(i) }; break }
                }
            }
        }
    }
}
