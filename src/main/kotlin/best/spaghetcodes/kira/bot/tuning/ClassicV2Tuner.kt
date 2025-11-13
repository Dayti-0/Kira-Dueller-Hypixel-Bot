package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.round

object ClassicV2Tuner {

    data class ClassicParams(
        val fullDrawMsMin: Int,
        val fullDrawMsMax: Int,
        val bowCancelCloseDist: Float,
        val bowMinUseDist: Float,
        val openVolleyMax: Int,
        val openSpacingMin: Long,
        val openSpacingMax: Long,
        val openShotMinDist: Float,
        val reactiveCdMs: Long,

        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,

        val reserveTightMs: Long,
        val earlyReserve: Int,
        val midReserve: Int,

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
        val reentryRodGraceMs: Long,

        val rodHoldCloseMinMs: Int,
        val rodHoldCloseMaxMs: Int,
        val rodHoldMidMinMs: Int,
        val rodHoldMidMaxMs: Int,

        val rodAntiSpamClosePassiveMin: Int,
        val rodAntiSpamClosePassiveMax: Int,
        val rodAntiSpamMidPassiveMin: Int,
        val rodAntiSpamMidPassiveMax: Int,
        val rodAntiSpamFarPassiveMin: Int,
        val rodAntiSpamFarPassiveMax: Int,

        val rodAntiSpamCloseActiveMin: Int,
        val rodAntiSpamCloseActiveMax: Int,
        val rodAntiSpamMidActiveMin: Int,
        val rodAntiSpamMidActiveMax: Int,
        val rodAntiSpamFarActiveMin: Int,
        val rodAntiSpamFarActiveMax: Int,

        val parryCloseCancelDist: Float,
        val parryCooldownMs: Long,
        val parryHoldMinMs: Int,
        val parryHoldMaxMs: Int,
        val parryStickMinMs: Int,
        val parryStickMaxMs: Int,
        val parryJumpCd: Long,
        val allowParryDelayMs: Long,

        val closeBurstWindowMinMs: Int,
        val closeBurstWindowMaxMs: Int,
        val closeBurstFlipMinMs: Int,
        val closeBurstFlipMaxMs: Int,
        val closeHoldWindowMinMs: Int,
        val closeHoldWindowMaxMs: Int,

        val forwardStickMinMs: Int,
        val forwardStickMaxMs: Int,
        val meleeFocusMinMs: Int,
        val meleeFocusMaxMs: Int
    )

    private enum class ParamType { FLOAT, INT, LONG, DOUBLE }
    private data class ParamSpec(val key: String, val min: Double, val max: Double, val step: Double, val def: Double, val type: ParamType)
    private data class ValueState(var value: Double = 0.0, var plays: Int = 0, var totalReward: Double = 0.0) { fun avg() = if (plays > 0) totalReward / plays else Double.NEGATIVE_INFINITY }
    private data class ParamState(var values: MutableMap<String, ValueState> = mutableMapOf(), var lastValue: Double = 0.0, var totalPlays: Int = 0)
    private data class StoredState(var version: Int = CURRENT_VERSION, var params: MutableMap<String, ParamState> = mutableMapOf())

