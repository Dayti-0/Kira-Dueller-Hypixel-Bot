package best.spaghetcodes.kira.bot.tuning

// AUTOTUNE BEGIN

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

object SumoTuner {

    data class Params(
        val blockZoneLockMs: Long,
        val rearmInnerDist: Float,
        val zoneRearmDelayMs: Long,
        val attackStartDist: Float,
        val attackLatchMs: Long,
        val prefireApproachDist: Float,
        val prefireLatchMs: Long,
        val stopForwardDist: Float,
        val reForwardDist: Float,
        val edgeProbeNear: Float,
        val edgeProbeFar: Float,
        val holdMsMin: Int,
        val holdMsMax: Int,
        val burstMsMin: Int,
        val burstMsMax: Int,
        val coastMsMin: Int,
        val coastMsMax: Int,
        val burstFlipMin: Int,
        val burstFlipMax: Int,
        val burstSkipPercent: Int,
        val longStrafeChance: Int,
        val longStrafeDurationMs: Long,
        val closeInnerMin: Long,
        val closeInnerMax: Long,
        val closeMidMin: Long,
        val closeMidMax: Long,
        val closeFarMin: Long,
        val closeFarMax: Long,
        val antiStallEps: Float,
        val antiStallDelayMs: Long,
        val centerBiasStrength: Int,
        val centerBiasIntervalMs: Long,

        // === Nouveaux paramètres Start-Hop ===
        val startHopModeInt: Int,          // 0=TIMER, 1=GROUND, 2=HYBRID
        val startHopTimerFudgeMs: Long,    // compensation autour de 300ms
        val groundTicksRequired: Int,      // nb de ticks onGround à enchaîner
        val groundMaxWaitMs: Long,         // filet de sécurité si onGround tarde
        val startAntivoidDisableMs: Long   // anti-void désactivé X ms au tout début
    )

    private enum class ParamType { FLOAT, INT, LONG }

    private data class ParamSpec(
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val default: Double,
        val type: ParamType
    )

