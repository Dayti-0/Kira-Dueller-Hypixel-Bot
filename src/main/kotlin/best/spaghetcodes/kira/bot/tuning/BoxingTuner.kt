package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.round

object BoxingTuner {

    data class Params(
        val jumpCooldownMs: Long,
        val noJumpCloseDist: Float,
        val warmupDistanceStop: Float,
        val warmupJumpEveryMin: Int,
        val warmupJumpEveryMax: Int,
        val warmupPressMin: Int,
        val warmupPressMax: Int,
        val comboLockMin: Int,
        val comboLockMax: Int,
        val forwardStickMinMs: Int,
        val forwardStickMaxMs: Int,
        val meleeFocusMinMs: Int,
        val meleeFocusMaxMs: Int,
        val microJitterMin: Int,
        val microJitterMax: Int,
        val stopForwardCloseDistCombo: Float,
        val resumeForwardDistCombo: Float,
        val stopForwardCloseDistDefault: Float,
        val resumeForwardDistDefault: Float,
        val kbRecoveryMin: Int,
        val kbRecoveryMax: Int,
        val heavyKbRecoveryMin: Int,
        val heavyKbRecoveryMax: Int,
        val heavyKbDelta: Float,
        val targetDistComboMin: Float,
        val targetDistComboMax: Float,
        val targetDistNeutralMin: Float,
        val targetDistNeutralMax: Float,
        val burstFlipMin: Int,
        val burstFlipMax: Int,
        val burstWindowMin: Int,
        val burstWindowMax: Int,
        val holdWindowMin: Int,
        val holdWindowMax: Int,
        val longStrafeMin: Int,
        val longStrafeMax: Int,
        val longStrafeDistanceCap: Float,
        val longStrafeBaseChance: Int,
        val antiStallEps: Float,
        val antiStallDelay: Int,
        val aimSpikeDeg: Float,
        val aimSpikeCooldown: Long,
        val wallNearMargin: Float,
        val wallEscapeTimeMsMin: Int,
        val wallEscapeTimeMsMax: Int,
        val enemyIframeSoft: Int
    )

    private enum class ParamType { FLOAT, INT, LONG }
    private data class ParamSpec(val key: String, val min: Double, val max: Double, val step: Double, val def: Double, val type: ParamType)
    private data class ValueState(var value: Double = 0.0, var plays: Int = 0, var totalReward: Double = 0.0) { fun avg() = if (plays > 0) totalReward / plays else Double.NEGATIVE_INFINITY }
    private data class ParamState(var values: MutableMap<String, ValueState> = mutableMapOf(), var lastValue: Double = 0.0, var totalPlays: Int = 0)
    private data class StoredState(var version: Int = CURRENT_VERSION, var params: MutableMap<String, ParamState> = mutableMapOf())

