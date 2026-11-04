package cc.baseballmaster.scorekeeper

import android.util.AtomicFile
import cc.baseballmaster.core.*
import java.io.File
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

@Serializable private data class LiveCredentialState(val version: Int=1,val bindings: List<LiveBinding>)

/** Independent noBackup file, with its own HKDF domain and authenticated AAD.
 * Team authorization replacement/rotation does not rotate this encryption key.
 */
internal class LiveCredentialFile(
    directory: File,private val secret: ()->ByteArray,private val beforeCommit: ()->Unit = {},
): LiveCredentialStorage {
    companion object {private val lock=Any()}
    override val monitor: Any get()=lock
    private val file=AtomicFile(File(directory,"publishers-v1.sealed"))
    private val aad="BaseballMaster.android.standalone-live.v1".toByteArray()
    private fun key(): SecretKeySpec {
        val input=secret();check(input.size==32)
        val hmac=Mac.getInstance("HmacSHA256");hmac.init(SecretKeySpec(aad,"HmacSHA256"));val prk=hmac.doFinal(input)
        hmac.init(SecretKeySpec(prk,"HmacSHA256"));return SecretKeySpec(hmac.doFinal("local-state".toByteArray()+byteArrayOf(1)),"AES")
    }
    private fun validate(bindings: List<LiveBinding>): List<LiveBinding> {
        check(bindings.size<=1000 && bindings.map {it.gameID}.distinct().size==bindings.size && bindings.map {it.requestID}.distinct().size==bindings.size) {"直播凭证资料无效，原文件已保留"}
        return bindings
    }
    override fun load(): List<LiveBinding> = synchronized(monitor) {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return@synchronized emptyList()
        val sealed=file.openRead().use {stream ->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val n=stream.read(buffer);if(n<0) break;check(output.size()+n<=2*1_048_576);output.write(buffer,0,n)}
            output.toByteArray()
        }
        check(sealed.size in 28..2*1_048_576) {"直播凭证文件无效，原文件已保留"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,sealed.copyOfRange(0,12)));cipher.updateAAD(aad)
        val state=GameJson.decodeFromString<LiveCredentialState>(cipher.doFinal(sealed.copyOfRange(12,sealed.size)).toString(Charsets.UTF_8))
        check(state.version==1) {"直播凭证版本不受支持，原文件已保留"};validate(state.bindings)
    }
    override fun save(bindings: List<LiveBinding>) = synchronized(monitor) {
        val plain=GameJson.encodeToString(LiveCredentialState(bindings=validate(bindings))).toByteArray()
        check(plain.size<=2*1_048_576-28 && (file.baseFile.parentFile!!.isDirectory || file.baseFile.parentFile!!.mkdirs()))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(aad)
        val sealed=cipher.iv+cipher.doFinal(plain);val output=file.startWrite()
        try {output.write(sealed);output.fd.sync();beforeCommit();file.finishWrite(output);check(file.readFully().contentEquals(sealed)) {"直播凭证尚未保存，未发送操作"}}
        catch(e: Exception) {file.failWrite(output);throw e}
    }
}
