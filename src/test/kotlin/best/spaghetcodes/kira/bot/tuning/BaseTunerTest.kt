package best.spaghetcodes.kira.bot.tuning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import java.io.File

/**
 * Test implementation of BaseTuner for testing purposes.
 */
class TestTuner : BaseTuner<TestTuner.TestParams>(
    tunerName = "TestTuner",
    fileName = "test_tuner.json",
) {
    data class TestParams(
        val param1: Int,
        val param2: Float,
        val param3: Long,
        val minVal: Double,
        val maxVal: Double,
    )

    override val specs = listOf(
        specI("param1", 0.0, 100.0, 5.0, 50.0, ParamImportance.HIGH),
        specF("param2", 0.0, 1.0, 0.1, 0.5, ParamImportance.MEDIUM),
        specL("param3", 100.0, 1000.0, 50.0, 500.0, ParamImportance.LOW),
        specD("minVal", 0.0, 50.0, 1.0, 25.0, ParamImportance.CRITICAL,
            correlatedWith = listOf("maxVal")),
        specD("maxVal", 50.0, 100.0, 1.0, 75.0, ParamImportance.CRITICAL),
    )

    override val orderedPairs = listOf(
        "minVal" to "maxVal"
    )

    override fun buildParams(values: Map<String, Double>): TestParams {
        return TestParams(
            param1 = values.int("param1"),
            param2 = values.float("param2"),
            param3 = values.long("param3"),
            minVal = values.double("minVal"),
            maxVal = values.double("maxVal"),
        )
    }
}

class BaseTunerTest {

    private fun createTuner(): TestTuner {
        val tuner = TestTuner()
        tuner.reset() // Start fresh
        return tuner
    }

    @Test
    fun `pickParams returns valid parameter values`() {
        val tuner = createTuner()
        val params = tuner.pickParams()

        assertTrue(params.param1 in 0..100)
        assertTrue(params.param2 in 0.0f..1.0f)
        assertTrue(params.param3 in 100L..1000L)
    }

    @Test
    fun `defaults returns correct default values`() {
        val tuner = createTuner()
        val defaults = tuner.defaults()

        assertEquals(50, defaults.param1)
        assertEquals(0.5f, defaults.param2, 0.01f)
        assertEquals(500L, defaults.param3)
        assertEquals(25.0, defaults.minVal, 0.01)
        assertEquals(75.0, defaults.maxVal, 0.01)
    }

    @Test
    fun `orderedPairs are maintained after pickParams`() {
        val tuner = createTuner()

        repeat(10) {
            val params = tuner.pickParams()
            assertTrue(
                params.minVal <= params.maxVal,
                "minVal (${params.minVal}) should be <= maxVal (${params.maxVal})"
            )
        }
    }

    @Test
    fun `report updates state`() {
        val tuner = createTuner()

        // Pick params first
        tuner.pickParams()

        // Report a win
        tuner.report(win = true, mistakes = MistakeSummary.ZERO)

        // Get stats
        val stats = tuner.getStats()
        assertEquals(1L, stats.totalMatches)
        assertEquals(1L, stats.wins)
        assertEquals(0L, stats.losses)
    }

    @Test
    fun `multiple reports track wins and losses`() {
        val tuner = createTuner()

        repeat(5) {
            tuner.pickParams()
            tuner.report(win = true, mistakes = MistakeSummary.ZERO)
        }

        repeat(3) {
            tuner.pickParams()
            tuner.report(win = false, mistakes = MistakeSummary.ZERO)
        }

        val stats = tuner.getStats()
        assertEquals(8L, stats.totalMatches)
        assertEquals(5L, stats.wins)
        assertEquals(3L, stats.losses)
        assertEquals(0.625, stats.winRate, 0.001)
    }

    @Test
    fun `mistake tracking works`() {
        val tuner = createTuner()

        tuner.noteJumpMistake()
        tuner.noteJumpMistake()
        tuner.noteRodMistake()
        tuner.noteBowMistake()

        val mistakes = tuner.takeAndResetMistakes(rodHits = 5, rodMisses = 2, bowShots = 3)

        assertEquals(2, mistakes.mistakesJump)
        assertEquals(1, mistakes.mistakesRod)
        assertEquals(1, mistakes.mistakesBow)
        assertEquals(5, mistakes.rodHits)
        assertEquals(2, mistakes.rodMisses)
        assertEquals(3, mistakes.bowShots)

        // After reset, should be zero
        val resetMistakes = tuner.takeAndResetMistakes(0, 0, 0)
        assertEquals(0, resetMistakes.totalMistakes)
    }

