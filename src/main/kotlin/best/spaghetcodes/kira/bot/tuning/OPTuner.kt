package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.ln

object OPTunerV2 {

    data class OPParams(
        val minGapIntervalMs: Long,
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

    private enum class ParamType { FLOAT, INT, LONG, DOUBLE }

    private data class ParamSpec(
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val def: Double,
        val type: ParamType,
        val optimal: Double? = null  // Valeur optimale découverte
    )

    private data class ValueState(
        var value: Double = 0.0, 
        var plays: Int = 0, 
        var totalReward: Double = 0.0,
        var lastPlayed: Long = System.currentTimeMillis()
    ) {
        fun avg(): Double = if (plays > 0) totalReward / plays else 0.0
        fun ucb(totalPlays: Int, explorationFactor: Double = 1.4): Double {
            if (plays == 0) return Double.POSITIVE_INFINITY
            val exploitation = avg()
            val exploration = explorationFactor * sqrt(ln(totalPlays.toDouble()) / plays)
            return exploitation + exploration
        }
    }

    private data class ParamState(
        var values: MutableMap<String, ValueState> = mutableMapOf(),
        var lastValue: Double = 0.0,
        var totalPlays: Int = 0,
        var locked: Boolean = false,  // Verrouillage si performance optimale
        var lockedValue: Double? = null
    )

    private data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf(),
        var globalStats: GlobalStats = GlobalStats()
    )

    private data class GlobalStats(
        var totalGames: Int = 0,
        var totalWins: Int = 0,
        var bestStreak: Int = 0,
        var currentStreak: Int = 0,
        var lastUpdate: Long = System.currentTimeMillis()
    )

    private const val CURRENT_VERSION = 2
    private const val MISTAKE_PENALTY = 0.1
    private const val WIN_STREAK_BONUS = 0.05
    private const val LOCK_THRESHOLD_PLAYS = 50
    private const val LOCK_THRESHOLD_WINRATE = 0.995

    private fun specI(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.INT, optimal)

    private fun specL(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.LONG, optimal)

    private fun specD(key: String, min: Double, max: Double, step: Double, def: Double, optimal: Double? = null) =
        ParamSpec(key, min, max, step, def, ParamType.DOUBLE, optimal)

