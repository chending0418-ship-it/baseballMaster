package cc.baseballmaster.scorekeeper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.AtomicFile
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.baseballmaster.core.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class AndroidLaunchPresentation(val configuration: LaunchScreenConfiguration, val image: Bitmap? = null)

/** Public, disposable cache. A manifest is replaced only after its image validates. */
internal class AndroidLaunchScreenRepository(
    private val directory: File, private val transport: LaunchScreenTransport,
    private val beforeManifestCommit: ()->Unit = {},
) {
    private val monitor=Any()
    private val refreshing=java.util.concurrent.atomic.AtomicBoolean(false)
    private val manifest get()=AtomicFile(File(directory,"configuration.json"))
    fun presentation(at: Long = System.currentTimeMillis()): AndroidLaunchPresentation? = synchronized(monitor) {
        try {
            val config=LaunchScreenConfiguration.decode(read(manifest,16_384))
            if(!config.enabled) return@synchronized null
            if(!config.isInSchedule(at)) return@synchronized AndroidLaunchPresentation(LaunchScreenConfiguration.bundled)
            AndroidLaunchPresentation(config,config.backgroundImage?.let {decodeImage(read(imageFile(it),3*1024*1024),it)})
        } catch(_: Exception) {AndroidLaunchPresentation(LaunchScreenConfiguration.bundled)}
    }
    fun refresh(): Boolean {
        if(!refreshing.compareAndSet(false,true)) return false
        return try {
            val config=LaunchScreenConfiguration.decode(transport.configuration())
            var descriptorToWrite: LaunchBackgroundImage?=null;var imageBytes: ByteArray?=null
            if(config.enabled) config.backgroundImage?.let {descriptor ->
                val cached=synchronized(monitor) {runCatching {read(imageFile(descriptor),3*1024*1024).also {decodeImage(it,descriptor)}}.getOrNull()}
                val bytes=cached ?: transport.image(descriptor).also {decodeImage(it,descriptor)}
                descriptorToWrite=descriptor;imageBytes=bytes
            }
            synchronized(monitor) {
                val previous=runCatching {LaunchScreenConfiguration.decode(read(manifest,16_384)).backgroundImage?.sha256}.getOrNull()
                descriptorToWrite?.let {write(imageFile(it),requireNotNull(imageBytes))}
                beforeManifestCommit()
                write(manifest,GameJson.encodeToString(LaunchScreenConfiguration.serializer(),config).toByteArray())
                val keep=setOfNotNull(previous,config.backgroundImage?.sha256).map {"$it.image"}.toSet()
                directory.listFiles()?.filter {it.extension=="image" && it.name !in keep}?.forEach {it.delete()}
            }
            true
        } catch(_: Exception) {false} finally {refreshing.set(false)}
    }
    private fun imageFile(descriptor: LaunchBackgroundImage)=AtomicFile(File(directory,"${descriptor.sha256}.image"))
    private fun write(file: AtomicFile, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val output=file.startWrite()
        try {output.write(bytes);output.fd.sync();file.finishWrite(output)} catch(problem: Exception) {file.failWrite(output);throw problem}
    }
    private fun read(file: AtomicFile, limit: Int): ByteArray {
        return file.openRead().use {stream ->
            require(file.baseFile.isFile && file.baseFile.length()<=limit)
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=limit);output.write(buffer,0,count)}
            output.toByteArray()
        }
    }
    companion object {
        fun standard(context: Context)=AndroidLaunchScreenRepository(File(context.cacheDir,"app-launch-screen"),LaunchScreenHttpTransport())
        fun decodeImage(bytes: ByteArray, descriptor: LaunchBackgroundImage): Bitmap {
            descriptor.validate();require(bytes.size==descriptor.byteCount)
            require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}==descriptor.sha256)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outMimeType==descriptor.mimeType && bounds.outWidth==descriptor.width && bounds.outHeight==descriptor.height)
            // Android's static decoder otherwise accepts an MPO's first JPEG.
            if(descriptor.mimeType=="image/jpeg") rejectMultiPictureJpeg(bytes)
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {decoder,info,_ ->
                require(!info.isAnimated && info.mimeType==descriptor.mimeType)
                val width=info.size.width;val height=info.size.height;val largest=maxOf(width,height)
                if(largest>2048) decoder.setTargetSize(maxOf(1,width*2048/largest),maxOf(1,height*2048/largest))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        private fun rejectMultiPictureJpeg(bytes: ByteArray) {
            require(bytes.size>=4 && bytes[0].toInt() and 255==255 && bytes[1].toInt() and 255==216)
            var offset=2
            while(offset+3<bytes.size) {
                require(bytes[offset].toInt() and 255==255);var markerOffset=offset+1
                while(markerOffset<bytes.size && bytes[markerOffset].toInt() and 255==255) markerOffset++
                require(markerOffset<bytes.size);val marker=bytes[markerOffset].toInt() and 255
                if(marker==0xda || marker==0xd9) return
                if(marker==0x01 || marker in 0xd0..0xd7) {offset=markerOffset+1;continue}
                require(markerOffset+2<bytes.size)
                val size=((bytes[markerOffset+1].toInt() and 255) shl 8)+(bytes[markerOffset+2].toInt() and 255)
                require(size>=2 && markerOffset+1+size<=bytes.size)
                if(marker==0xe2 && size>=6) require(!bytes.copyOfRange(markerOffset+3,markerOffset+7).contentEquals(byteArrayOf(77,80,70,0)))
                offset=markerOffset+1+size
            }
        }
    }
}

