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

        // Réserves
        val reserveTightMs: Long,
        val earlyReserve: Int,
        val midReserve: Int,

        // Rod
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

        // Parry
        val parryCloseCancelDist: Float,
        val parryCooldownMs: Long,
        val parryHoldMinMs: Int,
        val parryHoldMaxMs: Int,
        val parryStickMinMs: Int,
        val parryStickMaxMs: Int,
        val parryJumpCd: Long,
        val allowParryDelayMs: Long,

        // Strafe proche
        val closeBurstWindowMinMs: Int,
        val closeBurstWindowMaxMs: Int,
        val closeBurstFlipMinMs: Int,
        val closeBurstFlipMaxMs: Int,
        val closeHoldWindowMinMs: Int,
        val closeHoldWindowMaxMs: Int,

        // Post-hit
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

    private data class StoredState(
        var version: Int = CURRENT_VERSION,
        var params: MutableMap<String, ParamState> = mutableMapOf()
    )

    private const val CURRENT_VERSION = 3
    private const val MISTAKE_PENALTY = 0.25
    private const val TOP_N_KEEP = 16

    // Limite "effective" pour éviter que les bandits soient figés sur de très vieux samples.
    private const val MAX_EFFECTIVE_TOTAL_PLAYS: Long = 2000L

    // Lissage des rewards : on garde une inertie sur les bras très joués.
    private const val SMOOTHING_MIN_PLAYS_PER_ARM: Long = 10L
    private const val SMOOTHING_LEARNING_RATE: Double = 0.35

    // Stratégie par défaut pour ce tuner : UCB_TUNED est plus stable que UCB1 sur des rewards 0/1.
    private val DEFAULT_STRATEGY: UcbBandit.Strategy = UcbBandit.Strategy.UCB_TUNED

    // Reward de base en cas de défaite (permet de distinguer une bonne et une mauvaise défaite).
    private const val LOSS_BASE_REWARD: Double = 0.35

    // -------------------------- SPECS --------------------------
    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double) =
        ParamSpec(k, mi, ma, st, de, ParamType.FLOAT)

    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double) =
        ParamSpec(k, mi, ma, st, de, ParamType.INT)

    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double) =
        ParamSpec(k, mi, ma, st, de, ParamType.LONG)

    private fun specD(k: String, mi: Double, ma: Double, st: Double, de: Double) =
        ParamSpec(k, mi, ma, st, de, ParamType.DOUBLE)

    // VERSION HYBRID : Plages larges de l'ancien + defaults ajustés d'après les données
    private val specs = listOf(
        // BOW - Defaults V4 basés sur découvertes session (867 et 779 parties)
        specI("fullDrawMsMin", 780.0, 820.0, 10.0, 800.0),
        specI("fullDrawMsMax", 1080.0, 1120.0, 10.0, 1100.0),
        specF("bowCancelCloseDist", 6.0, 10.0, 0.1, 8.0),
        specF("bowMinUseDist", 7.0, 11.0, 0.1, 10.5),

        specI("openVolleyMax", 1.0, 1.0, 1.0, 1.0),

        specL("openSpacingMin", 450.0, 850.0, 10.0, 450.0),
        specL("openSpacingMax", 700.0, 1150.0, 10.0, 870.0),
        specF("openShotMinDist", 10.0, 16.0, 0.1, 13.0),
        specL("reactiveCdMs", 420.0, 780.0, 10.0, 600.0),

        // Détection mouvement
        specD("stillFrameThreshold", 0.02, 0.08, 0.002, 0.045),
        specI("stillFramesNeeded", 6.0, 16.0, 1.0, 10.0),
        specD("bowSlowThreshold", 0.04, 0.09, 0.002, 0.06),
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 3.0),

        // Réserves
        specL("reserveTightMs", 7000.0, 13000.0, 100.0, 10000.0),
        specI("earlyReserve", 2.0, 5.0, 1.0, 3.0),
        specI("midReserve", 1.0, 4.0, 1.0, 2.0),

        // ROD
        specL("rodCdCloseMsBase", 280.0, 420.0, 10.0, 310.0),
        specL("rodCdFarMsBase", 360.0, 620.0, 10.0, 480.0),
        specF("rodCdBiasMax", 1.05, 1.5, 0.01, 1.25),
        specF("rodBanMeleeDist", 3.0, 5.0, 0.05, 4.0),
        specF("rodCloseMin", 1.6, 2.6, 0.05, 2.0),
        specF("rodCloseMax", 2.4, 3.4, 0.05, 2.9),
        specF("rodMainMin", 2.8, 3.8, 0.05, 3.2),
        specF("rodMainMax", 3.6, 4.6, 0.05, 4.1),
        specF("rodInterceptMin", 3.9, 5.0, 0.05, 4.4),
        specF("rodInterceptMax", 5.0, 6.0, 0.05, 5.6),
        specF("rodMaxRangeHard", 6.5, 8.0, 0.05, 7.2),
        specF("rodMidInstantMin", 4.8, 6.2, 0.05, 5.5),
        specF("rodMidInstantMax", 6.2, 7.6, 0.05, 7.0),
        specF("farThreshold", 9.0, 14.0, 0.1, 11.0),
        specL("reentryRodGraceMs", 200.0, 500.0, 10.0, 300.0),

        specI("rodHoldCloseMinMs", 90.0, 160.0, 5.0, 118.0),
        specI("rodHoldCloseMaxMs", 110.0, 190.0, 5.0, 142.0),
        specI("rodHoldMidMinMs", 160.0, 260.0, 5.0, 208.0),
        specI("rodHoldMidMaxMs", 180.0, 300.0, 5.0, 232.0),

        // Rod anti-spam
        specI("rodAntiSpamClosePassiveMin", 260.0, 420.0, 10.0, 340.0),
        specI("rodAntiSpamClosePassiveMax", 340.0, 520.0, 10.0, 420.0),
        specI("rodAntiSpamMidPassiveMin", 400.0, 640.0, 10.0, 420.0),
        specI("rodAntiSpamMidPassiveMax", 520.0, 760.0, 10.0, 580.0),
        specI("rodAntiSpamFarPassiveMin", 480.0, 720.0, 10.0, 520.0),
        specI("rodAntiSpamFarPassiveMax", 600.0, 880.0, 10.0, 700.0),

        specI("rodAntiSpamCloseActiveMin", 200.0, 360.0, 10.0, 200.0),
        specI("rodAntiSpamCloseActiveMax", 260.0, 420.0, 10.0, 320.0),
        specI("rodAntiSpamMidActiveMin", 280.0, 480.0, 10.0, 380.0),
        specI("rodAntiSpamMidActiveMax", 400.0, 640.0, 10.0, 520.0),
        specI("rodAntiSpamFarActiveMin", 320.0, 520.0, 10.0, 400.0),
        specI("rodAntiSpamFarActiveMax", 420.0, 700.0, 10.0, 560.0),

        // Parry
        specF("parryCloseCancelDist", 11.0, 19.0, 0.2, 15.0),
        specL("parryCooldownMs", 600.0, 1200.0, 10.0, 900.0),
        specI("parryHoldMinMs", 520.0, 820.0, 10.0, 650.0),
        specI("parryHoldMaxMs", 820.0, 1200.0, 10.0, 980.0),
        specI("parryStickMinMs", 720.0, 1100.0, 10.0, 900.0),
        specI("parryStickMaxMs", 1200.0, 1800.0, 10.0, 1500.0),
        specL("parryJumpCd", 400.0, 800.0, 10.0, 580.0),
        specL("allowParryDelayMs", 2000.0, 3600.0, 50.0, 2800.0),

        // Strafe proche
        specI("closeBurstWindowMinMs", 200.0, 360.0, 10.0, 200.0),
        specI("closeBurstWindowMaxMs", 320.0, 520.0, 10.0, 300.0),
        specI("closeBurstFlipMinMs", 40.0, 100.0, 5.0, 60.0),
        specI("closeBurstFlipMaxMs", 80.0, 160.0, 5.0, 110.0),
        specI("closeHoldWindowMinMs", 160.0, 300.0, 10.0, 200.0),
        specI("closeHoldWindowMaxMs", 260.0, 420.0, 10.0, 300.0),

        // Post-hit
        specI("forwardStickMinMs", 160.0, 300.0, 10.0, 170.0),
        specI("forwardStickMaxMs", 250.0, 310.0, 10.0, 270.0),
        specI("meleeFocusMinMs", 220.0, 380.0, 10.0, 370.0),
        specI("meleeFocusMaxMs", 260.0, 420.0, 10.0, 390.0),

        // --- NOUVEAU : tuning des sauts ---
        specF("antiJumpZoneDist", 5.0, 10.0, 0.2, 8.0),
        specI("startupJumpDelayMs", 80.0, 220.0, 5.0, 140.0),
        specI("continuousJumpMinIntervalMs", 220.0, 520.0, 10.0, 320.0),
    )

    private val specByKey: Map<String, ParamSpec> = specs.associateBy { it.key }

    // ------------------------ RUNTIME STATE ------------------------
    private var state: StoredState = StoredState()
    private var loaded: Boolean = false

    // ------------------------ PUBLIC API ------------------------
    @Synchronized
    fun pickParams(): ClassicParams {
        ensureLoaded()
        val chosen = mutableMapOf<String, Double>()

        for (spec in specs) {
            val ps = state.params.getOrPut(spec.key) { ParamState() }

            if (ps.values.isEmpty()) {
                initializeValues(ps, spec)
            }

            val bandit = banditFor(ps)
            val shouldExplore = shouldExploreNew(ps, bandit)

            val selectedArm = if (shouldExplore) {
                val newValue = sample(spec)
                val idx = ensureValue(ps, newValue)
                val resized = resizeBandit(bandit, ps.values.size)
                ps.bandit = resized.toState()
                idx
            } else {
                val resized = resizeBandit(bandit, ps.values.size)
                ps.bandit = resized.toState()
                resized.selectArm()
            }

            ps.lastArm = selectedArm
            ps.lastValue = ps.values[selectedArm]
            chosen[spec.key] = ps.lastValue
        }

        save()
        val params = buildParams(chosen)
        lastPicked = params
        return params
    }

    private fun shouldExploreNew(ps: ParamState, bandit: UcbBandit): Boolean {
        // 1) On remplit d'abord un petit "pool" de valeurs différentes pour ce paramètre.
        val targetValues = 12
        if (ps.values.size < targetValues) {
            // Probabilité décroissante d'ajouter une nouvelle valeur à mesure qu'on se rapproche du pool.
            val base = 0.35 - 0.02 * ps.values.size
            val p = base.coerceIn(0.12, 0.35)
            return RandomUtils.randomDoubleInRange(0.0, 1.0) < p
        }

        // 2) Tant que certains bras ont très peu de plays, on se concentre sur l'exploration UCB des bras existants.
        val state = bandit.toState()
        val minPlays = state.plays.minOrNull() ?: 0L
        if (minPlays < SMOOTHING_MIN_PLAYS_PER_ARM) {
            return false
        }

        // 3) Exploration "forcée" périodique : toutes les N parties on tente un nouveau point.
        if (state.totalPlays > 0 && state.totalPlays % 50L == 0L) {
            return true
        }

        // 4) Exploration de fond qui décroît doucement avec le temps.
        val explorationRate = when {
            state.totalPlays < 150 -> 0.15
            state.totalPlays < 400 -> 0.08
            state.totalPlays < 1200 -> 0.04
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

        for (v in initialValues) {
            val clamped = clamp(quantize(v, spec.step), spec)
            val key = keyOf(clamped)
            if (!ps.values.any { keyOf(it) == key }) {
                ps.values.add(clamped)
            }
        }
    }

    private fun ensureValue(ps: ParamState, value: Double): Int {
        val key = keyOf(value)
        ps.values.forEachIndexed { idx, existing ->
            if (keyOf(existing) == key) return idx
        }
        ps.values.add(value)
        return ps.values.lastIndex
    }

    // ----------- Pruning -----------
    private fun prune() {
        for ((_, ps) in state.params) {
            if (ps.values.size <= TOP_N_KEEP) continue

            val bandit = banditFor(ps)
            val banditState = bandit.toState()

            val scores = ps.values.indices.map { idx ->
                val plays = banditState.plays.getOrElse(idx) { 0L }
                val avg = if (plays > 0L) banditState.rewards[idx] / plays else 0.0

                // Petit bonus pour les bras jamais / très peu testés pour éviter de tuer la diversité trop tôt.
                val explorationBonus = when {
                    plays == 0L -> 0.05
                    plays < 5L -> 0.02
                    else -> 0.0
                }
                avg + explorationBonus
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
                newRewards.toDoubleArray(),
                banditState.minReward,
                banditState.maxReward,
                DEFAULT_STRATEGY,
            ).toState()
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

    @Synchronized
    fun report(win: Boolean, mistakes: Int) {
        ensureLoaded()

        val clampedMistakes = mistakes.coerceAtLeast(0)
        val rewardBase = if (win) 1.0 else LOSS_BASE_REWARD
        val rawReward = rewardBase - clampedMistakes * MISTAKE_PENALTY
        val reward = rawReward.coerceIn(0.0, 1.0)

        var changed = false

        for ((_, ps) in state.params) {
            if (ps.values.isEmpty()) continue

            val bandit = resizeBandit(banditFor(ps), ps.values.size)
            val state = bandit.toState()

            val arm = ps.lastArm.coerceIn(ps.values.indices)
            ps.lastArm = arm
            ps.lastValue = ps.values[arm]

            val armPlays = state.plays.getOrElse(arm) { 0L }
            val armAvg = if (armPlays > 0L) {
                state.rewards[arm] / armPlays
            } else {
                // Valeur neutre : milieu de l'intervalle [minReward, maxReward]
                (state.minReward + state.maxReward) * 0.5
            }

            // Bras peu joué → on prend quasiment la nouvelle reward.
            // Bras très joué → on garde une grosse inertie sur la moyenne actuelle.
            val lr = if (armPlays < SMOOTHING_MIN_PLAYS_PER_ARM) 1.0 else SMOOTHING_LEARNING_RATE
            val mixedReward = armAvg * (1.0 - lr) + reward * lr

            val boundedReward = bandit.normalizeReward(mixedReward)
            ps.bandit = bandit.update(arm, boundedReward).toState()
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
        continuousJumpMinIntervalMs = map.int("continuousJumpMinIntervalMs"),
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
        return when {
            v < spec.min -> spec.min
            v > spec.max -> spec.max
            else -> v
        }
    }

    private fun quantize(v: Double, step: Double): Double =
        if (step <= 0.0) v else round(v / step) * step

    private fun keyOf(v: Double): String = "%.4f".format(v)

    // ------------------------ BANDIT HELPERS ------------------------
    // On "re-scale" l'état d'un bandit si trop de parties ont été accumulées.
    // On garde les moyennes reward par bras, mais on réduit artificiellement les plays.
    private fun capState(state: UcbBanditState): UcbBanditState {
        if (state.totalPlays <= MAX_EFFECTIVE_TOTAL_PLAYS) return state

        val plays = state.plays
        val rewards = state.rewards
        val total = plays.sum()

        // Si jamais totalPlays est déjà cohérent et pas si énorme que ça, on met juste à jour.
        if (total <= MAX_EFFECTIVE_TOTAL_PLAYS) {
            return state.copy(totalPlays = total)
        }

        val scale = MAX_EFFECTIVE_TOTAL_PLAYS.toDouble() / total.toDouble()
        val newPlays = LongArray(plays.size)
        val newRewards = DoubleArray(rewards.size)

        for (i in plays.indices) {
            val p = plays[i]
            if (p <= 0L) continue
            val avg = rewards[i] / p
            val scaledPlays = (p.toDouble() * scale).toLong().coerceAtLeast(1L)
            newPlays[i] = scaledPlays
            newRewards[i] = avg * scaledPlays
        }

        val newTotal = newPlays.sum()
        return state.copy(
            totalPlays = newTotal,
            plays = newPlays,
            rewards = newRewards,
        )
    }

    private fun banditFor(ps: ParamState): UcbBandit {
        require(ps.values.isNotEmpty()) { "bandit requested without available values" }

        val baseState = ps.bandit ?: UcbBandit.withArms(ps.values.size).toState()
        val capped = capState(baseState)

        val armCount = ps.values.size
        val plays = capped.plays.copyOf(armCount)
        val rewards = capped.rewards.copyOf(armCount)
        val total = plays.sum()

        return UcbBandit(
            armCount,
            total,
            plays,
            rewards,
            capped.minReward,
            capped.maxReward,
            DEFAULT_STRATEGY,
        )
    }

    private fun resizeBandit(bandit: UcbBandit, size: Int): UcbBandit {
        if (bandit.armCount == size) return bandit

        val capped = capState(bandit.toState())
        val plays = capped.plays.copyOf(size)
        val rewards = capped.rewards.copyOf(size)
        val total = plays.sum()

        return UcbBandit(
            size,
            total,
            plays,
            rewards,
            capped.minReward,
            capped.maxReward,
            DEFAULT_STRATEGY,
        )
    }

    // ------------------------ PERSISTENCE ------------------------
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun file(): File = File(kira.dataFolder, "classicv2_tuner.json")

    @Synchronized
    private fun save() {
        val f = file()
        try {
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.createNewFile()
            }
            f.writer().use { w ->
                gson.toJson(state, w)
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                val loadedState: StoredState = gson.fromJson(r, type)
                if (loadedState.version != CURRENT_VERSION) {
                    migrate(loadedState)
                } else {
                    loadedState
                }
            }
        } catch (ex: Exception) {
            tryBackupCorrupt(f, ex)
            StoredState()
        }
    }

    private fun migrate(old: StoredState): StoredState {
        // Pour l’instant, CURRENT_VERSION = 3 → pas de migration complexe.
        old.version = CURRENT_VERSION
        return old
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
