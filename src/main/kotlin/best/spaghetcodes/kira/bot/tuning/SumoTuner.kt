package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.round

object SumoTuner {

    data class Params(
        // Zone / verrou
        val blockZoneLockMs: Long,
        val rearmInnerDist: Float,
        val zoneRearmDelayMs: Long,

        // Combat windows (existants)
        val attackStartDist: Float,
        val attackLatchMs: Long,
        val prefireApproachDist: Float,
        val prefireLatchMs: Long,

        // Pré-fire “long” (nouveau)
        val hardAttackDist: Float,
        val preAimDot: Float,

        // W-tap + post-hit
        val wTapShortMs: Long,
        val wTapLongMs: Long,
        val postHitDriveMs: Long,

        // Avance / stop
        val stopForwardDist: Float,
        val reForwardDist: Float,

        // Anti-void
        val edgeProbeNear: Float,
        val edgeProbeFar: Float,
        val predictiveProbeBonus: Float,

        // Strafe timings
        val holdMsMin: Int, val holdMsMax: Int,
        val burstMsMin: Int, val burstMsMax: Int,
        val coastMsMin: Int, val coastMsMax: Int,
        val burstFlipMin: Int, val burstFlipMax: Int,
        val burstSkipPercent: Int,
        val longStrafeChance: Int,
        val longStrafeDurationMs: Long,

        // Strafe style/intensité (nouveau)
        val strafeStyleInt: Int,
        val strafeIntensity: Int,

        // Close-strafe
        val closeInnerMin: Long, val closeInnerMax: Long,
        val closeMidMin: Long,  val closeMidMax: Long,
        val closeFarMin: Long,  val closeFarMax: Long,

        // Anti-stall / centre / edge
        val antiStallEps: Float,
        val antiStallDelayMs: Long,
        val centerBiasStrength: Int,
        val centerBiasIntervalMs: Long,
        val edgeAggroWeight: Int,
        val edgeAggroRadiusBonus: Float,

        // Start-Hop
        val startHopModeInt: Int,
        val startHopTimerFudgeMs: Long,
        val startHopTimerTargetMs: Long,
        val groundTicksRequired: Int,
        val groundMaxWaitMs: Long,
        val startAntivoidDisableMs: Long,

        // Hit-Select assist (nouveau)
        val hsEnableInt: Int,          // 0/1
        val hsMinMs: Long,
        val hsMaxMs: Long,
        val hsLatchMs: Long,
        val hsMinDist: Float,
        val hsMaxDist: Float,
        val hsAimDot: Float,
        val hsOnlyIfOppOutsideInt: Int // 0/1
    )

    private enum class ParamType { FLOAT, INT, LONG }
    private data class ParamSpec(val key: String, val min: Double, val max: Double, val step: Double, val def: Double, val type: ParamType)
    private data class ValueState(var value: Double = 0.0, var plays: Int = 0, var totalReward: Double = 0.0) { fun avg() = if (plays > 0) totalReward / plays else Double.NEGATIVE_INFINITY }
    private data class ParamState(var values: MutableMap<String, ValueState> = mutableMapOf(), var lastValue: Double = 0.0, var totalPlays: Int = 0)
    private data class StoredState(var version: Int = CURRENT_VERSION, var params: MutableMap<String, ParamState> = mutableMapOf())

