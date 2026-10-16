package mihon.readingapp.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.ArrowDownward

/** Quiet storyboard chrome over the retained native image viewer and chapter actions. */
@Composable
fun StoryboardReaderBars(
    title:String,chapter:String,strip:Boolean,rtl:Boolean,page:Int,total:Int,
    bookmarked:Boolean,nextChapter:Boolean,previousChapter:Boolean,
    onBack:()->Unit,onForward:()->Unit,onBackward:()->Unit,onMode:()->Unit,
    onTools:()->Unit,onBookmark:()->Unit,onAuto:()->Unit,onNextChapter:()->Unit,onPreviousChapter:()->Unit,
    onTopSize:(Int)->Unit,onBottomSize:(Int)->Unit,
    autoReadControl: @Composable () -> Unit,
) {
    var menu by remember {mutableStateOf(false)}
    val paper=Ink.Canvas;val ink=Ink.Text
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged{onTopSize(it.height)},color=paper,contentColor=ink) {
            Column(Modifier.statusBarsPadding().padding(horizontal=20.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onBack) {Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack,"Back")}
                    Text(title,Modifier.weight(1f),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    Box {
                        TextButton(onClick={menu=true},colors=ButtonDefaults.textButtonColors(contentColor=ink),modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(Ink.Raised)) {Text(if(strip) "⋮" else if(rtl) "RTL" else "LTR",fontSize=12.sp)}
                        DropdownMenu(menu,{menu=false}) {
                            fun action(f:()->Unit){menu=false;f()}
                            DropdownMenuItem({Text("Reading mode")},{action(onMode)})
                            DropdownMenuItem({Text(if(bookmarked) "Remove bookmark" else "Bookmark chapter")},{action(onBookmark)})
                            DropdownMenuItem({Text("Auto read")},{action(onAuto)})
                            DropdownMenuItem({Text("Reader tools")},{action(onTools)})
                            DropdownMenuItem({Text("Next chapter")},{action(onNextChapter)},enabled=nextChapter)
                            DropdownMenuItem({Text("Previous chapter")},{action(onPreviousChapter)},enabled=previousChapter)
                        }
                    }
                }
                Text(if(strip) "Vertical webtoon · $chapter" else "Paged manga · $chapter",fontSize=10.sp,color=Ink.Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(bottom=12.dp))
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged{onBottomSize(it.height)},color=paper,contentColor=ink) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.Panel).heightIn(min=57.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(!strip) IconButton(enabled=total>0,onClick=if(rtl) onForward else onBackward) {Icon(if(rtl) MaterialSymbols.AutoMirroredRounded.ArrowForward else MaterialSymbols.AutoMirroredRounded.ArrowBack,if(rtl) "Next page" else "Previous page")}
                    Column(Modifier.weight(1f).padding(horizontal=if(strip) 16.dp else 4.dp),horizontalAlignment=if(strip) Alignment.Start else Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(if(strip) "$chapter · $page / $total" else "$page / $total",fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(if(strip) "Vertical scroll · adjustable spacing" else if(rtl) "Right-to-left · configurable" else "Left-to-right · configurable",fontSize=10.sp,color=Ink.Muted)
                    }
                    IconButton(enabled=total>0,onClick=if(strip||!rtl) onForward else onBackward) {Icon(if(strip) MaterialSymbols.Rounded.ArrowDownward else if(rtl) MaterialSymbols.AutoMirroredRounded.ArrowBack else MaterialSymbols.AutoMirroredRounded.ArrowForward,if(strip) "Next screen" else if(rtl) "Previous page" else "Next page")}
                }
                autoReadControl()
            }
        }
    }
}
