package mihon.readingapp.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

data class SourceShelfTitle(val title:String,val caption:String,val cover:Any?,val open:()->Unit)

/** Reference-shaped source shelf. Unsupported periods remain disabled; no invented chart order. */
@Composable
fun SourceDiscoveryPanel(title:String,genres:List<String>,items:List<SourceShelfTitle>,provenance:String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=16.dp,topEnd=16.dp)).background(Ink.Canvas).padding(start=22.dp,top=14.dp,end=22.dp,bottom=6.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=Ink.Display.copy(fontSize=20.sp,lineHeight=25.sp),color=Ink.Text)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            genres.take(2).forEach { genre -> Text(genre,Modifier.clip(RoundedCornerShape(8.dp)).background(Ink.Raised).padding(horizontal=12.dp,vertical=4.dp),
                color=Ink.Text,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Weekly","Monthly","Rising").forEach { label ->
                Box(Modifier.weight(1f).heightIn(min=25.dp).clip(RoundedCornerShape(8.dp)).background(Ink.Raised).semantics{disabled()},contentAlignment=Alignment.Center) {
                    Text(label,color=Ink.Muted.copy(alpha=.60f),fontSize=11.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items.take(2).forEach { item ->
                Row(Modifier.weight(1f).heightIn(min=48.dp).clip(RoundedCornerShape(8.dp)).background(Ink.Panel).inkPress(item.open).padding(horizontal=7.dp,vertical=6.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(29.dp).height(35.dp).clip(RoundedCornerShape(2.dp)).background(Ink.Raised)) {
                        if(item.cover!=null) AsyncImage(item.cover,null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title,fontWeight=FontWeight.Bold,fontSize=11.sp,color=Ink.Text,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(item.caption,fontSize=10.sp,color=Ink.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Text(provenance,color=Ink.Muted,fontSize=10.sp,lineHeight=13.sp)
    }
}
