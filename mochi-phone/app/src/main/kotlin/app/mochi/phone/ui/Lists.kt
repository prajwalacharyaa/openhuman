package app.mochi.phone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A list row that can be swiped away (either way) or long-pressed to start selecting several. While [selecting],
 * a tap ticks it instead of opening it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeRow(
    selecting: Boolean,
    selected: Boolean,
    deleteLabel: String,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = LocalMochi.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v != SwipeToDismissBoxValue.Settled) onDelete()
        v != SwipeToDismissBoxValue.Settled
    })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = !selecting, enableDismissFromEndToStart = !selecting,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(c.danger).padding(horizontal = Space.xl),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart
                else Alignment.CenterEnd) {
                Text(deleteLabel, style = Type.body.copy(fontWeight = Type.strong), color = Color.White)
            }
        }) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (selected) c.blueBg else c.card)
            .combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onToggle)
            .padding(Space.l), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            if (selecting) Box(Modifier.size(22.dp).clip(CircleShape)
                .background(if (selected) c.ink else c.field).semantics { contentDescription = if (selected) "Selected" else "Not selected" },
                contentAlignment = Alignment.Center) {
                if (selected) LineIcon(Icons2.Check, c.bg, 14.dp)
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