    private const val CURRENT_VERSION = 1
    private const val MISTAKE_PENALTY = 0.1

    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.FLOAT)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.INT)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.LONG)

    private val specs = listOf(
        // ----- Zone / verrou -----
        specL("blockZoneLockMs", 400.0, 900.0, 10.0, 600.0),
        specF("rearmInnerDist", 5.6, 6.8, 0.05, 6.2),
        specL("zoneRearmDelayMs", 1000.0, 2400.0, 20.0, 1400.0),

        // ----- Combat -----
        specF("attackStartDist", 3.8, 4.6, 0.02, 4.05),
        specL("attackLatchMs", 180.0, 260.0, 5.0, 220.0),
        specF("prefireApproachDist", 4.2, 5.0, 0.02, 4.6),
        specL("prefireLatchMs", 140.0, 220.0, 5.0, 160.0),

        // ----- Pré-fire “long” -----
        specF("hardAttackDist", 4.6, 5.1, 0.02, 4.9),
        specF("preAimDot", 0.92, 0.995, 0.005, 0.96),

        // ----- W-tap & post-hit -----
        specL("wTapShortMs", 40.0, 80.0, 5.0, 50.0),
        specL("wTapLongMs", 90.0, 130.0, 5.0, 100.0),
        specL("postHitDriveMs", 100.0, 220.0, 10.0, 140.0),

        // ----- Avance / stop -----
        specF("stopForwardDist", 1.0, 1.4, 0.01, 1.18),
        specF("reForwardDist", 1.6, 2.6, 0.02, 2.0),

        // ----- Anti-void + prédictif -----
        specF("edgeProbeNear", 1.2, 1.9, 0.02, 1.6),
        specF("edgeProbeFar", 2.0, 3.2, 0.02, 2.6),
        specF("predictiveProbeBonus", 0.0, 1.0, 0.05, 0.6),

        // ----- Strafe timings -----
        specI("holdMsMin", 750.0, 1100.0, 10.0, 900.0),
        specI("holdMsMax", 1200.0, 1700.0, 10.0, 1500.0),
        specI("burstMsMin", 220.0, 360.0, 10.0, 300.0),
        specI("burstMsMax", 400.0, 650.0, 10.0, 520.0),
        specI("coastMsMin", 350.0, 600.0, 10.0, 500.0),
        specI("coastMsMax", 700.0, 1100.0, 10.0, 900.0),
        specI("burstFlipMin", 90.0, 140.0, 5.0, 120.0),
        specI("burstFlipMax", 160.0, 260.0, 5.0, 200.0),
        specI("burstSkipPercent", 10.0, 60.0, 1.0, 30.0),
        specI("longStrafeChance", 0.0, 35.0, 1.0, 0.0),
        specL("longStrafeDurationMs", 0.0, 800.0, 20.0, 0.0),

        // ----- Strafe style/intensité -----
        specI("strafeStyleInt", 0.0, 2.0, 1.0, 0.0),  // 0=minimal,1=burst,2=hybride
        specI("strafeIntensity", 0.0, 100.0, 5.0, 25.0),

        // ----- Close-strafe -----
        specL("closeInnerMin", 280.0, 420.0, 10.0, 360.0),
        specL("closeInnerMax", 420.0, 620.0, 10.0, 520.0),
        specL("closeMidMin", 360.0, 520.0, 10.0, 420.0),
        specL("closeMidMax", 520.0, 720.0, 10.0, 600.0),
        specL("closeFarMin", 460.0, 640.0, 10.0, 520.0),
        specL("closeFarMax", 640.0, 860.0, 10.0, 700.0),

        // ----- Anti-stall / centre / edge -----
        specF("antiStallEps", 0.005, 0.03, 0.001, 0.01),
        specL("antiStallDelayMs", 260.0, 520.0, 10.0, 380.0),
        specI("centerBiasStrength", 60.0, 100.0, 1.0, 100.0),
        specL("centerBiasIntervalMs", 200.0, 420.0, 10.0, 300.0),
        specI("edgeAggroWeight", 0.0, 40.0, 1.0, 20.0),
        specF("edgeAggroRadiusBonus", 0.2, 1.2, 0.1, 0.8),

        // ----- Start-Hop -----
        specI("startHopModeInt", 0.0, 2.0, 1.0, 2.0),
        specL("startHopTimerFudgeMs", 20.0, 60.0, 5.0, 40.0),
        specL("startHopTimerTargetMs", 280.0, 330.0, 5.0, 300.0),
        specI("groundTicksRequired", 1.0, 3.0, 1.0, 2.0),
        specL("groundMaxWaitMs", 260.0, 340.0, 10.0, 300.0),
        specL("startAntivoidDisableMs", 400.0, 900.0, 50.0, 600.0),

        // ----- Hit-Select assist -----
        specI("hsEnableInt", 0.0, 1.0, 1.0, 1.0),
        specL("hsMinMs", 60.0, 120.0, 5.0, 80.0),
        specL("hsMaxMs", 150.0, 220.0, 5.0, 180.0),
        specL("hsLatchMs", 120.0, 220.0, 5.0, 170.0),
        specF("hsMinDist", 2.8, 3.4, 0.05, 3.0),
        specF("hsMaxDist", 4.6, 5.0, 0.02, 4.9),
        specF("hsAimDot", 0.92, 0.995, 0.005, 0.955),
        specI("hsOnlyIfOppOutsideInt", 0.0, 1.0, 1.0, 1.0)
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
            blockZoneLockMs = chosen.long("blockZoneLockMs"),
            rearmInnerDist = chosen.float("rearmInnerDist"),
            zoneRearmDelayMs = chosen.long("zoneRearmDelayMs"),

            attackStartDist = chosen.float("attackStartDist"),
            attackLatchMs = chosen.long("attackLatchMs"),
            prefireApproachDist = chosen.float("prefireApproachDist"),
            prefireLatchMs = chosen.long("prefireLatchMs"),

            hardAttackDist = chosen.float("hardAttackDist"),
            preAimDot = chosen.float("preAimDot"),

            wTapShortMs = chosen.long("wTapShortMs"),
            wTapLongMs = chosen.long("wTapLongMs"),
            postHitDriveMs = chosen.long("postHitDriveMs"),

            stopForwardDist = chosen.float("stopForwardDist"),
            reForwardDist = chosen.float("reForwardDist"),

            edgeProbeNear = chosen.float("edgeProbeNear"),
            edgeProbeFar = chosen.float("edgeProbeFar"),
            predictiveProbeBonus = chosen.float("predictiveProbeBonus"),

            holdMsMin = chosen.int("holdMsMin"),
            holdMsMax = chosen.int("holdMsMax"),
            burstMsMin = chosen.int("burstMsMin"),
            burstMsMax = chosen.int("burstMsMax"),
            coastMsMin = chosen.int("coastMsMin"),
            coastMsMax = chosen.int("coastMsMax"),
            burstFlipMin = chosen.int("burstFlipMin"),
            burstFlipMax = chosen.int("burstFlipMax"),
            burstSkipPercent = chosen.int("burstSkipPercent"),
            longStrafeChance = chosen.int("longStrafeChance"),
            longStrafeDurationMs = chosen.long("longStrafeDurationMs"),

            strafeStyleInt = chosen.int("strafeStyleInt"),
            strafeIntensity = chosen.int("strafeIntensity"),

            closeInnerMin = chosen.long("closeInnerMin"),
            closeInnerMax = chosen.long("closeInnerMax"),
            closeMidMin = chosen.long("closeMidMin"),
            closeMidMax = chosen.long("closeMidMax"),
            closeFarMin = chosen.long("closeFarMin"),
            closeFarMax = chosen.long("closeFarMax"),

            antiStallEps = chosen.float("antiStallEps"),
            antiStallDelayMs = chosen.long("antiStallDelayMs"),
            centerBiasStrength = chosen.int("centerBiasStrength"),
            centerBiasIntervalMs = chosen.long("centerBiasIntervalMs"),
            edgeAggroWeight = chosen.int("edgeAggroWeight"),
            edgeAggroRadiusBonus = chosen.float("edgeAggroRadiusBonus"),

            startHopModeInt = chosen.int("startHopModeInt"),
            startHopTimerFudgeMs = chosen.long("startHopTimerFudgeMs"),
            startHopTimerTargetMs = chosen.long("startHopTimerTargetMs"),
            groundTicksRequired = chosen.int("groundTicksRequired"),
            groundMaxWaitMs = chosen.long("groundMaxWaitMs"),
            startAntivoidDisableMs = chosen.long("startAntivoidDisableMs"),

            hsEnableInt = chosen.int("hsEnableInt"),
            hsMinMs = chosen.long("hsMinMs"),
            hsMaxMs = chosen.long("hsMaxMs"),
            hsLatchMs = chosen.long("hsLatchMs"),
            hsMinDist = chosen.float("hsMinDist"),
            hsMaxDist = chosen.float("hsMaxDist"),
            hsAimDot = chosen.float("hsAimDot"),
            hsOnlyIfOppOutsideInt = chosen.int("hsOnlyIfOppOutsideInt")
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

    // ===== internals =====
    private fun ensureLoaded() { if (!loaded) { state = load(); loaded = true } }
    private fun epsilon(totalPlays: Int): Double { val base = 0.35; val decay = totalPlays / 25.0; return max(0.05, base / (1.0 + decay)) }
    private fun exploreNow(epsilon: Double): Boolean = RandomUtils.randomDoubleInRange(0.0, 1.0) < epsilon
    private fun bestValue(ps: ParamState, spec: ParamSpec): Double = clamp(ps.values.values.maxByOrNull { it.avg() }?.value ?: spec.def, spec)
    private fun sample(spec: ParamSpec): Double = clamp(quantize(RandomUtils.randomDoubleInRange(spec.min, spec.max), spec.step), spec)
    private fun clamp(v: Double, spec: ParamSpec): Double {
        val c = v.coerceIn(spec.min, spec.max)
        return when (spec.type) { ParamType.FLOAT -> c; ParamType.INT -> round(c).toInt().toDouble(); ParamType.LONG -> round(c).toLong().toDouble() }
    }
    private fun quantize(v: Double, step: Double): Double { if (step <= 0.0) return v; val s = round(v / step); return s * step }
    private fun keyOf(v: Double): String = "%.4f".format(v)

    private fun Map<String, Double>.float(key: String): Float = (this[key] ?: specByKey[key]?.def ?: 0.0).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNum(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNum(this[key], key).toLong()
    private fun clampNum(v: Double?, key: String): Double { val spec = specByKey[key]; val raw = v ?: spec?.def ?: 0.0; return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw }

    private fun load(): StoredState {
        return try {
            val file = file()
            if (!file.exists()) { file.parentFile?.mkdirs(); StoredState() }
            else file.reader().use { reader ->
                val type = object : TypeToken<StoredState>() {}.type
                kira.gson.fromJson<StoredState>(reader, type) ?: StoredState()
            }
        } catch (ex: Exception) { ex.printStackTrace(); StoredState() }
    }
    private fun save() { try { val f = file(); f.parentFile?.mkdirs(); f.writer().use { writer -> kira.gson.toJson(state, writer) } } catch (_: Exception) {} }
    private fun file(): File = File(kira.tunerDir, "sumo_tuner.json")
}