    @Test
    fun `getLastPickedParams returns last picked`() {
        val tuner = createTuner()

        val params = tuner.pickParams()
        val lastPicked = tuner.getLastPickedParams()

        assertNotNull(lastPicked)
        assertEquals(params.param1, lastPicked.param1)
        assertEquals(params.param2, lastPicked.param2)
    }

    @Test
    fun `getLastPickedValue returns specific value`() {
        val tuner = createTuner()

        val params = tuner.pickParams()
        val param1Value = tuner.getLastPickedValue("param1")

        assertNotNull(param1Value)
        assertEquals(params.param1.toDouble(), param1Value, 0.001)
    }

    @Test
    fun `stats show explored parameters`() {
        val tuner = createTuner()

        repeat(20) {
            tuner.pickParams()
            tuner.report(win = it % 2 == 0, mistakes = MistakeSummary.ZERO)
        }

        val stats = tuner.getStats()

        // All params should have stats
        assertTrue(stats.paramStats.containsKey("param1"))
        assertTrue(stats.paramStats.containsKey("param2"))
        assertTrue(stats.paramStats.containsKey("param3"))

        // Should have explored some values
        val param1Stats = stats.paramStats["param1"]!!
        assertTrue(param1Stats.valuesExplored > 0)
        assertTrue(param1Stats.totalPlays > 0)
    }

    @Test
    fun `ParamSpec quantize works correctly`() {
        val spec = BaseTuner.ParamSpec(
            key = "test",
            min = 0.0,
            max = 100.0,
            step = 5.0,
            def = 50.0,
            type = BaseTuner.ParamType.INT
        )

        assertEquals(50.0, spec.quantize(52.0))
        assertEquals(55.0, spec.quantize(53.0))
        assertEquals(0.0, spec.quantize(2.0))
        assertEquals(100.0, spec.quantize(98.0))
    }

    @Test
    fun `ParamSpec clamp respects bounds and type`() {
        val intSpec = BaseTuner.ParamSpec(
            key = "intParam",
            min = 0.0,
            max = 100.0,
            step = 1.0,
            def = 50.0,
            type = BaseTuner.ParamType.INT
        )

        val floatSpec = BaseTuner.ParamSpec(
            key = "floatParam",
            min = 0.0,
            max = 1.0,
            step = 0.1,
            def = 0.5,
            type = BaseTuner.ParamType.FLOAT
        )

        // Test clamping
        assertEquals(0.0, intSpec.clamp(-10.0))
        assertEquals(100.0, intSpec.clamp(150.0))
        assertEquals(50.0, intSpec.clamp(50.0))

        // Test type conversion (INT should round)
        assertEquals(50.0, intSpec.clamp(50.4))
        assertEquals(51.0, intSpec.clamp(50.6))

        // FLOAT keeps decimals
        assertEquals(0.5, floatSpec.clamp(0.5), 0.001)
    }

    @Test
    fun `ParamSpec sample generates values in range`() {
        val spec = BaseTuner.ParamSpec(
            key = "test",
            min = 10.0,
            max = 20.0,
            step = 1.0,
            def = 15.0,
            type = BaseTuner.ParamType.INT
        )

        repeat(100) {
            val sampled = spec.sample()
            assertTrue(sampled in 10.0..20.0, "Sampled value $sampled out of range")
        }
    }

    @Test
    fun `importance levels affect exploration`() {
        // This is more of an integration test to verify importance levels work
        val tuner = createTuner()

        // Run many iterations
        repeat(50) {
            tuner.pickParams()
            tuner.report(win = true, mistakes = MistakeSummary.ZERO)
        }

        val stats = tuner.getStats()

        // All params should have been explored
        for ((key, paramStats) in stats.paramStats) {
            assertTrue(paramStats.totalPlays > 0, "Parameter $key should have plays")
        }
    }

    @Test
    fun `reset clears all state`() {
        val tuner = createTuner()

        // Build up some state
        repeat(10) {
            tuner.pickParams()
            tuner.report(win = true, mistakes = MistakeSummary.ZERO)
        }

        var stats = tuner.getStats()
        assertEquals(10L, stats.totalMatches)

        // Reset
        tuner.reset()

        stats = tuner.getStats()
        assertEquals(0L, stats.totalMatches)
        assertEquals(0L, stats.wins)
        assertEquals(0L, stats.losses)
    }
}
