package best.spaghetcodes.kira.core

enum class RequeueMode(val displayName: String) {
    FAST("Fast"),
    PAPER("Paper");

    companion object {
        fun fromConfig(value: String?): RequeueMode {
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FAST
        }
    }
}
