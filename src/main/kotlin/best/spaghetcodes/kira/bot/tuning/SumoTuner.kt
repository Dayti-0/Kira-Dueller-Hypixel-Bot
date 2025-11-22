package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.round

object SumoTuner {

    data class Params(
        // Zone / verrou
        val blockZoneLockMs: Long,
        val rearmInnerDist: Float,
        val zoneRearmDelayMs: Long,

        // Combat windows (existants)
        val attackStartDist: Float,
        val attackLatchMs: Long,
        val prefireApproachDist: Float,
        val prefireLatchMs: Long,

        // Pré-fire “long” (nouveau)
        val hardAttackDist: Float,
        val preAimDot: Float,

        // W-tap + post-hit
        val wTapShortMs: Long,
        val wTapLongMs: Long,
        val postHitDriveMs: Long,

        // Avance / stop
        val stopForwardDist: Float,
        val reForwardDist: Float,

        // Anti-void
        val edgeProbeNear: Float,
        val edgeProbeFar: Float,
        val predictiveProbeBonus: Float,

        // Strafe timings
        val holdMsMin: Int, val holdMsMax: Int,
        val burstMsMin: Int, val burstMsMax: Int,
        val coastMsMin: Int, val coastMsMax: Int,
        val burstFlipMin: Int, val burstFlipMax: Int,
        val burstSkipPercent: Int,
        val longStrafeChance: Int,
        val longStrafeDurationMs: Long,

        // Strafe style/intensité (nouveau)
        val strafeStyleInt: Int,
        val strafeIntensity: Int,

        // Close-strafe
        val closeInnerMin: Long, val closeInnerMax: Long,
        val closeMidMin: Long,  val closeMidMax: Long,
        val closeFarMin: Long,  val closeFarMax: Long,

        // Anti-stall / centre / edge
        val antiStallEps: Float,
        val antiStallDelayMs: Long,
        val centerBiasStrength: Int,
        val centerBiasIntervalMs: Long,
        val edgeAggroWeight: Int,
        val edgeAggroRadiusBonus: Float,

        // Start-Hop
        val startHopModeInt: Int,
        val startHopTimerFudgeMs: Long,
        val startHopTimerTargetMs: Long,
        val groundTicksRequired: Int,
        val groundMaxWaitMs: Long,
        val startAntivoidDisableMs: Long,

        // Hit-Select assist (nouveau)
        val hsEnableInt: Int,          // 0/1
        val hsMinMs: Long,
        val hsMaxMs: Long,
        val hsLatchMs: Long,
        val hsMinDist: Float,
        val hsMaxDist: Float,
        val hsAimDot: Float,
        val hsOnlyIfOppOutsideInt: Int // 0/1
    )

