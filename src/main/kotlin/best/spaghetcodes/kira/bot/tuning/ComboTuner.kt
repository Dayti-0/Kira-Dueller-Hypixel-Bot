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

    private data class ParamState(
        var values: MutableList<Double> = mutableListOf(),
        var lastValue: Double = 0.0,
        var bandit: UcbBanditState? = null,
        var lastArm: Int = 0
    )

    // Schéma aligné sur ClassicV2/OP : version + params uniquement
    private data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf(),
    )

    // Ancien schéma v3 avec globalStats, conservé pour lecture souple
    private data class LegacyStoredStateV3(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf(),
        @Suppress("unused")
        var globalStats: Any? = null,
    )

    private data class LegacyValueState(var value: Double = 0.0, var plays: Int = 0, var totalReward: Double = 0.0)

    private data class LegacyParamState(
        var values: MutableMap<String, LegacyValueState> = mutableMapOf(),
        var lastValue: Double = 0.0,
        var totalPlays: Int = 0
    )

    private data class LegacyStoredState(
        var version: Int = 2,
        var params: MutableMap<String, LegacyParamState> = mutableMapOf()
    )

    private const val CURRENT_VERSION = 3
    private const val MISTAKE_PENALTY = 0.25

    private fun specF(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.FLOAT)

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.INT)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double) =
        ParamSpec(key, min, max, step, def, ParamType.LONG)

    private const val LOCKED_GAP_PERIOD_MS = 26_000.0
    private const val LOCKED_PEARL_COOLDOWN_MS = 10_000.0

    private val specs = listOf(
        specL("strengthPeriodMs", 240000.0, 320000.0, 2000.0, 294000.0),
        specL("gapPeriodMs", LOCKED_GAP_PERIOD_MS, LOCKED_GAP_PERIOD_MS, 1.0, LOCKED_GAP_PERIOD_MS),
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
        specL("pearlCooldownMs", LOCKED_PEARL_COOLDOWN_MS, LOCKED_PEARL_COOLDOWN_MS, 1.0, LOCKED_PEARL_COOLDOWN_MS),
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
            if (ps.values.isEmpty()) continue
            val bandit = resizeBandit(banditFor(ps), ps.values.size)
            val arm = ps.lastArm.coerceIn(0, ps.values.lastIndex)
            val updated = bandit.update(arm, reward)
            ps.bandit = updated.toState()
            ps.lastArm = arm
            ps.lastValue = ps.values[arm]
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
            if (p.values.isEmpty()) {
                initializeValues(p, spec)
            }

            var bandit = banditFor(p)
            val shouldExplore = shouldExploreNew(p, bandit)

            val selectedArm = if (shouldExplore) {
                val newValue = sample(spec)
                val idx = ensureValue(p, newValue)
                bandit = resizeBandit(bandit, p.values.size)
                idx
            } else {
                bandit = resizeBandit(bandit, p.values.size)
                bandit.selectArm()
            }

            p.lastArm = selectedArm
            p.lastValue = p.values[selectedArm]
            p.bandit = bandit.toState()
            chosen[spec.key] = p.lastValue
        }
        save()
        return chosen
    }

    private fun shouldExploreNew(ps: ParamState, bandit: UcbBandit): Boolean {
        if (ps.values.size < 10) return RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.3
        if (bandit.totalPlays > 0 && bandit.totalPlays % 30 == 0L) return true

        val explorationRate = when {
            bandit.totalPlays < 100 -> 0.2
            bandit.totalPlays < 300 -> 0.1
            bandit.totalPlays < 1000 -> 0.05
            else -> 0.02
        }

        return RandomUtils.randomDoubleInRange(0.0, 1.0) < explorationRate
    }

    private fun initializeValues(ps: ParamState, spec: ParamSpec) {
        val defKey = keyOf(spec.def)
        if (!ps.values.any { keyOf(it) == defKey }) {
            ps.values.add(spec.def)
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
            if (!ps.values.any { keyOf(it) == key }) {
                ps.values.add(clamped)
            }
        }
    }

    private fun defaultValues(): Map<String, Double> = specs.associate { it.key to it.def }

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            state = load()
            loaded = true
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

    private fun banditFor(ps: ParamState): UcbBandit {
        val size = ps.values.size
        require(size > 0) { "bandit requested without available values" }
        val plays = ps.bandit?.plays ?: LongArray(size)
        val rewards = ps.bandit?.rewards ?: DoubleArray(size)
        val total = ps.bandit?.totalPlays ?: 0L
        return UcbBandit(size, total, plays.copyOf(size), rewards.copyOf(size))
    }

    private fun resizeBandit(bandit: UcbBandit, size: Int): UcbBandit {
        if (bandit.armCount == size) return bandit
        val state = bandit.toState()
        val plays = state.plays.copyOf(size)
        val rewards = state.rewards.copyOf(size)
        return UcbBandit(size, state.totalPlays, plays, rewards)
    }

    private fun ensureValue(ps: ParamState, value: Double): Int {
        val key = keyOf(value)
        ps.values.forEachIndexed { idx, existing ->
            if (keyOf(existing) == key) return idx
        }
        ps.values.add(value)
        return ps.values.lastIndex
    }

    private fun migrateLegacy(legacy: LegacyStoredState): StoredState {
        val migrated = StoredState(version = CURRENT_VERSION)
        for ((key, legacyParam) in legacy.params) {
            val ps = ParamState()
            val orderedValues = legacyParam.values.toSortedMap().values.toList()
            orderedValues.forEach { ps.values.add(it.value) }

            if (ps.values.isEmpty()) {
                specByKey[key]?.let { initializeValues(ps, it) }
            }

            if (ps.values.isNotEmpty()) {
                val plays = LongArray(ps.values.size)
                val rewards = DoubleArray(ps.values.size)
                orderedValues.forEachIndexed { idx, vs ->
                    plays[idx] = vs.plays.toLong()
                    rewards[idx] = vs.totalReward
                }
                val total = legacyParam.totalPlays.toLong().coerceAtLeast(plays.sum())
                ps.bandit = UcbBandit(ps.values.size, total, plays, rewards).toState()
                val lastArm = ps.values.indexOfFirst { keyOf(it) == keyOf(legacyParam.lastValue) }
                ps.lastArm = if (lastArm >= 0) lastArm else 0
                ps.lastValue = ps.values.getOrElse(ps.lastArm) { ps.values.first() }
            }

            migrated.params[key] = ps
        }
        return normalize(migrated)
    }

    private fun normalize(state: StoredState): StoredState {
        for ((_, ps) in state.params) {
            if (ps.values.isEmpty()) continue
            val bandit = resizeBandit(banditFor(ps), ps.values.size)
            ps.bandit = bandit.toState()
            ps.lastArm = ps.lastArm.coerceIn(0, ps.values.lastIndex)
            if (ps.lastValue !in ps.values) {
                ps.lastValue = ps.values[ps.lastArm]
            }
        }
        state.version = CURRENT_VERSION
        return state
    }

    private fun load(): StoredState {
        val f = file()
        if (!f.exists()) return StoredState()

        return try {
            f.reader().use { reader ->
                val type = object : TypeToken<StoredState>() {}.type
                val loadedState = kira.gson.fromJson<StoredState>(reader, type)
                if (loadedState != null && loadedState.version == CURRENT_VERSION) {
                    normalize(loadedState)
                } else {
                    tryLegacy(f) ?: StoredState()
                }
            }
        } catch (_: Exception) {
            tryLegacy(f) ?: StoredState()
        }
    }

    private fun tryLegacy(f: File): StoredState? {
        return try {
            f.reader().use { reader ->
                val legacyV3Type = object : TypeToken<LegacyStoredStateV3>() {}.type
                val legacyV2Type = object : TypeToken<LegacyStoredState>() {}.type
                kira.gson.fromJson<LegacyStoredStateV3>(reader, legacyV3Type)?.let { normalize(StoredState(it.version, it.params)) }
                    ?: kira.gson.fromJson<LegacyStoredState>(reader, legacyV2Type)?.let { migrateLegacy(it) }
            }
        } catch (_: Exception) {
            null
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
