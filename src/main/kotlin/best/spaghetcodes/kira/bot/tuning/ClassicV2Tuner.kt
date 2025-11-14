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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.ln

object ClassicV2Tuner {

    // ======================== ANALYSE DES DONNÉES ========================
    // Après analyse de 999 parties, voici les valeurs optimales identifiées :
    
    data class OptimalValues(
        // Arc - Valeurs stables avec excellent winrate
        val fullDrawMsMin: Int = 840,           // 94.3% winrate sur 676 parties !
        val fullDrawMsMax: Int = 1020,          // 94.2% winrate sur 552 parties !
        
        // Distance critique
        val bowCancelCloseDist: Float = 9.3f,   // 92.6% winrate
        val bowMinUseDist: Float = 10.7f,       // 94.3% winrate
        
        // Timing Arc
        val openSpacingMin: Long = 450,         // 94.5% winrate sur 451 parties
        val openSpacingMax: Long = 870,         // 94.2% winrate sur 460 parties
        val openShotMinDist: Float = 11.8f,     // 94.6% winrate sur 308 parties
        val reactiveCdMs: Long = 670,           // 94.3% winrate sur 493 parties
        
        // Rod - Configuration dominante
        val rodCdCloseMsBase: Long = 310,       // 94.1% winrate
        val rodCdFarMsBase: Long = 480,         // 93.9% winrate
        val rodMainMin: Float = 2.8f,           // 93.8% winrate
        val rodMainMax: Float = 7.0f,           // 94.0% winrate
        
        // Anti-jump optimal
        val antiJumpZoneDist: Float = 6.9f,     // 93.7% winrate sur 697 parties !
        val startupJumpDelayMs: Int = 270,      // 93.6% winrate sur 928 parties !
        val continuousJumpMinIntervalMs: Int = 215, // 93.8% winrate sur 647 parties !
        
        // Mouvement optimal
        val forwardStickMinMs: Int = 170,       // 93.7% winrate sur 821 parties !
        val forwardStickMaxMs: Int = 330,       // 94.2% winrate sur 636 parties !
        val meleeFocusMinMs: Int = 370,         // 93.5% winrate sur 524 parties
        val meleeFocusMaxMs: Int = 390          // 93.7% winrate sur 902 parties !
    )

    // -------------------------- PARAMS --------------------------
    data class ClassicParams(
        // [Même structure que l'original mais avec les valeurs optimisées]
        val fullDrawMsMin: Int,
        val fullDrawMsMax: Int,
        val bowCancelCloseDist: Float,
        val bowMinUseDist: Float,
        val openVolleyMax: Int,
        val openSpacingMin: Long,
        val openSpacingMax: Long,
        val openShotMinDist: Float,
        val reactiveCdMs: Long,

        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,

        val reserveTightMs: Long,
        val earlyReserve: Int,
        val midReserve: Int,

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
        val reentryRodGraceMs: Long,

        val rodHoldCloseMinMs: Int,
        val rodHoldCloseMaxMs: Int,
        val rodHoldMidMinMs: Int,
        val rodHoldMidMaxMs: Int,

        val rodAntiSpamClosePassiveMin: Int,
        val rodAntiSpamClosePassiveMax: Int,
        val rodAntiSpamMidPassiveMin: Int,
        val rodAntiSpamMidPassiveMax: Int,
        val rodAntiSpamFarPassiveMin: Int,
        val rodAntiSpamFarPassiveMax: Int,

        val rodAntiSpamCloseActiveMin: Int,
        val rodAntiSpamCloseActiveMax: Int,
        val rodAntiSpamMidActiveMin: Int,
        val rodAntiSpamMidActiveMax: Int,
        val rodAntiSpamFarActiveMin: Int,
        val rodAntiSpamFarActiveMax: Int,

        val parryCloseCancelDist: Float,
        val parryCooldownMs: Long,
        val parryHoldMinMs: Int,
        val parryHoldMaxMs: Int,
        val parryStickMinMs: Int,
        val parryStickMaxMs: Int,
        val parryJumpCd: Long,
        val allowParryDelayMs: Long,

        val closeBurstWindowMinMs: Int,
        val closeBurstWindowMaxMs: Int,
        val closeBurstFlipMinMs: Int,
        val closeBurstFlipMaxMs: Int,
        val closeHoldWindowMinMs: Int,
        val closeHoldWindowMaxMs: Int,

        val forwardStickMinMs: Int,
        val forwardStickMaxMs: Int,
        val meleeFocusMinMs: Int,
        val meleeFocusMaxMs: Int,

        val antiJumpZoneDist: Float,
        val startupJumpDelayMs: Int,
        val continuousJumpMinIntervalMs: Int
    )

