package cc.baseballmaster.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Reject ambiguous JSON before inspecting an externally supplied backup or archive. */
object AppleWireJson {
    private const val MAX_DEPTH = 128
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private fun validateUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "备份文本包含不完整 Unicode 字符" }
                index++
            } else require(!character.isLowSurrogate()) { "备份文本包含不完整 Unicode 字符" }
            index++
        }
    }
    private data class Container(val objectValue: Boolean, val keys: MutableSet<String> = mutableSetOf(), var expectsKey: Boolean = objectValue)

    fun parse(bytes: ByteArray): JsonElement {
        // String(bytes, UTF_8) silently replaces broken sequences; that is not a lossless import.
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val containers = ArrayDeque<Container>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    require(containers.size < MAX_DEPTH) { "备份 JSON 嵌套过深" }
                    containers.addLast(Container(text[index] == '{'))
                }
                '}', ']' -> if (containers.isNotEmpty()) containers.removeLast()
                ',' -> containers.lastOrNull()?.let { if (it.objectValue) it.expectsKey = true }
                ':' -> containers.lastOrNull()?.let { it.expectsKey = false }
                '"' -> {
                    val start = index++
                    while (index < text.length) {
                        val character = text[index]
                        require(character.code >= 0x20) { "备份 JSON 含未转义控制字符" }
                        if (character == '\\') {
                            index++
                            require(index < text.length && text[index] in "\"\\/bfnrtu") { "备份 JSON 转义无效" }
                            if (text[index] == 'u') {
                                require(index + 4 < text.length && text.substring(index + 1, index + 5).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "备份 Unicode 转义无效" }
                                index += 4
                            }
                        } else if (character == '"') break
                        index++
                    }
                    require(index < text.length) { "备份 JSON 文本不完整" }
                    val decoded = Json.decodeFromString<String>(text.substring(start, index + 1))
                    validateUnicode(decoded)
                    val current = containers.lastOrNull()
                    if (current?.objectValue == true && current.expectsKey) {
                        require(current.keys.add(decoded)) { "备份 JSON 字段重复：${decoded.take(80)}" }
                        current.expectsKey = false
                    }
                }
                ' ', '\t', '\r', '\n' -> Unit
                else -> {
                    val start = index
                    while (index < text.length && text[index] !in "{}[],:\" \t\r\n") index++
                    val token = text.substring(start, index)
                    require(token in setOf("true", "false", "null") || number.matches(token)) { "备份 JSON 数值或字面量无效" }
                    index--
                }
            }
            index++
        }
        return Json.parseToJsonElement(text)
    }
}
