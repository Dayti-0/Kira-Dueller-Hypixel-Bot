package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
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

    private data class ParamState(
        var values: MutableList<Double> = mutableListOf(),
        var lastValue: Double = 0.0,
        var bandit: UcbBanditState? = null,
        var lastArm: Int = 0
    )

    private data class GlobalStats(
        var wins: Int = 0,
        var losses: Int = 0,
        var draws: Int = 0,
        var totalGames: Int = 0,
        var winRate: Double = 0.0,
    ) {
        fun record(win: Boolean, draw: Boolean = false) {
            when {
                draw -> draws++
                win -> wins++
                else -> losses++
            }
            normalize()
        }

        fun normalize(): GlobalStats {
            totalGames = wins + losses + draws
            winRate = if (totalGames > 0) wins.toDouble() / totalGames else 0.0
            return this
        }
    }

    private data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf(),
        var globalStats: GlobalStats = GlobalStats(),
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
        state.globalStats.record(win)
        changed = true

        for ((_, ps) in state.params) {
            if (ps.values.isEmpty()) continue

            val bandit = resizeBandit(banditFor(ps), ps.values.size)
            val arm = ps.lastArm.coerceIn(0, ps.values.lastIndex)
            ps.bandit = bandit.update(arm, reward).toState()
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

    private fun defaultValues(): Map<String, Double> = specs.associate { it.key to it.def }

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            state = load()
            loaded = true
        }
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
        state.globalStats.normalize()
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
                val type = object : TypeToken<LegacyStoredState>() {}.type
                kira.gson.fromJson<LegacyStoredState>(reader, type)?.let { migrateLegacy(it) }
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

    private fun file(): File = File(configDir(), "classic_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
