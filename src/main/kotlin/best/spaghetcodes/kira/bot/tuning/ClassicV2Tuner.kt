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
import kotlin.math.round

object ClassicV2Tuner {

    // -------------------------- PARAMS --------------------------
    data class ClassicParams(
        // BOW (ouverture & réactif)
        val fullDrawMsMin: Int,
        val fullDrawMsMax: Int,
        val bowCancelCloseDist: Float,
        val bowMinUseDist: Float,
        val bowAimPitchBias: Float,
        val bowDrawParryEnabled: Int,
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
        val antiJumpZoneDist: Float,
        val startupJumpDelayMs: Int,
        val continuousJumpMinIntervalMs: Int
    )

    // -------------------------- STORAGE --------------------------
    private enum class ParamType { FLOAT, INT, LONG, DOUBLE }
    private data class ParamSpec(val key: String, val min: Double, val max: Double, val step: Double, val def: Double, val type: ParamType)
    private data class ParamState(
        var values: MutableList<Double> = mutableListOf(),
        var lastValue: Double = 0.0,
        var bandit: UcbBanditState? = null,
        var lastArm: Int = 0
    )

    private data class StoredState(var version: Int = CURRENT_VERSION, var params: MutableMap<String, ParamState> = mutableMapOf())

    private const val CURRENT_VERSION = 3
    private const val TOP_N_KEEP = 16
    private const val BANDIT_DECAY_FACTOR = 0.999

