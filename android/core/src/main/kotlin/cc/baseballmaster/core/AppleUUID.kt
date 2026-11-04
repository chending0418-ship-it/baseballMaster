package cc.baseballmaster.core

import java.util.UUID

/** Java accepts abbreviated UUID segments; Swift UUID decoding requires the full canonical form. */
internal fun appleUUID(value: String): String {
    val canonical = UUID.fromString(value).toString()
    require(canonical.equals(value, ignoreCase = true)) { "编号格式无效" }
    return canonical
}