    private const val CURRENT_VERSION = 2
    private const val MISTAKE_PENALTY = 0.25

    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.FLOAT)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.INT)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.LONG)

    private val specs = listOf(
        specL("jumpCooldownMs", 700.0, 1200.0, 10.0, 900.0),
        specF("noJumpCloseDist", 3.0, 4.2, 0.05, 3.6),
        specF("warmupDistanceStop", 6.0, 8.0, 0.1, 7.0),
        specI("warmupJumpEveryMin", 200.0, 320.0, 5.0, 240.0),
        specI("warmupJumpEveryMax", 320.0, 480.0, 5.0, 380.0),
        specI("warmupPressMin", 110.0, 170.0, 5.0, 130.0),
        specI("warmupPressMax", 160.0, 230.0, 5.0, 190.0),
        specI("comboLockMin", 480.0, 720.0, 5.0, 560.0),
        specI("comboLockMax", 640.0, 900.0, 5.0, 760.0),
        specI("forwardStickMinMs", 220.0, 320.0, 5.0, 260.0),
        specI("forwardStickMaxMs", 280.0, 380.0, 5.0, 340.0),
        specI("meleeFocusMinMs", 360.0, 460.0, 5.0, 420.0),
        specI("meleeFocusMaxMs", 460.0, 620.0, 5.0, 520.0),
        specI("microJitterMin", 80.0, 160.0, 5.0, 120.0),
        specI("microJitterMax", 140.0, 240.0, 5.0, 180.0),
        specF("stopForwardCloseDistCombo", 0.9, 1.2, 0.01, 1.0),
        specF("resumeForwardDistCombo", 1.3, 1.7, 0.01, 1.45),
        specF("stopForwardCloseDistDefault", 0.95, 1.3, 0.01, 1.1),
        specF("resumeForwardDistDefault", 1.4, 1.9, 0.01, 1.6),
        specI("kbRecoveryMin", 460.0, 680.0, 5.0, 520.0),
        specI("kbRecoveryMax", 680.0, 920.0, 5.0, 760.0),
        specI("heavyKbRecoveryMin", 560.0, 760.0, 5.0, 650.0),
        specI("heavyKbRecoveryMax", 780.0, 960.0, 5.0, 900.0),
        specF("heavyKbDelta", 0.3, 0.6, 0.01, 0.45),
        specF("targetDistComboMin", 0.9, 1.2, 0.01, 1.08),
        specF("targetDistComboMax", 1.3, 1.7, 0.01, 1.5),
        specF("targetDistNeutralMin", 1.5, 2.0, 0.01, 1.75),
        specF("targetDistNeutralMax", 2.0, 2.8, 0.01, 2.35),
        specI("burstFlipMin", 40.0, 80.0, 1.0, 55.0),
        specI("burstFlipMax", 80.0, 130.0, 1.0, 95.0),
        specI("burstWindowMin", 180.0, 320.0, 5.0, 240.0),
        specI("burstWindowMax", 320.0, 480.0, 5.0, 380.0),
        specI("holdWindowMin", 160.0, 300.0, 5.0, 220.0),
        specI("holdWindowMax", 280.0, 420.0, 5.0, 340.0),
        specI("longStrafeMin", 700.0, 1200.0, 10.0, 900.0),
        specI("longStrafeMax", 1200.0, 2000.0, 10.0, 1600.0),
        specF("longStrafeDistanceCap", 3.0, 4.0, 0.05, 3.4),
        specI("longStrafeBaseChance", 15.0, 45.0, 1.0, 30.0),
        specF("antiStallEps", 0.008, 0.03, 0.001, 0.015),
        specI("antiStallDelay", 200.0, 420.0, 5.0, 260.0),
        specF("aimSpikeDeg", 10.0, 18.0, 0.1, 14.0),
        specL("aimSpikeCooldown", 140.0, 260.0, 5.0, 180.0),
        specF("wallNearMargin", 0.7, 1.2, 0.05, 0.9),
        specI("wallEscapeTimeMsMin", 500.0, 800.0, 5.0, 600.0),
        specI("wallEscapeTimeMsMax", 700.0, 1100.0, 5.0, 900.0),
        specI("enemyIframeSoft", 2.0, 5.0, 1.0, 3.0)
    )

    private val specByKey = specs.associateBy { it.key }

    private var loaded = false
    private var state = StoredState()

    fun pickParams(): Params {
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
        return Params(
            jumpCooldownMs = chosen.long("jumpCooldownMs"),
            noJumpCloseDist = chosen.float("noJumpCloseDist"),
            warmupDistanceStop = chosen.float("warmupDistanceStop"),
            warmupJumpEveryMin = chosen.int("warmupJumpEveryMin"),
            warmupJumpEveryMax = chosen.int("warmupJumpEveryMax"),
            warmupPressMin = chosen.int("warmupPressMin"),
            warmupPressMax = chosen.int("warmupPressMax"),
            comboLockMin = chosen.int("comboLockMin"),
            comboLockMax = chosen.int("comboLockMax"),
            forwardStickMinMs = chosen.int("forwardStickMinMs"),
            forwardStickMaxMs = chosen.int("forwardStickMaxMs"),
            meleeFocusMinMs = chosen.int("meleeFocusMinMs"),
            meleeFocusMaxMs = chosen.int("meleeFocusMaxMs"),
            microJitterMin = chosen.int("microJitterMin"),
            microJitterMax = chosen.int("microJitterMax"),
            stopForwardCloseDistCombo = chosen.float("stopForwardCloseDistCombo"),
            resumeForwardDistCombo = chosen.float("resumeForwardDistCombo"),
            stopForwardCloseDistDefault = chosen.float("stopForwardCloseDistDefault"),
            resumeForwardDistDefault = chosen.float("resumeForwardDistDefault"),
            kbRecoveryMin = chosen.int("kbRecoveryMin"),
            kbRecoveryMax = chosen.int("kbRecoveryMax"),
            heavyKbRecoveryMin = chosen.int("heavyKbRecoveryMin"),
            heavyKbRecoveryMax = chosen.int("heavyKbRecoveryMax"),
            heavyKbDelta = chosen.float("heavyKbDelta"),
            targetDistComboMin = chosen.float("targetDistComboMin"),
            targetDistComboMax = chosen.float("targetDistComboMax"),
            targetDistNeutralMin = chosen.float("targetDistNeutralMin"),
            targetDistNeutralMax = chosen.float("targetDistNeutralMax"),
            burstFlipMin = chosen.int("burstFlipMin"),
            burstFlipMax = chosen.int("burstFlipMax"),
            burstWindowMin = chosen.int("burstWindowMin"),
            burstWindowMax = chosen.int("burstWindowMax"),
            holdWindowMin = chosen.int("holdWindowMin"),
            holdWindowMax = chosen.int("holdWindowMax"),
            longStrafeMin = chosen.int("longStrafeMin"),
            longStrafeMax = chosen.int("longStrafeMax"),
            longStrafeDistanceCap = chosen.float("longStrafeDistanceCap"),
            longStrafeBaseChance = chosen.int("longStrafeBaseChance"),
            antiStallEps = chosen.float("antiStallEps"),
            antiStallDelay = chosen.int("antiStallDelay"),
            aimSpikeDeg = chosen.float("aimSpikeDeg"),
            aimSpikeCooldown = chosen.long("aimSpikeCooldown"),
            wallNearMargin = chosen.float("wallNearMargin"),
            wallEscapeTimeMsMin = chosen.int("wallEscapeTimeMsMin"),
            wallEscapeTimeMsMax = chosen.int("wallEscapeTimeMsMax"),
            enemyIframeSoft = chosen.int("enemyIframeSoft")
        )
    }

    fun defaults(): Params = Params(
        jumpCooldownMs = 900L,
        noJumpCloseDist = 3.6f,
        warmupDistanceStop = 7.0f,
        warmupJumpEveryMin = 240,
        warmupJumpEveryMax = 380,
        warmupPressMin = 130,
        warmupPressMax = 190,
        comboLockMin = 560,
        comboLockMax = 760,
        forwardStickMinMs = 260,
        forwardStickMaxMs = 340,
        meleeFocusMinMs = 420,
        meleeFocusMaxMs = 520,
        microJitterMin = 120,
        microJitterMax = 180,
        stopForwardCloseDistCombo = 1.00f,
        resumeForwardDistCombo = 1.45f,
        stopForwardCloseDistDefault = 1.10f,
        resumeForwardDistDefault = 1.60f,
        kbRecoveryMin = 520,
        kbRecoveryMax = 760,
        heavyKbRecoveryMin = 650,
        heavyKbRecoveryMax = 900,
        heavyKbDelta = 0.45f,
        targetDistComboMin = 1.08f,
        targetDistComboMax = 1.50f,
        targetDistNeutralMin = 1.75f,
        targetDistNeutralMax = 2.35f,
        burstFlipMin = 55,
        burstFlipMax = 95,
        burstWindowMin = 240,
        burstWindowMax = 380,
        holdWindowMin = 220,
        holdWindowMax = 340,
        longStrafeMin = 900,
        longStrafeMax = 1600,
        longStrafeDistanceCap = 3.4f,
        longStrafeBaseChance = 30,
        antiStallEps = 0.015f,
        antiStallDelay = 260,
        aimSpikeDeg = 14f,
        aimSpikeCooldown = 180L,
        wallNearMargin = 0.9f,
        wallEscapeTimeMsMin = 600,
        wallEscapeTimeMsMax = 900,
        enemyIframeSoft = 3
    )

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

    private fun ensureLoaded() { if (!loaded) { state = load(); loaded = true } }
    private fun epsilon(totalPlays: Int): Double { val base = 0.35; val decay = totalPlays / 25.0; return max(0.05, base / (1.0 + decay)) }
    private fun exploreNow(epsilon: Double): Boolean = RandomUtils.randomDoubleInRange(0.0, 1.0) < epsilon
    private fun bestValue(ps: ParamState, spec: ParamSpec): Double = clamp(ps.values.values.maxByOrNull { it.avg() }?.value ?: spec.def, spec)
    private fun sample(spec: ParamSpec): Double = clamp(quantize(RandomUtils.randomDoubleInRange(spec.min, spec.max), spec.step), spec)
    private fun clamp(v: Double, spec: ParamSpec): Double {
        val c = v.coerceIn(spec.min, spec.max)
        return when (spec.type) {
            ParamType.FLOAT -> c
            ParamType.INT -> round(c).toInt().toDouble()
            ParamType.LONG -> round(c).toLong().toDouble()
        }
    }
    private fun quantize(v: Double, step: Double): Double { if (step <= 0.0) return v; val s = round(v / step); return s * step }
    private fun keyOf(v: Double): String = "%.4f".format(v)

    private fun Map<String, Double>.float(key: String): Float = clampNum(this[key], key).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNum(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNum(this[key], key).toLong()
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

    private fun file(): File = File(kira.tunerDir, "boxing_tuner.json")
}
