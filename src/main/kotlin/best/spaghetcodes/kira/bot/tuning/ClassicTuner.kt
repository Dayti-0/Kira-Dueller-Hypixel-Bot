package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.round

object ClassicTuner {

    data class ClassicParams(
        val fullDrawMsMin: Int,
        val fullDrawMsMax: Int,
        val openSpacingMin: Long,
        val openSpacingMax: Long,
        val openShotMinDist: Float,
        val bowCancelCloseDist: Float,
        val bowMinUseDist: Float,
        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,
        val reactiveCdMs: Long,
        val reserveTightMs: Long,
        val rodCdCloseMsBase: Long,
        val rodCdFarMsBase: Long,
        val rodCdBiasMax: Float,
        val rodBanMeleeDist: Float,
        val rodCloseMin: Float,
        val rodCloseMax: Float,
        val rodMainMin: Float,
        val rodMainMax: Float,
        val rodInterceptMin: Float,
        val rodInterceptMax: Float,
        val rodMaxRangeHard: Float,
        val rodMidInstantMin: Float,
        val rodMidInstantMax: Float,
        val farThreshold: Float,
        val parryMinDist: Float,
        val parryCloseCancelDist: Float,
        val parryCooldownMs: Long,
        val parryHoldMinMs: Int,
        val parryHoldMaxMs: Int,
        val parryStickMinMs: Int,
        val parryStickMaxMs: Int,
        val parryJumpCd: Long
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

    private const val CURRENT_VERSION = 2
    private const val MISTAKE_PENALTY = 0.1

    private fun specF(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.FLOAT)

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.INT)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.LONG)

    private fun specD(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.DOUBLE)

    private val specs = listOf(
        specI("fullDrawMsMin", 760.0, 860.0, 10.0, 820.0),
        specI("fullDrawMsMax", 960.0, 1080.0, 10.0, 980.0),
        specL("openSpacingMin", 500.0, 800.0, 10.0, 650.0),
        specL("openSpacingMax", 700.0, 1100.0, 10.0, 900.0),
        specF("openShotMinDist", 7.0, 11.0, 0.1, 9.0),
        specF("bowCancelCloseDist", 6.0, 10.0, 0.1, 8.0),
        specF("bowMinUseDist", 7.0, 12.0, 0.1, 9.0),
        specD("stillFrameThreshold", 0.006, 0.02, 0.0005, 0.0125),
        specI("stillFramesNeeded", 4.0, 16.0, 1.0, 10.0),
        specD("bowSlowThreshold", 0.03, 0.09, 0.001, 0.06),
        specI("bowSlowFramesNeeded", 1.0, 6.0, 1.0, 3.0),
        specL("reactiveCdMs", 500.0, 900.0, 10.0, 650.0),
        specL("reserveTightMs", 8000.0, 12000.0, 100.0, 10000.0),
        specL("rodCdCloseMsBase", 260.0, 360.0, 10.0, 300.0),
        specL("rodCdFarMsBase", 380.0, 520.0, 10.0, 420.0),
        specF("rodCdBiasMax", 1.1, 1.5, 0.01, 1.25),
        specF("rodBanMeleeDist", 3.0, 4.5, 0.05, 3.5),
        specF("rodCloseMin", 1.5, 2.5, 0.05, 2.0),
        specF("rodCloseMax", 3.0, 4.5, 0.05, 3.4),
        specF("rodMainMin", 2.5, 4.0, 0.05, 3.0),
        specF("rodMainMax", 5.0, 8.0, 0.05, 6.8),
        specF("rodInterceptMin", 4.5, 6.5, 0.05, 5.8),
        specF("rodInterceptMax", 6.0, 8.0, 0.05, 7.2),
        specF("rodMaxRangeHard", 6.0, 8.0, 0.05, 7.2),
        specF("rodMidInstantMin", 4.5, 6.5, 0.05, 5.5),
        specF("rodMidInstantMax", 6.0, 8.0, 0.05, 7.0),
        specF("farThreshold", 9.0, 13.0, 0.1, 11.0),
        specF("parryCloseCancelDist", 10.0, 16.0, 0.2, 12.0),
        specL("parryCooldownMs", 700.0, 1100.0, 10.0, 900.0),
        specI("parryHoldMinMs", 520.0, 760.0, 10.0, 650.0),
        specI("parryHoldMaxMs", 780.0, 1100.0, 10.0, 980.0),
        specF("parryMinDist", 9.0, 15.0, 0.2, 12.0),
        specI("parryStickMinMs", 600.0, 1100.0, 10.0, 900.0),
        specI("parryStickMaxMs", 1200.0, 1800.0, 10.0, 1500.0),
        specL("parryJumpCd", 400.0, 800.0, 10.0, 580.0)
    )

    private val specByKey = specs.associateBy { it.key }
    private var loaded = false
    private var state = StoredState()

    fun pickParams(): ClassicParams = build(pickValues())
    fun defaults(): ClassicParams = build(defaultValues())

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

    private fun build(values: Map<String, Double>) = ClassicParams(
        fullDrawMsMin = values.int("fullDrawMsMin"),
        fullDrawMsMax = values.int("fullDrawMsMax"),
        openSpacingMin = values.long("openSpacingMin"),
        openSpacingMax = values.long("openSpacingMax"),
        openShotMinDist = values.float("openShotMinDist"),
        bowCancelCloseDist = values.float("bowCancelCloseDist"),
        bowMinUseDist = values.float("bowMinUseDist"),
        stillFrameThreshold = values.double("stillFrameThreshold"),
        stillFramesNeeded = values.int("stillFramesNeeded"),
        bowSlowThreshold = values.double("bowSlowThreshold"),
        bowSlowFramesNeeded = values.int("bowSlowFramesNeeded"),
        reactiveCdMs = values.long("reactiveCdMs"),
        reserveTightMs = values.long("reserveTightMs"),
        rodCdCloseMsBase = values.long("rodCdCloseMsBase"),
        rodCdFarMsBase = values.long("rodCdFarMsBase"),
        rodCdBiasMax = values.float("rodCdBiasMax"),
        rodBanMeleeDist = values.float("rodBanMeleeDist"),
        rodCloseMin = values.float("rodCloseMin"),
        rodCloseMax = values.float("rodCloseMax"),
        rodMainMin = values.float("rodMainMin"),
        rodMainMax = values.float("rodMainMax"),
        rodInterceptMin = values.float("rodInterceptMin"),
        rodInterceptMax = values.float("rodInterceptMax"),
        rodMaxRangeHard = values.float("rodMaxRangeHard"),
        rodMidInstantMin = values.float("rodMidInstantMin"),
        rodMidInstantMax = values.float("rodMidInstantMax"),
        farThreshold = values.float("farThreshold"),
        parryMinDist = values.float("parryMinDist"),
        parryCloseCancelDist = values.float("parryCloseCancelDist"),
        parryCooldownMs = values.long("parryCooldownMs"),
        parryHoldMinMs = values.int("parryHoldMinMs"),
        parryHoldMaxMs = values.int("parryHoldMaxMs"),
        parryStickMinMs = values.int("parryStickMinMs"),
        parryStickMaxMs = values.int("parryStickMaxMs"),
        parryJumpCd = values.long("parryJumpCd")
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
        val base = 0.5
        val decay = totalPlays / 40.0
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
    private fun Map<String, Double>.double(key: String): Double = clampNum(this[key], key)

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

    private fun file(): File = File(configDir(), "classic_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
