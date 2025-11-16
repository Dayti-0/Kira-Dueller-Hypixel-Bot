package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.round

object ComboTuner {

    data class ComboParams(
        val strengthPeriodMs: Long,
        val gapPeriodMs: Long,
        val maxPearls: Int,
        val closeInnerThreshold: Float,
        val closeMidThreshold: Float,
        val closeInnerDelayMs: Long,
        val closeMidDelayMs: Long,
        val closeFarDelayMs: Long,
        val openingStrengthHoldMs: Int,
        val openingGapHoldMs: Int,
        val recurringGapHoldMs: Int,
        val gapPreMinMs: Int,
        val gapPreMaxMs: Int,
        val strengthRecastPreMinMs: Int,
        val strengthRecastPreMaxMs: Int,
        val strengthRecastHoldMs: Int,
        val farJumpCooldownMs: Long,
        val pearlCooldownMs: Long,
        val quickPearlDistanceMin: Float,
        val defensivePearlMinMotionY: Float
    )

    private enum class ParamType { FLOAT, INT, LONG, DOUBLE }

    private data class ParamSpec(
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val def: Double,
        val type: ParamType
    )

    private data class ValueState(var value: Double = 0.0, var plays: Int = 0, var totalReward: Double = 0.0) {
        fun avg(): Double = if (plays > 0) totalReward / plays else Double.NEGATIVE_INFINITY
    }

    private data class ParamState(
        var values: MutableMap<String, ValueState> = mutableMapOf(),
        var lastValue: Double = 0.0,
        var totalPlays: Int = 0
    )

