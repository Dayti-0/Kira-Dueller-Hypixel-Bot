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
                    tickYawChange = if (WorldUtils.airInFront(kira.mc.thePlayer, 4f)) {
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
            var left = RandomUtils.randomBool()

            val speed = RandomUtils.randomDoubleInRange(3.0, 9.0).toFloat()

            tickYawChange = if (left) -speed else speed
            TimeUtils.setTimeout(fun () {
                Movement.startForward()
                Movement.startSprinting()
                TimeUtils.setTimeout(fun () {
                    Movement.startJumping()
                }, RandomUtils.randomIntInRange(400, 800))
                intervals.add(TimeUtils.setInterval(fun () {
                    tickYawChange = if (WorldUtils.airInFront(kira.mc.thePlayer, 7f)) {
                        if (WorldUtils.airInFront(kira.mc.thePlayer, 3f)) {
                            RandomUtils.randomDoubleInRange(if (left) 9.5 else -9.5, if (left) 13.0 else -13.0).toFloat()
                        } else RandomUtils.randomDoubleInRange(if (left) 4.5 else -4.5, if (left) 7.0 else -7.0).toFloat()
                    } else {
                        0f
                    }
                }, 0, RandomUtils.randomIntInRange(50, 100)))
                intervals.add(TimeUtils.setTimeout(fun () {
                    intervals.add(TimeUtils.setInterval(fun () {
                        left = !left
                    }, 0, RandomUtils.randomIntInRange(5000, 10000)))
                }, RandomUtils.randomIntInRange(5000, 10000)))
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
