package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.round

object OPTuner {

    data class OPParams(
        val maxArrows: Int,
        val speedPots: Int,
        val regenPots: Int,
        val gaps: Int,
        val minGapIntervalMs: Long,
        val longStrafeChance: Int,
        val rodCdCloseMsBase: Long,
        val rodCdFarMsBase: Long
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

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.INT)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.LONG)

    private val specs = listOf(
        specI("maxArrows", 12.0, 28.0, 1.0, 20.0),
        specI("speedPots", 1.0, 3.0, 1.0, 2.0),
        specI("regenPots", 1.0, 3.0, 1.0, 2.0),
        specI("gaps", 4.0, 8.0, 1.0, 6.0),
        specL("minGapIntervalMs", 3500.0, 5500.0, 100.0, 4500.0),
        specI("longStrafeChance", 10.0, 40.0, 1.0, 25.0),
        specL("rodCdCloseMsBase", 300.0, 420.0, 10.0, 340.0),
        specL("rodCdFarMsBase", 440.0, 600.0, 10.0, 480.0)
    )

    private val specByKey = specs.associateBy { it.key }
    private var loaded = false
    private var state = StoredState()

    fun pickParams(): OPParams = build(pickValues())
    fun defaults(): OPParams = build(defaultValues())

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

    private fun build(values: Map<String, Double>) = OPParams(
        maxArrows = values.int("maxArrows"),
        speedPots = values.int("speedPots"),
        regenPots = values.int("regenPots"),
        gaps = values.int("gaps"),
        minGapIntervalMs = values.long("minGapIntervalMs"),
        longStrafeChance = values.int("longStrafeChance"),
        rodCdCloseMsBase = values.long("rodCdCloseMsBase"),
        rodCdFarMsBase = values.long("rodCdFarMsBase")
    )

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
        val base = 0.35
        val decay = totalPlays / 25.0
        return max(0.05, base / (1.0 + decay))
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

    private fun file(): File = File(configDir(), "op_tuner.json")

    private fun configDir(): File {
        return try {
            val mcDir = kira.mc.mcDataDir
            if (mcDir != null) File(mcDir, "config") else File(File(System.getProperty("user.home"), ".kira"), "config")
        } catch (_: Throwable) {
            File("config")
        }
    }
}