    // Paramètres avec valeurs optimales découvertes
    private val specs = listOf(
        specL("minGapIntervalMs", 3500.0, 5500.0, 100.0, 4500.0, 5400.0),
        specI("longStrafeChance", 10.0, 40.0, 1.0, 25.0, 10.0),
        specL("rodCdCloseMsBase", 300.0, 420.0, 10.0, 340.0, 420.0),
        specL("rodCdFarMsBase", 440.0, 600.0, 10.0, 480.0, 580.0),
        specD("rodCdBiasMax", 1.05, 1.35, 0.01, 1.25, 1.17),
        specD("rodBanMeleeDist", 3.5, 4.5, 0.05, 4.0, 3.65),
        specD("rodCloseMin", 1.6, 2.6, 0.05, 2.0, 2.25),
        specD("rodCloseMax", 2.6, 4.0, 0.05, 3.4, 3.10),
        specD("rodMainMin", 2.5, 4.0, 0.05, 3.0, 3.95),
        specD("rodMainMax", 5.0, 7.2, 0.05, 6.8, 5.75),
        specD("rodInterceptMin", 4.5, 6.5, 0.05, 5.8, 6.10),
        specD("rodInterceptMax", 6.0, 7.6, 0.05, 7.2, 7.60),
        specD("rodMaxRangeHard", 6.2, 7.8, 0.05, 7.2, 7.70),
        specD("rodMidInstantMin", 4.8, 6.4, 0.05, 5.5, 6.15),
        specD("rodMidInstantMax", 6.0, 7.6, 0.05, 7.0, 6.00),
        specD("farThreshold", 9.0, 13.0, 0.1, 11.0, 11.20),
        specD("stillFrameThreshold", 0.008, 0.02, 0.0005, 0.0125, 0.01),
        specI("stillFramesNeeded", 6.0, 14.0, 1.0, 10.0, 11.0),
        specD("bowSlowThreshold", 0.04, 0.12, 0.0025, 0.06, 0.08),
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 3.0, 3.0),
        specD("feetSplashSafeDistance", 4.5, 6.5, 0.05, 5.6, 6.00),
        specL("feetSplashRetreatMaxMs", 500.0, 1100.0, 20.0, 700.0, 920.0),
        specL("secondRegenGapDelayMs", 20000.0, 45000.0, 500.0, 30000.0, 20000.0)
    )

    private val specByKey = specs.associateBy { it.key }
    private var loaded = false
    private var state = StoredState()
    
    // Mode de fonctionnement
    enum class Mode {
        EXPLORATION,    // Exploration complète
        EXPLOITATION,   // Utilisation des valeurs optimales
        ADAPTIVE,      // UCB adaptatif (défaut)
        OPTIMAL        // Force les valeurs optimales découvertes
    }
    
    private var currentMode = Mode.ADAPTIVE

    fun setMode(mode: Mode) {
        currentMode = mode
        println("[OPTunerV2] Mode défini sur: $mode")
    }

    fun pickParams(): OPParams = build(when(currentMode) {
        Mode.EXPLORATION -> pickExploration()
        Mode.EXPLOITATION -> pickExploitation()
        Mode.ADAPTIVE -> pickAdaptive()
        Mode.OPTIMAL -> optimalValues()
    })
    
    fun defaults(): OPParams = build(defaultValues())
    fun optimal(): OPParams = build(optimalValues())

    fun report(win: Boolean, mistakes: Int = 0, streak: Int = 0) {
        ensureLoaded()
        
        // Calcul de la récompense avec bonus de streak
        val baseReward = if (win) 1.0 else 0.0
        val mistakePenalty = mistakes * MISTAKE_PENALTY
        val streakBonus = if (win && streak > 5) streak * WIN_STREAK_BONUS else 0.0
        val reward = (baseReward + streakBonus - mistakePenalty).coerceAtLeast(0.0).coerceAtMost(1.5)
        
        // Mise à jour des statistiques globales
        state.globalStats.totalGames++
        if (win) {
            state.globalStats.totalWins++
            state.globalStats.currentStreak++
            if (state.globalStats.currentStreak > state.globalStats.bestStreak) {
                state.globalStats.bestStreak = state.globalStats.currentStreak
            }
        } else {
            state.globalStats.currentStreak = 0
        }
        
        // Mise à jour des paramètres
        var changed = false
        for ((key, ps) in state.params) {
            val entryKey = keyOf(ps.lastValue)
            val vs = ps.values.getOrPut(entryKey) { ValueState(value = ps.lastValue) }
            vs.plays += 1
            vs.totalReward += reward
            vs.lastPlayed = System.currentTimeMillis()
            ps.totalPlays += 1
            
            // Vérification du verrouillage automatique
            if (!ps.locked && vs.plays >= LOCK_THRESHOLD_PLAYS) {
                val winRate = vs.avg()
                if (winRate >= LOCK_THRESHOLD_WINRATE) {
                    ps.locked = true
                    ps.lockedValue = vs.value
                    println("[OPTunerV2] Paramètre $key verrouillé sur ${vs.value} (${(winRate * 100).toInt()}% win rate)")
                }
            }
            
            changed = true
        }
        
        if (changed) save()
    }

    private fun build(values: Map<String, Double>) = OPParams(
        minGapIntervalMs = values.long("minGapIntervalMs"),
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

    // UCB Adaptatif
    private fun pickAdaptive(): Map<String, Double> {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()
        
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            
            // Si paramètre verrouillé, utiliser la valeur verrouillée
            if (p.locked && p.lockedValue != null) {
                chosen[spec.key] = p.lockedValue!!
                p.lastValue = p.lockedValue!!
                continue
            }
            
            // Générer toutes les valeurs possibles si nécessaire
            ensureAllValues(p, spec)
            
            // Sélection UCB
            val value = selectUCB(p, spec)
            
            p.lastValue = value
            chosen[spec.key] = value
        }
        
        save()
        return chosen
    }

    // Exploration pure
    private fun pickExploration(): Map<String, Double> {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()
        
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            val value = sample(spec)
            val key = keyOf(value)
            
            if (!p.values.containsKey(key)) {
                p.values[key] = ValueState(value = value)
            }
            
            p.lastValue = value
            chosen[spec.key] = value
        }
        
        save()
        return chosen
    }

    // Exploitation des meilleures valeurs trouvées
    private fun pickExploitation(): Map<String, Double> {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()
        
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            
            // Utiliser la valeur verrouillée ou la meilleure trouvée
            val value = if (p.locked && p.lockedValue != null) {
                p.lockedValue!!
            } else if (p.values.isNotEmpty()) {
                p.values.values.maxByOrNull { it.avg() }?.value ?: spec.optimal ?: spec.def
            } else {
                spec.optimal ?: spec.def
            }
            
            p.lastValue = value
            chosen[spec.key] = value
        }
        
        save()
        return chosen
    }

    // Sélection UCB
    private fun selectUCB(ps: ParamState, spec: ParamSpec): Double {
        if (ps.values.isEmpty()) {
            return spec.optimal ?: spec.def
        }
        
        // Calculer l'UCB pour chaque valeur
        var bestValue = spec.def
        var bestScore = Double.NEGATIVE_INFINITY
        
        for ((_, vs) in ps.values) {
            val score = vs.ucb(ps.totalPlays)
            if (score > bestScore) {
                bestScore = score
                bestValue = vs.value
            }
        }
        
        return clamp(bestValue, spec)
    }

    // Génération de toutes les valeurs possibles pour un paramètre
    private fun ensureAllValues(ps: ParamState, spec: ParamSpec) {
        if (ps.values.size < 5) {  // Initialiser avec quelques valeurs
            // Ajouter la valeur optimale si elle existe
            spec.optimal?.let { 
                val key = keyOf(it)
                if (!ps.values.containsKey(key)) {
                    ps.values[key] = ValueState(value = it)
                }
            }
            
            // Ajouter la valeur par défaut
            val defKey = keyOf(spec.def)
            if (!ps.values.containsKey(defKey)) {
                ps.values[defKey] = ValueState(value = spec.def)
            }
            
            // Ajouter quelques valeurs aléatoires
            repeat(3) {
                val value = sample(spec)
                val key = keyOf(value)
                if (!ps.values.containsKey(key)) {
                    ps.values[key] = ValueState(value = value)
                }
            }
        }
    }

    private fun defaultValues(): Map<String, Double> = specs.associate { it.key to it.def }
    
    private fun optimalValues(): Map<String, Double> = specs.associate { 
        it.key to (it.optimal ?: it.def) 
    }

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
                StoredState()
            } else {
                f.reader().use { reader ->
                    val type = object : TypeToken<StoredState>() {}.type
                    val loadedState = kira.gson.fromJson<StoredState>(reader, type)
                    if (loadedState == null || loadedState.version != CURRENT_VERSION) {
                        migrateOldData() ?: StoredState()
                    } else {
                        loadedState
                    }
                }
            }
        } catch (_: Exception) {
            StoredState()
        }
    }

    // Migration des données de l'ancienne version
    private fun migrateOldData(): StoredState? {
        try {
            val oldFile = File(configDir(), "op_tuner.json")
            if (oldFile.exists()) {
                // Charger l'ancien format et migrer
                println("[OPTunerV2] Migration des données de l'ancienne version...")
                // Code de migration ici si nécessaire
                return null
            }
        } catch (_: Exception) {}
        return null
    }

    private fun save() {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            state.globalStats.lastUpdate = System.currentTimeMillis()
            f.writer().use { writer ->
                kira.gson.toJson(state, writer)
            }
        } catch (_: Exception) {
        }
    }

    private fun file(): File = File(configDir(), "op_tuner_v2.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }

    // Fonctions utilitaires pour l'analyse
    fun getStatistics(): String {
        ensureLoaded()
        val sb = StringBuilder()
        sb.appendLine("=== OPTunerV2 Statistiques ===")
        sb.appendLine("Mode: $currentMode")
        sb.appendLine("Parties totales: ${state.globalStats.totalGames}")
        sb.appendLine("Victoires: ${state.globalStats.totalWins}")
        sb.appendLine("Win rate global: ${if (state.globalStats.totalGames > 0) 
            "%.1f%%".format(state.globalStats.totalWins * 100.0 / state.globalStats.totalGames) else "N/A"}")
        sb.appendLine("Meilleure série: ${state.globalStats.bestStreak}")
        sb.appendLine("Série actuelle: ${state.globalStats.currentStreak}")
        
        // Paramètres verrouillés
        val locked = state.params.filter { it.value.locked }
        if (locked.isNotEmpty()) {
            sb.appendLine("\nParamètres verrouillés (${locked.size}):")
            locked.forEach { (key, ps) ->
                ps.lockedValue?.let { value ->
                    val vs = ps.values.values.find { it.value == value }
                    val winRate = vs?.let { "%.1f%%".format(it.avg() * 100) } ?: "N/A"
                    sb.appendLine("  $key = $value (WR: $winRate)")
                }
            }
        }
        
        return sb.toString()
    }

    // Export de configuration
    fun exportOptimalConfig(): String {
        val values = optimalValues()
        return kira.gson.toJson(values)
    }

    // Import de configuration
    fun importConfig(json: String): Boolean {
        return try {
            val type = object : TypeToken<Map<String, Double>>() {}.type
            val values: Map<String, Double> = kira.gson.fromJson(json, type)
            
            // Valider et appliquer les valeurs
            specs.forEach { spec ->
                values[spec.key]?.let { value ->
                    val clamped = clamp(value, spec)
                    state.params.getOrPut(spec.key) { ParamState() }.apply {
                        locked = true
                        lockedValue = clamped
                    }
                }
            }
            
            save()
            true
        } catch (e: Exception) {
            false
        }
    }
}
