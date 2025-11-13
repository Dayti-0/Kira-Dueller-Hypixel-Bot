package best.spaghetcodes.kira.bot.tuning

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.minecraft.client.Minecraft
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Tuner epsilon-greedy persistant (format JSON inchangé).
 * Ajouts:
 *  - reportDetailed(GameMetrics) avec récompense enrichie
 *  - normalisation min/max pour éviter les bornes invalides
 *  - checkpoints mémoire + rollback doux si régression
 *  - pruning léger par param pour éviter l’enflure du JSON
 */
object ClassicV2Tuner {

    // ---------- Métriques détaillées ----------
    data class GameMetrics(
        val win: Boolean,
        val durationMs: Long = 0L,
        val damageDealt: Int = 0,
        val damageTaken: Int = 0,
        val rodAttempts: Int = 0,
        val rodHits: Int = 0,
        val bowShots: Int = 0,
        val bowHits: Int = 0,
        val mistakes: Int = 0
    )

    // ---------- Paramètres injectés ----------
    data class ClassicParams(
        // ARC
        val fullDrawMsMin: Int,
        val fullDrawMsMax: Int,
        val bowCancelCloseDist: Float,
        val bowMinUseDist: Float,
        val openVolleyMax: Int,
        val openSpacingMin: Long,
        val openSpacingMax: Long,
        val openShotMinDist: Float,
        val reactiveCdMs: Long,

        // Détection
        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,

        // Réserves
        val reserveTightMs: Long,
        val earlyReserve: Int,
        val midReserve: Int,

        // ROD (distances/ranges)
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

        // ROD (hold & anti-spam)
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

        // Parade
        val parryCloseCancelDist: Float,
        val parryCooldownMs: Long,
        val parryHoldMinMs: Int,
        val parryHoldMaxMs: Int,
        val parryStickMinMs: Int,
        val parryStickMaxMs: Int,
        val parryJumpCd: Long,
        val allowParryDelayMs: Long,

        // Close-strafe
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

        // Mid/long + band
        val midStrafeSwitchMinMs: Int,
        val midStrafeSwitchMaxMs: Int,
        val midTightRangeMin: Float,
        val midTightRangeMax: Float,
        val midTightEps: Float,
        val midTightFlipCooldownMs: Int,
        val randomStrafeBandMin: Float,
        val randomStrafeBandMax: Float,

        // Anti-jump + premier saut
        val antiJumpZoneDist: Float,
        val startupJumpForceMs: Int
    )

    // ---------- Espace de recherche ----------
    private data class Spec(
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val def: Double,
        val isInt: Boolean
    )

    private val gson = Gson()
    private val file by lazy {
        val dir = Minecraft.getMinecraft().mcDataDir
        File(dir, "config/classicv2_tuner.json")
    }

    // Map<paramKey, Map<valueString, score>>
    private var scores: MutableMap<String, MutableMap<String, Double>> = mutableMapOf()
    private var lastPick: MutableMap<String, String> = mutableMapOf()

    private var mistakesCounter = 0

    // Historique récent & checkpoints (mémoire)
    private val recent = ArrayDeque<Boolean>() // taille <= 25
    private data class Checkpoint(val ts: Long, val winRate: Double, val config: Map<String, String>)
    private val checkpoints = ArrayDeque<Checkpoint>()

    private fun load() {
        if (!file.exists()) return
        val type = object : TypeToken<MutableMap<String, MutableMap<String, Double>>>() {}.type
        scores = gson.fromJson(file.readText(), type) ?: mutableMapOf()
    }
    private fun save() {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeText(gson.toJson(scores))
    }

    private fun totalPlays(): Int {
        var t = 0
        for (e in scores.values) t += e.size
        return t
    }