    private const val CURRENT_VERSION = 1
    private const val MISTAKE_PENALTY = 0.1

    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.FLOAT)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.INT)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.LONG)
    private fun specD(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.DOUBLE)

    private val specs = listOf(
        specI("fullDrawMsMin", 700.0, 1000.0, 10.0, 820.0),
        specI("fullDrawMsMax", 900.0, 1100.0, 10.0, 980.0),
        specF("bowCancelCloseDist", 6.0, 10.0, 0.1, 8.0),
        specF("bowMinUseDist", 7.0, 11.0, 0.1, 9.0),
        specI("openVolleyMax", 1.0, 2.0, 1.0, 1.0),
        specL("openSpacingMin", 450.0, 850.0, 10.0, 650.0),
        specL("openSpacingMax", 700.0, 1150.0, 10.0, 900.0),
        specF("openShotMinDist", 7.0, 12.0, 0.1, 9.0),
        specL("reactiveCdMs", 450.0, 900.0, 10.0, 650.0),

        specD("stillFrameThreshold", 0.008, 0.02, 0.0005, 0.0125),
        specI("stillFramesNeeded", 6.0, 16.0, 1.0, 10.0),
        specD("bowSlowThreshold", 0.04, 0.09, 0.002, 0.06),
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 3.0),

        specL("reserveTightMs", 7000.0, 13000.0, 100.0, 10000.0),
        specI("earlyReserve", 2.0, 5.0, 1.0, 3.0),
        specI("midReserve", 1.0, 4.0, 1.0, 2.0),

        specL("rodCdCloseMsBase", 280.0, 420.0, 10.0, 340.0),
        specL("rodCdFarMsBase", 360.0, 620.0, 10.0, 480.0),
        specF("rodCdBiasMax", 1.05, 1.5, 0.01, 1.25),
        specF("rodBanMeleeDist", 3.0, 5.0, 0.05, 4.0),
        specF("rodCloseMin", 1.6, 2.6, 0.05, 2.0),
        specF("rodCloseMax", 2.6, 4.0, 0.05, 3.4),
        specF("rodMainMin", 2.4, 3.6, 0.05, 3.0),
        specF("rodMainMax", 5.5, 8.2, 0.05, 6.8),
        specF("rodInterceptMin", 4.8, 6.4, 0.05, 5.8),
        specF("rodInterceptMax", 6.2, 8.2, 0.05, 7.2),
        specF("rodMaxRangeHard", 6.5, 8.0, 0.05, 7.2),
        specF("rodMidInstantMin", 4.8, 6.2, 0.05, 5.5),
        specF("rodMidInstantMax", 6.2, 7.6, 0.05, 7.0),
        specF("farThreshold", 9.0, 14.0, 0.1, 11.0),
        specL("reentryRodGraceMs", 200.0, 500.0, 10.0, 300.0),

        specI("rodHoldCloseMinMs", 90.0, 160.0, 5.0, 118.0),
        specI("rodHoldCloseMaxMs", 110.0, 190.0, 5.0, 142.0),
        specI("rodHoldMidMinMs", 160.0, 260.0, 5.0, 208.0),
        specI("rodHoldMidMaxMs", 180.0, 300.0, 5.0, 232.0),

        specI("rodAntiSpamClosePassiveMin", 260.0, 420.0, 10.0, 340.0),
        specI("rodAntiSpamClosePassiveMax", 340.0, 520.0, 10.0, 420.0),
        specI("rodAntiSpamMidPassiveMin", 400.0, 640.0, 10.0, 520.0),
        specI("rodAntiSpamMidPassiveMax", 520.0, 820.0, 10.0, 680.0),
        specI("rodAntiSpamFarPassiveMin", 400.0, 640.0, 10.0, 520.0),
        specI("rodAntiSpamFarPassiveMax", 540.0, 860.0, 10.0, 700.0),

        specI("rodAntiSpamCloseActiveMin", 200.0, 360.0, 10.0, 260.0),
        specI("rodAntiSpamCloseActiveMax", 260.0, 420.0, 10.0, 320.0),
        specI("rodAntiSpamMidActiveMin", 280.0, 480.0, 10.0, 380.0),
        specI("rodAntiSpamMidActiveMax", 400.0, 640.0, 10.0, 520.0),
        specI("rodAntiSpamFarActiveMin", 320.0, 520.0, 10.0, 400.0),
        specI("rodAntiSpamFarActiveMax", 420.0, 700.0, 10.0, 560.0),

        specF("parryCloseCancelDist", 11.0, 19.0, 0.2, 15.0),
        specL("parryCooldownMs", 600.0, 1200.0, 10.0, 900.0),
        specI("parryHoldMinMs", 520.0, 820.0, 10.0, 650.0),
        specI("parryHoldMaxMs", 820.0, 1200.0, 10.0, 980.0),
        specI("parryStickMinMs", 720.0, 1100.0, 10.0, 900.0),
        specI("parryStickMaxMs", 1200.0, 1800.0, 10.0, 1500.0),
        specL("parryJumpCd", 400.0, 800.0, 10.0, 580.0),
        specL("allowParryDelayMs", 2000.0, 3600.0, 50.0, 2800.0),

        specI("closeBurstWindowMinMs", 200.0, 360.0, 10.0, 280.0),
        specI("closeBurstWindowMaxMs", 320.0, 520.0, 10.0, 420.0),
        specI("closeBurstFlipMinMs", 40.0, 100.0, 5.0, 60.0),
        specI("closeBurstFlipMaxMs", 80.0, 160.0, 5.0, 110.0),
        specI("closeHoldWindowMinMs", 160.0, 300.0, 10.0, 220.0),
        specI("closeHoldWindowMaxMs", 260.0, 420.0, 10.0, 340.0),

        specI("forwardStickMinMs", 160.0, 300.0, 10.0, 220.0),
        specI("forwardStickMaxMs", 220.0, 360.0, 10.0, 280.0),
        specI("meleeFocusMinMs", 220.0, 380.0, 10.0, 300.0),
        specI("meleeFocusMaxMs", 260.0, 420.0, 10.0, 340.0)
    )

    private val specByKey = specs.associateBy { it.key }

    private var loaded = false
    private var state = StoredState()

    fun pickParams(): ClassicParams {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            val eps = epsilon(p.totalPlays)
            val explore = exploreNow(eps) || p.values.isEmpty()
            val value = if (explore) sample(spec) else bestValue(p, spec)
            val key = keyOf(value)
            if (!p.values.containsKey(key)) p.values[key] = ValueState(value = value)
            p.lastValue = value
            chosen[spec.key] = value
        }
        save()
        return ClassicParams(
            fullDrawMsMin = chosen.int("fullDrawMsMin"),
            fullDrawMsMax = chosen.int("fullDrawMsMax"),
            bowCancelCloseDist = chosen.float("bowCancelCloseDist"),
            bowMinUseDist = chosen.float("bowMinUseDist"),
            openVolleyMax = chosen.int("openVolleyMax"),
            openSpacingMin = chosen.long("openSpacingMin"),
            openSpacingMax = chosen.long("openSpacingMax"),
            openShotMinDist = chosen.float("openShotMinDist"),
            reactiveCdMs = chosen.long("reactiveCdMs"),

            stillFrameThreshold = chosen.double("stillFrameThreshold"),
            stillFramesNeeded = chosen.int("stillFramesNeeded"),
            bowSlowThreshold = chosen.double("bowSlowThreshold"),
            bowSlowFramesNeeded = chosen.int("bowSlowFramesNeeded"),

            reserveTightMs = chosen.long("reserveTightMs"),
            earlyReserve = chosen.int("earlyReserve"),
            midReserve = chosen.int("midReserve"),

            rodCdCloseMsBase = chosen.long("rodCdCloseMsBase"),
            rodCdFarMsBase = chosen.long("rodCdFarMsBase"),
            rodCdBiasMax = chosen.float("rodCdBiasMax"),
            rodBanMeleeDist = chosen.float("rodBanMeleeDist"),
            rodCloseMin = chosen.float("rodCloseMin"),
            rodCloseMax = chosen.float("rodCloseMax"),
            rodMainMin = chosen.float("rodMainMin"),
            rodMainMax = chosen.float("rodMainMax"),
            rodInterceptMin = chosen.float("rodInterceptMin"),
            rodInterceptMax = chosen.float("rodInterceptMax"),
            rodMaxRangeHard = chosen.float("rodMaxRangeHard"),
            rodMidInstantMin = chosen.float("rodMidInstantMin"),
            rodMidInstantMax = chosen.float("rodMidInstantMax"),
            farThreshold = chosen.float("farThreshold"),
            reentryRodGraceMs = chosen.long("reentryRodGraceMs"),

            rodHoldCloseMinMs = chosen.int("rodHoldCloseMinMs"),
            rodHoldCloseMaxMs = chosen.int("rodHoldCloseMaxMs"),
            rodHoldMidMinMs = chosen.int("rodHoldMidMinMs"),
            rodHoldMidMaxMs = chosen.int("rodHoldMidMaxMs"),

            rodAntiSpamClosePassiveMin = chosen.int("rodAntiSpamClosePassiveMin"),
            rodAntiSpamClosePassiveMax = chosen.int("rodAntiSpamClosePassiveMax"),
            rodAntiSpamMidPassiveMin = chosen.int("rodAntiSpamMidPassiveMin"),
            rodAntiSpamMidPassiveMax = chosen.int("rodAntiSpamMidPassiveMax"),
            rodAntiSpamFarPassiveMin = chosen.int("rodAntiSpamFarPassiveMin"),
            rodAntiSpamFarPassiveMax = chosen.int("rodAntiSpamFarPassiveMax"),

            rodAntiSpamCloseActiveMin = chosen.int("rodAntiSpamCloseActiveMin"),
            rodAntiSpamCloseActiveMax = chosen.int("rodAntiSpamCloseActiveMax"),
            rodAntiSpamMidActiveMin = chosen.int("rodAntiSpamMidActiveMin"),
            rodAntiSpamMidActiveMax = chosen.int("rodAntiSpamMidActiveMax"),
            rodAntiSpamFarActiveMin = chosen.int("rodAntiSpamFarActiveMin"),
            rodAntiSpamFarActiveMax = chosen.int("rodAntiSpamFarActiveMax"),

            parryCloseCancelDist = chosen.float("parryCloseCancelDist"),
            parryCooldownMs = chosen.long("parryCooldownMs"),
            parryHoldMinMs = chosen.int("parryHoldMinMs"),
            parryHoldMaxMs = chosen.int("parryHoldMaxMs"),
            parryStickMinMs = chosen.int("parryStickMinMs"),
            parryStickMaxMs = chosen.int("parryStickMaxMs"),
            parryJumpCd = chosen.long("parryJumpCd"),
            allowParryDelayMs = chosen.long("allowParryDelayMs"),

            closeBurstWindowMinMs = chosen.int("closeBurstWindowMinMs"),
            closeBurstWindowMaxMs = chosen.int("closeBurstWindowMaxMs"),
            closeBurstFlipMinMs = chosen.int("closeBurstFlipMinMs"),
            closeBurstFlipMaxMs = chosen.int("closeBurstFlipMaxMs"),
            closeHoldWindowMinMs = chosen.int("closeHoldWindowMinMs"),
            closeHoldWindowMaxMs = chosen.int("closeHoldWindowMaxMs"),

            forwardStickMinMs = chosen.int("forwardStickMinMs"),
            forwardStickMaxMs = chosen.int("forwardStickMaxMs"),
            meleeFocusMinMs = chosen.int("meleeFocusMinMs"),
            meleeFocusMaxMs = chosen.int("meleeFocusMaxMs")
        )
    }

    fun report(win: Boolean, mistakes: Int) {
        ensureLoaded()
        val rewardRaw = if (win) 1.0 else 0.0
        val reward = rewardRaw - mistakes * MISTAKE_PENALTY
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

    private fun exploreNow(epsilon: Double): Boolean = RandomUtils.randomDoubleInRange(0.0, 1.0) < epsilon

    private fun bestValue(ps: ParamState, spec: ParamSpec): Double = clamp(ps.values.values.maxByOrNull { it.avg() }?.value ?: spec.def, spec)

    private fun sample(spec: ParamSpec): Double = clamp(quantize(RandomUtils.randomDoubleInRange(spec.min, spec.max), spec.step), spec)

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

    private fun clampNum(v: Double?, key: String): Double {
        val spec = specByKey[key]
        val raw = v ?: spec?.def ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    private fun load(): StoredState {
        return try {
            val file = file()
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                StoredState()
            } else {
                file.reader().use { reader ->
                    val type = object : TypeToken<StoredState>() {}.type
                    kira.gson.fromJson<StoredState>(reader, type) ?: StoredState()
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            StoredState()
        }
    }

    private fun save() {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            f.writer().use { writer -> kira.gson.toJson(state, writer) }
        } catch (_: Exception) {
        }
    }

    private fun file(): File {
        val base = File(kira.mc.mcDataDir, "config")
        return File(base, "classicv2_tuner.json")
    }
}
