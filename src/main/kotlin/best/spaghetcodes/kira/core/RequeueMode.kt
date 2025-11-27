package best.spaghetcodes.kira.core

enum class RequeueMode(val displayNameKey: String) {
    FAST("kira.gui.requeue.fast"),
    PAPER("kira.gui.requeue.paper");

    companion object {
        fun fromConfig(value: String?): RequeueMode {
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FAST
        }
    }
}
