package cc.baseballmaster.core

import java.nio.charset.CharacterCodingException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppleWireJsonTest {
    @Test fun rejectsDuplicateKeysEvenWhenEscapedButAllowsSameNameInSeparateObjects() {
        assertThrows(IllegalArgumentException::class.java) { AppleWireJson.parse("""{"id":1,"id":2}""".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { AppleWireJson.parse("""{"id":1,"\u0069d":2}""".toByteArray()) }
        val source = """{"records":[{"id":"a"},{"id":"b"}],"text":"commas, colons: and braces{}[] \""}"""
        assertEquals(Json.parseToJsonElement(source), AppleWireJson.parse(source.toByteArray()))
    }

    @Test fun rejectsBrokenUtf8RatherThanSilentlyReplacingTheOriginalName() {
        val bytes = """{"name":"""".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertThrows(CharacterCodingException::class.java) { AppleWireJson.parse(bytes) }
    }

    @Test fun deeplyNestedInputsAreRejectedBeforeParsing() {
        val nested = "[".repeat(129) + "0" + "]".repeat(129)
        assertThrows(IllegalArgumentException::class.java) { AppleWireJson.parse(nested.toByteArray()) }
        val ordinary = "[".repeat(128) + "0" + "]".repeat(128)
        assertEquals(Json.parseToJsonElement(ordinary), AppleWireJson.parse(ordinary.toByteArray()))
    }

    @Test fun rejectsMalformedJsonLexemesAndUnpairedEscapedSurrogates() {
        val invalid = listOf("\"raw\nnewline\"", "\"raw\u0000nul\"", "NaN", "Infinity", "-Infinity", "+1", "01", ".5", "1.", "1e", "{bare:0}", "\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800x\"")
        invalid.forEach { source ->
            assertThrows("must reject $source", IllegalArgumentException::class.java) { AppleWireJson.parse(source.toByteArray()) }
        }
        val valid = listOf("-1.25e+3", "0", "true", "false", "null", "\"\\uD83D\\uDE00\"", "\"escaped\\nnewline\"")
        valid.forEach { source -> assertEquals(Json.parseToJsonElement(source), AppleWireJson.parse(source.toByteArray())) }
    }
}
