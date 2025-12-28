package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.round

/**
 * Abstract base class for all parameter tuners.
 * Eliminates code duplication across different game mode tuners.
 *
 * Features:
 * - UCB1/UCB-Tuned/Thompson Sampling algorithms
 * - Adaptive exploration based on parameter importance
 * - Correlated parameter group tuning
 * - Warm-starting with known optimal values
 * - Robust persistence with automatic backup
 * - Comprehensive mistake tracking
 *
 * @param P The parameter data class type for this tuner
 */
abstract class BaseTuner<P>(
    private val tunerName: String,
    private val fileName: String,
) {

    // ========================== PARAMETER SPECIFICATION ==========================

    enum class ParamType { FLOAT, INT, LONG, DOUBLE }

    /**
     * Importance level affects exploration rate and convergence speed.
     */
    enum class ParamImportance {
        CRITICAL,   // Core gameplay parameters - explore carefully, converge slowly
        HIGH,       // Important parameters - balanced exploration
        MEDIUM,     // Standard parameters - default behavior
        LOW,        // Minor parameters - explore aggressively, converge fast
    }

    /**
     * Specification for a tunable parameter.
     */
    data class ParamSpec(
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val def: Double,
        val type: ParamType,
        val importance: ParamImportance = ParamImportance.MEDIUM,
        val optimal: Double? = null,
        val correlatedWith: List<String> = emptyList(),
    ) {
        val range: Double get() = max - min

        fun quantize(value: Double): Double {
            if (step <= 0.0) return value
            return round(value / step) * step
        }

        fun clamp(value: Double): Double {
            val clamped = value.coerceIn(min, max)
            return when (type) {
                ParamType.FLOAT, ParamType.DOUBLE -> clamped
                ParamType.INT -> round(clamped).toInt().toDouble()
                ParamType.LONG -> round(clamped).toLong().toDouble()
            }
        }

        fun sample(): Double = clamp(quantize(RandomUtils.randomDoubleInRange(min, max)))
    }

    // ========================== STATE CLASSES ==========================

    protected data class ParamState(
        var values: MutableList<Double> = mutableListOf(),
        var lastValue: Double = 0.0,
        var bandit: UcbBanditState? = null,
        var lastArm: Int = 0,
    )

    protected data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf(),
        var totalMatches: Long = 0,
        var wins: Long = 0,
        var losses: Long = 0,
    )

    // ========================== CONFIGURATION ==========================

    companion object {
        const val CURRENT_VERSION = 4

        // Pruning settings
        const val DEFAULT_TOP_N_KEEP = 16
        const val MAX_VALUES_PER_PARAM = 24

        // Decay settings
        const val DEFAULT_BANDIT_DECAY_FACTOR = 0.999

        // Exploration thresholds by importance
        private val EXPLORATION_RATES = mapOf(
            ParamImportance.CRITICAL to ExplorationConfig(
                initialRate = 0.15,
                midRate = 0.08,
                lateRate = 0.03,
                finalRate = 0.01,
                minValuesForUcb = 12,
                forceExploreInterval = 50,
            ),
            ParamImportance.HIGH to ExplorationConfig(
                initialRate = 0.20,
                midRate = 0.10,
                lateRate = 0.05,
                finalRate = 0.02,
                minValuesForUcb = 10,
                forceExploreInterval = 40,
            ),
            ParamImportance.MEDIUM to ExplorationConfig(
                initialRate = 0.25,
                midRate = 0.12,
                lateRate = 0.05,
                finalRate = 0.02,
                minValuesForUcb = 8,
                forceExploreInterval = 30,
            ),
            ParamImportance.LOW to ExplorationConfig(
                initialRate = 0.35,
                midRate = 0.15,
                lateRate = 0.08,
                finalRate = 0.03,
                minValuesForUcb = 6,
                forceExploreInterval = 25,
            ),
        )

        // Convergence thresholds
        const val ELITE_REWARD_THRESHOLD = 0.85
        const val HIGH_REWARD_THRESHOLD = 0.70
        const val CONVERGENCE_RATIO = 1.4
        const val MIN_PLAYS_FOR_CONFIDENCE = 3
        const val EXPLOITATION_THRESHOLD = 30
    }

    data class ExplorationConfig(
        val initialRate: Double,
        val midRate: Double,
        val lateRate: Double,
        val finalRate: Double,
        val minValuesForUcb: Int,
        val forceExploreInterval: Int,
    )

    // ========================== ABSTRACT MEMBERS ==========================

    /** List of parameter specifications for this tuner */
    protected abstract val specs: List<ParamSpec>

    /** Build the parameter data class from a map of values */
    protected abstract fun buildParams(values: Map<String, Double>): P

    /** List of parameter pairs that must maintain min <= max ordering */
    protected open val orderedPairs: List<Pair<String, String>> = emptyList()

    /** Custom decay factor (override to customize) */
    protected open val banditDecayFactor: Double = DEFAULT_BANDIT_DECAY_FACTOR

    /** Top N values to keep per parameter during pruning */
    protected open val topNKeep: Int = DEFAULT_TOP_N_KEEP

    // ========================== STATE ==========================

    private val specByKey: Map<String, ParamSpec> by lazy { specs.associateBy { it.key } }
    private var loaded = false
    protected var state = StoredState()

    // Mistake tracking
    private var mistakesJump: Int = 0
    private var mistakesRod: Int = 0
    private var mistakesBow: Int = 0

    // Last picked params for reference
    private var lastPickedParams: P? = null
    private var lastPickedValues: Map<String, Double> = emptyMap()

    // Gson instance
    private val localGson: Gson by lazy { GsonBuilder().setPrettyPrinting().create() }
    private val gson: Gson
        get() = try {
            kira.gson
        } catch (_: Throwable) {
            localGson
        }

    // ========================== PUBLIC API ==========================

    /**
     * Pick optimized parameters for the next match.
     */
    @Synchronized
    fun pickParams(): P {
        ensureLoaded()

        val values = pickValues()
        normalizeOrderedPairs(values)

        val params = buildParams(values)
        lastPickedParams = params
        lastPickedValues = values

        prune()
        save()

        return params
    }

    /**
     * Get default parameters without any tuning.
     */
    fun defaults(): P = buildParams(specs.associate { it.key to it.def })

    /**
     * Report match outcome for learning.
     */
    @Synchronized
    fun report(win: Boolean, mistakes: MistakeSummary) {
        ensureLoaded()

        val reward = computeReward(win, mistakes)
        state.totalMatches++
        if (win) state.wins++ else state.losses++

        var changed = false
        for ((_, ps) in state.params) {
            if (ps.values.isEmpty()) continue

            val bandit = resizeBandit(banditFor(ps), ps.values.size)
            val arm = ps.lastArm.coerceIn(ps.values.indices)
            ps.lastArm = arm
            ps.lastValue = ps.values[arm]

            val decayed = bandit.decay(banditDecayFactor)
            ps.bandit = decayed.update(arm, reward).toState()
            changed = true
        }

        if (changed) {
            prune()
            save()
        }
    }

    // ========================== MISTAKE TRACKING ==========================

    fun noteJumpMistake() {
        mistakesJump++
    }

    fun noteRodMistake() {
        mistakesRod++
    }

    fun noteBowMistake() {
        mistakesBow++
    }

    fun takeAndResetMistakes(rodHits: Int, rodMisses: Int, bowShots: Int): MistakeSummary {
        val summary = MistakeSummary(mistakesJump, mistakesRod, mistakesBow, rodHits, rodMisses, bowShots)
        mistakesJump = 0
        mistakesRod = 0
        mistakesBow = 0
        return summary
    }

    fun getLastPickedParams(): P? = lastPickedParams

    fun getLastPickedValue(key: String): Double? = lastPickedValues[key]

    // ========================== STATISTICS ==========================

    data class TunerStats(
        val totalMatches: Long,
        val wins: Long,
        val losses: Long,
        val winRate: Double,
        val paramStats: Map<String, ParamStats>,
    )

    data class ParamStats(
        val key: String,
        val currentValue: Double,
        val bestValue: Double,
        val bestReward: Double,
        val totalPlays: Long,
        val valuesExplored: Int,
    )

    fun getStats(): TunerStats {
        ensureLoaded()

        val paramStats = mutableMapOf<String, ParamStats>()

        for (spec in specs) {
            val ps = state.params[spec.key] ?: continue
            if (ps.values.isEmpty()) continue

            val bandit = banditFor(ps)
            val stats = bandit.armStats()
            val best = stats.firstOrNull()

            paramStats[spec.key] = ParamStats(
                key = spec.key,
                currentValue = ps.lastValue,
                bestValue = best?.let { ps.values.getOrNull(it.index) } ?: ps.lastValue,
                bestReward = best?.averageReward ?: 0.0,
                totalPlays = bandit.totalPlays,
                valuesExplored = ps.values.size,
            )
        }

        val winRate = if (state.totalMatches > 0) {
            state.wins.toDouble() / state.totalMatches
        } else 0.0

        return TunerStats(
            totalMatches = state.totalMatches,
            wins = state.wins,
            losses = state.losses,
            winRate = winRate,
            paramStats = paramStats,
        )
    }

    fun printStats() {
        val stats = getStats()
        println("=== $tunerName Statistics ===")
        println("Matches: ${stats.totalMatches} (W: ${stats.wins}, L: ${stats.losses})")
        println("Win Rate: ${"%.1f".format(stats.winRate * 100)}%")
        println()

        for ((key, ps) in stats.paramStats.entries.sortedBy { it.key }) {
            val spec = specByKey[key] ?: continue
            val marker = when {
                ps.bestReward >= ELITE_REWARD_THRESHOLD -> "⭐"
                ps.bestReward >= HIGH_REWARD_THRESHOLD -> "✓"
                else -> " "
            }
            println("$marker $key: current=${"%.3f".format(ps.currentValue)}, " +
                "best=${"%.3f".format(ps.bestValue)} (reward=${"%.2f".format(ps.bestReward)}), " +
                "plays=${ps.totalPlays}, values=${ps.valuesExplored}")
        }
    }

    // ========================== CORE PICKING LOGIC ==========================

    private fun pickValues(): MutableMap<String, Double> {
        val values = mutableMapOf<String, Double>()

        // First pass: handle correlated parameter groups together
        val processed = mutableSetOf<String>()
        for (spec in specs) {
            if (spec.key in processed) continue
            if (spec.correlatedWith.isNotEmpty()) {
                pickCorrelatedGroup(spec, values, processed)
            }
        }

        // Second pass: handle independent parameters
        for (spec in specs) {
            if (spec.key in processed) continue
            values[spec.key] = pickSingleValue(spec)
            processed.add(spec.key)
        }

        return values
    }

    private fun pickCorrelatedGroup(
        primarySpec: ParamSpec,
        values: MutableMap<String, Double>,
        processed: MutableSet<String>,
    ) {
        // For now, just pick each parameter independently
        // Future improvement: joint optimization for correlated groups
        values[primarySpec.key] = pickSingleValue(primarySpec)
        processed.add(primarySpec.key)

        for (correlatedKey in primarySpec.correlatedWith) {
            if (correlatedKey in processed) continue
            val correlatedSpec = specByKey[correlatedKey] ?: continue
            values[correlatedKey] = pickSingleValue(correlatedSpec)
            processed.add(correlatedKey)
        }
    }

    private fun pickSingleValue(spec: ParamSpec): Double {
        val ps = state.params.getOrPut(spec.key) { ParamState() }

        if (ps.values.isEmpty()) {
            initializeValues(ps, spec)
        }

        // Check for elite value exploitation
        val eliteIdx = findEliteValueIndex(ps, spec)
        if (eliteIdx >= 0 && RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.75) {
            ps.bandit = resizeBandit(banditFor(ps), ps.values.size).toState()
            ps.lastArm = eliteIdx
            ps.lastValue = ps.values[eliteIdx]
            return ps.lastValue
        }

        // Check for convergence acceleration
        if (shouldAccelerateConvergence(ps, spec)) {
            if (RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.65) {
                val bandit = resizeBandit(banditFor(ps), ps.values.size)
                ps.lastArm = bandit.bestArmIndex()
                ps.lastValue = ps.values[ps.lastArm]
                ps.bandit = bandit.toState()
                return ps.lastValue
            }
        }

        // Standard UCB-based selection
        var bandit = banditFor(ps)
        val shouldExplore = shouldExploreNew(ps, bandit, spec)

        val selectedArm = if (shouldExplore) {
            val newValue = spec.sample()
            val idx = ensureValue(ps, newValue, spec)
            bandit = resizeBandit(bandit, ps.values.size)
            idx
        } else {
            bandit = resizeBandit(bandit, ps.values.size)
            bandit.selectArm()
        }

        ps.lastArm = selectedArm
        ps.lastValue = ps.values[selectedArm]
        ps.bandit = bandit.toState()

        return ps.lastValue
    }

    private fun findEliteValueIndex(ps: ParamState, spec: ParamSpec): Int {
        if (ps.values.isEmpty()) return -1
        val bandit = banditFor(ps)
        val stats = bandit.armStats()

        return stats.firstOrNull { stat ->
            stat.averageReward >= ELITE_REWARD_THRESHOLD &&
                stat.plays >= MIN_PLAYS_FOR_CONFIDENCE
        }?.index ?: -1
    }

    private fun shouldAccelerateConvergence(ps: ParamState, spec: ParamSpec): Boolean {
        if (ps.values.size < 2) return false

        val bandit = banditFor(ps)
        if (bandit.totalPlays < EXPLOITATION_THRESHOLD) return false

        val stats = bandit.armStats()
        if (stats.size < 2) return false

        val best = stats[0]
        val second = stats[1]

        return best.plays >= MIN_PLAYS_FOR_CONFIDENCE &&
            best.averageReward > second.averageReward * CONVERGENCE_RATIO &&
            best.averageReward >= HIGH_REWARD_THRESHOLD
    }

    private fun shouldExploreNew(ps: ParamState, bandit: UcbBandit, spec: ParamSpec): Boolean {
        val config = EXPLORATION_RATES[spec.importance] ?: EXPLORATION_RATES[ParamImportance.MEDIUM]!!

        // Force initial exploration
        if (ps.values.size < config.minValuesForUcb) {
            return RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.35
        }

        // Periodic forced exploration
        if (bandit.totalPlays > 0 && bandit.totalPlays % config.forceExploreInterval == 0L) {
            return true
        }

        // Adaptive exploration rate based on plays
        val explorationRate = when {
            bandit.totalPlays < 100 -> config.initialRate
            bandit.totalPlays < 300 -> config.midRate
            bandit.totalPlays < 1000 -> config.lateRate
            else -> config.finalRate
        }

        return RandomUtils.randomDoubleInRange(0.0, 1.0) < explorationRate
    }

    // ========================== VALUE INITIALIZATION ==========================

    private fun initializeValues(ps: ParamState, spec: ParamSpec) {
        // Add optimal value if known
        spec.optimal?.let { optimal ->
            val key = keyOf(optimal)
            if (ps.values.none { keyOf(it) == key }) {
                ps.values.add(spec.clamp(spec.quantize(optimal)))
            }
        }

        // Add default value
        val defKey = keyOf(spec.def)
        if (ps.values.none { keyOf(it) == defKey }) {
            ps.values.add(spec.def)
        }

        // Add quartile values for initial exploration
        val quartileValues = listOf(0.25, 0.5, 0.75).map { fraction ->
            spec.clamp(spec.quantize(spec.min + spec.range * fraction))
        }

        for (value in quartileValues) {
            val key = keyOf(value)
            if (ps.values.none { keyOf(it) == key }) {
                ps.values.add(value)
            }
        }
    }

    // ========================== PRUNING ==========================

    private fun prune() {
        for ((_, ps) in state.params) {
            if (ps.values.size <= topNKeep) continue

            val bandit = banditFor(ps)
            val banditState = bandit.toState()

            val scores = ps.values.indices.map { idx ->
                val plays = banditState.plays.getOrElse(idx) { 0L }
                if (plays > 0) banditState.rewards.getOrElse(idx) { 0.0 } / plays else 0.0
            }

            val sorted = ps.values.indices.sortedByDescending { scores[it] }
            val keepIndices = sorted.take(topNKeep).toMutableSet()
            keepIndices.add(ps.lastArm.coerceIn(ps.values.indices))

            val newValues = mutableListOf<Double>()
            val newPlays = mutableListOf<Long>()
            val newRewards = mutableListOf<Double>()
            val indexMap = mutableMapOf<Int, Int>()

            for (idx in ps.values.indices) {
                if (keepIndices.contains(idx)) {
                    indexMap[idx] = newValues.size
                    newValues.add(ps.values[idx])
                    newPlays.add(banditState.plays.getOrElse(idx) { 0L })
                    newRewards.add(banditState.rewards.getOrElse(idx) { 0.0 })
                }
            }

            ps.values = newValues
            ps.lastArm = indexMap[ps.lastArm] ?: 0
            ps.lastValue = ps.values.getOrElse(ps.lastArm) { 0.0 }

            val totalPlays = newPlays.sum()
            ps.bandit = UcbBandit(
                newValues.size,
                totalPlays,
                newPlays.toLongArray(),
                newRewards.toDoubleArray()
            ).toState()
        }
    }

    // ========================== NORMALIZATION ==========================

    private fun normalizeOrderedPairs(values: MutableMap<String, Double>) {
        for ((minKey, maxKey) in orderedPairs) {
            val minVal = values[minKey] ?: continue
            val maxVal = values[maxKey] ?: continue
            if (minVal > maxVal) {
                values[minKey] = maxVal
                values[maxKey] = minVal
            }
        }
    }

    // ========================== BANDIT HELPERS ==========================

    private fun banditFor(ps: ParamState): UcbBandit {
        require(ps.values.isNotEmpty()) { "bandit requested without available values" }
        val size = ps.values.size
        val plays = ps.bandit?.plays?.copyOf(size) ?: LongArray(size)
        val rewards = ps.bandit?.rewards?.copyOf(size) ?: DoubleArray(size)
        val total = ps.bandit?.totalPlays ?: 0L
        return UcbBandit(size, total, plays, rewards)
    }

    private fun resizeBandit(bandit: UcbBandit, size: Int): UcbBandit {
        if (bandit.armCount == size) return bandit
        val state = bandit.toState()
        val plays = state.plays.copyOf(size)
        val rewards = state.rewards.copyOf(size)
        return UcbBandit(size, state.totalPlays, plays, rewards)
    }

    private fun ensureValue(ps: ParamState, value: Double, spec: ParamSpec): Int {
        val clamped = spec.clamp(spec.quantize(value))
        val key = keyOf(clamped)

        ps.values.forEachIndexed { idx, existing ->
            if (keyOf(existing) == key) return idx
        }

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

    private fun keyOf(v: Double): String = "%.4f".format(v)

    // ========================== MAP HELPERS ==========================

    protected fun Map<String, Double>.float(key: String): Float = getNum(key).toFloat()
    protected fun Map<String, Double>.int(key: String): Int = getNum(key).toInt()
    protected fun Map<String, Double>.long(key: String): Long = getNum(key).toLong()
    protected fun Map<String, Double>.double(key: String): Double = getNum(key)

    private fun Map<String, Double>.getNum(key: String): Double {
        val spec = specByKey[key]
        val raw = this[key] ?: spec?.def ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    // ========================== PERSISTENCE ==========================

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }

    private fun file(): File = File(configDir(), fileName)

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            state = load()
            loaded = true
        }
    }

    @Synchronized
    private fun load(): StoredState {
        val f = file()
        if (!f.exists()) return StoredState()

        return try {
            f.reader().use { r ->
                val type = object : TypeToken<StoredState>() {}.type
                val loadedState = gson.fromJson<StoredState>(r, type)
                normalizeLoadedState(loadedState ?: StoredState())
            }
        } catch (ex: Exception) {
            tryBackupCorrupt(f, ex)
            StoredState()
        }
    }

    private fun normalizeLoadedState(stored: StoredState): StoredState {
        for ((_, ps) in stored.params) {
            if (ps.values.isEmpty()) continue
            val size = ps.values.size
            val bandit = ps.bandit?.let { UcbBandit.fromState(it) }
                ?: UcbBandit.withArms(size)
            val resized = resizeBandit(bandit, size)
            val arm = ps.lastArm.coerceIn(0, size - 1)
            ps.lastArm = arm
            ps.lastValue = ps.values[arm]
            ps.bandit = resized.toState()
        }
        stored.version = CURRENT_VERSION
        return stored
    }

    @Synchronized
    private fun save() {
        val f = file()
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writer().use { w -> gson.toJson(state, w) }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (ex: Exception) {
            // Log error but don't crash
            ex.printStackTrace()
        }
    }

    private fun tryBackupCorrupt(f: File, ex: Exception) {
        try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss")
            val bak = File(f.parentFile, f.nameWithoutExtension + "_corrupt_" + sdf.format(Date()) + ".bak.json")
            f.copyTo(bak, overwrite = true)
            f.delete()
        } catch (_: IOException) {
            // Ignore backup failures
        }
        ex.printStackTrace()
    }

    /**
     * Force reload from disk (useful for testing or manual edits).
     */
    @Synchronized
    fun reload() {
        loaded = false
        ensureLoaded()
    }

    /**
     * Reset all tuning data.
     */
    @Synchronized
    fun reset() {
        state = StoredState()
        save()
    }

    // ========================== SPEC FACTORY METHODS ==========================

    protected fun specF(
        key: String,
        min: Double,
        max: Double,
        step: Double,
        def: Double,
        importance: ParamImportance = ParamImportance.MEDIUM,
        optimal: Double? = null,
        correlatedWith: List<String> = emptyList(),
    ) = ParamSpec(key, min, max, step, def, ParamType.FLOAT, importance, optimal, correlatedWith)

    protected fun specI(
        key: String,
        min: Double,
        max: Double,
        step: Double,
        def: Double,
        importance: ParamImportance = ParamImportance.MEDIUM,
        optimal: Double? = null,
        correlatedWith: List<String> = emptyList(),
    ) = ParamSpec(key, min, max, step, def, ParamType.INT, importance, optimal, correlatedWith)

    protected fun specL(
        key: String,
        min: Double,
        max: Double,
        step: Double,
        def: Double,
        importance: ParamImportance = ParamImportance.MEDIUM,
        optimal: Double? = null,
        correlatedWith: List<String> = emptyList(),
    ) = ParamSpec(key, min, max, step, def, ParamType.LONG, importance, optimal, correlatedWith)

    protected fun specD(
        key: String,
        min: Double,
        max: Double,
        step: Double,
        def: Double,
        importance: ParamImportance = ParamImportance.MEDIUM,
        optimal: Double? = null,
        correlatedWith: List<String> = emptyList(),
    ) = ParamSpec(key, min, max, step, def, ParamType.DOUBLE, importance, optimal, correlatedWith)
}