@Composable internal fun AndroidLaunchContainer(model: ScorekeeperViewModel, content: @Composable ()->Unit) {
    val repository=model.launchRepository
    var presentation by remember {mutableStateOf(if(model.launchDismissed) null else repository.presentation())}
    val suppress=model.recoveryRequired || model.incomingLinkVisible || model.incomingTeamLink!=null
    LaunchedEffect(Unit) {model.refreshLaunchOnce()}
    LaunchedEffect(suppress) {if(suppress) {model.dismissLaunch();presentation=null}}
    LaunchedEffect(presentation) {
        val current=presentation
        if(current==null) model.dismissLaunch()
        else {
            val deadline=model.launchDeadline(current.configuration.displayDurationMs)
            delay(maxOf(0,deadline-SystemClock.elapsedRealtime()))
            model.dismissLaunch();presentation=null
        }
    }
    val current=presentation
    if(current==null || suppress || model.launchDismissed) content()
    else {
        BackHandler {model.dismissLaunch();presentation=null}
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xff092a2a)).testTag("configurable-launch")) {
            if(current.image==null) Image(painterResource(R.drawable.launch_artwork),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else {Image(current.image.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop);Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.66f)))}
            Image(painterResource(R.drawable.launch_identity),"BaseballMaster 棒球比赛记录大师",Modifier.align(Alignment.TopCenter).offset(y=maxHeight*.46968f-111.5.dp).size(260.dp,223.dp).testTag("launch-fixed-bm"))
            Image(painterResource(R.drawable.launch_app_icon),"棒球比赛记录大师 App 图标",Modifier.align(Alignment.BottomCenter).padding(bottom=81.dp).size(44.dp).clip(RoundedCornerShape(10.dp)).testTag("launch-fixed-app-icon"))
            Text(current.configuration.message,Modifier.align(Alignment.BottomCenter).padding(bottom=29.dp).widthIn(max=280.dp).padding(horizontal=8.dp).height(38.dp).testTag("launch-message"),color=Color(0xffc6d5cc),fontSize=11.sp,fontWeight=FontWeight.Medium,letterSpacing=2.42.sp,lineHeight=15.sp,textAlign=TextAlign.Center,maxLines=2)
            TextButton(onClick={model.dismissLaunch();presentation=null},modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end=20.dp).heightIn(min=44.dp).background(Color.Black.copy(alpha=.16f),RoundedCornerShape(24.dp)).testTag("skip-launch-screen")) {Text("跳过",color=Color.White,fontSize=12.sp)}
        }
    }
}
