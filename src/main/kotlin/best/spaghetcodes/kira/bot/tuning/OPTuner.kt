package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.bot.tuning.MistakeSummary.Companion.ZERO
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * OPTuner - Version Optimisée avec UCB
 * Optimisation continue avec exploration intelligente
 * Basée sur l'analyse de 1001 parties avec 99.5% de win rate
 */
object OPTuner {

    data class OPParams(
        val minGapIntervalMs: Long = 4500L,  // Valeur fixe non tunable
        val longStrafeChance: Int,
        val rodCdCloseMsBase: Long,
        val rodCdFarMsBase: Long,
        val rodCdBiasMax: Double,
        val rodBanMeleeDist: Double,
        val rodCloseMin: Double,
        val rodCloseMax: Double,
        val rodMainMin: Double,
        val rodMainMax: Double,
        val rodInterceptMin: Double,
        val rodInterceptMax: Double,
        val rodMaxRangeHard: Double,
        val rodMidInstantMin: Double,
        val rodMidInstantMax: Double,
        val farThreshold: Double,
        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,
        val feetSplashSafeDistance: Double,
        val feetSplashRetreatMaxMs: Long,
        val secondRegenGapDelayMs: Long
    )

    private enum class ParamType { INT, LONG, DOUBLE }

    private data class ParamSpec(
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val def: Double,
        val type: ParamType,
        val optimal: Double? = null  // Valeur optimale découverte lors des tests
    )

    private data class ParamState(
        var values: MutableList<Double> = mutableListOf(),
        var lastValue: Double = 0.0,
        var bandit: UcbBanditState? = null,
        var lastArm: Int = 0
    )

    private data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf(),
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

    private data class LegacyValueStateV3(
        var value: Double = 0.0,
        var plays: Int = 0,
        var totalReward: Double = 0.0,
    )

    private data class LegacyParamStateV3(
        var values: MutableList<LegacyValueStateV3> = mutableListOf(),
        var lastValue: Double = 0.0,
        var lastArm: Int = 0,
    )

    private data class LegacyStoredStateV3(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, LegacyParamStateV3> = mutableMapOf(),
    )

