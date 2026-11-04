package cc.baseballmaster.core

object Participation {
    fun validate(rules: Rules, order: List<String>, positions: Map<Int, String>, dh: String?) {
        require(
            order.distinct() == order &&
                positions.keys == (1..rules.fielders).toSet() &&
                positions.values.distinct().size == rules.fielders
        ) {
            "请核对打序与守备人数"
        }
        if (rules.mode == GameMode.slowPitch)
            require(order.size >= 10 && positions.values.all { it in order } && dh == null) {
                "慢垒十名守备员必须全部在打序，额外打者不重复守备"
            }
        else if (dh == null)
            require(order.size == 9 && order.toSet() == positions.values.toSet()) {
                "未启用 DH 时，九名守备员须全部参与打序"
            }
        else
            require(
                rules.dhEnabled &&
                    order.size == 9 &&
                    dh in order &&
                    ((rules.twoWayEnabled &&
                        dh == positions[1] &&
                        order.toSet() == positions.values.toSet()) ||
                        (dh !in positions.values &&
                            order.toSet() == positions.filterKeys { it != 1 }.values.toSet() + dh))
            ) {
                "请核对 DH、投手与打序的对应关系"
            }
    }
}