    private data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf()
    )

    private const val CURRENT_VERSION = 1
    private const val MISTAKE_PENALTY = 0.1

    private fun specF(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.FLOAT)

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.INT)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.LONG)

    private val specs = listOf(
        specL("strengthPeriodMs", 240000.0, 320000.0, 2000.0, 294000.0),
        specL("gapPeriodMs", 22000.0, 34000.0, 500.0, 26000.0),
        specI("maxPearls", 3.0, 6.0, 1.0, 5.0),
        specF("closeInnerThreshold", 1.1, 1.8, 0.05, 1.4),
        specF("closeMidThreshold", 1.6, 2.4, 0.05, 2.0),
        specL("closeInnerDelayMs", 260.0, 420.0, 10.0, 340.0),
        specL("closeMidDelayMs", 200.0, 320.0, 10.0, 260.0),
        specL("closeFarDelayMs", 160.0, 280.0, 10.0, 200.0),
        specL("openingStrengthHoldMs", 1900.0, 2400.0, 50.0, 2100.0),
        specL("openingGapHoldMs", 2200.0, 2800.0, 50.0, 2400.0),
        specL("recurringGapHoldMs", 2200.0, 2600.0, 25.0, 2400.0),
        specI("gapPreMinMs", 60.0, 150.0, 5.0, 110.0),
        specI("gapPreMaxMs", 120.0, 240.0, 5.0, 160.0),
        specI("strengthRecastPreMinMs", 60.0, 200.0, 5.0, 110.0),
        specI("strengthRecastPreMaxMs", 120.0, 300.0, 5.0, 160.0),
        specL("strengthRecastHoldMs", 2000.0, 2800.0, 50.0, 2400.0),
        specL("farJumpCooldownMs", 420.0, 720.0, 20.0, 540.0),
        specL("pearlCooldownMs", 9000.0, 13000.0, 250.0, 11000.0),
        specF("quickPearlDistanceMin", 20.0, 28.0, 0.5, 24.0),
        specF("defensivePearlMinMotionY", 0.12, 0.2, 0.01, 0.15)
    )

    private val specByKey = specs.associateBy { it.key }
    private var loaded = false
    private var state = StoredState()

    fun pickParams(): ComboParams = build(pickValues())
    fun defaults(): ComboParams = build(defaultValues())

    fun report(win: Boolean, mistakes: Int = 0) {
        ensureLoaded()
        val rewardRaw = if (win) 1.0 else 0.0
        val reward = (rewardRaw - mistakes * MISTAKE_PENALTY).coerceAtLeast(0.0)
        var changed = false
        for ((_, ps) in state.params) {
            val entryKey = keyOf(ps.lastValue)
            val vs = ps.values.getOrPut(entryKey) { ValueState(value = ps.lastValue) }
            vs.plays += 1
            vs.totalReward += reward
            ps.totalPlays += 1
            changed = true
        }
        if (changed) save()
    }

    private fun build(values: Map<String, Double>): ComboParams {
        val inner = values.float("closeInnerThreshold")
        val mid = values.float("closeMidThreshold").coerceAtLeast(inner + 0.1f)
        val gapPreMin = values.int("gapPreMinMs")
        val gapPreMax = max(gapPreMin + 5, values.int("gapPreMaxMs"))
        val strengthPreMin = values.int("strengthRecastPreMinMs")
        val strengthPreMax = max(strengthPreMin + 5, values.int("strengthRecastPreMaxMs"))
        return ComboParams(
            strengthPeriodMs = values.long("strengthPeriodMs"),
            gapPeriodMs = values.long("gapPeriodMs"),
            maxPearls = values.int("maxPearls"),
            closeInnerThreshold = inner,
            closeMidThreshold = mid,
            closeInnerDelayMs = values.long("closeInnerDelayMs"),
            closeMidDelayMs = values.long("closeMidDelayMs"),
            closeFarDelayMs = values.long("closeFarDelayMs"),
            openingStrengthHoldMs = values.long("openingStrengthHoldMs").toInt(),
            openingGapHoldMs = values.long("openingGapHoldMs").toInt(),
            recurringGapHoldMs = values.long("recurringGapHoldMs").toInt(),
            gapPreMinMs = gapPreMin,
            gapPreMaxMs = gapPreMax,
            strengthRecastPreMinMs = strengthPreMin,
            strengthRecastPreMaxMs = strengthPreMax,
            strengthRecastHoldMs = values.long("strengthRecastHoldMs").toInt(),
            farJumpCooldownMs = values.long("farJumpCooldownMs"),
            pearlCooldownMs = values.long("pearlCooldownMs"),
            quickPearlDistanceMin = values.float("quickPearlDistanceMin"),
            defensivePearlMinMotionY = values.float("defensivePearlMinMotionY")
        )
    }

    private fun pickValues(): Map<String, Double> {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            val eps = epsilon(p.totalPlays)
            val explore = exploreNow(eps) || p.values.isEmpty()
            val value = if (explore) sample(spec) else bestValue(p, spec)
            val key = keyOf(value)
            if (!p.values.containsKey(key)) {
                p.values[key] = ValueState(value = value)
            }
            p.lastValue = value
            chosen[spec.key] = value
        }
        save()
        return chosen
    }

    private fun defaultValues(): Map<String, Double> = specs.associate { it.key to it.def }

    private fun ensureLoaded() {
        if (!loaded) {
            state = load()
            loaded = true
        }
    }

    private fun epsilon(totalPlays: Int): Double {
        val base = 0.55
        val decay = totalPlays / 35.0
        return max(0.1, base / (1.0 + decay))
    }

    private fun exploreNow(epsilon: Double): Boolean =
        RandomUtils.randomDoubleInRange(0.0, 1.0) < epsilon

    private fun bestValue(ps: ParamState, spec: ParamSpec): Double {
        val best = ps.values.values.maxByOrNull { it.avg() }?.value ?: spec.def
        return clamp(best, spec)
    }

    private fun sample(spec: ParamSpec): Double {
        val sampled = RandomUtils.randomDoubleInRange(spec.min, spec.max)
        return clamp(quantize(sampled, spec.step), spec)
    }

    private fun clamp(v: Double, spec: ParamSpec): Double {
        val c = v.coerceIn(spec.min, spec.max)
        return when (spec.type) {
            ParamType.FLOAT -> c
            ParamType.INT -> round(c).toInt().toDouble()
            ParamType.LONG -> round(c).toLong().toDouble()
            ParamType.DOUBLE -> c
        }
    }

    private fun quantize(v: Double, step: Double): Double {
        if (step <= 0.0) return v
        val s = round(v / step)
        return s * step
    }

    private fun keyOf(v: Double): String = "%.4f".format(v)

    private fun Map<String, Double>.float(key: String): Float = clampNum(this[key], key).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNum(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNum(this[key], key).toLong()

    private fun clampNum(value: Double?, key: String): Double {
        val spec = specByKey[key]
        val raw = value ?: spec?.def ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    private fun load(): StoredState {
        return try {
            val f = file()
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                StoredState()
            } else {
                f.reader().use { reader ->
                    val type = object : TypeToken<StoredState>() {}.type
                    val loadedState = kira.gson.fromJson<StoredState>(reader, type)
                    if (loadedState == null || loadedState.version != CURRENT_VERSION) {
                        StoredState()
                    } else {
                        loadedState
                    }
                }
            }
        } catch (_: Exception) {
            StoredState()
        }
    }

    private fun save() {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            f.writer().use { writer ->
                kira.gson.toJson(state, writer)
            }
        } catch (_: Exception) {
        }
    }

    private fun file(): File = File(configDir(), "combo_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