    // Schéma aligné sur ClassicV2 (version 3)
    private const val CURRENT_VERSION = 3

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.INT, optimal)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.LONG, optimal)

    private fun specD(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.DOUBLE, optimal)

    // Paramètres avec valeurs optimales découvertes lors de vos tests
    private val specs = listOf(
        // Strafe
        specI("longStrafeChance", 10.0, 40.0, 1.0, 25.0, 10.0),  // 10% optimal (100% WR)
        
        // Rod cooldowns
        specL("rodCdCloseMsBase", 300.0, 420.0, 10.0, 340.0, 420.0),  // 420ms optimal
        specL("rodCdFarMsBase", 440.0, 600.0, 10.0, 480.0, 580.0),    // 580ms optimal
        specD("rodCdBiasMax", 1.05, 1.35, 0.01, 1.25, 1.17),         // 1.17 optimal
        
        // Rod distances
        specD("rodBanMeleeDist", 3.5, 4.5, 0.05, 4.0, 3.65),         // 3.65 optimal
        specD("rodCloseMin", 1.6, 2.6, 0.05, 2.0, 2.25),             // 2.25 optimal
        specD("rodCloseMax", 2.6, 4.0, 0.05, 3.4, 3.10),             // 3.10 optimal
        specD("rodMainMin", 2.5, 4.0, 0.05, 3.0, 3.95),              // 3.95 optimal
        specD("rodMainMax", 5.0, 7.2, 0.05, 6.8, 5.75),              // 5.75 optimal
        specD("rodInterceptMin", 4.5, 6.5, 0.05, 5.8, 6.10),         // 6.10 optimal
        specD("rodInterceptMax", 6.0, 7.6, 0.05, 7.2, 7.60),         // 7.60 optimal
        specD("rodMaxRangeHard", 6.2, 7.8, 0.05, 7.2, 7.70),         // 7.70 optimal
        specD("rodMidInstantMin", 4.8, 6.4, 0.05, 5.5, 6.15),        // 6.15 optimal
        specD("rodMidInstantMax", 6.0, 7.6, 0.05, 7.0, 6.00),        // 6.00 optimal
        
        // Seuils de distance
        specD("farThreshold", 9.0, 13.0, 0.1, 11.0, 11.20),          // 11.20 optimal
        
        // Détection d'immobilité
        specD("stillFrameThreshold", 0.008, 0.02, 0.0005, 0.0125, 0.01),  // 0.01 optimal
        specI("stillFramesNeeded", 6.0, 14.0, 1.0, 10.0, 11.0),           // 11 optimal
        
        // Bow slow detection
        specD("bowSlowThreshold", 0.04, 0.12, 0.0025, 0.06, 0.08),        // 0.08 optimal
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 3.0, 3.0),           // 3 optimal
        
        // Feet splash
        specD("feetSplashSafeDistance", 4.5, 6.5, 0.05, 5.6, 6.00),      // 6.00 optimal
        specL("feetSplashRetreatMaxMs", 500.0, 1100.0, 20.0, 700.0, 920.0),  // 920ms optimal
        
        // Second regen timing
        specL("secondRegenGapDelayMs", 20000.0, 45000.0, 500.0, 30000.0, 20000.0)  // 20s optimal
    )

    private val specByKey = specs.associateBy { it.key }
    private var loaded = false
    private var state = StoredState()

    // ----------- Hooks -----------
    private var mistakesJump: Int = 0
    private var mistakesRod: Int = 0
    private var mistakesBow: Int = 0

    fun noteJumpMistake() {
        mistakesJump += 1
    }

    fun noteRodMistake() {
        mistakesRod += 1
    }

    fun noteBowMistake() {
        mistakesBow += 1
    }

    fun takeAndResetMistakes(rodHits: Int, rodMisses: Int, bowShots: Int): MistakeSummary {
        val summary = MistakeSummary(mistakesJump, mistakesRod, mistakesBow, rodHits, rodMisses, bowShots)
        mistakesJump = 0
        mistakesRod = 0
        mistakesBow = 0
        return summary
    }

    fun pickParams(): OPParams {
        ensureLoaded()

        // Optimisation continue avec exploration intelligente (UCB)
        val values = pickValues()

        return build(values)
    }

    fun defaults(): OPParams = build(defaultValues())

    fun report(win: Boolean, mistakes: MistakeSummary = ZERO) {
        ensureLoaded()

        val reward = computeReward(win, mistakes)

        // Mise à jour des paramètres
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

    private fun build(values: Map<String, Double>) = OPParams(
        minGapIntervalMs = 4500L,  // Valeur fixe
        longStrafeChance = values.int("longStrafeChance"),
        rodCdCloseMsBase = values.long("rodCdCloseMsBase"),
        rodCdFarMsBase = values.long("rodCdFarMsBase"),
        rodCdBiasMax = values.double("rodCdBiasMax"),
        rodBanMeleeDist = values.double("rodBanMeleeDist"),
        rodCloseMin = values.double("rodCloseMin"),
        rodCloseMax = values.double("rodCloseMax"),
        rodMainMin = values.double("rodMainMin"),
        rodMainMax = values.double("rodMainMax"),
        rodInterceptMin = values.double("rodInterceptMin"),
        rodInterceptMax = values.double("rodInterceptMax"),
        rodMaxRangeHard = values.double("rodMaxRangeHard"),
        rodMidInstantMin = values.double("rodMidInstantMin"),
        rodMidInstantMax = values.double("rodMidInstantMax"),
        farThreshold = values.double("farThreshold"),
        stillFrameThreshold = values.double("stillFrameThreshold"),
        stillFramesNeeded = values.int("stillFramesNeeded"),
        bowSlowThreshold = values.double("bowSlowThreshold"),
        bowSlowFramesNeeded = values.int("bowSlowFramesNeeded"),
        feetSplashSafeDistance = values.double("feetSplashSafeDistance"),
        feetSplashRetreatMaxMs = values.long("feetSplashRetreatMaxMs"),
        secondRegenGapDelayMs = values.long("secondRegenGapDelayMs")
    )

    private fun pickValues(): Map<String, Double> {
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
                val idx = ensureValue(p, newValue, spec)
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
        spec.optimal?.let { optimal ->
            if (ps.values.none { keyOf(it) == keyOf(optimal) }) {
                ps.values.add(optimal)
            }
        }

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
    private fun Map<String, Double>.double(key: String): Double = clampNum(this[key], key)

    private fun clampNum(value: Double?, key: String): Double {
        val spec = specByKey[key]
        val raw = value ?: spec?.def ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    private fun ensureValue(ps: ParamState, value: Double, spec: ParamSpec): Int {
        val clamped = clamp(quantize(value, spec.step), spec)
        val key = keyOf(clamped)
        val existing = ps.values.indexOfFirst { keyOf(it) == key }
        if (existing >= 0) return existing
        ps.values.add(clamped)
        return ps.values.lastIndex
    }

    private fun load(): StoredState {
        val f = file()
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            val newState = StoredState()
            for (spec in specs) {
                spec.optimal?.let { optimalValue ->
                    val ps = ParamState()
                    ps.values.add(optimalValue)
                    ps.lastValue = optimalValue
                    ps.lastArm = 0
                    ps.bandit = emptyBandit(1).update(0, 1.0).toState()
                    newState.params[spec.key] = ps
                }
            }
            return newState
        }

        val json = try {
            f.readText()
        } catch (_: Exception) {
            return StoredState()
        }

        return try {
            val type = object : TypeToken<StoredState>() {}.type
            val loadedState = kira.gson.fromJson<StoredState>(json, type)
            if (loadedState?.version == CURRENT_VERSION) {
                normalize(loadedState)
            } else {
                migrateLegacy(json)
            }
        } catch (_: Exception) {
            migrateLegacy(json)
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

    private fun normalize(stored: StoredState): StoredState {
        for ((_, ps) in stored.params) {
            if (ps.values.isEmpty()) continue
            val size = ps.values.size
            val bandit = ps.bandit?.let { UcbBandit.fromState(it) } ?: emptyBandit(size)
            val resized = resizeBandit(bandit, size)
            val arm = ps.lastArm.coerceIn(0, size - 1)
            ps.lastArm = arm
            ps.lastValue = ps.values[arm]
            ps.bandit = resized.toState()
        }
        stored.version = CURRENT_VERSION
        return stored
    }

    private fun migrateLegacy(json: String): StoredState {
        migrateFromLegacyV3(json)?.let { return it }
        migrateFromLegacyV2(json)?.let { return it }
        return StoredState()
    }

    private fun migrateFromLegacyV3(json: String): StoredState? {
        return try {
            val type = object : TypeToken<LegacyStoredStateV3>() {}.type
            val legacy = kira.gson.fromJson<LegacyStoredStateV3>(json, type) ?: return null
            if (legacy.params.isEmpty()) return null

            val migrated = StoredState()
            for ((key, lp) in legacy.params) {
                if (lp.values.isEmpty()) continue
                val ps = ParamState()
                val plays = LongArray(lp.values.size)
                val rewards = DoubleArray(lp.values.size)
                val numericValues = mutableListOf<Double>()

                lp.values.forEachIndexed { idx, entry ->
                    numericValues.add(entry.value)
                    plays[idx] = entry.plays.toLong()
                    rewards[idx] = entry.totalReward
                }

                val bandit = UcbBandit(numericValues.size, plays.sum(), plays, rewards)
                val arm = numericValues.indexOfFirst { keyOf(it) == keyOf(lp.lastValue) }.takeIf { it >= 0 } ?: 0

                ps.values = numericValues
                ps.lastArm = arm.coerceIn(0, numericValues.lastIndex)
                ps.lastValue = numericValues[ps.lastArm]
                ps.bandit = bandit.toState()
                migrated.params[key] = ps
            }

            if (migrated.params.isEmpty()) return null
            normalize(migrated)
        } catch (_: Exception) {
            null
        }
    }

    private fun migrateFromLegacyV2(json: String): StoredState? {
        return try {
            val type = object : TypeToken<LegacyStoredState>() {}.type
            val legacy = kira.gson.fromJson<LegacyStoredState>(json, type) ?: return null
            if (legacy.params.isEmpty()) return null

            val migrated = StoredState()
            for ((key, lp) in legacy.params) {
                val ps = ParamState()
                val sorted = lp.values.entries.sortedBy { it.key }
                val values = sorted.map { it.value.value }.toMutableList()
                if (values.isEmpty()) continue
                ps.values = values
                val plays = LongArray(values.size)
                val rewards = DoubleArray(values.size)
                for ((idx, entry) in sorted.withIndex()) {
                    plays[idx] = entry.value.plays.toLong()
                    rewards[idx] = entry.value.totalReward
                }
                val total = max(lp.totalPlays.toLong(), plays.sum())
                val bandit = UcbBandit(values.size, total, plays, rewards)
                val arm = values.indexOfFirst { keyOf(it) == keyOf(lp.lastValue) }.takeIf { it >= 0 } ?: 0
                ps.lastArm = arm
                ps.lastValue = values[arm]
                ps.bandit = bandit.toState()
                migrated.params[key] = ps
            }
            if (migrated.params.isEmpty()) return null
            normalize(migrated)
        } catch (_: Exception) {
            null
        }
    }

    private fun banditFor(ps: ParamState): UcbBandit {
        val size = ps.values.size
        require(size > 0) { "bandit requested without available values" }
        val plays = ps.bandit?.plays ?: LongArray(size)
        val rewards = ps.bandit?.rewards ?: DoubleArray(size)
        val total = ps.bandit?.totalPlays ?: 0L
        val bandit = UcbBandit(size, total, plays, rewards)
        val arm = ps.lastArm.coerceIn(0, size - 1)
        ps.lastArm = arm
        ps.lastValue = ps.values[arm]
        return bandit
    }

    private fun resizeBandit(bandit: UcbBandit, size: Int): UcbBandit {
        if (bandit.armCount == size) return bandit
        val state = bandit.toState()
        val plays = LongArray(size)
        val rewards = DoubleArray(size)
        for (i in 0 until min(size, state.plays.size)) {
            plays[i] = state.plays[i]
            rewards[i] = state.rewards[i]
        }
        val total = plays.sum()
        return UcbBandit(size, total, plays, rewards)
    }

    private fun emptyBandit(size: Int) = UcbBandit(size, 0, LongArray(size), DoubleArray(size))

    private fun file(): File = File(configDir(), "op_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
