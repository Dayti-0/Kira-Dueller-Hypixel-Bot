package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.Timer

object LobbyMovement {

    private var tickYawChange = 0f
    private var initialYaw = 0f
    private var lastDirectionChange = 0L
    private var intervals: ArrayList<Timer?> = ArrayList()

    fun sumo() {
        /*val opt = RandomUtils.randomIntInRange(0, 1)
        when (opt) {
            0 -> sumo1()
            1 -> twerk()
        }*/
        sumo1()
    }

    fun generic() {
        if (kira.mc.thePlayer != null) {
            Movement.startForward()
            Movement.startSprinting()
            initialYaw = kira.mc.thePlayer.rotationYaw
            lastDirectionChange = System.currentTimeMillis()

            intervals.add(TimeUtils.setInterval(
                fun () {
                    if (RandomUtils.randomBool()) {
                        Movement.singleJump(RandomUtils.randomIntInRange(120, 200))
                    } else {
                        if (Movement.jumping()) {
                            Movement.stopJumping()
                        } else {
                            Movement.startJumping()
                        }
                    }
                },
                RandomUtils.randomIntInRange(400, 800),
                RandomUtils.randomIntInRange(900, 1800)
            ))

            intervals.add(TimeUtils.setInterval(
                fun () {
                    val now = System.currentTimeMillis()
                    tickYawChange = if (WorldUtils.isObstacleAhead(kira.mc.thePlayer, 4f) || now - lastDirectionChange > 7000) {
                        lastDirectionChange = now
                        RandomUtils.randomDoubleInRange(-13.0, 13.0).toFloat()
                    } else {
                        0f
                    }
                },
                0,
                RandomUtils.randomIntInRange(50, 100)
            ))
        }
    }

    fun stop() {
        Movement.clearAll()
        tickYawChange = 0f
        intervals.forEach { it?.cancel() }
    }

    private fun sumo1() {
        if (kira.mc.thePlayer != null) {
            val speed = RandomUtils.randomDoubleInRange(3.0, 9.0).toFloat()
            val dir = if (RandomUtils.randomBool()) -1 else 1
            tickYawChange = speed * dir
            lastDirectionChange = System.currentTimeMillis()
            TimeUtils.setTimeout(fun () {
                Movement.startForward()
                Movement.startSprinting()
                TimeUtils.setTimeout(fun () {
                    Movement.startJumping()
                }, RandomUtils.randomIntInRange(400, 800))
                intervals.add(TimeUtils.setInterval(fun () {
                    val now = System.currentTimeMillis()
                    val needTurn = WorldUtils.isObstacleAhead(kira.mc.thePlayer, 7f) || now - lastDirectionChange > 7000
                    tickYawChange = if (needTurn) {
                        lastDirectionChange = now
                        val turnDir = if (RandomUtils.randomBool()) 1 else -1
                        if (WorldUtils.isObstacleAhead(kira.mc.thePlayer, 3f)) {
                            RandomUtils.randomDoubleInRange(9.5, 13.0).toFloat() * turnDir
                        } else {
                            RandomUtils.randomDoubleInRange(4.5, 7.0).toFloat() * turnDir
                        }
                    } else {
                        0f
                    }
                }, 0, RandomUtils.randomIntInRange(50, 100)))
            }, RandomUtils.randomIntInRange(100, 250))
        }
    }

    private fun twerk() {
        intervals.add(TimeUtils.setInterval(
            fun () {
                if (Movement.sneaking()) {
                    Movement.stopSneaking()
                } else {
                    Movement.startSneaking()
                }
        }, RandomUtils.randomIntInRange(500, 900), RandomUtils.randomIntInRange(200, 500)))
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onTick(event: ClientTickEvent) {
        if (kira.bot?.toggled() == true && kira.config?.lobbyMovement == true && tickYawChange != 0f &&
            kira.mc.thePlayer != null && StateManager.state != StateManager.States.PLAYING
        ) {
            kira.mc.thePlayer.rotationYaw += tickYawChange
        }
    }
}