    // ---------- Spécifications ----------
    private val specs: List<Spec> by lazy {
        fun i(k: String, mn: Int, mx: Int, st: Int, df: Int) =
            Spec(k, mn.toDouble(), mx.toDouble(), st.toDouble(), df.toDouble(), true)
        fun f(k: String, mn: Float, mx: Float, st: Float, df: Float) =
            Spec(k, mn.toDouble(), mx.toDouble(), st.toDouble(), df.toDouble(), false)
        fun l(k: String, mn: Long, mx: Long, st: Long, df: Long) =
            Spec(k, mn.toDouble(), mx.toDouble(), st.toDouble(), df.toDouble(), true)

        listOf(
            // ARC
            i("fullDrawMsMin", 700, 1000, 10, 820),
            i("fullDrawMsMax", 900, 1100, 10, 980),
            f("bowCancelCloseDist", 6.0f, 10.0f, 0.1f, 8.0f),
            f("bowMinUseDist", 7.5f, 11.0f, 0.1f, 9.0f),
            i("openVolleyMax", 1, 2, 1, 1),
            l("openSpacingMin", 480, 900, 10, 650),
            l("openSpacingMax", 620, 1200, 10, 900),
            f("openShotMinDist", 7.0f, 12.0f, 0.1f, 9.0f),
            l("reactiveCdMs", 420, 900, 10, 650),

            // Détection
            f("stillFrameThreshold", 0.008f, 0.02f, 0.0005f, 0.0125f),
            i("stillFramesNeeded", 6, 16, 1, 10),
            f("bowSlowThreshold", 0.03f, 0.10f, 0.005f, 0.06f),
            i("bowSlowFramesNeeded", 2, 6, 1, 3),

            // Réserves
            l("reserveTightMs", 6000, 14000, 200, 10000),
            i("earlyReserve", 2, 4, 1, 3),
            i("midReserve", 1, 3, 1, 2),

            // ROD
            l("rodCdCloseMsBase", 260, 480, 10, 340),
            l("rodCdFarMsBase", 380, 650, 10, 480),
            f("rodCdBiasMax", 1.05f, 1.40f, 0.01f, 1.25f),
            f("rodBanMeleeDist", 3.4f, 4.8f, 0.05f, 4.0f),
            f("rodCloseMin", 1.6f, 2.4f, 0.05f, 2.0f),
            f("rodCloseMax", 3.0f, 3.8f, 0.05f, 3.4f),
            f("rodMainMin", 2.6f, 3.6f, 0.05f, 3.0f),
            f("rodMainMax", 6.2f, 7.6f, 0.05f, 6.8f),
            f("rodInterceptMin", 5.2f, 6.4f, 0.05f, 5.8f),
            f("rodInterceptMax", 6.6f, 7.8f, 0.05f, 7.2f),
            f("rodMaxRangeHard", 6.8f, 7.8f, 0.05f, 7.2f),
            f("rodMidInstantMin", 5.0f, 6.4f, 0.05f, 5.5f),
            f("rodMidInstantMax", 6.2f, 7.4f, 0.05f, 7.0f),
            f("farThreshold", 9.0f, 13.0f, 0.1f, 11.0f),
            l("reentryRodGraceMs", 180, 520, 10, 300),

            // Holds
            i("rodHoldCloseMinMs", 100, 160, 2, 118),
            i("rodHoldCloseMaxMs", 130, 200, 2, 142),
            i("rodHoldMidMinMs", 180, 260, 2, 208),
            i("rodHoldMidMaxMs", 210, 280, 2, 232),

            // Anti-spam PASSIF
            i("rodAntiSpamClosePassiveMin", 300, 420, 5, 340),
            i("rodAntiSpamClosePassiveMax", 360, 520, 5, 420),
            i("rodAntiSpamMidPassiveMin", 460, 620, 5, 520),
            i("rodAntiSpamMidPassiveMax", 600, 780, 5, 680),
            i("rodAntiSpamFarPassiveMin", 480, 680, 5, 520),
            i("rodAntiSpamFarPassiveMax", 640, 820, 5, 700),

            // Anti-spam ACTIF
            i("rodAntiSpamCloseActiveMin", 220, 320, 5, 260),
            i("rodAntiSpamCloseActiveMax", 280, 380, 5, 320),
            i("rodAntiSpamMidActiveMin", 340, 460, 5, 380),
            i("rodAntiSpamMidActiveMax", 480, 640, 5, 520),
            i("rodAntiSpamFarActiveMin", 360, 520, 5, 400),
            i("rodAntiSpamFarActiveMax", 520, 680, 5, 560),

            // Parade
            f("parryCloseCancelDist", 10.0f, 20.0f, 0.2f, 15.0f),
            l("parryCooldownMs", 600, 1400, 10, 900),
            i("parryHoldMinMs", 520, 820, 5, 650),
            i("parryHoldMaxMs", 780, 1200, 5, 980),
            i("parryStickMinMs", 680, 1200, 10, 900),
            i("parryStickMaxMs", 1200, 2000, 10, 1500),
            l("parryJumpCd", 380, 820, 10, 580),
            l("allowParryDelayMs", 1800, 3600, 50, 2800),

            // Close-strafe
            i("closeBurstWindowMinMs", 240, 360, 5, 280),
            i("closeBurstWindowMaxMs", 360, 520, 5, 420),
            i("closeBurstFlipMinMs", 50, 90, 2, 60),
            i("closeBurstFlipMaxMs", 90, 140, 2, 110),
            i("closeHoldWindowMinMs", 180, 280, 5, 220),
            i("closeHoldWindowMaxMs", 280, 380, 5, 340),

            i("forwardStickMinMs", 180, 260, 5, 220),
            i("forwardStickMaxMs", 240, 320, 5, 280),
            i("meleeFocusMinMs", 260, 340, 5, 300),
            i("meleeFocusMaxMs", 300, 380, 5, 340),

            // Mid/long & band
            i("midStrafeSwitchMinMs", 720, 980, 10, 820),
            i("midStrafeSwitchMaxMs", 1000, 1400, 10, 1100),
            f("midTightRangeMin", 1.6f, 2.2f, 0.02f, 1.8f),
            f("midTightRangeMax", 3.0f, 4.2f, 0.02f, 3.6f),
            f("midTightEps", 0.015f, 0.06f, 0.002f, 0.03f),
            i("midTightFlipCooldownMs", 180, 420, 5, 260),
            f("randomStrafeBandMin", 7.0f, 9.0f, 0.1f, 8.0f),
            f("randomStrafeBandMax", 14.0f, 17.0f, 0.1f, 15.0f),

            // Anti-jump + premier saut
            f("antiJumpZoneDist", 7.0f, 8.6f, 0.1f, 7.8f),
            i("startupJumpForceMs", 120, 260, 5, 160)
        )
    }

