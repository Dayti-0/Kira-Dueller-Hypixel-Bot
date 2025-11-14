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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

object ClassicV2Tuner {

    // -------------------------- PARAMS --------------------------
    data class ClassicParams(
        // BOW (ouverture & réactif)
        val fullDrawMsMin: Int,
        val fullDrawMsMax: Int,
        val bowCancelCloseDist: Float,
        val bowMinUseDist: Float,
        val openVolleyMax: Int,
        val openSpacingMin: Long,
        val openSpacingMax: Long,
        val openShotMinDist: Float,
        val reactiveCdMs: Long,

        // Détection mouvement
        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,

        // Réserves flèches
        val reserveTightMs: Long,
        val earlyReserve: Int,
        val midReserve: Int,

        // ROD (cd, ranges)
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

        // ROD (hold + anti-spam)
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

        // PARADE épée
        val parryCloseCancelDist: Float,
        val parryCooldownMs: Long,
        val parryHoldMinMs: Int,
        val parryHoldMaxMs: Int,
        val parryStickMinMs: Int,
        val parryStickMaxMs: Int,
        val parryJumpCd: Long,
        val allowParryDelayMs: Long,

        // STRAFE proche
        val closeBurstWindowMinMs: Int,
        val closeBurstWindowMaxMs: Int,
        val closeBurstFlipMinMs: Int,
        val closeBurstFlipMaxMs: Int,
        val closeHoldWindowMinMs: Int,
        val closeHoldWindowMaxMs: Int,

        // POST-HIT
        val forwardStickMinMs: Int,
        val forwardStickMaxMs: Int,
        val meleeFocusMinMs: Int,
        val meleeFocusMaxMs: Int,

        // ---- NOUVEAU : tuning des sauts ----
        val antiJumpZoneDist: Float,          // Interdit tout saut si distance <= ceci
        val startupJumpDelayMs: Int,          // Délai du premier saut (ms) ~ 0.3s
        val continuousJumpMinIntervalMs: Int  // Intervalle mini entre deux "single jumps"
    )

    // -------------------------- STORAGE --------------------------
    private enum class ParamType { FLOAT, INT, LONG, DOUBLE }
    
    // Catégorie de convergence par paramètre
    private enum class ConvergenceLevel { LOCKED, CONVERGED, MODERATE, EXPLORE }
    
    private data class ParamSpec(
        val key: String, 
        val min: Double, 
        val max: Double, 
        val step: Double, 
        val def: Double, 
        val type: ParamType,
        val convergence: ConvergenceLevel = ConvergenceLevel.MODERATE
    )
    
    private data class ValueState(var value: Double = 0.0, var plays: Int = 0, var totalReward: Double = 0.0) {
        fun avg() = if (plays > 0) totalReward / plays else Double.NEGATIVE_INFINITY
    }
    
    private data class ParamState(var values: MutableMap<String, ValueState> = mutableMapOf(), var lastValue: Double = 0.0, var totalPlays: Int = 0)
    private data class StoredState(var version: Int = CURRENT_VERSION, var params: MutableMap<String, ParamState> = mutableMapOf())

    private const val CURRENT_VERSION = 2
    private const val MISTAKE_PENALTY = 0.25
    private const val TOP_N_KEEP = 16
    private const val UCB_EXPLORATION_FACTOR = 0.5

