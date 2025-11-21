package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.ln

object BowDuelTuner {

    data class BowParams(
        val shotCooldownMs: Long,
        val pearlCooldownMs: Long,
        val pearlEscapeDist: Float,
        val burstMin: Int,
        val burstMax: Int
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
        fun avg(): Double = if (plays > 0) totalReward / plays else 0.0

        fun ucb(totalPlays: Int, c: Double = 1.4): Double {
            if (plays == 0) return Double.POSITIVE_INFINITY
            val exploitation = avg()
            val exploration = c * sqrt(ln(totalPlays.toDouble()) / plays)
            return exploitation + exploration
        }
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

    private const val CURRENT_VERSION = 2
    private const val MISTAKE_PENALTY = 0.25

    private fun specF(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.FLOAT)

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.INT)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.LONG)

    private val specs = listOf(
        specL("shotCooldownMs", 600.0, 1100.0, 20.0, 800.0),
        specL("pearlCooldownMs", 4000.0, 9000.0, 100.0, 6000.0),
        specF("pearlEscapeDist", 15.0, 28.0, 0.5, 20.0),
        specI("burstMin", 2.0, 4.0, 1.0, 3.0),
        specI("burstMax", 3.0, 5.0, 1.0, 4.0)
    )

    private val specByKey = specs.associateBy { it.key }
    private var loaded = false
    private var state = StoredState()

    fun pickParams(): BowParams = build(pickValues())
    fun defaults(): BowParams = build(defaultValues())

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

    private fun build(values: Map<String, Double>): BowParams {
        val minBurst = values.int("burstMin")
        val maxBurst = values.int("burstMax").coerceAtLeast(minBurst)
        return BowParams(
            shotCooldownMs = values.long("shotCooldownMs"),
            pearlCooldownMs = values.long("pearlCooldownMs"),
            pearlEscapeDist = values.float("pearlEscapeDist"),
            burstMin = minBurst,
            burstMax = maxBurst
        )
    }

    private fun pickValues(): Map<String, Double> {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }

            if (p.values.isEmpty()) {
                initializeValues(p, spec)
            }

            val value = if (shouldExploreNew(p)) {
                val newValue = sample(spec)
                val key = keyOf(newValue)
                if (!p.values.containsKey(key)) {
                    p.values[key] = ValueState(value = newValue)
                }
                newValue
            } else {
                selectByUCB(p, spec)
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

    private fun selectByUCB(ps: ParamState, spec: ParamSpec): Double {
        if (ps.values.isEmpty()) return spec.def

        var bestValue = spec.def
        var bestScore = Double.NEGATIVE_INFINITY

        val c = when {
            ps.totalPlays < 50 -> 2.0
            ps.totalPlays < 200 -> 1.4
            ps.totalPlays < 500 -> 1.0
            else -> 0.5
        }

        for ((_, vs) in ps.values) {
            val score = vs.ucb(ps.totalPlays, c)
            if (score > bestScore) {
                bestScore = score
                bestValue = vs.value
            }
        }

        return clamp(bestValue, spec)
    }

    private fun shouldExploreNew(ps: ParamState): Boolean {
        if (ps.values.size < 10) return RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.3
        if (ps.totalPlays > 0 && ps.totalPlays % 30 == 0) return true

        val explorationRate = when {
            ps.totalPlays < 100 -> 0.2
            ps.totalPlays < 300 -> 0.1
            ps.totalPlays < 1000 -> 0.05
            else -> 0.02
        }

        return RandomUtils.randomDoubleInRange(0.0, 1.0) < explorationRate
    }

    private fun initializeValues(ps: ParamState, spec: ParamSpec) {
        val defKey = keyOf(spec.def)
        if (!ps.values.containsKey(defKey)) {
            ps.values[defKey] = ValueState(value = spec.def)
        }

        val range = spec.max - spec.min
        val initialValues = listOf(
            spec.min + range * 0.25,
            spec.min + range * 0.5,
            spec.min + range * 0.75
        )

        for (value in initialValues) {
            val quantized = quantize(value, spec.step)
            val clamped = clamp(quantized, spec)
            val key = keyOf(clamped)
            if (!ps.values.containsKey(key)) {
                ps.values[key] = ValueState(value = clamped)
            }
        }
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

    private fun file(): File = File(configDir(), "bowduel_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
