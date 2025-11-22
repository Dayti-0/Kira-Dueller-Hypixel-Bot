package best.spaghetcodes.kira.bot.tuning

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.round

object BoxingTuner {

    data class Params(
        val jumpCooldownMs: Long,
        val noJumpCloseDist: Float,
        val warmupDistanceStop: Float,
        val warmupJumpEveryMin: Int,
        val warmupJumpEveryMax: Int,
        val warmupPressMin: Int,
        val warmupPressMax: Int,
        val comboLockMin: Int,
        val comboLockMax: Int,
        val forwardStickMinMs: Int,
        val forwardStickMaxMs: Int,
        val meleeFocusMinMs: Int,
        val meleeFocusMaxMs: Int,
        val microJitterMin: Int,
        val microJitterMax: Int,
        val stopForwardCloseDistCombo: Float,
        val resumeForwardDistCombo: Float,
        val stopForwardCloseDistDefault: Float,
        val resumeForwardDistDefault: Float,
        val kbRecoveryMin: Int,
        val kbRecoveryMax: Int,
        val heavyKbRecoveryMin: Int,
        val heavyKbRecoveryMax: Int,
        val heavyKbDelta: Float,
        val targetDistComboMin: Float,
        val targetDistComboMax: Float,
        val targetDistNeutralMin: Float,
        val targetDistNeutralMax: Float,
        val burstFlipMin: Int,
        val burstFlipMax: Int,
        val burstWindowMin: Int,
        val burstWindowMax: Int,
        val holdWindowMin: Int,
        val holdWindowMax: Int,
        val longStrafeMin: Int,
        val longStrafeMax: Int,
        val longStrafeDistanceCap: Float,
        val longStrafeBaseChance: Int,
        val antiStallEps: Float,
        val antiStallDelay: Int,
        val aimSpikeDeg: Float,
        val aimSpikeCooldown: Long,
        val wallNearMargin: Float,
        val wallEscapeTimeMsMin: Int,
        val wallEscapeTimeMsMax: Int,
        val enemyIframeSoft: Int
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

    private data class LegacyStoredState(var version: Int = 2, var params: MutableMap<String, LegacyParamState> = mutableMapOf())

    private const val CURRENT_VERSION = 3
    private const val MISTAKE_PENALTY = 0.25

    private fun specF(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.FLOAT)
    private fun specI(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.INT)
    private fun specL(k: String, mi: Double, ma: Double, st: Double, de: Double) = ParamSpec(k, mi, ma, st, de, ParamType.LONG)

    private val specs = listOf(
        specL("jumpCooldownMs", 700.0, 1200.0, 10.0, 900.0),
        specF("noJumpCloseDist", 3.0, 4.2, 0.05, 3.6),
        specF("warmupDistanceStop", 6.0, 8.0, 0.1, 7.0),
        specI("warmupJumpEveryMin", 200.0, 320.0, 5.0, 240.0),
        specI("warmupJumpEveryMax", 320.0, 480.0, 5.0, 380.0),
        specI("warmupPressMin", 110.0, 170.0, 5.0, 130.0),
        specI("warmupPressMax", 160.0, 230.0, 5.0, 190.0),
        specI("comboLockMin", 480.0, 720.0, 5.0, 560.0),
        specI("comboLockMax", 640.0, 900.0, 5.0, 760.0),
        specI("forwardStickMinMs", 220.0, 320.0, 5.0, 260.0),
        specI("forwardStickMaxMs", 280.0, 380.0, 5.0, 340.0),
        specI("meleeFocusMinMs", 360.0, 460.0, 5.0, 420.0),
        specI("meleeFocusMaxMs", 460.0, 620.0, 5.0, 520.0),
        specI("microJitterMin", 80.0, 160.0, 5.0, 120.0),
        specI("microJitterMax", 140.0, 240.0, 5.0, 180.0),
        specF("stopForwardCloseDistCombo", 0.9, 1.2, 0.01, 1.0),
        specF("resumeForwardDistCombo", 1.3, 1.7, 0.01, 1.45),
        specF("stopForwardCloseDistDefault", 0.95, 1.3, 0.01, 1.1),
        specF("resumeForwardDistDefault", 1.4, 1.9, 0.01, 1.6),
        specI("kbRecoveryMin", 460.0, 680.0, 5.0, 520.0),
        specI("kbRecoveryMax", 680.0, 920.0, 5.0, 760.0),
        specI("heavyKbRecoveryMin", 560.0, 760.0, 5.0, 650.0),
        specI("heavyKbRecoveryMax", 780.0, 960.0, 5.0, 900.0),
        specF("heavyKbDelta", 0.3, 0.6, 0.01, 0.45),
        specF("targetDistComboMin", 0.9, 1.2, 0.01, 1.08),
        specF("targetDistComboMax", 1.3, 1.7, 0.01, 1.5),
        specF("targetDistNeutralMin", 1.5, 2.0, 0.01, 1.75),
        specF("targetDistNeutralMax", 2.0, 2.8, 0.01, 2.35),
        specI("burstFlipMin", 40.0, 80.0, 1.0, 55.0),
        specI("burstFlipMax", 80.0, 130.0, 1.0, 95.0),
        specI("burstWindowMin", 180.0, 320.0, 5.0, 240.0),
        specI("burstWindowMax", 320.0, 480.0, 5.0, 380.0),
        specI("holdWindowMin", 160.0, 300.0, 5.0, 220.0),
        specI("holdWindowMax", 280.0, 420.0, 5.0, 340.0),
        specI("longStrafeMin", 700.0, 1200.0, 10.0, 900.0),
        specI("longStrafeMax", 1200.0, 2000.0, 10.0, 1600.0),
        specF("longStrafeDistanceCap", 3.0, 4.0, 0.05, 3.4),
        specI("longStrafeBaseChance", 15.0, 45.0, 1.0, 30.0),
        specF("antiStallEps", 0.008, 0.03, 0.001, 0.015),
        specI("antiStallDelay", 200.0, 420.0, 5.0, 260.0),
        specF("aimSpikeDeg", 10.0, 18.0, 0.1, 14.0),
        specL("aimSpikeCooldown", 140.0, 260.0, 5.0, 180.0),
        specF("wallNearMargin", 0.7, 1.2, 0.05, 0.9),
        specI("wallEscapeTimeMsMin", 500.0, 800.0, 5.0, 600.0),
        specI("wallEscapeTimeMsMax", 700.0, 1100.0, 5.0, 900.0),
        specI("enemyIframeSoft", 2.0, 5.0, 1.0, 3.0)
    )

    private val specByKey = specs.associateBy { it.key }

    private var loaded = false
    private var state = StoredState()

    fun pickParams(): Params = build(pickValues())
    fun defaults(): Params = build(defaultValues())

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
            val updated = bandit.update(arm, reward)
            ps.bandit = updated.toState()
            ps.lastArm = arm
            ps.lastValue = ps.values[arm]
            changed = true
        }
        if (changed) save()
    }

    private fun build(values: Map<String, Double>): Params {
        return Params(
            jumpCooldownMs = values.long("jumpCooldownMs"),
            noJumpCloseDist = values.float("noJumpCloseDist"),
            warmupDistanceStop = values.float("warmupDistanceStop"),
            warmupJumpEveryMin = values.int("warmupJumpEveryMin"),
            warmupJumpEveryMax = values.int("warmupJumpEveryMax"),
            warmupPressMin = values.int("warmupPressMin"),
            warmupPressMax = values.int("warmupPressMax"),
            comboLockMin = values.int("comboLockMin"),
            comboLockMax = values.int("comboLockMax"),
            forwardStickMinMs = values.int("forwardStickMinMs"),
            forwardStickMaxMs = values.int("forwardStickMaxMs"),
            meleeFocusMinMs = values.int("meleeFocusMinMs"),
            meleeFocusMaxMs = values.int("meleeFocusMaxMs"),
            microJitterMin = values.int("microJitterMin"),
            microJitterMax = values.int("microJitterMax"),
            stopForwardCloseDistCombo = values.float("stopForwardCloseDistCombo"),
            resumeForwardDistCombo = values.float("resumeForwardDistCombo"),
            stopForwardCloseDistDefault = values.float("stopForwardCloseDistDefault"),
            resumeForwardDistDefault = values.float("resumeForwardDistDefault"),
            kbRecoveryMin = values.int("kbRecoveryMin"),
            kbRecoveryMax = values.int("kbRecoveryMax"),
            heavyKbRecoveryMin = values.int("heavyKbRecoveryMin"),
            heavyKbRecoveryMax = values.int("heavyKbRecoveryMax"),
            heavyKbDelta = values.float("heavyKbDelta"),
            targetDistComboMin = values.float("targetDistComboMin"),
            targetDistComboMax = values.float("targetDistComboMax"),
            targetDistNeutralMin = values.float("targetDistNeutralMin"),
            targetDistNeutralMax = values.float("targetDistNeutralMax"),
            burstFlipMin = values.int("burstFlipMin"),
            burstFlipMax = values.int("burstFlipMax"),
            burstWindowMin = values.int("burstWindowMin"),
            burstWindowMax = values.int("burstWindowMax"),
            holdWindowMin = values.int("holdWindowMin"),
            holdWindowMax = values.int("holdWindowMax"),
            longStrafeMin = values.int("longStrafeMin"),
            longStrafeMax = values.int("longStrafeMax"),
            longStrafeDistanceCap = values.float("longStrafeDistanceCap"),
            longStrafeBaseChance = values.int("longStrafeBaseChance"),
            antiStallEps = values.float("antiStallEps"),
            antiStallDelay = values.int("antiStallDelay"),
            aimSpikeDeg = values.float("aimSpikeDeg"),
            aimSpikeCooldown = values.long("aimSpikeCooldown"),
            wallNearMargin = values.float("wallNearMargin"),
            wallEscapeTimeMsMin = values.int("wallEscapeTimeMsMin"),
            wallEscapeTimeMsMax = values.int("wallEscapeTimeMsMax"),
            enemyIframeSoft = values.int("enemyIframeSoft")
        )
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
        if (ps.values.none { keyOf(it) == defKey }) {
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
            ensureValue(ps, clamped)
        }
    }

    private fun sample(spec: ParamSpec): Double = clamp(quantize(RandomUtils.randomDoubleInRange(spec.min, spec.max), spec.step), spec)

    private fun clamp(v: Double, spec: ParamSpec): Double {
        val c = v.coerceIn(spec.min, spec.max)
        return when (spec.type) {
            ParamType.FLOAT -> c
            ParamType.INT -> round(c).toInt().toDouble()
            ParamType.LONG -> round(c).toLong().toDouble()
        }
    }

    private fun quantize(v: Double, step: Double): Double {
        if (step <= 0.0) return v
        val s = round(v / step)
        return s * step
    }

    private fun keyOf(v: Double): String = "%.4f".format(v)

    private fun Map<String, Double>.float(key: String): Float = clampNum(this[key], key).toFloat()
    private fun Map<String, Double>.int(key: String): Int = clampNum(this[key], key).toInt()
    private fun Map<String, Double>.long(key: String): Long = clampNum(this[key], key).toLong()

    private fun clampNum(v: Double?, key: String): Double {
        val spec = specByKey[key]
        val raw = v ?: spec?.def ?: 0.0
        return spec?.let { raw.coerceIn(it.min, it.max) } ?: raw
    }

    private fun defaultValues(): Map<String, Double> = specs.associate { it.key to it.def }

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

    private fun ensureValue(ps: ParamState, value: Double): Int {
        val key = keyOf(value)
        ps.values.forEachIndexed { idx, existing ->
            if (keyOf(existing) == key) return idx
        }
        ps.values.add(value)
        return ps.values.lastIndex
    }

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
        for ((key, ps) in state.params) {
            if (ps.values.isEmpty()) {
                specByKey[key]?.let { initializeValues(ps, it) }
            }
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
            f.writer().use { writer -> kira.gson.toJson(state, writer) }
        } catch (_: Exception) {
        }
    }

    private fun ensureLoaded() {
        if (!loaded) {
            state = load()
            loaded = true
        }
    }

    private fun file(): File = File(configDir(), "boxing_tuner.json")

    private fun configDir(): File {
        return try {
            kira.tunerDir
        } catch (_: Throwable) {
            File(File(File(System.getProperty("user.home"), ".kira"), "config"), "Kira/Tuner")
        }
    }
}