    // ---------- Quantification ----------
    private fun kQuant(v: Double, step: Double): Double {
        val r = kotlin.math.round(v / step) * step
        return if (step >= 1.0) r.toInt().toDouble()
        else String.format("%.6f", r).toDouble()
    }

    // ---------- Normalisations min/max ----------
    private fun fixIntPair(map: MutableMap<String, Double>, kMin: String, kMax: String, gap: Int = 1) {
        val a = map[kMin]!!.toInt()
        var b = map[kMax]!!.toInt()
        if (b <= a) b = a + gap
        map[kMax] = b.toDouble()
    }
    private fun fixLongPair(map: MutableMap<String, Double>, kMin: String, kMax: String, gap: Long = 10L) {
        val a = map[kMin]!!.toLong()
        var b = map[kMax]!!.toLong()
        if (b <= a) b = a + gap
        map[kMax] = b.toDouble()
    }
    private fun fixFloatPair(map: MutableMap<String, Double>, kMin: String, kMax: String, eps: Float = 0.001f) {
        val a = map[kMin]!!.toFloat()
        var b = map[kMax]!!.toFloat()
        if (b < a + eps) b = a + eps
        map[kMax] = b.toDouble()
    }

    // ---------- Choix ----------
    fun pickParams(): ClassicParams {
        load()
        val plays = totalPlays().coerceAtLeast(1)
        val eps = max(0.05, 0.30 * exp(-plays / 120.0)) // ~0.30 -> ~0.08

        lastPick.clear()
        val picked = mutableMapOf<String, Double>()

        for (sp in specs) {
            val candidates = mutableListOf<Double>()
            var v = sp.min
            while (v <= sp.max + 1e-9) { candidates.add(kQuant(v, sp.step)); v += sp.step }

            val table = scores.getOrPut(sp.key) { mutableMapOf() }
            val explore = (Random.nextDouble() < eps) || table.isEmpty()

            val choice = if (explore) {
                candidates.random()
            } else {
                var best = sp.def
                var bestS = Double.NEGATIVE_INFINITY
                for (c in candidates) {
                    val s = table[c.toString()] ?: 0.0
                    if (s > bestS) { bestS = s; best = c }
                }
                best
            }

            picked[sp.key] = choice
            lastPick[sp.key] = choice.toString()
        }

        // Anti-crash: re-ordonner toutes les paires min/max
        fixIntPair(picked, "fullDrawMsMin", "fullDrawMsMax", 5)
        fixLongPair(picked, "openSpacingMin", "openSpacingMax", 10)
        fixIntPair(picked, "closeBurstWindowMinMs", "closeBurstWindowMaxMs", 2)
        fixIntPair(picked, "closeBurstFlipMinMs", "closeBurstFlipMaxMs", 1)
        fixIntPair(picked, "closeHoldWindowMinMs", "closeHoldWindowMaxMs", 2)
        fixIntPair(picked, "forwardStickMinMs", "forwardStickMaxMs", 2)
        fixIntPair(picked, "meleeFocusMinMs", "meleeFocusMaxMs", 2)
        fixIntPair(picked, "rodHoldCloseMinMs", "rodHoldCloseMaxMs", 1)
        fixIntPair(picked, "rodHoldMidMinMs", "rodHoldMidMaxMs", 1)

        fixIntPair(picked, "rodAntiSpamClosePassiveMin", "rodAntiSpamClosePassiveMax", 5)
        fixIntPair(picked, "rodAntiSpamMidPassiveMin", "rodAntiSpamMidPassiveMax", 5)
        fixIntPair(picked, "rodAntiSpamFarPassiveMin", "rodAntiSpamFarPassiveMax", 5)
        fixIntPair(picked, "rodAntiSpamCloseActiveMin", "rodAntiSpamCloseActiveMax", 5)
        fixIntPair(picked, "rodAntiSpamMidActiveMin", "rodAntiSpamMidActiveMax", 5)
        fixIntPair(picked, "rodAntiSpamFarActiveMin", "rodAntiSpamFarActiveMax", 5)

        fixFloatPair(picked, "rodCloseMin", "rodCloseMax", 0.01f)
        fixFloatPair(picked, "rodMainMin", "rodMainMax", 0.01f)
        fixFloatPair(picked, "rodInterceptMin", "rodInterceptMax", 0.01f)
        fixFloatPair(picked, "rodMidInstantMin", "rodMidInstantMax", 0.01f)
        fixIntPair(picked, "midStrafeSwitchMinMs", "midStrafeSwitchMaxMs", 10)
        fixFloatPair(picked, "midTightRangeMin", "midTightRangeMax", 0.01f)
        fixFloatPair(picked, "randomStrafeBandMin", "randomStrafeBandMax", 0.1f)

        fun gi(k: String) = picked[k]!!.toInt()
        fun gf(k: String) = picked[k]!!.toFloat()
        fun gl(k: String) = picked[k]!!.toLong()
        fun gd(k: String) = picked[k]!!

        return ClassicParams(
            gi("fullDrawMsMin"), gi("fullDrawMsMax"),
            gf("bowCancelCloseDist"), gf("bowMinUseDist"),
            gi("openVolleyMax"), gl("openSpacingMin"), gl("openSpacingMax"),
            gf("openShotMinDist"), gl("reactiveCdMs"),

            gd("stillFrameThreshold"), gi("stillFramesNeeded"),
            gd("bowSlowThreshold"), gi("bowSlowFramesNeeded"),

            gl("reserveTightMs"), gi("earlyReserve"), gi("midReserve"),

            gl("rodCdCloseMsBase"), gl("rodCdFarMsBase"), gf("rodCdBiasMax"), gf("rodBanMeleeDist"),
            gf("rodCloseMin"), gf("rodCloseMax"), gf("rodMainMin"), gf("rodMainMax"),
            gf("rodInterceptMin"), gf("rodInterceptMax"), gf("rodMaxRangeHard"),
            gf("rodMidInstantMin"), gf("rodMidInstantMax"), gf("farThreshold"),
            gl("reentryRodGraceMs"),

            gi("rodHoldCloseMinMs"), gi("rodHoldCloseMaxMs"),
            gi("rodHoldMidMinMs"), gi("rodHoldMidMaxMs"),

            gi("rodAntiSpamClosePassiveMin"), gi("rodAntiSpamClosePassiveMax"),
            gi("rodAntiSpamMidPassiveMin"), gi("rodAntiSpamMidPassiveMax"),
            gi("rodAntiSpamFarPassiveMin"), gi("rodAntiSpamFarPassiveMax"),

            gi("rodAntiSpamCloseActiveMin"), gi("rodAntiSpamCloseActiveMax"),
            gi("rodAntiSpamMidActiveMin"), gi("rodAntiSpamMidActiveMax"),
            gi("rodAntiSpamFarActiveMin"), gi("rodAntiSpamFarActiveMax"),

            gf("parryCloseCancelDist"), gl("parryCooldownMs"),
            gi("parryHoldMinMs"), gi("parryHoldMaxMs"),
            gi("parryStickMinMs"), gi("parryStickMaxMs"),
            gl("parryJumpCd"), gl("allowParryDelayMs"),

            gi("closeBurstWindowMinMs"), gi("closeBurstWindowMaxMs"),
            gi("closeBurstFlipMinMs"), gi("closeBurstFlipMaxMs"),
            gi("closeHoldWindowMinMs"), gi("closeHoldWindowMaxMs"),

            gi("forwardStickMinMs"), gi("forwardStickMaxMs"),
            gi("meleeFocusMinMs"), gi("meleeFocusMaxMs"),

            gi("midStrafeSwitchMinMs"), gi("midStrafeSwitchMaxMs"),
            gf("midTightRangeMin"), gf("midTightRangeMax"),
            gf("midTightEps"), gi("midTightFlipCooldownMs"),
            gf("randomStrafeBandMin"), gf("randomStrafeBandMax"),

            gf("antiJumpZoneDist"), gi("startupJumpForceMs")
        )
    }

