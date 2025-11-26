package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

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
    private const val MISTAKE_PENALTY = 0.25
    private const val EXPLOITATION_THRESHOLD = 25     // Après 25 parties, favoriser l'exploitation
    private const val EXPLOITATION_FACTOR = 0.4       // Réduire l'exploration de 60%
    private const val CONVERGENCE_RATIO = 1.5         // Si best > second * 1.5, converger
    private const val MIN_PLAYS_FOR_CONFIDENCE = 2    // Minimum de plays pour considérer une valeur
    private const val MAX_VALUES_PER_PARAM = 20       // Maximum de valeurs distinctes par paramètre
    private const val HIGH_REWARD_THRESHOLD = 50.0    // Reward > 50 = très bon
    private const val ELITE_REWARD_THRESHOLD = 90.0   // Reward > 90 = élite

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.INT, optimal)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.LONG, optimal)

    private fun specD(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.DOUBLE, optimal)

    // Paramètres avec valeurs optimales découvertes lors de vos tests
    private val specs = listOf(
        // Strafe - DÉCOUVERTE MAJEURE: 40% est 10x meilleur que 10%!
        specI("longStrafeChance", 10.0, 45.0, 5.0, 30.0, 35.0),  // optimal: 35% (compromis entre 40% testé et stabilité)

        // Rod cooldowns - Les cooldowns courts performent mieux
        specL("rodCdCloseMsBase", 300.0, 420.0, 20.0, 320.0, 320.0),  // optimal: 320ms (137.3 reward)
        specL("rodCdFarMsBase", 440.0, 600.0, 20.0, 520.0, 520.0),     // optimal: 520ms (moyenne entre 470 et 560)
        specD("rodCdBiasMax", 1.05, 1.30, 0.03, 1.20, 1.22),          // optimal: 1.22 (121.5 reward)

        // Rod distances - Distances plus grandes en melee = meilleur
        specD("rodBanMeleeDist", 3.5, 4.5, 0.10, 4.0, 4.10),          // optimal: 4.10 (proche de 4.15 testé)
        specD("rodCloseMin", 1.6, 2.6, 0.10, 2.0, 2.30),              // optimal: 2.30 (entre 2.25 et 2.35)
        specD("rodCloseMax", 2.6, 4.0, 0.10, 3.2, 3.10),              // garder 3.10
        specD("rodMainMin", 2.5, 4.0, 0.15, 3.5, 3.70),               // optimal: 3.70 (120.8 reward)
        specD("rodMainMax", 5.0, 7.2, 0.10, 6.0, 5.75),               // garder 5.75
        specD("rodInterceptMin", 4.5, 6.5, 0.10, 5.8, 6.10),          // garder 6.10
        specD("rodInterceptMax", 6.0, 7.6, 0.10, 7.2, 7.60),          // garder 7.60
        specD("rodMaxRangeHard", 6.2, 7.8, 0.10, 7.2, 7.70),          // garder 7.70
        specD("rodMidInstantMin", 4.8, 6.4, 0.10, 5.5, 6.15),         // garder 6.15
        specD("rodMidInstantMax", 6.0, 7.6, 0.10, 7.0, 6.00),         // garder 6.00

        // Seuils de distance
        specD("farThreshold", 9.0, 13.0, 0.2, 11.0, 11.30),           // optimal: 11.30 (116.8 reward)

        // Détection d'immobilité
        specD("stillFrameThreshold", 0.008, 0.020, 0.001, 0.014, 0.014),  // optimal: 0.014 (moyenne des meilleures)
        specI("stillFramesNeeded", 6.0, 14.0, 2.0, 10.0, 10.0),           // optimal: 10 (bon équilibre)

        // Bow slow detection
        specD("bowSlowThreshold", 0.04, 0.12, 0.005, 0.08, 0.08),         // garder 0.08
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 3.0, 3.0),            // garder 3

        // Feet splash
        specD("feetSplashSafeDistance", 4.5, 6.5, 0.10, 5.8, 6.00),       // garder 6.00
        specL("feetSplashRetreatMaxMs", 500.0, 1100.0, 40.0, 800.0, 800.0),  // optimal: 800ms (compromis)

        // Second regen timing
        specL("secondRegenGapDelayMs", 20000.0, 45000.0, 1000.0, 25000.0, 26000.0)  // optimal: 26s
    )

    private val specByKey = specs.associateBy { it.key }
    private var loaded = false
    private var state = StoredState()

    fun pickParams(): OPParams {
        ensureLoaded()

        // Optimisation continue avec exploration intelligente (UCB)
        val values = pickValues()

        return build(values)
    }
    
    fun defaults(): OPParams = build(defaultValues())

    fun report(win: Boolean, mistakes: Int = 0) {
        ensureLoaded()

        val baseReward = if (win) 1.0 else 0.0
        val reward = (baseReward - mistakes * MISTAKE_PENALTY).coerceAtLeast(0.0)

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
        val values = mutableMapOf<String, Double>()

        for (spec in specs) {
            val ps = state.params.getOrPut(spec.key) { ParamState() }

            if (ps.values.isEmpty()) {
                initializeValues(ps, spec)
            }

            // Si on a déjà trouvé une valeur élite, la favoriser fortement
            if (hasEliteValue(ps)) {
                val eliteIndex = getEliteValueIndex(ps)
                if (eliteIndex >= 0 && RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.8) {
                    ps.bandit = resizeBandit(banditFor(ps), ps.values.size).toState()
                    ps.lastArm = eliteIndex
                    ps.lastValue = ps.values[eliteIndex]
                    values[spec.key] = ps.lastValue
                    continue
                }
            }

            // Vérifier si on doit converger
            if (shouldAccelerateConvergence(ps)) {
                // 70% de chance d'utiliser la meilleure valeur
                if (RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.7) {
                    val bandit = resizeBandit(banditFor(ps), ps.values.size)
                    val bestArm = bandit.bestArmIndex()
                    ps.lastArm = bestArm
                    ps.lastValue = ps.values[bestArm]
                    ps.bandit = bandit.toState()
                    values[spec.key] = ps.lastValue
                    continue
                }
            }

            // Logique standard avec UCB modifié
            val value = pickValueForParam(spec, ps)
            values[spec.key] = value
        }

        save()
        return values
    }

    private fun hasEliteValue(ps: ParamState): Boolean {
        if (ps.values.isEmpty()) return false
        val bandit = banditFor(ps)
        val stats = bandit.armStats()
        return stats.any { it.averageReward > ELITE_REWARD_THRESHOLD && it.plays >= MIN_PLAYS_FOR_CONFIDENCE }
    }

    private fun getEliteValueIndex(ps: ParamState): Int {
        if (ps.values.isEmpty()) return -1
        val bandit = banditFor(ps)
        val stats = bandit.armStats()
        return stats.firstOrNull {
            it.averageReward > ELITE_REWARD_THRESHOLD && it.plays >= MIN_PLAYS_FOR_CONFIDENCE
        }?.index ?: -1
    }

    private fun shouldAccelerateConvergence(ps: ParamState): Boolean {
        if (ps.values.size < 2) return false

        val bandit = banditFor(ps)
        if (bandit.totalPlays < EXPLOITATION_THRESHOLD) return false

        val stats = bandit.armStats()
        if (stats.size < 2) return false

        val best = stats[0]
        val second = stats[1]

        return best.plays >= MIN_PLAYS_FOR_CONFIDENCE &&
            best.averageReward > second.averageReward * CONVERGENCE_RATIO &&
            best.averageReward > HIGH_REWARD_THRESHOLD
    }

    private fun pickValueForParam(spec: ParamSpec, ps: ParamState): Double {
        var bandit = banditFor(ps)
        val shouldExplore = shouldExploreNew(ps, bandit)

        val arm = if (shouldExplore) {
            val newValue = sample(spec)
            val idx = ensureValue(ps, newValue, spec)
            bandit = resizeBandit(bandit, ps.values.size)
            idx
        } else {
            bandit = resizeBandit(bandit, ps.values.size)
            if (bandit.totalPlays > EXPLOITATION_THRESHOLD) {
                selectArmWithExploitation(bandit)
            } else {
                bandit.selectArm()
            }
        }

        ps.lastArm = arm
        ps.lastValue = ps.values[arm]
        ps.bandit = bandit.toState()
        return ps.lastValue
    }

    private fun selectArmWithExploitation(bandit: UcbBandit): Int {
        val stats = bandit.armStats()
        for (stat in stats) {
            if (stat.plays == 0L) return stat.index
        }

        val t = max(bandit.totalPlays.toDouble(), 1.0)
        val logN = ln(t)
        var bestArm = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (stat in stats) {
            val i = stat.index
            val averageReward = stat.averageReward

            val explorationBonus = sqrt(2.0 * logN / stat.plays) * EXPLOITATION_FACTOR
            val score = averageReward + explorationBonus

            val eliteBonus = if (averageReward > ELITE_REWARD_THRESHOLD && stat.plays >= MIN_PLAYS_FOR_CONFIDENCE) {
                0.5
            } else 0.0

            val finalScore = score + eliteBonus

            if (finalScore > bestScore) {
                bestScore = finalScore
                bestArm = i
            }
        }

        return bestArm
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
        if (ps.values.size >= MAX_VALUES_PER_PARAM) {
            return findClosestValueIndex(ps.values, clamped)
        }

        ps.values.add(clamped)
        return ps.values.lastIndex
    }

    private fun findClosestValueIndex(values: List<Double>, target: Double): Int {
        var closestIndex = 0
        var minDistance = Double.MAX_VALUE

        values.forEachIndexed { index, value ->
            val distance = abs(value - target)
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = index
            }
        }

        return closestIndex
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

    fun printStats() {
        println("=== OPTuner Statistics ===")

        for ((key, ps) in state.params) {
            if (ps.values.isEmpty()) continue

            val spec = specByKey[key] ?: continue
            val bandit = banditFor(ps)
            val stats = bandit.armStats()

            println("\n$key (optimal: ${spec.optimal}):")
            stats.take(5).forEach { stat ->
                val value = ps.values[stat.index]
                val marker = when {
                    stat.averageReward > ELITE_REWARD_THRESHOLD -> "⭐"
                    stat.averageReward > HIGH_REWARD_THRESHOLD -> "✓"
                    else -> " "
                }
                println("  $marker %.2f: reward=%.1f, plays=${stat.plays}".format(value, stat.averageReward))
            }
        }
    }

    fun eliteParams(): OPParams {
        return OPParams(
            minGapIntervalMs = 4500L,
            longStrafeChance = 40,
            rodCdCloseMsBase = 320L,
            rodCdFarMsBase = 520L,
            rodCdBiasMax = 1.22,
            rodBanMeleeDist = 4.10,
            rodCloseMin = 2.30,
            rodCloseMax = 3.10,
            rodMainMin = 3.70,
            rodMainMax = 5.75,
            rodInterceptMin = 6.10,
            rodInterceptMax = 7.60,
            rodMaxRangeHard = 7.70,
            rodMidInstantMin = 6.15,
            rodMidInstantMax = 6.00,
            farThreshold = 11.30,
            stillFrameThreshold = 0.014,
            stillFramesNeeded = 10,
            bowSlowThreshold = 0.08,
            bowSlowFramesNeeded = 3,
            feetSplashSafeDistance = 6.00,
            feetSplashRetreatMaxMs = 800L,
            secondRegenGapDelayMs = 26000L,
        )
    }
}