    // -------------------------- SPECS --------------------------
    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double, conv: ConvergenceLevel = ConvergenceLevel.MODERATE) = 
        ParamSpec(k, mi, ma, st, de, ParamType.FLOAT, conv)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double, conv: ConvergenceLevel = ConvergenceLevel.MODERATE) = 
        ParamSpec(k, mi, ma, st, de, ParamType.INT, conv)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double, conv: ConvergenceLevel = ConvergenceLevel.MODERATE) = 
        ParamSpec(k, mi, ma, st, de, ParamType.LONG, conv)
    private fun specD(k: String, mi: Double, ma: Double, st: Double, de: Double, conv: ConvergenceLevel = ConvergenceLevel.MODERATE) = 
        ParamSpec(k, mi, ma, st, de, ParamType.DOUBLE, conv)

    // SPECS CORRIGÉES - antiJumpZoneDist AUGMENTÉE à 8.0
    private val specs = listOf(
        // BOW - ULTRA CONVERGED
        specI("fullDrawMsMin", 820.0, 860.0, 5.0, 840.0, ConvergenceLevel.LOCKED),
        specI("fullDrawMsMax", 1000.0, 1040.0, 5.0, 1020.0, ConvergenceLevel.LOCKED),
        specF("bowCancelCloseDist", 6.0, 10.0, 0.1, 6.3, ConvergenceLevel.MODERATE),
        specF("bowMinUseDist", 10.4, 11.0, 0.1, 10.7, ConvergenceLevel.CONVERGED),
        
        specI("openVolleyMax", 1.0, 1.0, 1.0, 1.0, ConvergenceLevel.LOCKED),
        
        specL("openSpacingMin", 420.0, 480.0, 10.0, 450.0, ConvergenceLevel.CONVERGED),
        specL("openSpacingMax", 840.0, 900.0, 10.0, 870.0, ConvergenceLevel.CONVERGED),
        specF("openShotMinDist", 11.5, 12.1, 0.1, 11.8, ConvergenceLevel.CONVERGED),
        specL("reactiveCdMs", 640.0, 700.0, 10.0, 670.0, ConvergenceLevel.CONVERGED),

        // Détection mouvement
        specD("stillFrameThreshold", 0.008, 0.02, 0.0005, 0.0125, ConvergenceLevel.MODERATE),
        specI("stillFramesNeeded", 6.0, 16.0, 1.0, 10.0, ConvergenceLevel.MODERATE),
        specD("bowSlowThreshold", 0.04, 0.09, 0.002, 0.06, ConvergenceLevel.MODERATE),
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 3.0, ConvergenceLevel.MODERATE),

        // Réserves
        specL("reserveTightMs", 7000.0, 13000.0, 100.0, 10000.0, ConvergenceLevel.MODERATE),
        specI("earlyReserve", 2.0, 5.0, 1.0, 3.0, ConvergenceLevel.MODERATE),
        specI("midReserve", 1.0, 4.0, 1.0, 2.0, ConvergenceLevel.MODERATE),

        // ROD - EXPLORATION MODÉRÉE
        specL("rodCdCloseMsBase", 290.0, 330.0, 10.0, 310.0, ConvergenceLevel.MODERATE),
        specL("rodCdFarMsBase", 460.0, 500.0, 10.0, 480.0, ConvergenceLevel.MODERATE),
        specF("rodCdBiasMax", 1.05, 1.5, 0.01, 1.25, ConvergenceLevel.MODERATE),
        specF("rodBanMeleeDist", 3.0, 5.0, 0.05, 4.0, ConvergenceLevel.MODERATE),
        
        specF("rodCloseMin", 1.8, 2.4, 0.05, 2.0, ConvergenceLevel.EXPLORE),
        specF("rodCloseMax", 2.8, 3.6, 0.05, 3.2, ConvergenceLevel.EXPLORE),
        specF("rodMainMin", 2.6, 3.2, 0.05, 2.8, ConvergenceLevel.EXPLORE),
        specF("rodMainMax", 6.5, 7.5, 0.05, 7.0, ConvergenceLevel.EXPLORE),
        specF("rodInterceptMin", 4.8, 6.4, 0.05, 5.8, ConvergenceLevel.EXPLORE),
        specF("rodInterceptMax", 6.2, 8.2, 0.05, 7.2, ConvergenceLevel.EXPLORE),
        specF("rodMaxRangeHard", 6.5, 8.0, 0.05, 7.2, ConvergenceLevel.EXPLORE),
        specF("rodMidInstantMin", 4.8, 6.2, 0.05, 5.5, ConvergenceLevel.EXPLORE),
        specF("rodMidInstantMax", 6.2, 7.6, 0.05, 7.0, ConvergenceLevel.EXPLORE),
        specF("farThreshold", 9.0, 14.0, 0.1, 11.0, ConvergenceLevel.MODERATE),
        specL("reentryRodGraceMs", 200.0, 500.0, 10.0, 300.0, ConvergenceLevel.MODERATE),

        specI("rodHoldCloseMinMs", 90.0, 160.0, 5.0, 118.0, ConvergenceLevel.MODERATE),
        specI("rodHoldCloseMaxMs", 110.0, 190.0, 5.0, 142.0, ConvergenceLevel.MODERATE),
        specI("rodHoldMidMinMs", 160.0, 260.0, 5.0, 208.0, ConvergenceLevel.MODERATE),
        specI("rodHoldMidMaxMs", 180.0, 300.0, 5.0, 232.0, ConvergenceLevel.MODERATE),

        // Rod anti-spam
        specI("rodAntiSpamClosePassiveMin", 260.0, 420.0, 10.0, 340.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamClosePassiveMax", 340.0, 520.0, 10.0, 420.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamMidPassiveMin", 400.0, 460.0, 10.0, 420.0, ConvergenceLevel.MODERATE),
        specI("rodAntiSpamMidPassiveMax", 520.0, 820.0, 10.0, 680.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamFarPassiveMin", 400.0, 640.0, 10.0, 520.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamFarPassiveMax", 540.0, 860.0, 10.0, 700.0, ConvergenceLevel.EXPLORE),

        specI("rodAntiSpamCloseActiveMin", 180.0, 220.0, 10.0, 200.0, ConvergenceLevel.MODERATE),
        specI("rodAntiSpamCloseActiveMax", 260.0, 420.0, 10.0, 320.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamMidActiveMin", 280.0, 480.0, 10.0, 380.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamMidActiveMax", 400.0, 640.0, 10.0, 520.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamFarActiveMin", 320.0, 520.0, 10.0, 400.0, ConvergenceLevel.EXPLORE),
        specI("rodAntiSpamFarActiveMax", 420.0, 700.0, 10.0, 560.0, ConvergenceLevel.EXPLORE),

        // PARRY
        specF("parryCloseCancelDist", 11.0, 19.0, 0.2, 15.0, ConvergenceLevel.EXPLORE),
        specL("parryCooldownMs", 600.0, 1200.0, 10.0, 900.0, ConvergenceLevel.EXPLORE),
        specI("parryHoldMinMs", 520.0, 820.0, 10.0, 650.0, ConvergenceLevel.EXPLORE),
        specI("parryHoldMaxMs", 820.0, 1200.0, 10.0, 980.0, ConvergenceLevel.EXPLORE),
        specI("parryStickMinMs", 720.0, 1100.0, 10.0, 900.0, ConvergenceLevel.EXPLORE),
        specI("parryStickMaxMs", 1200.0, 1800.0, 10.0, 1500.0, ConvergenceLevel.EXPLORE),
        specL("parryJumpCd", 400.0, 800.0, 10.0, 580.0, ConvergenceLevel.EXPLORE),
        specL("allowParryDelayMs", 2000.0, 3600.0, 50.0, 2800.0, ConvergenceLevel.EXPLORE),

        // STRAFE PROCHE
        specI("closeBurstWindowMinMs", 185.0, 215.0, 10.0, 200.0, ConvergenceLevel.CONVERGED),
        specI("closeBurstWindowMaxMs", 280.0, 320.0, 10.0, 300.0, ConvergenceLevel.CONVERGED),
        specI("closeBurstFlipMinMs", 40.0, 100.0, 5.0, 60.0, ConvergenceLevel.MODERATE),
        specI("closeBurstFlipMaxMs", 80.0, 160.0, 5.0, 110.0, ConvergenceLevel.MODERATE),
        specI("closeHoldWindowMinMs", 185.0, 215.0, 10.0, 200.0, ConvergenceLevel.CONVERGED),
        specI("closeHoldWindowMaxMs", 280.0, 320.0, 10.0, 300.0, ConvergenceLevel.CONVERGED),

        // POST-HIT
        specI("forwardStickMinMs", 160.0, 180.0, 5.0, 170.0, ConvergenceLevel.LOCKED),
        specI("forwardStickMaxMs", 315.0, 345.0, 5.0, 330.0, ConvergenceLevel.LOCKED),
        specI("meleeFocusMinMs", 360.0, 380.0, 10.0, 370.0, ConvergenceLevel.CONVERGED),
        specI("meleeFocusMaxMs", 380.0, 400.0, 5.0, 390.0, ConvergenceLevel.LOCKED),

        // JUMP - CORRECTION CRITIQUE : antiJumpZoneDist AUGMENTÉE À 8.0
        specF("antiJumpZoneDist", 7.5, 8.5, 0.1, 8.0, ConvergenceLevel.EXPLORE),             // *** CORRIGÉ ***
        specI("startupJumpDelayMs", 260.0, 280.0, 5.0, 270.0, ConvergenceLevel.LOCKED),
        specI("continuousJumpMinIntervalMs", 205.0, 225.0, 5.0, 215.0, ConvergenceLevel.LOCKED)
    )

    private val specByKey = specs.associateBy { it.key }

    // -------------------------- STATE --------------------------
    private var loaded = false
    private var state = StoredState()

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
    private fun file(): File = File(configDir(), "classicv2_tuner.json")

    // ----------- Normalisation anti-crash des paires -----------
    private fun MutableMap<String, Double>.order(a: String, b: String) {
        val av = this[a] ?: return
        val bv = this[b] ?: return
        if (av > bv) { this[a] = bv; this[b] = av }
    }
    
    private fun normalize(chosen: MutableMap<String, Double>) {
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

    // ----------- Epsilon par niveau de convergence -----------
    private fun epsilonForSpec(spec: ParamSpec, totalPlays: Int): Double {
        val baseEpsilon = when (spec.convergence) {
            ConvergenceLevel.LOCKED -> 0.01
            ConvergenceLevel.CONVERGED -> 0.03
            ConvergenceLevel.MODERATE -> 0.08
            ConvergenceLevel.EXPLORE -> 0.15
        }
        
        val minEpsilon = when (spec.convergence) {
            ConvergenceLevel.LOCKED -> 0.005
            ConvergenceLevel.CONVERGED -> 0.01
            ConvergenceLevel.MODERATE -> 0.03
            ConvergenceLevel.EXPLORE -> 0.08
        }
        
        val decay = totalPlays / 60.0
        return max(minEpsilon, baseEpsilon / (1.0 + decay))
    }
    
    private fun exploreNow(epsilon: Double): Boolean =
        RandomUtils.randomDoubleInRange(0.0, 1.0) < epsilon

    // ----------- UCB selection -----------
    private fun ucbScore(vs: ValueState, totalPlays: Int): Double {
        if (vs.plays == 0) return Double.POSITIVE_INFINITY
        val exploitation = vs.avg()
        val exploration = UCB_EXPLORATION_FACTOR * sqrt(ln(totalPlays.toDouble()) / vs.plays)
        return exploitation + exploration
    }

    // ----------- Pruning -----------
    private fun prune() {
        for ((_, ps) in state.params) {
            if (ps.values.size <= TOP_N_KEEP) continue
            val sorted = ps.values.entries.sortedByDescending { it.value.avg() }
            val keep = sorted.take(TOP_N_KEEP).associate { it.key to it.value }.toMutableMap()
            val lastKey = keyOf(ps.lastValue)
            if (!keep.containsKey(lastKey)) {
                ps.values[lastKey]?.let { keep[lastKey] = it }
            }
            ps.values = keep
        }
    }

    // ----------- Hooks -----------
    private var currentMistakes: Int = 0
    private var lastPicked: ClassicParams? = null

    fun noteCloseJump(distance: Float, holdingBow: Boolean) {
        val zone = lastPicked?.antiJumpZoneDist ?: 8.0f
        if (holdingBow || distance <= zone) {
            currentMistakes += 1
        }
    }
    
    fun takeAndResetMistakes(): Int {
        val m = currentMistakes
        currentMistakes = 0
        return m
    }

    // -------------------------- API --------------------------
    @Synchronized
    fun pickParams(): ClassicParams {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()
        
        for (spec in specs) {
            val p = state.params.getOrPut(spec.key) { ParamState() }
            val eps = epsilonForSpec(spec, p.totalPlays)
            
            val value = when {
                p.values.isEmpty() -> spec.def
                exploreNow(eps) -> sample(spec)
                else -> {
                    val bestEntry = p.values.entries.maxByOrNull { 
                        ucbScore(it.value, p.totalPlays) 
                    }
                    bestEntry?.value?.value ?: spec.def
                }
            }
            
            val key = keyOf(value)
            if (!p.values.containsKey(key)) p.values[key] = ValueState(value = value)
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
        val rewardBase = if (win) 1.0 else 0.0
        val reward = (rewardBase - mistakes * MISTAKE_PENALTY).coerceAtLeast(0.0)
        var changed = false
        
        for ((_, ps) in state.params) {
            val entryKey = keyOf(ps.lastValue)
            val vs = ps.values.getOrPut(entryKey) { ValueState(value = ps.lastValue) }
            vs.plays += 1
            vs.totalReward += reward
            ps.totalPlays += 1
            changed = true
        }
        
        if (changed) {
            prune()
            save()
        }
    }

    fun defaults(): ClassicParams = buildParams(specs.associate { it.key to it.def })

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

    // ------------------------ HELPERS ------------------------
    private fun Map<String, Double>.float(key: String): Float = clampNum(this[key], key).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNum(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNum(this[key], key).toLong()
    private fun Map<String, Double>.double(key: String): Double = clampNum(this[key], key)

    private fun clampNum(v: Double?, key: String): Double {
        val spec = specByKey[key]
        val raw = v ?: spec?.def ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    private fun sample(spec: ParamSpec): Double =
        clamp(quantize(RandomUtils.randomDoubleInRange(spec.min, spec.max), if (spec.step <= 0.0) 1.0 else spec.step), spec)

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
            tryBackupCorrupt(f, ex)
            StoredState()
        }
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
        } catch (_: Exception) {
        }
    }

    private fun tryBackupCorrupt(f: File, ex: Exception) {
        try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss")
            val bak = File(f.parentFile, f.nameWithoutExtension + "_corrupt_" + sdf.format(Date()) + ".bak.json")
            f.copyTo(bak, overwrite = true)
            f.delete()
        } catch (_: IOException) {
        }
        ex.printStackTrace()
    }
}