    private enum class ParamType { FLOAT, INT, LONG }
    private data class ParamSpec(val key: String, val min: Double, val max: Double, val step: Double, val def: Double, val type: ParamType)
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

    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.FLOAT)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.INT)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.LONG)

    private val specs = listOf(
        // ----- Zone / verrou -----
        specL("blockZoneLockMs", 400.0, 900.0, 10.0, 600.0),
        specF("rearmInnerDist", 5.6, 6.8, 0.05, 6.2),
        specL("zoneRearmDelayMs", 1000.0, 2400.0, 20.0, 1400.0),

        // ----- Combat -----
        specF("attackStartDist", 3.8, 4.6, 0.02, 4.05),
        specL("attackLatchMs", 180.0, 260.0, 5.0, 220.0),
        specF("prefireApproachDist", 4.2, 5.0, 0.02, 4.6),
        specL("prefireLatchMs", 140.0, 220.0, 5.0, 160.0),

        // ----- Pré-fire “long” -----
        specF("hardAttackDist", 4.6, 5.1, 0.02, 4.9),
        specF("preAimDot", 0.92, 0.995, 0.005, 0.96),

        // ----- W-tap & post-hit -----
        specL("wTapShortMs", 40.0, 80.0, 5.0, 50.0),
        specL("wTapLongMs", 90.0, 130.0, 5.0, 100.0),
        specL("postHitDriveMs", 100.0, 220.0, 10.0, 140.0),

        // ----- Avance / stop -----
        specF("stopForwardDist", 1.0, 1.4, 0.01, 1.18),
        specF("reForwardDist", 1.6, 2.6, 0.02, 2.0),

        // ----- Anti-void + prédictif -----
        specF("edgeProbeNear", 1.2, 1.9, 0.02, 1.6),
        specF("edgeProbeFar", 2.0, 3.2, 0.02, 2.6),
        specF("predictiveProbeBonus", 0.0, 1.0, 0.05, 0.6),

        // ----- Strafe timings -----
        specI("holdMsMin", 750.0, 1100.0, 10.0, 900.0),
        specI("holdMsMax", 1200.0, 1700.0, 10.0, 1500.0),
        specI("burstMsMin", 220.0, 360.0, 10.0, 300.0),
        specI("burstMsMax", 400.0, 650.0, 10.0, 520.0),
        specI("coastMsMin", 350.0, 600.0, 10.0, 500.0),
        specI("coastMsMax", 700.0, 1100.0, 10.0, 900.0),
        specI("burstFlipMin", 90.0, 140.0, 5.0, 120.0),
        specI("burstFlipMax", 160.0, 260.0, 5.0, 200.0),
        specI("burstSkipPercent", 10.0, 60.0, 1.0, 30.0),
        specI("longStrafeChance", 0.0, 35.0, 1.0, 0.0),
        specL("longStrafeDurationMs", 0.0, 800.0, 20.0, 0.0),

        // ----- Strafe style/intensité -----
        specI("strafeStyleInt", 0.0, 2.0, 1.0, 0.0),  // 0=minimal,1=burst,2=hybride
        specI("strafeIntensity", 0.0, 100.0, 5.0, 25.0),

        // ----- Close-strafe -----
        specL("closeInnerMin", 280.0, 420.0, 10.0, 360.0),
        specL("closeInnerMax", 420.0, 620.0, 10.0, 520.0),
        specL("closeMidMin", 360.0, 520.0, 10.0, 420.0),
        specL("closeMidMax", 520.0, 720.0, 10.0, 600.0),
        specL("closeFarMin", 460.0, 640.0, 10.0, 520.0),
        specL("closeFarMax", 640.0, 860.0, 10.0, 700.0),

        // ----- Anti-stall / centre / edge -----
        specF("antiStallEps", 0.005, 0.03, 0.001, 0.01),
        specL("antiStallDelayMs", 260.0, 520.0, 10.0, 380.0),
        specI("centerBiasStrength", 60.0, 100.0, 1.0, 100.0),
        specL("centerBiasIntervalMs", 200.0, 420.0, 10.0, 300.0),
        specI("edgeAggroWeight", 0.0, 40.0, 1.0, 20.0),
        specF("edgeAggroRadiusBonus", 0.2, 1.2, 0.1, 0.8),

        // ----- Start-Hop -----
        specI("startHopModeInt", 0.0, 2.0, 1.0, 2.0),
        specL("startHopTimerFudgeMs", 20.0, 60.0, 5.0, 40.0),
        specL("startHopTimerTargetMs", 280.0, 330.0, 5.0, 300.0),
        specI("groundTicksRequired", 1.0, 3.0, 1.0, 2.0),
        specL("groundMaxWaitMs", 260.0, 340.0, 10.0, 300.0),
        specL("startAntivoidDisableMs", 400.0, 900.0, 50.0, 600.0),

        // ----- Hit-Select assist -----
        specI("hsEnableInt", 0.0, 1.0, 1.0, 1.0),
        specL("hsMinMs", 60.0, 120.0, 5.0, 80.0),
        specL("hsMaxMs", 150.0, 220.0, 5.0, 180.0),
        specL("hsLatchMs", 120.0, 220.0, 5.0, 170.0),
        specF("hsMinDist", 2.8, 3.4, 0.05, 3.0),
        specF("hsMaxDist", 4.6, 5.0, 0.02, 4.9),
        specF("hsAimDot", 0.92, 0.995, 0.005, 0.955),
        specI("hsOnlyIfOppOutsideInt", 0.0, 1.0, 1.0, 1.0)
    )

    private val specByKey = specs.associateBy { it.key }

    private var loaded = false
    private var state = StoredState()

    fun pickParams(): Params = buildParams(pickValues())

    fun defaults(): Params = buildParams(defaultValues())

    fun report(win: Boolean, mistakes: Int) {
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

    // ===== internals =====
    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            state = load()
            loaded = true
        }
    }

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

    private fun ensureValue(ps: ParamState, value: Double): Int {
        val key = keyOf(value)
        ps.values.forEachIndexed { idx, existing ->
            if (keyOf(existing) == key) return idx
        }
        ps.values.add(value)
        return ps.values.lastIndex
    }

    private fun sample(spec: ParamSpec): Double = clamp(quantize(RandomUtils.randomDoubleInRange(spec.min, spec.max), spec.step), spec)
    private fun clamp(v: Double, spec: ParamSpec): Double {
        val c = v.coerceIn(spec.min, spec.max)
        return when (spec.type) { ParamType.FLOAT -> c; ParamType.INT -> round(c).toInt().toDouble(); ParamType.LONG -> round(c).toLong().toDouble() }
    }
    private fun quantize(v: Double, step: Double): Double { if (step <= 0.0) return v; val s = round(v / step); return s * step }
    private fun keyOf(v: Double): String = "%.4f".format(v)

    private fun Map<String, Double>.float(key: String): Float = (this[key] ?: specByKey[key]?.def ?: 0.0).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNum(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNum(this[key], key).toLong()
    private fun clampNum(v: Double?, key: String): Double { val spec = specByKey[key]; val raw = v ?: spec?.def ?: 0.0; return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw }

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

    private fun buildParams(values: Map<String, Double>): Params = Params(
        blockZoneLockMs = values.long("blockZoneLockMs"),
        rearmInnerDist = values.float("rearmInnerDist"),
        zoneRearmDelayMs = values.long("zoneRearmDelayMs"),

        attackStartDist = values.float("attackStartDist"),
        attackLatchMs = values.long("attackLatchMs"),
        prefireApproachDist = values.float("prefireApproachDist"),
        prefireLatchMs = values.long("prefireLatchMs"),

        hardAttackDist = values.float("hardAttackDist"),
        preAimDot = values.float("preAimDot"),

        wTapShortMs = values.long("wTapShortMs"),
        wTapLongMs = values.long("wTapLongMs"),
        postHitDriveMs = values.long("postHitDriveMs"),

        stopForwardDist = values.float("stopForwardDist"),
        reForwardDist = values.float("reForwardDist"),

        edgeProbeNear = values.float("edgeProbeNear"),
        edgeProbeFar = values.float("edgeProbeFar"),
        predictiveProbeBonus = values.float("predictiveProbeBonus"),

        holdMsMin = values.int("holdMsMin"),
        holdMsMax = values.int("holdMsMax"),
        burstMsMin = values.int("burstMsMin"),
        burstMsMax = values.int("burstMsMax"),
        coastMsMin = values.int("coastMsMin"),
        coastMsMax = values.int("coastMsMax"),
        burstFlipMin = values.int("burstFlipMin"),
        burstFlipMax = values.int("burstFlipMax"),
        burstSkipPercent = values.int("burstSkipPercent"),
        longStrafeChance = values.int("longStrafeChance"),
        longStrafeDurationMs = values.long("longStrafeDurationMs"),

        strafeStyleInt = values.int("strafeStyleInt"),
        strafeIntensity = values.int("strafeIntensity"),

        closeInnerMin = values.long("closeInnerMin"),
        closeInnerMax = values.long("closeInnerMax"),
        closeMidMin = values.long("closeMidMin"),
        closeMidMax = values.long("closeMidMax"),
        closeFarMin = values.long("closeFarMin"),
        closeFarMax = values.long("closeFarMax"),

        antiStallEps = values.float("antiStallEps"),
        antiStallDelayMs = values.long("antiStallDelayMs"),
        centerBiasStrength = values.int("centerBiasStrength"),
        centerBiasIntervalMs = values.long("centerBiasIntervalMs"),
        edgeAggroWeight = values.int("edgeAggroWeight"),
        edgeAggroRadiusBonus = values.float("edgeAggroRadiusBonus"),

        startHopModeInt = values.int("startHopModeInt"),
        startHopTimerFudgeMs = values.long("startHopTimerFudgeMs"),
        startHopTimerTargetMs = values.long("startHopTimerTargetMs"),
        groundTicksRequired = values.int("groundTicksRequired"),
        groundMaxWaitMs = values.long("groundMaxWaitMs"),
        startAntivoidDisableMs = values.long("startAntivoidDisableMs"),

        hsEnableInt = values.int("hsEnableInt"),
        hsMinMs = values.long("hsMinMs"),
        hsMaxMs = values.long("hsMaxMs"),
        hsLatchMs = values.long("hsLatchMs"),
        hsMinDist = values.float("hsMinDist"),
        hsMaxDist = values.float("hsMaxDist"),
        hsAimDot = values.float("hsAimDot"),
        hsOnlyIfOppOutsideInt = values.int("hsOnlyIfOppOutsideInt")
    )

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

    private fun file(): File = File(configDir(), "sumo_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