    // ---------- Reporting ----------
    fun report(win: Boolean, mistakes: Int) {
        reportDetailed(GameMetrics(win = win, mistakes = mistakes))
    }

    fun takeAndResetMistakes(): Int { val m = mistakesCounter; mistakesCounter = 0; return m }
    fun addMistakes(n: Int = 1) { mistakesCounter += n }

    private const val TOP_N_KEEP = 16
    private fun prune(table: MutableMap<String, Double>) {
        if (table.size <= TOP_N_KEEP) return
        val top = table.entries.sortedByDescending { it.value }.take(TOP_N_KEEP)
        table.clear()
        for (e in top) table[e.key] = e.value
    }

    fun reportDetailed(m: GameMetrics) {
        load()

        // --- Score enrichi ---
        val quickWin = m.win && m.durationMs in 1..30_000
        val closeLoss = !m.win && (m.damageDealt >= m.damageTaken) // approximation

        val base = when {
            quickWin -> 1.2
            m.win    -> 1.0
            closeLoss -> 0.3
            else     -> 0.0
        }

        val dmgBonus = if (m.damageTaken == 0) 0.2 else
            (m.damageDealt.toDouble() / max(1.0, m.damageTaken.toDouble())).coerceIn(0.0, 2.0) * 0.2

        val rodAcc = if (m.rodAttempts > 0) (m.rodHits.toDouble() / m.rodAttempts).coerceIn(0.0, 1.0) else 0.0
        val bowAcc = if (m.bowShots > 0) (m.bowHits.toDouble() / m.bowShots).coerceIn(0.0, 1.0) else 0.0
        val accBonus = rodAcc * 0.15 + bowAcc * 0.15

        val mistakePenalty = m.mistakes * 0.08
        val reward = (base + dmgBonus + accBonus - mistakePenalty).coerceIn(0.0, 2.0)

        // --- Mise à jour pondérée ---
        for ((k, vStr) in lastPick) {
            val t = scores.getOrPut(k) { mutableMapOf() }
            val prev = t[vStr] ?: 0.0
            val lr = 0.25 // learning rate
            t[vStr] = prev * (1.0 - lr) + reward * lr
            prune(t)
        }

        // --- Historique & checkpoints ---
        recent.addLast(m.win)
        if (recent.size > 25) recent.removeFirst()
        val recentWinRate = recent.count { it }.toDouble() / recent.size.toDouble()

        // checkpoint si amélioration
        val bestWr = checkpoints.maxByOrNull { it.winRate }?.winRate ?: 0.0
        if (recent.size >= 10 && recentWinRate > bestWr) {
            checkpoints.addLast(Checkpoint(System.currentTimeMillis(), recentWinRate, HashMap(lastPick)))
            if (checkpoints.size > 5) checkpoints.removeFirst()
        }

        // rollback doux si régression forte
        if (recent.size >= 12 && recentWinRate < bestWr * 0.7 && checkpoints.isNotEmpty()) {
            val best = checkpoints.maxByOrNull { it.winRate }!!
            // On biaise la table vers la config gagnante
            for ((k, vStr) in best.config) {
                val t = scores.getOrPut(k) { mutableMapOf() }
                val prev = t[vStr] ?: 0.0
                t[vStr] = prev + 0.4
                prune(t)
            }
        }

        save()
    }

    // ---------- Valeurs par défaut sûres ----------
    fun defaults(): ClassicParams = ClassicParams(
        // ARC
        820, 980, 8.0f, 9.0f, 1, 650L, 900L, 9.0f, 650L,
        // Détection
        0.0125, 10, 0.06, 3,
        // Réserves
        10_000L, 3, 2,
        // ROD ranges
        340L, 480L, 1.25f, 4.0f,
        2.0f, 3.4f, 3.0f, 6.8f,
        5.8f, 7.2f, 7.2f,
        5.5f, 7.0f, 11.0f, 300L,
        // Holds & anti-spam
        118, 142, 208, 232,
        340, 420, 520, 680, 520, 700,
        260, 320, 380, 520, 400, 560,
        // Parade
        15.0f, 900L, 650, 980, 900, 1500, 580L, 2800L,
        // Close-strafe
        280, 420, 60, 110, 220, 340,
        220, 280, 300, 340,
        // Mid/long + band
        820, 1100, 1.8f, 3.6f, 0.03f, 260, 8.0f, 15.0f,
        // Anti-jump + premier saut
        7.8f, 160
    )
}