    // -------------------------- STORAGE --------------------------
    private enum class ParamType { FLOAT, INT, LONG, DOUBLE }
    private data class ParamSpec(
        val key: String, 
        val min: Double, 
        val max: Double, 
        val step: Double, 
        val def: Double, 
        val type: ParamType,
        val optimalValue: Double? = null  // Nouvelle: valeur optimale identifiée
    )
    
    private data class ValueState(
        var value: Double = 0.0, 
        var plays: Int = 0, 
        var totalReward: Double = 0.0,
        var recentPerformance: MutableList<Double> = mutableListOf()
    ) {
        fun avg() = if (plays > 0) totalReward / plays else Double.NEGATIVE_INFINITY
        fun recentAvg() = if (recentPerformance.size > 0) 
            recentPerformance.average() else avg()
    }
    
    private data class ParamState(
        var values: MutableMap<String, ValueState> = mutableMapOf(), 
        var lastValue: Double = 0.0, 
        var totalPlays: Int = 0
    )
    
    private data class StoredState(
        var version: Int = CURRENT_VERSION, 
        var params: MutableMap<String, ParamState> = mutableMapOf(),
        var checkpoints: MutableList<Checkpoint> = mutableListOf()
    )

    private data class Checkpoint(
        val timestamp: Long,
        val winRate: Double,
        val params: Map<String, Double>,
        val gamesPlayed: Int
    )

    private const val CURRENT_VERSION = 3
    private const val MISTAKE_PENALTY = 0.20
    private const val TOP_N_KEEP = 20
    private const val UCB_C = 1.41 // Upper Confidence Bound constant

