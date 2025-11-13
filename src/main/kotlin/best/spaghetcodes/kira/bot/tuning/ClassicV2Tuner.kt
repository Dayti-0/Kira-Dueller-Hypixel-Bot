package best.spaghetcodes.kira.bot.tuning

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.minecraft.client.Minecraft
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Tuner epsilon-greedy persistant pour ClassicV2.
 * - persistance JSON: config/classicv2_tuner.json (dans mcDataDir)
 * - epsilon décroissant avec l'expérience
 * - specs identiques aux valeurs par défaut de ClassicV2 (± bornes réalistes)
 */
object ClassicV2Tuner {

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

        // Détection mouvement (Double pour matcher ClassicV2)
        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,

        // Réserves flèches
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

        // PARADE
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

        // MID/LONG strafe + random band + anti-jump Classic
        val midStrafeSwitchMinMs: Int,
        val midStrafeSwitchMaxMs: Int,
        val midTightRangeMin: Float,
        val midTightRangeMax: Float,
        val midTightEps: Float,
        val midTightFlipCooldownMs: Int,
        val randomStrafeBandMin: Float,
        val randomStrafeBandMax: Float,

        val antiJumpZoneDist: Float,
        val startupJumpForceMs: Int
    )

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

    // petit compteur d'erreurs rapportées par la partie
    private var mistakesCounter: Int = 0

    // ===================== Specs =====================
    private val specs: List<Spec> by lazy {
        fun i(key: String, min: Int, max: Int, step: Int, def: Int) =
            Spec(key, min.toDouble(), max.toDouble(), step.toDouble(), def.toDouble(), true)
        fun f(key: String, min: Float, max: Float, step: Float, def: Float) =
            Spec(key, min.toDouble(), max.toDouble(), step.toDouble(), def.toDouble(), false)
        fun l(key: String, min: Long, max: Long, step: Long, def: Long) =
            Spec(key, min.toDouble(), max.toDouble(), step.toDouble(), def.toDouble(), true)

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

            // Détection (Double côté ClassicV2, mais spec en float OK)
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

            // ROD hold & anti-spam
            i("rodHoldCloseMinMs", 100, 160, 2, 118),
            i("rodHoldCloseMaxMs", 130, 200, 2, 142),
            i("rodHoldMidMinMs", 180, 260, 2, 208),
            i("rodHoldMidMaxMs", 210, 280, 2, 232),

            i("rodAntiSpamClosePassiveMin", 300, 420, 5, 340),
            i("rodAntiSpamClosePassiveMax", 360, 520, 5, 420),
            i("rodAntiSpamMidPassiveMin", 460, 620, 5, 520),
            i("rodAntiSpamMidPassiveMax", 600, 780, 5, 680),
            i("rodAntiSpamFarPassiveMin", 480, 680, 5, 520),
            i("rodAntiSpamFarPassiveMax", 640, 820, 5, 700),

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

            // Mid/long strafe + band + anti-jump Classic
            i("midStrafeSwitchMinMs", 720, 980, 10, 820),
            i("midStrafeSwitchMaxMs", 1000, 1400, 10, 1100),
            f("midTightRangeMin", 1.6f, 2.2f, 0.02f, 1.8f),
            f("midTightRangeMax", 3.0f, 4.2f, 0.02f, 3.6f),
            f("midTightEps", 0.015f, 0.06f, 0.002f, 0.03f),
            i("midTightFlipCooldownMs", 180, 420, 5, 260),
            f("randomStrafeBandMin", 7.0f, 9.0f, 0.1f, 8.0f),
            f("randomStrafeBandMax", 14.0f, 17.0f, 0.1f, 15.0f),

            f("antiJumpZoneDist", 7.0f, 8.6f, 0.1f, 7.8f),
            i("startupJumpForceMs", 100, 260, 5, 160)
        )
    }

    // ===================== Persistence =====================
    private fun load() {
        if (file.exists()) {
            val type = object : TypeToken<MutableMap<String, MutableMap<String, Double>>>() {}.type
            scores = gson.fromJson(file.readText(), type) ?: mutableMapOf()
        }
    }

    private fun save() {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeText(gson.toJson(scores))
    }

    private fun totalPlays(): Int {
        var t = 0
        for (entry in scores.values) t += entry.size
        return t
    }

    // ===================== Choix & Crédit =====================
    fun pickParams(): ClassicParams {
        load()

        val plays = totalPlays().coerceAtLeast(1)
        val eps = max(0.05, 0.30 * exp(-plays / 120.0)) // ~0.30 -> ~0.08 après ~150 choix

        lastPick.clear()
        val picked = mutableMapOf<String, Double>()

        for (sp in specs) {
            val candidates = mutableListOf<Double>()
            var v = sp.min
            while (v <= sp.max + 1e-9) {
                candidates.add(kQuant(v, sp.step))
                v += sp.step
            }

            val table = scores.getOrPut(sp.key) { mutableMapOf() }
            val explore = (Random.nextDouble() < eps) || table.isEmpty()

            val choice = if (explore) {
                candidates.random()
            } else {
                var bestV = sp.def
                var bestS = Double.NEGATIVE_INFINITY
                for (c in candidates) {
                    val k = c.toString()
                    val s = table[k] ?: 0.0
                    if (s > bestS) { bestS = s; bestV = c }
                }
                bestV
            }

            picked[sp.key] = choice
            lastPick[sp.key] = choice.toString()
        }

        fun gi(k: String) = picked[k]!!.toInt()
        fun gf(k: String) = picked[k]!!.toFloat()
        fun gl(k: String) = picked[k]!!.toLong()
        fun gd(k: String) = picked[k]!!              // Double

        return ClassicParams(
            // ARC
            gi("fullDrawMsMin"), gi("fullDrawMsMax"),
            gf("bowCancelCloseDist"), gf("bowMinUseDist"),
            gi("openVolleyMax"), gl("openSpacingMin"), gl("openSpacingMax"),
            gf("openShotMinDist"), gl("reactiveCdMs"),

            // Détection (retourne Double)
            gd("stillFrameThreshold"),
            gi("stillFramesNeeded"),
            gd("bowSlowThreshold"),
            gi("bowSlowFramesNeeded"),

            // Réserves
            gl("reserveTightMs"), gi("earlyReserve"), gi("midReserve"),

            // ROD ranges
            gl("rodCdCloseMsBase"), gl("rodCdFarMsBase"), gf("rodCdBiasMax"), gf("rodBanMeleeDist"),
            gf("rodCloseMin"), gf("rodCloseMax"), gf("rodMainMin"), gf("rodMainMax"),
            gf("rodInterceptMin"), gf("rodInterceptMax"), gf("rodMaxRangeHard"),
            gf("rodMidInstantMin"), gf("rodMidInstantMax"), gf("farThreshold"),
            gl("reentryRodGraceMs"),

            // ROD hold & anti-spam
            gi("rodHoldCloseMinMs"), gi("rodHoldCloseMaxMs"),
            gi("rodHoldMidMinMs"), gi("rodHoldMidMaxMs"),

            gi("rodAntiSpamClosePassiveMin"), gi("rodAntiSpamClosePassiveMax"),
            gi("rodAntiSpamMidPassiveMin"), gi("rodAntiSpamMidPassiveMax"),
            gi("rodAntiSpamFarPassiveMin"), gi("rodAntiSpamFarPassiveMax"),

            gi("rodAntiSpamCloseActiveMin"), gi("rodAntiSpamCloseActiveMax"),
            gi("rodAntiSpamMidActiveMin"), gi("rodAntiSpamMidActiveMax"),
            gi("rodAntiSpamFarActiveMin"), gi("rodAntiSpamFarActiveMax"),

            // Parade
            gf("parryCloseCancelDist"), gl("parryCooldownMs"),
            gi("parryHoldMinMs"), gi("parryHoldMaxMs"),
            gi("parryStickMinMs"), gi("parryStickMaxMs"),
            gl("parryJumpCd"), gl("allowParryDelayMs"),

            // Close-strafe
            gi("closeBurstWindowMinMs"), gi("closeBurstWindowMaxMs"),
            gi("closeBurstFlipMinMs"), gi("closeBurstFlipMaxMs"),
            gi("closeHoldWindowMinMs"), gi("closeHoldWindowMaxMs"),

            gi("forwardStickMinMs"), gi("forwardStickMaxMs"),
            gi("meleeFocusMinMs"), gi("meleeFocusMaxMs"),

            // Mid/long strafe + band + anti-jump
            gi("midStrafeSwitchMinMs"), gi("midStrafeSwitchMaxMs"),
            gf("midTightRangeMin"), gf("midTightRangeMax"),
            gf("midTightEps"), gi("midTightFlipCooldownMs"),
            gf("randomStrafeBandMin"), gf("randomStrafeBandMax"),

            gf("antiJumpZoneDist"), gi("startupJumpForceMs")
        )
    }

    fun report(win: Boolean, mistakes: Int) {
        val base = if (win) 1.0 else -1.0
        val reward = base - mistakes * 0.1
        for ((k, v) in lastPick) {
            val table = scores.getOrPut(k) { mutableMapOf() }
            val prev = table[v] ?: 0.0
            table[v] = prev * 0.8 + reward * 0.2
        }
        save()
    }

    fun addMistakes(n: Int = 1) { mistakesCounter += n }
    fun takeAndResetMistakes(): Int {
        val m = mistakesCounter
        mistakesCounter = 0
        return m
    }

    fun defaults(): ClassicParams {
        // Les mêmes valeurs que les "def" des specs ci-dessus
        return ClassicParams(
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
            // ROD hold & anti-spam
            118, 142, 208, 232,
            340, 420, 520, 680, 520, 700,
            260, 320, 380, 520, 400, 560,
            // Parade
            15.0f, 900L, 650, 980, 900, 1500, 580L, 2800L,
            // Close-strafe
            280, 420, 60, 110, 220, 340,
            220, 280, 300, 340,
            // Mid/long strafe + band + anti-jump
            820, 1100, 1.8f, 3.6f, 0.03f, 260, 8.0f, 15.0f,
            7.8f, 160
        )
    }

    // ===================== Utils =====================
    private fun kQuant(v: Double, step: Double): Double {
        val r = kotlin.math.round(v / step) * step
        return if (step >= 1.0) r.toInt().toDouble()
        else String.format("%.6f", r).toDouble()
    }
}
