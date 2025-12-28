package best.spaghetcodes.kira.bot.tuning

/**
 * ClassicV3Tuner - Modern implementation using BaseTuner framework.
 *
 * This tuner demonstrates how to migrate from the legacy ClassicV2Tuner pattern
 * to the new BaseTuner framework, which provides:
 *
 * - Automatic persistence and state management
 * - Multiple bandit algorithms (UCB1, UCB-Tuned, Thompson Sampling)
 * - Adaptive exploration based on parameter importance
 * - Correlated parameter group optimization
 * - Built-in statistics and debugging
 * - Robust error handling and backup
 *
 * To migrate your own tuner:
 * 1. Extend BaseTuner<YourParamsClass>
 * 2. Define your parameter specs using specI/specF/specL/specD
 * 3. Implement buildParams() to construct your params class
 * 4. Optionally override orderedPairs for min/max constraints
 */
object ClassicV3Tuner : BaseTuner<ClassicV3Tuner.ClassicParams>(
    tunerName = "ClassicV3Tuner",
    fileName = "classicv3_tuner.json",
) {

    // ========================== PARAMS DATA CLASS ==========================

    data class ClassicParams(
        // BOW (opening & reactive)
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

        // Movement detection
        val stillFrameThreshold: Double,
        val stillFramesNeeded: Int,
        val bowSlowThreshold: Double,
        val bowSlowFramesNeeded: Int,

        // Arrow reserves
        val reserveTightMs: Long,
        val earlyReserve: Int,
        val midReserve: Int,

        // ROD (cooldowns, ranges)
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

        // Sword parry
        val parryCloseCancelDist: Float,
        val parryCooldownMs: Long,
        val parryHoldMinMs: Int,
        val parryHoldMaxMs: Int,
        val parryStickMinMs: Int,
        val parryStickMaxMs: Int,
        val parryJumpCd: Long,
        val allowParryDelayMs: Long,

        // Close strafe
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

        // Jump tuning
        val antiJumpZoneDist: Float,
        val startupJumpDelayMs: Int,
        val continuousJumpMinIntervalMs: Int,
    )

    // ========================== PARAMETER SPECIFICATIONS ==========================

    override val specs = listOf(
        // BOW - Core shooting parameters (CRITICAL importance)
        specI("fullDrawMsMin", 770.0, 810.0, 10.0, 780.0, ParamImportance.HIGH),
        specI("fullDrawMsMax", 1070.0, 1110.0, 10.0, 1090.0, ParamImportance.HIGH,
            correlatedWith = listOf("fullDrawMsMin")),
        specF("bowCancelCloseDist", 6.0, 8.0, 0.1, 6.8, ParamImportance.CRITICAL,
            optimal = 6.8),
        specF("bowMinUseDist", 7.0, 10.0, 0.1, 8.9, ParamImportance.HIGH),
        specF("bowAimPitchBias", -0.3, 0.1, 0.05, -0.1, ParamImportance.CRITICAL,
            optimal = -0.1),
        specI("bowDrawParryEnabled", 0.0, 1.0, 1.0, 1.0, ParamImportance.LOW),

        specI("openVolleyMax", 1.0, 1.0, 1.0, 1.0, ParamImportance.LOW),

        specL("openSpacingMin", 650.0, 850.0, 10.0, 750.0, ParamImportance.MEDIUM),
        specL("openSpacingMax", 850.0, 920.0, 10.0, 900.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("openSpacingMin")),
        specF("openShotMinDist", 9.5, 11.0, 0.1, 10.1, ParamImportance.MEDIUM),
        specL("reactiveCdMs", 550.0, 700.0, 10.0, 610.0, ParamImportance.HIGH),

        // Movement detection
        specD("stillFrameThreshold", 0.015, 0.022, 0.0005, 0.0195, ParamImportance.CRITICAL,
            optimal = 0.0195),
        specI("stillFramesNeeded", 6.0, 16.0, 1.0, 10.0, ParamImportance.MEDIUM),
        specD("bowSlowThreshold", 0.04, 0.07, 0.002, 0.05, ParamImportance.CRITICAL,
            optimal = 0.05),
        specI("bowSlowFramesNeeded", 2.0, 6.0, 1.0, 2.0, ParamImportance.MEDIUM),

        // Arrow reserves
        specL("reserveTightMs", 11000.0, 13000.0, 100.0, 12300.0, ParamImportance.HIGH,
            optimal = 12300.0),
        specI("earlyReserve", 2.0, 4.0, 1.0, 2.0, ParamImportance.LOW),
        specI("midReserve", 2.0, 4.0, 1.0, 3.0, ParamImportance.LOW),

        // ROD - Cooldowns (CRITICAL for rod timing)
        specL("rodCdCloseMsBase", 320.0, 400.0, 10.0, 340.0, ParamImportance.CRITICAL,
            optimal = 340.0),
        specL("rodCdFarMsBase", 480.0, 580.0, 10.0, 520.0, ParamImportance.CRITICAL,
            optimal = 520.0),
        specF("rodCdBiasMax", 1.05, 1.5, 0.01, 1.25, ParamImportance.MEDIUM),
        specF("rodBanMeleeDist", 4.2, 5.0, 0.05, 4.85, ParamImportance.HIGH),

        // ROD ranges
        specF("rodCloseMin", 1.6, 2.6, 0.05, 2.0, ParamImportance.HIGH),
        specF("rodCloseMax", 2.6, 4.0, 0.05, 2.7, ParamImportance.HIGH,
            correlatedWith = listOf("rodCloseMin")),
        specF("rodMainMin", 3.2, 3.8, 0.05, 3.55, ParamImportance.CRITICAL,
            optimal = 3.55),
        specF("rodMainMax", 6.0, 7.0, 0.05, 6.5, ParamImportance.CRITICAL,
            optimal = 6.5, correlatedWith = listOf("rodMainMin")),
        specF("rodInterceptMin", 4.8, 6.4, 0.05, 5.8, ParamImportance.MEDIUM),
        specF("rodInterceptMax", 6.2, 8.2, 0.05, 7.2, ParamImportance.MEDIUM,
            correlatedWith = listOf("rodInterceptMin")),
        specF("rodMaxRangeHard", 6.5, 8.0, 0.05, 7.2, ParamImportance.HIGH),
        specF("rodMidInstantMin", 4.8, 6.2, 0.05, 5.5, ParamImportance.MEDIUM),
        specF("rodMidInstantMax", 6.2, 7.6, 0.05, 7.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("rodMidInstantMin")),
        specF("farThreshold", 9.0, 14.0, 0.1, 11.0, ParamImportance.MEDIUM),
        specL("reentryRodGraceMs", 200.0, 500.0, 10.0, 300.0, ParamImportance.LOW),

        // ROD hold timing
        specI("rodHoldCloseMinMs", 90.0, 160.0, 5.0, 118.0, ParamImportance.MEDIUM),
        specI("rodHoldCloseMaxMs", 110.0, 190.0, 5.0, 142.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("rodHoldCloseMinMs")),
        specI("rodHoldMidMinMs", 180.0, 220.0, 5.0, 190.0, ParamImportance.MEDIUM),
        specI("rodHoldMidMaxMs", 180.0, 300.0, 5.0, 232.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("rodHoldMidMinMs")),

        // Rod anti-spam
        specI("rodAntiSpamClosePassiveMin", 340.0, 400.0, 10.0, 350.0, ParamImportance.LOW),
        specI("rodAntiSpamClosePassiveMax", 340.0, 520.0, 10.0, 340.0, ParamImportance.LOW,
            correlatedWith = listOf("rodAntiSpamClosePassiveMin")),
        specI("rodAntiSpamMidPassiveMin", 400.0, 640.0, 10.0, 420.0, ParamImportance.LOW),
        specI("rodAntiSpamMidPassiveMax", 520.0, 820.0, 10.0, 680.0, ParamImportance.LOW,
            correlatedWith = listOf("rodAntiSpamMidPassiveMin")),
        specI("rodAntiSpamFarPassiveMin", 400.0, 640.0, 10.0, 520.0, ParamImportance.LOW),
        specI("rodAntiSpamFarPassiveMax", 540.0, 860.0, 10.0, 700.0, ParamImportance.LOW,
            correlatedWith = listOf("rodAntiSpamFarPassiveMin")),

        specI("rodAntiSpamCloseActiveMin", 270.0, 320.0, 10.0, 290.0, ParamImportance.LOW),
        specI("rodAntiSpamCloseActiveMax", 260.0, 420.0, 10.0, 320.0, ParamImportance.LOW,
            correlatedWith = listOf("rodAntiSpamCloseActiveMin")),
        specI("rodAntiSpamMidActiveMin", 280.0, 480.0, 10.0, 380.0, ParamImportance.LOW),
        specI("rodAntiSpamMidActiveMax", 400.0, 640.0, 10.0, 430.0, ParamImportance.LOW,
            correlatedWith = listOf("rodAntiSpamMidActiveMin")),
        specI("rodAntiSpamFarActiveMin", 320.0, 520.0, 10.0, 400.0, ParamImportance.LOW),
        specI("rodAntiSpamFarActiveMax", 420.0, 700.0, 10.0, 560.0, ParamImportance.LOW,
            correlatedWith = listOf("rodAntiSpamFarActiveMin")),

        // Parry
        specF("parryCloseCancelDist", 11.0, 19.0, 0.2, 15.0, ParamImportance.MEDIUM),
        specL("parryCooldownMs", 600.0, 1200.0, 10.0, 900.0, ParamImportance.MEDIUM),
        specI("parryHoldMinMs", 520.0, 820.0, 10.0, 650.0, ParamImportance.MEDIUM),
        specI("parryHoldMaxMs", 820.0, 1200.0, 10.0, 980.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("parryHoldMinMs")),
        specI("parryStickMinMs", 720.0, 1100.0, 10.0, 900.0, ParamImportance.MEDIUM),
        specI("parryStickMaxMs", 1200.0, 1800.0, 10.0, 1500.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("parryStickMinMs")),
        specL("parryJumpCd", 400.0, 800.0, 10.0, 580.0, ParamImportance.LOW),
        specL("allowParryDelayMs", 2000.0, 3600.0, 50.0, 2800.0, ParamImportance.LOW),

        // Close strafe
        specI("closeBurstWindowMinMs", 200.0, 360.0, 10.0, 210.0, ParamImportance.HIGH),
        specI("closeBurstWindowMaxMs", 320.0, 520.0, 10.0, 300.0, ParamImportance.HIGH,
            correlatedWith = listOf("closeBurstWindowMinMs")),
        specI("closeBurstFlipMinMs", 40.0, 100.0, 5.0, 60.0, ParamImportance.MEDIUM),
        specI("closeBurstFlipMaxMs", 80.0, 160.0, 5.0, 110.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("closeBurstFlipMinMs")),
        specI("closeHoldWindowMinMs", 160.0, 300.0, 10.0, 200.0, ParamImportance.MEDIUM),
        specI("closeHoldWindowMaxMs", 240.0, 300.0, 10.0, 260.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("closeHoldWindowMinMs")),

        // Post-hit
        specI("forwardStickMinMs", 160.0, 300.0, 10.0, 170.0, ParamImportance.HIGH),
        specI("forwardStickMaxMs", 250.0, 310.0, 10.0, 270.0, ParamImportance.HIGH,
            correlatedWith = listOf("forwardStickMinMs")),
        specI("meleeFocusMinMs", 320.0, 360.0, 10.0, 340.0, ParamImportance.MEDIUM),
        specI("meleeFocusMaxMs", 260.0, 420.0, 10.0, 390.0, ParamImportance.MEDIUM,
            correlatedWith = listOf("meleeFocusMinMs")),

        // Jump
        specF("antiJumpZoneDist", 8.0, 8.4, 0.05, 8.15, ParamImportance.HIGH),
        specI("startupJumpDelayMs", 255.0, 270.0, 5.0, 260.0, ParamImportance.MEDIUM),
        specI("continuousJumpMinIntervalMs", 180.0, 210.0, 5.0, 190.0, ParamImportance.MEDIUM),
    )

    // ========================== ORDERED PAIRS ==========================

    override val orderedPairs = listOf(
        "fullDrawMsMin" to "fullDrawMsMax",
        "openSpacingMin" to "openSpacingMax",
        "rodCloseMin" to "rodCloseMax",
        "rodMainMin" to "rodMainMax",
        "rodInterceptMin" to "rodInterceptMax",
        "rodMidInstantMin" to "rodMidInstantMax",
        "rodHoldCloseMinMs" to "rodHoldCloseMaxMs",
        "rodHoldMidMinMs" to "rodHoldMidMaxMs",
        "rodAntiSpamClosePassiveMin" to "rodAntiSpamClosePassiveMax",
        "rodAntiSpamMidPassiveMin" to "rodAntiSpamMidPassiveMax",
        "rodAntiSpamFarPassiveMin" to "rodAntiSpamFarPassiveMax",
        "rodAntiSpamCloseActiveMin" to "rodAntiSpamCloseActiveMax",
        "rodAntiSpamMidActiveMin" to "rodAntiSpamMidActiveMax",
        "rodAntiSpamFarActiveMin" to "rodAntiSpamFarActiveMax",
        "parryHoldMinMs" to "parryHoldMaxMs",
        "parryStickMinMs" to "parryStickMaxMs",
        "closeBurstWindowMinMs" to "closeBurstWindowMaxMs",
        "closeBurstFlipMinMs" to "closeBurstFlipMaxMs",
        "closeHoldWindowMinMs" to "closeHoldWindowMaxMs",
        "forwardStickMinMs" to "forwardStickMaxMs",
        "meleeFocusMinMs" to "meleeFocusMaxMs",
    )

    // ========================== BUILD PARAMS ==========================

    override fun buildParams(values: Map<String, Double>): ClassicParams = ClassicParams(
        fullDrawMsMin = values.int("fullDrawMsMin"),
        fullDrawMsMax = values.int("fullDrawMsMax"),
        bowCancelCloseDist = values.float("bowCancelCloseDist"),
        bowMinUseDist = values.float("bowMinUseDist"),
        bowAimPitchBias = values.float("bowAimPitchBias"),
        bowDrawParryEnabled = values.int("bowDrawParryEnabled"),
        openVolleyMax = values.int("openVolleyMax"),
        openSpacingMin = values.long("openSpacingMin"),
        openSpacingMax = values.long("openSpacingMax"),
        openShotMinDist = values.float("openShotMinDist"),
        reactiveCdMs = values.long("reactiveCdMs"),

        stillFrameThreshold = values.double("stillFrameThreshold"),
        stillFramesNeeded = values.int("stillFramesNeeded"),
        bowSlowThreshold = values.double("bowSlowThreshold"),
        bowSlowFramesNeeded = values.int("bowSlowFramesNeeded"),

        reserveTightMs = values.long("reserveTightMs"),
        earlyReserve = values.int("earlyReserve"),
        midReserve = values.int("midReserve"),

        rodCdCloseMsBase = values.long("rodCdCloseMsBase"),
        rodCdFarMsBase = values.long("rodCdFarMsBase"),
        rodCdBiasMax = values.float("rodCdBiasMax"),
        rodBanMeleeDist = values.float("rodBanMeleeDist"),
        rodCloseMin = values.float("rodCloseMin"),
        rodCloseMax = values.float("rodCloseMax"),
        rodMainMin = values.float("rodMainMin"),
        rodMainMax = values.float("rodMainMax"),
        rodInterceptMin = values.float("rodInterceptMin"),
        rodInterceptMax = values.float("rodInterceptMax"),
        rodMaxRangeHard = values.float("rodMaxRangeHard"),
        rodMidInstantMin = values.float("rodMidInstantMin"),
        rodMidInstantMax = values.float("rodMidInstantMax"),
        farThreshold = values.float("farThreshold"),
        reentryRodGraceMs = values.long("reentryRodGraceMs"),

        rodHoldCloseMinMs = values.int("rodHoldCloseMinMs"),
        rodHoldCloseMaxMs = values.int("rodHoldCloseMaxMs"),
        rodHoldMidMinMs = values.int("rodHoldMidMinMs"),
        rodHoldMidMaxMs = values.int("rodHoldMidMaxMs"),

        rodAntiSpamClosePassiveMin = values.int("rodAntiSpamClosePassiveMin"),
        rodAntiSpamClosePassiveMax = values.int("rodAntiSpamClosePassiveMax"),
        rodAntiSpamMidPassiveMin = values.int("rodAntiSpamMidPassiveMin"),
        rodAntiSpamMidPassiveMax = values.int("rodAntiSpamMidPassiveMax"),
        rodAntiSpamFarPassiveMin = values.int("rodAntiSpamFarPassiveMin"),
        rodAntiSpamFarPassiveMax = values.int("rodAntiSpamFarPassiveMax"),

        rodAntiSpamCloseActiveMin = values.int("rodAntiSpamCloseActiveMin"),
        rodAntiSpamCloseActiveMax = values.int("rodAntiSpamCloseActiveMax"),
        rodAntiSpamMidActiveMin = values.int("rodAntiSpamMidActiveMin"),
        rodAntiSpamMidActiveMax = values.int("rodAntiSpamMidActiveMax"),
        rodAntiSpamFarActiveMin = values.int("rodAntiSpamFarActiveMin"),
        rodAntiSpamFarActiveMax = values.int("rodAntiSpamFarActiveMax"),

        parryCloseCancelDist = values.float("parryCloseCancelDist"),
        parryCooldownMs = values.long("parryCooldownMs"),
        parryHoldMinMs = values.int("parryHoldMinMs"),
        parryHoldMaxMs = values.int("parryHoldMaxMs"),
        parryStickMinMs = values.int("parryStickMinMs"),
        parryStickMaxMs = values.int("parryStickMaxMs"),
        parryJumpCd = values.long("parryJumpCd"),
        allowParryDelayMs = values.long("allowParryDelayMs"),

        closeBurstWindowMinMs = values.int("closeBurstWindowMinMs"),
        closeBurstWindowMaxMs = values.int("closeBurstWindowMaxMs"),
        closeBurstFlipMinMs = values.int("closeBurstFlipMinMs"),
        closeBurstFlipMaxMs = values.int("closeBurstFlipMaxMs"),
        closeHoldWindowMinMs = values.int("closeHoldWindowMinMs"),
        closeHoldWindowMaxMs = values.int("closeHoldWindowMaxMs"),

        forwardStickMinMs = values.int("forwardStickMinMs"),
        forwardStickMaxMs = values.int("forwardStickMaxMs"),
        meleeFocusMinMs = values.int("meleeFocusMinMs"),
        meleeFocusMaxMs = values.int("meleeFocusMaxMs"),

        antiJumpZoneDist = values.float("antiJumpZoneDist"),
        startupJumpDelayMs = values.int("startupJumpDelayMs"),
        continuousJumpMinIntervalMs = values.int("continuousJumpMinIntervalMs"),
    )

    // ========================== CONVENIENCE METHODS ==========================

    /**
     * Helper for close jump detection.
     */
    fun noteCloseJump(distance: Float, holdingBow: Boolean) {
        val zone = getLastPickedParams()?.antiJumpZoneDist ?: 8.0f
        if (holdingBow || distance <= zone) {
            noteJumpMistake()
        }
    }

    /**
     * Get the last picked bow aim pitch bias.
     */
    fun lastBowAimPitchBias(): Float? = getLastPickedParams()?.bowAimPitchBias
}