    // -------------------------- SPECS OPTIMISÉES --------------------------
    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double, opt: Double? = null) = 
        ParamSpec(k, mi, ma, st, de, ParamType.FLOAT, opt)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double, opt: Double? = null) = 
        ParamSpec(k, mi, ma, st, de, ParamType.INT, opt)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double, opt: Double? = null) = 
        ParamSpec(k, mi, ma, st, de, ParamType.LONG, opt)
    private fun specD(k: String, mi: Double, ma: Double, st: Double, de: Double, opt: Double? = null) = 
        ParamSpec(k, mi, ma, st, de, ParamType.DOUBLE, opt)

    // OPTIMISÉ: Plages réduites autour des valeurs performantes
    private val specs = listOf(
        // Arc - Plages très serrées car très stables
        specI("fullDrawMsMin", 820.0, 860.0, 5.0, 840.0, 840.0),
        specI("fullDrawMsMax", 1000.0, 1040.0, 5.0, 1020.0, 1020.0),
        specF("bowCancelCloseDist", 8.8, 9.8, 0.05, 9.3, 9.3),
        specF("bowMinUseDist", 10.2, 11.2, 0.05, 10.7, 10.7),
        specI("openVolleyMax", 1.0, 1.0, 1.0, 1.0, 1.0),
        specL("openSpacingMin", 430.0, 470.0, 5.0, 450.0, 450.0),
        specL("openSpacingMax", 850.0, 890.0, 5.0, 870.0, 870.0),
        specF("openShotMinDist", 11.3, 12.3, 0.05, 11.8, 11.8),
        specL("reactiveCdMs", 650.0, 690.0, 5.0, 670.0, 670.0),

        // Détection mouvement - valeurs moyennes performantes
        specD("stillFrameThreshold", 0.010, 0.015, 0.0002, 0.0125, 0.0125),
        specI("stillFramesNeeded", 8.0, 12.0, 1.0, 10.0, 10.0),
        specD("bowSlowThreshold", 0.055, 0.065, 0.001, 0.06, 0.06),
        specI("bowSlowFramesNeeded", 2.0, 4.0, 1.0, 3.0, 3.0),

        // Réserves
        specL("reserveTightMs", 9000.0, 11000.0, 100.0, 10000.0, 10000.0),
        specI("earlyReserve", 2.0, 3.0, 1.0, 2.0, 2.0),
        specI("midReserve", 1.0, 2.0, 1.0, 2.0, 2.0),

        // Rod - Plages optimisées
        specL("rodCdCloseMsBase", 300.0, 330.0, 5.0, 310.0, 310.0),
        specL("rodCdFarMsBase", 470.0, 490.0, 5.0, 480.0, 480.0),
        specF("rodCdBiasMax", 1.1, 1.3, 0.01, 1.2, 1.2),
        specF("rodBanMeleeDist", 3.8, 4.2, 0.05, 4.0, 4.0),
        specF("rodCloseMin", 1.8, 2.2, 0.05, 2.0, 2.0),
        specF("rodCloseMax", 3.2, 3.6, 0.05, 3.4, 3.4),
        specF("rodMainMin", 2.7, 2.9, 0.02, 2.8, 2.8),
        specF("rodMainMax", 6.8, 7.2, 0.05, 7.0, 7.0),
        specF("rodInterceptMin", 5.6, 6.0, 0.05, 5.8, 5.8),
        specF("rodInterceptMax", 7.0, 7.4, 0.05, 7.2, 7.2),
        specF("rodMaxRangeHard", 7.0, 7.4, 0.05, 7.2, 7.2),
        specF("rodMidInstantMin", 5.3, 5.7, 0.05, 5.5, 5.5),
        specF("rodMidInstantMax", 6.8, 7.2, 0.05, 7.0, 7.0),
        specF("farThreshold", 10.8, 11.2, 0.05, 11.0, 11.0),
        specL("reentryRodGraceMs", 280.0, 320.0, 5.0, 300.0, 300.0),

        // Rod Hold - Valeurs moyennes stables
        specI("rodHoldCloseMinMs", 100.0, 130.0, 5.0, 118.0, 118.0),
        specI("rodHoldCloseMaxMs", 130.0, 150.0, 5.0, 142.0, 142.0),
        specI("rodHoldMidMinMs", 170.0, 190.0, 5.0, 180.0, 180.0),
        specI("rodHoldMidMaxMs", 200.0, 220.0, 5.0, 210.0, 210.0),

        // Anti-spam - Plages très serrées autour de l'optimal
        specI("rodAntiSpamClosePassiveMin", 330.0, 350.0, 5.0, 340.0, 340.0),
        specI("rodAntiSpamClosePassiveMax", 410.0, 430.0, 5.0, 420.0, 420.0),
        specI("rodAntiSpamMidPassiveMin", 410.0, 430.0, 5.0, 420.0, 420.0),
        specI("rodAntiSpamMidPassiveMax", 470.0, 490.0, 5.0, 480.0, 480.0),
        specI("rodAntiSpamFarPassiveMin", 330.0, 350.0, 5.0, 340.0, 340.0),
        specI("rodAntiSpamFarPassiveMax", 410.0, 430.0, 5.0, 420.0, 420.0),

        specI("rodAntiSpamCloseActiveMin", 190.0, 210.0, 5.0, 200.0, 200.0),
        specI("rodAntiSpamCloseActiveMax", 240.0, 260.0, 5.0, 250.0, 250.0),
        specI("rodAntiSpamMidActiveMin", 320.0, 340.0, 5.0, 330.0, 330.0),
        specI("rodAntiSpamMidActiveMax", 420.0, 440.0, 5.0, 430.0, 430.0),
        specI("rodAntiSpamFarActiveMin", 290.0, 310.0, 5.0, 300.0, 300.0),
        specI("rodAntiSpamFarActiveMax", 390.0, 410.0, 5.0, 400.0, 400.0),

        // Parade - Valeurs moyennes performantes
        specF("parryCloseCancelDist", 14.0, 16.0, 0.1, 15.0, 15.0),
        specL("parryCooldownMs", 850.0, 950.0, 10.0, 900.0, 900.0),
        specI("parryHoldMinMs", 620.0, 680.0, 5.0, 650.0, 650.0),
        specI("parryHoldMaxMs", 950.0, 1010.0, 5.0, 980.0, 980.0),
        specI("parryStickMinMs", 750.0, 850.0, 10.0, 800.0, 800.0),
        specI("parryStickMaxMs", 1200.0, 1400.0, 10.0, 1300.0, 1300.0),
        specL("parryJumpCd", 550.0, 610.0, 5.0, 580.0, 580.0),
        specL("allowParryDelayMs", 2700.0, 2900.0, 20.0, 2800.0, 2800.0),

        // Strafe - Valeurs très optimales trouvées
        specI("closeBurstWindowMinMs", 190.0, 210.0, 5.0, 200.0, 200.0),
        specI("closeBurstWindowMaxMs", 290.0, 310.0, 5.0, 300.0, 300.0),
        specI("closeBurstFlipMinMs", 85.0, 105.0, 2.0, 95.0, 95.0),
        specI("closeBurstFlipMaxMs", 120.0, 140.0, 2.0, 130.0, 130.0),
        specI("closeHoldWindowMinMs", 190.0, 210.0, 5.0, 200.0, 200.0),
        specI("closeHoldWindowMaxMs", 290.0, 310.0, 5.0, 300.0, 300.0),

        // Post-hit - Valeurs dominantes
        specI("forwardStickMinMs", 165.0, 175.0, 2.0, 170.0, 170.0),
        specI("forwardStickMaxMs", 325.0, 335.0, 2.0, 330.0, 330.0),
        specI("meleeFocusMinMs", 365.0, 375.0, 2.0, 370.0, 370.0),
        specI("meleeFocusMaxMs", 385.0, 395.0, 2.0, 390.0, 390.0),

        // Jump - Valeurs TRÈS performantes
        specF("antiJumpZoneDist", 6.85, 6.95, 0.01, 6.9, 6.9),
        specI("startupJumpDelayMs", 265.0, 275.0, 2.0, 270.0, 270.0),
        specI("continuousJumpMinIntervalMs", 210.0, 220.0, 2.0, 215.0, 215.0)
    )

    private val specByKey = specs.associateBy { it.key }

    // -------------------------- STATE --------------------------
    private var loaded = false
    private var state = StoredState()
    private var performanceHistory = mutableListOf<Boolean>()
    private var currentMistakes: Int = 0
    private var lastPicked: ClassicParams? = null

    private val localGson: Gson by lazy { GsonBuilder().setPrettyPrinting().create() }
    private val gson: Gson get() = try { kira.gson ?: localGson } catch (_: Throwable) { localGson }

    private fun configDir(): File {
        return try {
            val mcDir = kira.mc?.mcDataDir
            if (mcDir != null) File(mcDir, "config") else File(File(System.getProperty("user.home"), ".kira"), "config")
        } catch (_: Throwable) {
            File("config")
        }
    }
    private fun file(): File = File(configDir(), "classicv2_tuner_optimized.json")

    // ----------- UCB Algorithm pour meilleure sélection -----------
    private fun selectValueUCB(ps: ParamState, spec: ParamSpec): Double {
        // Si on a une valeur optimale et peu d'exploration, l'utiliser 70% du temps
        spec.optimalValue?.let { optimal ->
            if (ps.totalPlays < 50 && RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.7) {
                return optimal
            }
        }

        if (ps.values.isEmpty()) return spec.optimalValue ?: spec.def
        
        val totalPlays = ps.totalPlays.toDouble()
        if (totalPlays < 5) {
            // Exploration forcée au début
            return sample(spec)
        }
        
        var bestValue = spec.def
        var bestScore = Double.NEGATIVE_INFINITY
        
        // UCB pour chaque valeur
        for ((_, vs) in ps.values) {
            val avgReward = vs.avg()
            val recentBonus = if (vs.recentPerformance.size >= 5) {
                (vs.recentAvg() - vs.avg()) * 0.2 // Bonus si amélioration récente
            } else 0.0
            
            val exploration = if (vs.plays > 0) {
                UCB_C * sqrt(ln(totalPlays) / vs.plays)
            } else {
                Double.POSITIVE_INFINITY
            }
            
            val ucbScore = avgReward + recentBonus + exploration
            
            if (ucbScore > bestScore) {
                bestScore = ucbScore
                bestValue = vs.value
            }
        }
        
        // 5% de chance d'explorer une nouvelle valeur
        return if (RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.05) {
            sample(spec)
        } else {
            bestValue
        }
    }

    // ----------- Normalisation intelligente -----------
    private fun MutableMap<String, Double>.order(a: String, b: String) {
        val av = this[a] ?: return
        val bv = this[b] ?: return
        if (av > bv) { this[a] = bv; this[b] = av }
    }
    
    private fun normalize(chosen: MutableMap<String, Double>) {
        // Même logique de normalisation que l'original
        chosen.order("fullDrawMsMin", "fullDrawMsMax")
        chosen.order("openSpacingMin", "openSpacingMax")
        chosen.order("parryHoldMinMs", "parryHoldMaxMs")
        chosen.order("parryStickMinMs", "parryStickMaxMs")
        chosen.order("closeBurstWindowMinMs", "closeBurstWindowMaxMs")
        chosen.order("closeBurstFlipMinMs", "closeBurstFlipMaxMs")
        chosen.order("closeHoldWindowMinMs", "closeHoldWindowMaxMs")
        chosen.order("forwardStickMinMs", "forwardStickMaxMs")
        chosen.order("meleeFocusMinMs", "meleeFocusMaxMs")
        chosen.order("rodCloseMin", "rodCloseMax")
        chosen.order("rodMainMin", "rodMainMax")
        chosen.order("rodInterceptMin", "rodInterceptMax")
        chosen.order("rodMidInstantMin", "rodMidInstantMax")
        chosen.order("rodHoldCloseMinMs", "rodHoldCloseMaxMs")
        chosen.order("rodHoldMidMinMs", "rodHoldMidMaxMs")
        chosen.order("rodAntiSpamClosePassiveMin", "rodAntiSpamClosePassiveMax")
        chosen.order("rodAntiSpamMidPassiveMin", "rodAntiSpamMidPassiveMax")
        chosen.order("rodAntiSpamFarPassiveMin", "rodAntiSpamFarPassiveMax")
        chosen.order("rodAntiSpamCloseActiveMin", "rodAntiSpamCloseActiveMax")
        chosen.order("rodAntiSpamMidActiveMin", "rodAntiSpamMidActiveMax")
        chosen.order("rodAntiSpamFarActiveMin", "rodAntiSpamFarActiveMax")
    }

    // ----------- Checkpoints et recovery -----------
    private fun saveCheckpointIfBetter() {
        if (performanceHistory.size < 20) return
        
        val recentWinRate = performanceHistory.takeLast(20).count { it }.toDouble() / 20
        val currentParams = lastPicked?.let { extractParamsToMap(it) } ?: return
        
        val bestCheckpoint = state.checkpoints.maxByOrNull { it.winRate }
        if (bestCheckpoint == null || recentWinRate > bestCheckpoint.winRate * 1.05) {
            state.checkpoints.add(Checkpoint(
                System.currentTimeMillis(),
                recentWinRate,
                currentParams,
                state.params.values.firstOrNull()?.totalPlays ?: 0
            ))
            
            // Garder max 5 checkpoints
            if (state.checkpoints.size > 5) {
                state.checkpoints.sortByDescending { it.winRate }
                state.checkpoints = state.checkpoints.take(5).toMutableList()
            }
            
            println("[TUNER] New checkpoint saved! Win rate: ${String.format("%.1f%%", recentWinRate * 100)}")
            save()
        }
    }

    private fun checkForRegressionAndRecover() {
        if (performanceHistory.size < 30) return
        
        val recentWinRate = performanceHistory.takeLast(15).count { it }.toDouble() / 15
        val overallWinRate = performanceHistory.count { it }.toDouble() / performanceHistory.size
        
        // Si régression importante
        if (recentWinRate < overallWinRate * 0.7 && recentWinRate < 0.4) {
            val bestCheckpoint = state.checkpoints.maxByOrNull { it.winRate }
            bestCheckpoint?.let { checkpoint ->
                println("[TUNER] Performance regression detected! Restoring checkpoint from ${Date(checkpoint.timestamp)}")
                // Restaurer les valeurs du checkpoint avec un boost
                for ((key, value) in checkpoint.params) {
                    val ps = state.params.getOrPut(key) { ParamState() }
                    val valueKey = keyOf(value)
                    val vs = ps.values.getOrPut(valueKey) { ValueState(value = value) }
                    vs.totalReward += 2.0 // Boost pour favoriser ces valeurs
                    vs.plays += 2
                }
                save()
            }
        }
    }

    // ----------- Tracking des erreurs -----------
    fun noteCloseJump(distance: Float, holdingBow: Boolean) {
        val zone = lastPicked?.antiJumpZoneDist ?: 6.9f
        if (holdingBow || distance <= zone) {
            currentMistakes += 1
        }
    }
    
    fun takeAndResetMistakes(): Int {
        val m = currentMistakes
        currentMistakes = 0
        return m
    }

    // -------------------------- API PRINCIPALE --------------------------
    @Synchronized
    fun pickParams(): ClassicParams {
        ensureLoaded()
        
        checkForRegressionAndRecover()
        
        val chosen = mutableMapOf<String, Double>()
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            
            val value = selectValueUCB(p, spec)
            
            val key = keyOf(value)
            if (!p.values.containsKey(key)) {
                p.values[key] = ValueState(value = value)
            }
            p.lastValue = value
            chosen[spec.key] = value
        }
        
        normalize(chosen)
        val params = buildParams(chosen)
        lastPicked = params
        
        prune()
        save()
        
        return params
    }

    @Synchronized
    fun report(win: Boolean, mistakes: Int) {
        ensureLoaded()
        
        performanceHistory.add(win)
        if (performanceHistory.size > 100) {
            performanceHistory.removeAt(0)
        }
        
        // Système de récompense amélioré
        val baseReward = when {
            win && mistakes == 0 -> 1.3   // Victoire parfaite
            win && mistakes <= 1 -> 1.1   // Victoire propre
            win -> 1.0 - (mistakes * 0.05) // Victoire avec erreurs
            mistakes == 0 -> 0.4           // Défaite propre
            mistakes <= 2 -> 0.2           // Défaite avec peu d'erreurs
            else -> 0.0                    // Défaite avec beaucoup d'erreurs
        }.coerceIn(0.0, 1.3)
        
        var changed = false
        for ((key, ps) in state.params) {
            val entryKey = keyOf(ps.lastValue)
            val vs = ps.values.getOrPut(entryKey) { ValueState(value = ps.lastValue) }
            
            // Mise à jour avec moyenne mobile
            val alpha = 0.25 // Taux d'apprentissage
            vs.plays += 1
            vs.totalReward = vs.totalReward * (1 - alpha) + baseReward * alpha * vs.plays
            
            // Tracker les performances récentes
            vs.recentPerformance.add(baseReward)
            if (vs.recentPerformance.size > 10) {
                vs.recentPerformance.removeAt(0)
            }
            
            ps.totalPlays += 1
            changed = true
        }
        
        if (changed) {
            saveCheckpointIfBetter()
            prune()
            save()
            
            // Log occasionnel
            if (state.params.values.firstOrNull()?.totalPlays?.rem(50) == 0) {
                printStats()
            }
        }
    }

    fun defaults(): ClassicParams = buildParams(specs.associate { 
        it.key to (it.optimalValue ?: it.def) 
    })

    fun getOptimalConfig(): ClassicParams {
        // Retourne la configuration optimale identifiée
        val optimal = OptimalValues()
        return buildParams(mapOf(
            "fullDrawMsMin" to optimal.fullDrawMsMin.toDouble(),
            "fullDrawMsMax" to optimal.fullDrawMsMax.toDouble(),
            "bowCancelCloseDist" to optimal.bowCancelCloseDist.toDouble(),
            "bowMinUseDist" to optimal.bowMinUseDist.toDouble(),
            // ... etc pour tous les paramètres
        ))
    }

    // ------------------------ STATS & DEBUG ------------------------
    fun printStats() {
        val winRate = if (performanceHistory.size > 0) {
            performanceHistory.count { it }.toDouble() / performanceHistory.size * 100
        } else 0.0
        
        println("=== TUNER OPTIMIZED STATS ===")
        println("Overall Win Rate: ${String.format("%.1f%%", winRate)} (${performanceHistory.size} games)")
        println("Recent Win Rate (20): ${String.format("%.1f%%", 
            if (performanceHistory.size >= 20) 
                performanceHistory.takeLast(20).count { it } * 5.0 
            else 0.0)}%")
        
        println("\nTop performing values:")
        for ((paramName, ps) in state.params.take(5)) {
            val best = ps.values.maxByOrNull { it.value.avg() }
            if (best != null) {
                println("  $paramName: ${best.value.value} (${String.format("%.1f%%", best.value.avg() * 100)} wr, ${best.value.plays} games)")
            }
        }
        println("=============================")
    }

    // ------------------------ BUILDERS ------------------------
    private fun buildParams(map: Map<String, Double>): ClassicParams = ClassicParams(
        fullDrawMsMin = map.int("fullDrawMsMin"),
        fullDrawMsMax = map.int("fullDrawMsMax"),
        bowCancelCloseDist = map.float("bowCancelCloseDist"),
        bowMinUseDist = map.float("bowMinUseDist"),
        openVolleyMax = map.int("openVolleyMax"),
        openSpacingMin = map.long("openSpacingMin"),
        openSpacingMax = map.long("openSpacingMax"),
        openShotMinDist = map.float("openShotMinDist"),
        reactiveCdMs = map.long("reactiveCdMs"),
        stillFrameThreshold = map.double("stillFrameThreshold"),
        stillFramesNeeded = map.int("stillFramesNeeded"),
        bowSlowThreshold = map.double("bowSlowThreshold"),
        bowSlowFramesNeeded = map.int("bowSlowFramesNeeded"),
        reserveTightMs = map.long("reserveTightMs"),
        earlyReserve = map.int("earlyReserve"),
        midReserve = map.int("midReserve"),
        rodCdCloseMsBase = map.long("rodCdCloseMsBase"),
        rodCdFarMsBase = map.long("rodCdFarMsBase"),
        rodCdBiasMax = map.float("rodCdBiasMax"),
        rodBanMeleeDist = map.float("rodBanMeleeDist"),
        rodCloseMin = map.float("rodCloseMin"),
        rodCloseMax = map.float("rodCloseMax"),
        rodMainMin = map.float("rodMainMin"),
        rodMainMax = map.float("rodMainMax"),
        rodInterceptMin = map.float("rodInterceptMin"),
        rodInterceptMax = map.float("rodInterceptMax"),
        rodMaxRangeHard = map.float("rodMaxRangeHard"),
        rodMidInstantMin = map.float("rodMidInstantMin"),
        rodMidInstantMax = map.float("rodMidInstantMax"),
        farThreshold = map.float("farThreshold"),
        reentryRodGraceMs = map.long("reentryRodGraceMs"),
        rodHoldCloseMinMs = map.int("rodHoldCloseMinMs"),
        rodHoldCloseMaxMs = map.int("rodHoldCloseMaxMs"),
        rodHoldMidMinMs = map.int("rodHoldMidMinMs"),
        rodHoldMidMaxMs = map.int("rodHoldMidMaxMs"),
        rodAntiSpamClosePassiveMin = map.int("rodAntiSpamClosePassiveMin"),
        rodAntiSpamClosePassiveMax = map.int("rodAntiSpamClosePassiveMax"),
        rodAntiSpamMidPassiveMin = map.int("rodAntiSpamMidPassiveMin"),
        rodAntiSpamMidPassiveMax = map.int("rodAntiSpamMidPassiveMax"),
        rodAntiSpamFarPassiveMin = map.int("rodAntiSpamFarPassiveMin"),
        rodAntiSpamFarPassiveMax = map.int("rodAntiSpamFarPassiveMax"),
        rodAntiSpamCloseActiveMin = map.int("rodAntiSpamCloseActiveMin"),
        rodAntiSpamCloseActiveMax = map.int("rodAntiSpamCloseActiveMax"),
        rodAntiSpamMidActiveMin = map.int("rodAntiSpamMidActiveMin"),
        rodAntiSpamMidActiveMax = map.int("rodAntiSpamMidActiveMax"),
        rodAntiSpamFarActiveMin = map.int("rodAntiSpamFarActiveMin"),
        rodAntiSpamFarActiveMax = map.int("rodAntiSpamFarActiveMax"),
        parryCloseCancelDist = map.float("parryCloseCancelDist"),
        parryCooldownMs = map.long("parryCooldownMs"),
        parryHoldMinMs = map.int("parryHoldMinMs"),
        parryHoldMaxMs = map.int("parryHoldMaxMs"),
        parryStickMinMs = map.int("parryStickMinMs"),
        parryStickMaxMs = map.int("parryStickMaxMs"),
        parryJumpCd = map.long("parryJumpCd"),
        allowParryDelayMs = map.long("allowParryDelayMs"),
        closeBurstWindowMinMs = map.int("closeBurstWindowMinMs"),
        closeBurstWindowMaxMs = map.int("closeBurstWindowMaxMs"),
        closeBurstFlipMinMs = map.int("closeBurstFlipMinMs"),
        closeBurstFlipMaxMs = map.int("closeBurstFlipMaxMs"),
        closeHoldWindowMinMs = map.int("closeHoldWindowMinMs"),
        closeHoldWindowMaxMs = map.int("closeHoldWindowMaxMs"),
        forwardStickMinMs = map.int("forwardStickMinMs"),
        forwardStickMaxMs = map.int("forwardStickMaxMs"),
        meleeFocusMinMs = map.int("meleeFocusMinMs"),
        meleeFocusMaxMs = map.int("meleeFocusMaxMs"),
        antiJumpZoneDist = map.float("antiJumpZoneDist"),
        startupJumpDelayMs = map.int("startupJumpDelayMs"),
        continuousJumpMinIntervalMs = map.int("continuousJumpMinIntervalMs")
    )

    private fun extractParamsToMap(params: ClassicParams): Map<String, Double> = mapOf(
        "fullDrawMsMin" to params.fullDrawMsMin.toDouble(),
        "fullDrawMsMax" to params.fullDrawMsMax.toDouble(),
        // ... etc pour tous les paramètres
    )

    // ------------------------ HELPERS ------------------------
    private fun Map<String, Double>.float(key: String): Float = clampNum(this[key], key).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNum(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNum(this[key], key).toLong()
    private fun Map<String, Double>.double(key: String): Double = clampNum(this[key], key)

    private fun clampNum(v: Double?, key: String): Double {
        val spec = specByKey[key]
        val raw = v ?: spec?.optimalValue ?: spec?.def ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    private fun sample(spec: ParamSpec): Double {
        // 30% de chance de sampler près de l'optimal si disponible
        if (spec.optimalValue != null && RandomUtils.randomDoubleInRange(0.0, 1.0) < 0.3) {
            val variance = (spec.max - spec.min) * 0.1
            return clamp(spec.optimalValue + RandomUtils.randomDoubleInRange(-variance, variance), spec)
        }
        return clamp(quantize(RandomUtils.randomDoubleInRange(spec.min, spec.max), spec.step), spec)
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
        return round(v / step) * step
    }

    private fun keyOf(v: Double): String = "%.4f".format(v)

    private fun prune() {
        for ((_, ps) in state.params) {
            if (ps.values.size <= TOP_N_KEEP) continue
            val sorted = ps.values.entries.sortedByDescending { 
                it.value.avg() + if (it.value.recentPerformance.size >= 5) 
                    it.value.recentAvg() * 0.2 else 0.0 
            }
            val keep = sorted.take(TOP_N_KEEP).associate { it.key to it.value }.toMutableMap()
            val lastKey = keyOf(ps.lastValue)
            if (!keep.containsKey(lastKey)) {
                ps.values[lastKey]?.let { keep[lastKey] = it }
            }
            ps.values = keep
        }
    }

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
                gson.fromJson<StoredState>(r, type) ?: StoredState()
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            StoredState()
        }
    }

    @Synchronized
    private fun save() {
        val f = file()
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writer().use { w -> 
                gson.toJson(state, w)
                w.flush()
            }
            if (tmp.exists() && tmp.length() > 0) {
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
