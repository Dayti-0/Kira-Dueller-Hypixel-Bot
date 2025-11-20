package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.ln

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

    private data class ValueState(
        var value: Double = 0.0,
        var plays: Int = 0,
        var totalReward: Double = 0.0,
        var lastPlayed: Long = System.currentTimeMillis(),
        var winStreak: Int = 0,
        var bestStreak: Int = 0
    ) {
        fun avg(): Double = if (plays > 0) totalReward / plays else 0.0
        
        fun ucb(totalPlays: Int, c: Double = 1.4): Double {
            if (plays == 0) return Double.POSITIVE_INFINITY
            val exploitation = avg()
            val exploration = c * sqrt(ln(totalPlays.toDouble()) / plays)
            // Pénalité légère pour les valeurs non jouées récemment
            val recency = if (totalPlays > 100) {
                0.001 * (System.currentTimeMillis() - lastPlayed) / (1000 * 60 * 60)
            } else 0.0
            return exploitation + exploration - recency
        }
    }

    private data class ParamState(
        var values: MutableMap<String, ValueState> = mutableMapOf(),
        var lastValue: Double = 0.0,
        var totalPlays: Int = 0,
        var locked: Boolean = false,
        var lockedValue: Double? = null
    )

    private data class GlobalStats(
        var totalGames: Int = 0,
        var totalWins: Int = 0,
        var bestStreak: Int = 0,
        var currentStreak: Int = 0,
        var lastUpdate: Long = System.currentTimeMillis()
    ) {
        fun winRate(): Double = if (totalGames > 0) totalWins.toDouble() / totalGames else 0.0
    }

    private data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf(),
        var globalStats: GlobalStats = GlobalStats()
    )

    private const val CURRENT_VERSION = 3
    private const val MISTAKE_PENALTY = 0.1
    private const val WIN_STREAK_BONUS = 0.02
    private const val LOCK_THRESHOLD_PLAYS = 100
    private const val LOCK_THRESHOLD_WINRATE = 0.995

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

    fun pickParams(): OPParams {
        ensureLoaded()
        
        // Optimisation continue avec exploration intelligente (UCB)
        val values = pickWithUCB()
        
        // Log périodique du progrès
        if (state.globalStats.totalGames > 0 && state.globalStats.totalGames % 100 == 0) {
            println("[OPTuner] ${state.globalStats.totalGames} parties | WR: ${(state.globalStats.winRate() * 100).toInt()}% | Série: ${state.globalStats.currentStreak}")
        }
        
        return build(values)
    }
    
    fun defaults(): OPParams = build(defaultValues())

    fun report(win: Boolean, mistakes: Int = 0) {
        ensureLoaded()
        
        // Mise à jour des stats globales
        state.globalStats.totalGames++
        state.globalStats.lastUpdate = System.currentTimeMillis()
        
        if (win) {
            state.globalStats.totalWins++
            state.globalStats.currentStreak++
            if (state.globalStats.currentStreak > state.globalStats.bestStreak) {
                state.globalStats.bestStreak = state.globalStats.currentStreak
            }
        } else {
            state.globalStats.currentStreak = 0
        }
        
        // Calcul de la récompense avec bonus/malus
        val baseReward = if (win) 1.0 else 0.0
        val mistakePenalty = mistakes * MISTAKE_PENALTY
        val streakBonus = if (win && state.globalStats.currentStreak > 5) {
            state.globalStats.currentStreak * WIN_STREAK_BONUS
        } else 0.0
        
        val reward = (baseReward + streakBonus - mistakePenalty)
            .coerceAtLeast(0.0)
            .coerceAtMost(1.5)
        
        // Mise à jour des paramètres
        for ((key, ps) in state.params) {
            val entryKey = keyOf(ps.lastValue)
            val vs = ps.values.getOrPut(entryKey) { ValueState(value = ps.lastValue) }
            
            vs.plays += 1
            vs.totalReward += reward
            vs.lastPlayed = System.currentTimeMillis()
            
            if (win) {
                vs.winStreak++
                if (vs.winStreak > vs.bestStreak) {
                    vs.bestStreak = vs.winStreak
                }
            } else {
                vs.winStreak = 0
            }
            
            ps.totalPlays += 1
            
            // Vérification du verrouillage automatique (paramètres excellents)
            if (!ps.locked && vs.plays >= LOCK_THRESHOLD_PLAYS) {
                val winRate = vs.avg()
                if (winRate >= LOCK_THRESHOLD_WINRATE) {
                    ps.locked = true
                    ps.lockedValue = vs.value
                    println("[OPTuner] Paramètre ${key} verrouillé: ${vs.value} (${(winRate * 100).toInt()}% WR sur ${vs.plays} parties)")
                }
            }
        }
        
        save()
        
        // Affichage périodique des statistiques
        if (state.globalStats.totalGames % 500 == 0) {
            printDetailedStats()
        }
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

    private fun pickWithUCB(): Map<String, Double> {
        val chosen = mutableMapOf<String, Double>()
        
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            
            // Si verrouillé, utiliser la valeur verrouillée
            if (p.locked && p.lockedValue != null) {
                chosen[spec.key] = p.lockedValue!!
                p.lastValue = p.lockedValue!!
                continue
            }
            
            // Initialiser avec la valeur optimale et quelques autres
            if (p.values.isEmpty()) {
                initializeValues(p, spec)
            }
            
            // Décider si on explore une nouvelle valeur ou on exploite
            val shouldExplore = shouldExploreNew(p)
            
            val value = if (shouldExplore) {
                // Explorer une nouvelle valeur
                val newValue = sample(spec)
                val key = keyOf(newValue)
                if (!p.values.containsKey(key)) {
                    p.values[key] = ValueState(value = newValue)
                }
                newValue
            } else {
                // Utiliser UCB pour sélectionner parmi les valeurs existantes
                selectByUCB(p, spec)
            }
            
            p.lastValue = value
            chosen[spec.key] = value
        }
        
        save()
        return chosen
    }

    private fun selectByUCB(ps: ParamState, spec: ParamSpec): Double {
        if (ps.values.isEmpty()) {
            return spec.optimal ?: spec.def
        }
        
        var bestValue = spec.def
        var bestScore = Double.NEGATIVE_INFINITY
        
        // Ajuster le facteur d'exploration selon le nombre de parties
        val c = when {
            ps.totalPlays < 50 -> 2.0     // Beaucoup d'exploration au début
            ps.totalPlays < 200 -> 1.4    // Standard UCB
            ps.totalPlays < 500 -> 1.0    // Réduction progressive
            else -> 0.5                    // Exploitation principalement
        }
        
        for ((_, vs) in ps.values) {
            val score = vs.ucb(ps.totalPlays, c)
            if (score > bestScore) {
                bestScore = score
                bestValue = vs.value
            }
        }
        
        return clamp(bestValue, spec)
    }

    private fun shouldExploreNew(ps: ParamState): Boolean {
        // Explorer si on a peu de valeurs testées
        if (ps.values.size < 10) return RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.3
        
        // Explorer périodiquement pour découvrir de nouvelles valeurs
        if (ps.totalPlays > 0 && ps.totalPlays % 30 == 0) return true
        
        // Exploration adaptative selon le nombre de parties
        val explorationRate = when {
            ps.totalPlays < 100 -> 0.2
            ps.totalPlays < 300 -> 0.1
            ps.totalPlays < 1000 -> 0.05
            else -> 0.02
        }
        
        return RandomUtils.randomDoubleInRange(0.0, 1.0) < explorationRate
    }

    private fun initializeValues(ps: ParamState, spec: ParamSpec) {
        // Toujours ajouter la valeur optimale si elle existe
        spec.optimal?.let {
            val key = keyOf(it)
            ps.values[key] = ValueState(value = it)
        }
        
        // Ajouter la valeur par défaut
        val defKey = keyOf(spec.def)
        if (!ps.values.containsKey(defKey)) {
            ps.values[defKey] = ValueState(value = spec.def)
        }
        
        // Ajouter quelques valeurs réparties dans la plage
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
            if (!ps.values.containsKey(key)) {
                ps.values[key] = ValueState(value = clamped)
            }
        }
    }

    private fun printDetailedStats() {
        val stats = state.globalStats
        println("=== OPTuner Statistiques Détaillées ===")
        println("Parties: ${stats.totalGames} | Win Rate: ${(stats.winRate() * 100).toInt()}%")
        println("Meilleure série: ${stats.bestStreak}")
        
        // Afficher les top 3 valeurs pour quelques paramètres clés
        val keyParams = listOf("longStrafeChance", "rodCdCloseMsBase", "rodCdFarMsBase")
        for (paramKey in keyParams) {
            state.params[paramKey]?.let { ps ->
                val top3 = ps.values.values
                    .filter { it.plays >= 10 }
                    .sortedByDescending { it.avg() }
                    .take(3)
                
                if (top3.isNotEmpty()) {
                    println("$paramKey:")
                    top3.forEach { vs ->
                        println("  ${vs.value}: ${(vs.avg() * 100).toInt()}% WR (${vs.plays} parties)")
                    }
                }
            }
        }
        
        val locked = state.params.filter { it.value.locked }
        if (locked.isNotEmpty()) {
            println("Paramètres verrouillés: ${locked.size}/22")
        }
    }

    private fun defaultValues(): Map<String, Double> = specs.associate { it.key to it.def }

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

    private fun load(): StoredState {
        return try {
            val f = file()
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                // Démarrer avec les valeurs optimales découvertes
                val newState = StoredState()
                // Pré-initialiser avec les valeurs optimales
                for (spec in specs) {
                    spec.optimal?.let { optimalValue ->
                        val ps = ParamState()
                        val key = keyOf(optimalValue)
                        ps.values[key] = ValueState(value = optimalValue, plays = 1, totalReward = 1.0)
                        ps.lastValue = optimalValue
                        ps.totalPlays = 1
                        newState.params[spec.key] = ps
                    }
                }
                newState
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

    private fun file(): File = File(configDir(), "op_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