    private data class ValueState(
        var value: Double = 0.0,
        var plays: Int = 0,
        var totalReward: Double = 0.0
    ) {
        fun averageReward(): Double = if (plays > 0) totalReward / plays else Double.NEGATIVE_INFINITY
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

    private val specs = listOf(
        // ----- Réglages existants -----
        ParamSpec("blockZoneLockMs", 400.0, 900.0, 10.0, 600.0, ParamType.LONG),
        ParamSpec("rearmInnerDist", 5.6, 6.8, 0.05, 6.2, ParamType.FLOAT),
        ParamSpec("zoneRearmDelayMs", 1000.0, 2400.0, 20.0, 1400.0, ParamType.LONG),
        ParamSpec("attackStartDist", 3.8, 4.4, 0.02, 4.05, ParamType.FLOAT),
        ParamSpec("attackLatchMs", 180.0, 260.0, 5.0, 220.0, ParamType.LONG),
        ParamSpec("prefireApproachDist", 4.3, 5.1, 0.02, 4.6, ParamType.FLOAT),
        ParamSpec("prefireLatchMs", 140.0, 220.0, 5.0, 160.0, ParamType.LONG),
        ParamSpec("stopForwardDist", 1.0, 1.4, 0.01, 1.18, ParamType.FLOAT),
        ParamSpec("reForwardDist", 1.6, 2.6, 0.02, 2.0, ParamType.FLOAT),
        ParamSpec("edgeProbeNear", 1.2, 1.9, 0.02, 1.6, ParamType.FLOAT),
        ParamSpec("edgeProbeFar", 2.0, 3.2, 0.02, 2.6, ParamType.FLOAT),
        ParamSpec("holdMsMin", 750.0, 1100.0, 10.0, 900.0, ParamType.INT),
        ParamSpec("holdMsMax", 1200.0, 1700.0, 10.0, 1500.0, ParamType.INT),
        ParamSpec("burstMsMin", 220.0, 360.0, 10.0, 300.0, ParamType.INT),
        ParamSpec("burstMsMax", 400.0, 650.0, 10.0, 520.0, ParamType.INT),
        ParamSpec("coastMsMin", 350.0, 600.0, 10.0, 500.0, ParamType.INT),
        ParamSpec("coastMsMax", 700.0, 1100.0, 10.0, 900.0, ParamType.INT),
        ParamSpec("burstFlipMin", 90.0, 140.0, 5.0, 120.0, ParamType.INT),
        ParamSpec("burstFlipMax", 160.0, 260.0, 5.0, 200.0, ParamType.INT),
        ParamSpec("burstSkipPercent", 10.0, 60.0, 1.0, 30.0, ParamType.INT),
        ParamSpec("longStrafeChance", 0.0, 35.0, 1.0, 0.0, ParamType.INT),
        ParamSpec("longStrafeDurationMs", 0.0, 800.0, 20.0, 0.0, ParamType.LONG),
        ParamSpec("closeInnerMin", 280.0, 420.0, 10.0, 360.0, ParamType.LONG),
        ParamSpec("closeInnerMax", 420.0, 620.0, 10.0, 520.0, ParamType.LONG),
        ParamSpec("closeMidMin", 360.0, 520.0, 10.0, 420.0, ParamType.LONG),
        ParamSpec("closeMidMax", 520.0, 720.0, 10.0, 600.0, ParamType.LONG),
        ParamSpec("closeFarMin", 460.0, 640.0, 10.0, 520.0, ParamType.LONG),
        ParamSpec("closeFarMax", 640.0, 860.0, 10.0, 700.0, ParamType.LONG),
        ParamSpec("antiStallEps", 0.005, 0.03, 0.001, 0.01, ParamType.FLOAT),
        ParamSpec("antiStallDelayMs", 260.0, 520.0, 10.0, 380.0, ParamType.LONG),
        ParamSpec("centerBiasStrength", 60.0, 100.0, 1.0, 100.0, ParamType.INT),
        ParamSpec("centerBiasIntervalMs", 200.0, 420.0, 10.0, 300.0, ParamType.LONG),

        // ----- Nouveaux pour le Start-Hop -----
        ParamSpec("startHopModeInt", 0.0, 2.0, 1.0, 2.0, ParamType.INT),            // 2 = HYBRID par défaut
        ParamSpec("startHopTimerFudgeMs", 20.0, 60.0, 5.0, 40.0, ParamType.LONG),
        ParamSpec("groundTicksRequired", 1.0, 3.0, 1.0, 2.0, ParamType.INT),
        ParamSpec("groundMaxWaitMs", 260.0, 340.0, 10.0, 290.0, ParamType.LONG),
        ParamSpec("startAntivoidDisableMs", 400.0, 900.0, 50.0, 600.0, ParamType.LONG)
    )

    private val specByKey = specs.associateBy { it.key }

    private var loaded = false
    private var state = StoredState()

    fun pickParams(): Params {
        ensureLoaded()

        val chosen = mutableMapOf<String, Double>()
        for (spec in specs) {
            val paramState = state.params.getOrPut(spec.key) { ParamState() }
            val epsilon = computeEpsilon(paramState.totalPlays)
            val explore = shouldExplore(epsilon) || paramState.values.isEmpty()
            val value = if (explore) {
                sample(spec)
            } else {
                selectBestValue(paramState, spec)
            }
            val key = valueKey(value)
            if (!paramState.values.containsKey(key)) {
                paramState.values[key] = ValueState(value = value)
            }
            paramState.lastValue = value
            chosen[spec.key] = value
        }

        saveState()

        return Params(
            blockZoneLockMs = chosen.long("blockZoneLockMs"),
            rearmInnerDist = chosen.float("rearmInnerDist"),
            zoneRearmDelayMs = chosen.long("zoneRearmDelayMs"),
            attackStartDist = chosen.float("attackStartDist"),
            attackLatchMs = chosen.long("attackLatchMs"),
            prefireApproachDist = chosen.float("prefireApproachDist"),
            prefireLatchMs = chosen.long("prefireLatchMs"),
            stopForwardDist = chosen.float("stopForwardDist"),
            reForwardDist = chosen.float("reForwardDist"),
            edgeProbeNear = chosen.float("edgeProbeNear"),
            edgeProbeFar = chosen.float("edgeProbeFar"),
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

            // Nouveaux (Start-Hop)
            startHopModeInt = chosen.int("startHopModeInt"),
            startHopTimerFudgeMs = chosen.long("startHopTimerFudgeMs"),
            groundTicksRequired = chosen.int("groundTicksRequired"),
            groundMaxWaitMs = chosen.long("groundMaxWaitMs"),
            startAntivoidDisableMs = chosen.long("startAntivoidDisableMs")
        )
    }

    fun report(win: Boolean, mistakes: Int) {
        ensureLoaded()
        val rewardRaw = if (win) 1.0 else 0.0
        val penalty = mistakes * MISTAKE_PENALTY
        val reward = rewardRaw - penalty

        var changed = false
        for ((key, paramState) in state.params) {
            val spec = specByKey[key] ?: continue
            val chosenValue = paramState.lastValue
            val entryKey = valueKey(chosenValue)
            val valueState = paramState.values.getOrPut(entryKey) { ValueState(value = chosenValue) }
            valueState.plays += 1
            valueState.totalReward += reward
            paramState.totalPlays += 1

            val bestState = paramState.values.values.maxByOrNull { it.averageReward() }
            if (bestState != null) {
                val bestSpec = clampToSpec(bestState.value, spec)
                if (abs(paramState.lastValue - bestSpec) > 1e-6) {
                    paramState.lastValue = bestSpec
                }
            }
            changed = true
        }

        if (changed) {
            saveState()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        state = loadState()
        loaded = true
    }

    private fun computeEpsilon(totalPlays: Int): Double {
        val base = 0.35
        val decay = totalPlays / 25.0
        return max(0.05, base / (1.0 + decay))
    }

    private fun shouldExplore(epsilon: Double): Boolean {
        val roll = RandomUtils.randomDoubleInRange(0.0, 1.0)
        return roll < epsilon
    }

    private fun selectBestValue(paramState: ParamState, spec: ParamSpec): Double {
        val best = paramState.values.values.maxByOrNull { it.averageReward() }
        return clampToSpec(best?.value ?: spec.default, spec)
    }

    private fun sample(spec: ParamSpec): Double {
        val raw = RandomUtils.randomDoubleInRange(spec.min, spec.max)
        val quantized = quantize(raw, spec.step)
        return clampToSpec(quantized, spec)
    }

    private fun clampToSpec(value: Double, spec: ParamSpec): Double {
        val clamped = value.coerceIn(spec.min, spec.max)
        return when (spec.type) {
            ParamType.FLOAT -> clamped
            ParamType.INT -> round(clamped).toInt().toDouble()
            ParamType.LONG -> round(clamped).toLong().toDouble()
        }
    }

    private fun quantize(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val scaled = round(value / step)
        return scaled * step
    }

    private fun valueKey(value: Double): String = "%.4f".format(value)

    private fun Map<String, Double>.float(key: String): Float = (this[key] ?: specByKey[key]?.default ?: 0.0).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNumeric(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNumeric(this[key], key).toLong()

    private fun clampNumeric(value: Double?, key: String): Double {
        val spec = specByKey[key]
        val raw = value ?: spec?.default ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    private fun loadState(): StoredState {
        return try {
            val file = tunerFile()
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

    private fun saveState() {
        try {
            val file = tunerFile()
            file.parentFile?.mkdirs()
            file.writer().use { writer ->
                kira.gson.toJson(state, writer)
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun tunerFile(): File {
        val base = File(kira.mc.mcDataDir, "config")
        return File(base, "sumo_tuner.json")
    }
}

// AUTOTUNE END