    // -------------------------- SPECS --------------------------
    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.FLOAT)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.INT)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.LONG)
    private fun specD(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.DOUBLE)

    // VERSION HYBRID : Plages larges de l'ancien + defaults ajustés d'après les données
    private val specs = listOf(
        // BOW - V5 basé sur nouvelle session
        specI("fullDrawMsMin", 770.0, 810.0, 10.0, 780.0),        // 800→780 (64% gain)
        specI("fullDrawMsMax", 1070.0, 1110.0, 10.0, 1080.0),     // 1100→1080 (91% gain, LOW conf)
        specF("bowCancelCloseDist", 6.0, 8.0, 0.1, 6.8),          // 8.0→6.8 ⭐ HIGH conf
        specF("bowMinUseDist", 7.0, 10.0, 0.1, 8.9),              // 10.5→8.9 (215% gain)
        specF("bowAimPitchBias", -0.3, 0.1, 0.05, -0.1),          // 0.0→-0.1 ⭐ HIGH conf, plage resserrée
        specI("bowDrawParryEnabled", 0.0, 1.0, 1.0, 1.0),         // bascule heuristique parade arc

        specI("openVolleyMax", 1.0, 1.0, 1.0, 1.0),                // verrouillé (prouvé)
        
        specL("openSpacingMin", 650.0, 850.0, 10.0, 750.0),       // 450→750 (données)
        specL("openSpacingMax", 850.0, 920.0, 10.0, 900.0),
        specF("openShotMinDist", 9.5, 11.0, 0.1, 10.1),           // 11.5→10.1
        specL("reactiveCdMs", 550.0, 700.0, 10.0, 610.0),         // 670→610 (218% gain)

        // Détection mouvement - ajustements
        specD("stillFrameThreshold", 0.015, 0.022, 0.0005, 0.0195), // 0.0125→0.0195 ⭐ HIGH
        specI("stillFramesNeeded", 6.0, 16.0, 1.0, 10.0),          // inchangé
        specD("bowSlowThreshold", 0.04, 0.07, 0.002, 0.05),        // 0.06→0.05 ⭐ HIGH conf
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 2.0),  // était 3.0

        // Réserves - ajustements
        specL("reserveTightMs", 11000.0, 13000.0, 100.0, 12300.0), // 10000→12300 ⭐ HIGH
        specI("earlyReserve", 2.0, 4.0, 1.0, 3.0),                 // 4→3
        specI("midReserve", 2.0, 4.0, 1.0, 3.0),                   // 2→3 (+45%)

        // ROD - Cooldowns plus conservateurs
        specL("rodCdCloseMsBase", 320.0, 400.0, 10.0, 340.0),      // 310→340 ⭐ HIGH
        specL("rodCdFarMsBase", 480.0, 580.0, 10.0, 520.0),        // 480→520 ⭐ HIGH
        specF("rodCdBiasMax", 1.05, 1.5, 0.01, 1.25),              // inchangé
        specF("rodBanMeleeDist", 4.2, 5.0, 0.05, 4.85),  // était 4.5
        specF("rodCloseMin", 1.6, 2.6, 0.05, 2.0),                 // inchangé
        specF("rodCloseMax", 2.6, 4.0, 0.05, 3.4),                 // inchangé
        specF("rodMainMin", 3.2, 3.8, 0.05, 3.55),                 // 2.8→3.55 ⭐ HIGH, plage resserrée
        specF("rodMainMax", 6.0, 7.0, 0.05, 6.5),                  // 7.0→6.5 ⭐ HIGH
        specF("rodInterceptMin", 4.8, 6.4, 0.05, 5.8),             // inchangé
        specF("rodInterceptMax", 6.2, 8.2, 0.05, 7.2),             // inchangé
        specF("rodMaxRangeHard", 6.5, 8.0, 0.05, 7.2),             // inchangé
        specF("rodMidInstantMin", 4.8, 6.2, 0.05, 5.5),            // inchangé
        specF("rodMidInstantMax", 6.2, 7.6, 0.05, 7.0),            // inchangé
        specF("farThreshold", 9.0, 14.0, 0.1, 11.0),               // inchangé
        specL("reentryRodGraceMs", 200.0, 500.0, 10.0, 300.0),     // inchangé

        specI("rodHoldCloseMinMs", 90.0, 160.0, 5.0, 118.0),       // inchangé
        specI("rodHoldCloseMaxMs", 110.0, 190.0, 5.0, 142.0),      // inchangé
        specI("rodHoldMidMinMs", 180.0, 220.0, 5.0, 190.0),        // 208→190
        specI("rodHoldMidMaxMs", 180.0, 300.0, 5.0, 232.0),        // inchangé

        // Rod anti-spam - Plages larges, defaults ajustés
        specI("rodAntiSpamClosePassiveMin", 340.0, 400.0, 10.0, 350.0),
        specI("rodAntiSpamClosePassiveMax", 340.0, 520.0, 10.0, 340.0),
        specI("rodAntiSpamMidPassiveMin", 400.0, 640.0, 10.0, 420.0),    // default 520→420 (données)
        specI("rodAntiSpamMidPassiveMax", 520.0, 820.0, 10.0, 680.0),
        specI("rodAntiSpamFarPassiveMin", 400.0, 640.0, 10.0, 520.0),
        specI("rodAntiSpamFarPassiveMax", 540.0, 860.0, 10.0, 700.0),

        specI("rodAntiSpamCloseActiveMin", 270.0, 320.0, 10.0, 290.0),   // 200→290
        specI("rodAntiSpamCloseActiveMax", 260.0, 420.0, 10.0, 320.0),
        specI("rodAntiSpamMidActiveMin", 280.0, 480.0, 10.0, 380.0),
        specI("rodAntiSpamMidActiveMax", 400.0, 640.0, 10.0, 520.0),
        specI("rodAntiSpamFarActiveMin", 320.0, 520.0, 10.0, 400.0),
        specI("rodAntiSpamFarActiveMax", 420.0, 700.0, 10.0, 560.0),

        // PARRY - Plages larges conservées
        specF("parryCloseCancelDist", 11.0, 19.0, 0.2, 15.0),
        specL("parryCooldownMs", 600.0, 1200.0, 10.0, 900.0),
        specI("parryHoldMinMs", 520.0, 820.0, 10.0, 650.0),
        specI("parryHoldMaxMs", 820.0, 1200.0, 10.0, 980.0),
        specI("parryStickMinMs", 720.0, 1100.0, 10.0, 900.0),
        specI("parryStickMaxMs", 1200.0, 1800.0, 10.0, 1500.0),
        specL("parryJumpCd", 400.0, 800.0, 10.0, 580.0),
        specL("allowParryDelayMs", 2000.0, 3600.0, 50.0, 2800.0),

        // STRAFE PROCHE - Plages larges, defaults ajustés
        specI("closeBurstWindowMinMs", 200.0, 360.0, 10.0, 210.0),       // default 280→200 (données)
        specI("closeBurstWindowMaxMs", 320.0, 520.0, 10.0, 300.0),       // default 420→300 (données)
        specI("closeBurstFlipMinMs", 40.0, 100.0, 5.0, 60.0),
        specI("closeBurstFlipMaxMs", 80.0, 160.0, 5.0, 110.0),
        specI("closeHoldWindowMinMs", 160.0, 300.0, 10.0, 200.0),        // default 220→200 (données)
        specI("closeHoldWindowMaxMs", 240.0, 300.0, 10.0, 260.0),        // 300→260

        // POST-HIT - Plages larges, defaults ajustés
        specI("forwardStickMinMs", 160.0, 300.0, 10.0, 170.0),           // default 220→170 (données)
        specI("forwardStickMaxMs", 250.0, 310.0, 10.0, 270.0),           // default 280→330→270 (découverte 95.6% WR!)
        specI("meleeFocusMinMs", 320.0, 360.0, 10.0, 340.0),             // 370→340
        specI("meleeFocusMaxMs", 260.0, 420.0, 10.0, 390.0),             // default 340→390 (données)

        // JUMP
        specF("antiJumpZoneDist", 8.0, 8.4, 0.05, 8.15),  // était 8.25
        specI("startupJumpDelayMs", 255.0, 270.0, 5.0, 260.0),           // 265→260
        specI("continuousJumpMinIntervalMs", 180.0, 210.0, 5.0, 190.0)   // 195→190
    )

    private val specByKey = specs.associateBy { it.key }

    // -------------------------- STATE --------------------------
    private var loaded = false
    private var state = StoredState()

    private val localGson: Gson by lazy { GsonBuilder().setPrettyPrinting().create() }
    private val gson: Gson
        get() = try {
            kira.gson
        } catch (_: Throwable) {
            localGson
        }

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
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

    // ----------- UCB (Upper Confidence Bound) -----------
    private fun pickWithBandit(): Map<String, Double> {
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
        val defaultKey = keyOf(spec.def)
        if (!ps.values.any { keyOf(it) == defaultKey }) {
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

    // ----------- Pruning -----------
    private fun prune() {
        for ((_, ps) in state.params) {
            if (ps.values.size <= TOP_N_KEEP) continue
            val bandit = banditFor(ps)
            val banditState = bandit.toState()
            val scores = ps.values.indices.map { idx ->
                val plays = banditState.plays[idx]
                if (plays > 0) banditState.rewards[idx] / plays else 0.0
            }
            val sorted = ps.values.indices.sortedByDescending { scores[it] }
            val keepIndices = sorted.take(TOP_N_KEEP).toMutableSet()
            keepIndices.add(ps.lastArm)

            val newValues = mutableListOf<Double>()
            val newPlays = mutableListOf<Long>()
            val newRewards = mutableListOf<Double>()
            val indexMap = mutableMapOf<Int, Int>()
            for (idx in ps.values.indices) {
                if (keepIndices.contains(idx)) {
                    indexMap[idx] = newValues.size
                    newValues.add(ps.values[idx])
                    newPlays.add(banditState.plays[idx])
                    newRewards.add(banditState.rewards[idx])
                }
            }

            ps.values = newValues
            ps.lastArm = indexMap[ps.lastArm] ?: 0
            ps.lastValue = ps.values.getOrElse(ps.lastArm) { 0.0 }
            val totalPlays = newPlays.sum()
            ps.bandit = UcbBandit(newValues.size, totalPlays, newPlays.toLongArray(), newRewards.toDoubleArray()).toState()
        }
    }

    // ----------- Hooks -----------
    private var mistakesJump: Int = 0
    private var mistakesRod: Int = 0
    private var mistakesBow: Int = 0
    private var lastPicked: ClassicParams? = null

    fun noteJumpMistake() {
        mistakesJump += 1
    }

    fun noteRodMistake() {
        mistakesRod += 1
    }

    fun noteBowMistake() {
        mistakesBow += 1
    }

    fun noteCloseJump(distance: Float, holdingBow: Boolean) {
        val zone = lastPicked?.antiJumpZoneDist ?: 8.0f
        if (holdingBow || distance <= zone) {
            noteJumpMistake()
        }
    }

    fun takeAndResetMistakes(rodHits: Int, rodMisses: Int, bowShots: Int): MistakeSummary {
        val summary = MistakeSummary(mistakesJump, mistakesRod, mistakesBow, rodHits, rodMisses, bowShots)
        mistakesJump = 0
        mistakesRod = 0
        mistakesBow = 0
        return summary
    }

    fun lastBowAimPitchBias(): Float? = lastPicked?.bowAimPitchBias

    // -------------------------- API --------------------------
    @Synchronized
    fun pickParams(): ClassicParams {
        ensureLoaded()
        val chosen = pickWithBandit().toMutableMap()
        normalize(chosen)
        val params = buildParams(chosen)
        lastPicked = params
        prune()
        save()
        return params
    }

    @Synchronized
    fun report(win: Boolean, mistakes: MistakeSummary) {
        ensureLoaded()
        val reward = computeReward(win, mistakes)
        var changed = false
        for ((_, ps) in state.params) {
            if (ps.values.isEmpty()) continue
            val bandit = resizeBandit(banditFor(ps), ps.values.size)
            val arm = ps.lastArm.coerceIn(ps.values.indices)
            ps.lastArm = arm
            ps.lastValue = ps.values[arm]
            val decayed = bandit.decay(BANDIT_DECAY_FACTOR)
            ps.bandit = decayed.update(arm, reward).toState()
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
        bowAimPitchBias = map.float("bowAimPitchBias"),
        bowDrawParryEnabled = map.int("bowDrawParryEnabled"),
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

    private fun banditFor(ps: ParamState): UcbBandit {
        require(ps.values.isNotEmpty()) { "bandit requested without available values" }
        val plays = ps.bandit?.plays ?: LongArray(ps.values.size)
        val rewards = ps.bandit?.rewards ?: DoubleArray(ps.values.size)
        val total = ps.bandit?.totalPlays ?: 0L
        return UcbBandit(ps.values.size, total, plays.copyOf(ps.values.size), rewards.copyOf(ps.values.size))
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
