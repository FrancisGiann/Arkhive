package mihon.readingapp.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.readingapp.design.Ink

/** Four primary destinations, matching the approved board; Settings is a header utility. */
@Composable fun InkNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Ink.Canvas).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        listOf("Library", "Browse", "Downloads", "Buy").forEachIndexed { index, title ->
            val active = selected == index
            val tint = if (active) Ink.Link else Ink.Muted
            Column(Modifier.weight(1f).heightIn(min = 48.dp).selectable(active, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(48.dp, 30.dp).clip(RoundedCornerShape(12.dp)).background(if (active) Ink.Raised else Ink.Canvas), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        val unit = size.width / 24f
                        val stroke = Stroke(1.7f * unit)
                        when(index) {
                            0 -> { drawRect(tint, Offset(3*unit,4*unit), Size(4*unit,16*unit)); drawRect(tint,Offset(10*unit,4*unit),Size(4*unit,16*unit));
                                drawPath(Path().apply {moveTo(17*unit,4*unit);lineTo(21*unit,3*unit);lineTo(24*unit,19*unit);lineTo(20*unit,20*unit);close()},tint) }
                            1 -> { drawCircle(tint,10*unit,style=stroke); drawPath(Path().apply {moveTo(16*unit,7*unit);lineTo(13*unit,14*unit);lineTo(8*unit,17*unit);lineTo(10*unit,10*unit);close()},tint) }
                            2 -> { drawLine(tint,Offset(12*unit,2*unit),Offset(12*unit,15*unit),1.7f*unit);drawPath(Path().apply {moveTo(7*unit,10*unit);lineTo(12*unit,15*unit);lineTo(17*unit,10*unit)},tint,style=stroke);
                                drawPath(Path().apply {moveTo(4*unit,17*unit);lineTo(4*unit,21*unit);lineTo(20*unit,21*unit);lineTo(20*unit,17*unit)},tint,style=stroke) }
                            3 -> { drawRect(tint,Offset(5*unit,8*unit),Size(14*unit,13*unit),style=stroke);drawArc(tint,180f,180f,false,Offset(8*unit,1*unit),Size(8*unit,14*unit),style=stroke) }
                        }
                    }
                }
                Text(title, color = tint, fontSize = 11.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
