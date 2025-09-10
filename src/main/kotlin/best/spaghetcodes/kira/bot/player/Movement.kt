package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.settings.KeyBinding

object Movement {
    private var forward = false
    private var backward = false
    private var left = false
    private var right = false
    private var jumping = false
    private var sprinting = false
    private var sneaking = false

    fun startForward() {
        if (kira.bot?.toggled() == true) { // need to do this because the type is Boolean? so it could be null
            forward = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindForward.keyCode, true)
        }
    }

    fun stopForward() {
        if (kira.bot?.toggled() == true) {
            forward = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindForward.keyCode, false)
        }
    }

    fun startBackward() {
        if (kira.bot?.toggled() == true) {
            backward = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindBack.keyCode, true)
        }
    }

    fun stopBackward() {
        if (kira.bot?.toggled() == true) {
            backward = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindBack.keyCode, false)
        }
    }

    fun startLeft() {
        if (kira.bot?.toggled() == true) {
            left = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindLeft.keyCode, true)
        }
    }

    fun stopLeft() {
        if (kira.bot?.toggled() == true) {
            left = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindLeft.keyCode, false)
        }
    }

    fun startRight() {
        if (kira.bot?.toggled() == true) {
            right = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindRight.keyCode, true)
        }
    }

    fun stopRight() {
        if (kira.bot?.toggled() == true) {
            right = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindRight.keyCode, false)
        }
    }

    fun startJumping() {
        if (kira.bot?.toggled() == true) {
            jumping = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindJump.keyCode, true)
        }
    }

    fun stopJumping() {
        if (kira.bot?.toggled() == true) {
            jumping = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindJump.keyCode, false)
        }
    }

    fun startSprinting() {
        if (kira.bot?.toggled() == true) {
            sprinting = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindSprint.keyCode, true)
        }
    }

    fun stopSprinting() {
        if (kira.bot?.toggled() == true) {
            sprinting = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindSprint.keyCode, false)
        }
    }

    fun startSneaking() {
        if (kira.bot?.toggled() == true) {
            sneaking = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindSneak.keyCode, true)
        }
    }

    fun stopSneaking() {
        if (kira.bot?.toggled() == true) {
            sneaking = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindSneak.keyCode, false)
        }
    }

    fun singleJump(holdDuration: Int) {
        startJumping()
        TimeUtils.setTimeout(this::stopJumping, holdDuration)
    }

    fun clearAll() {
        stopForward()
        stopBackward()
        stopLeft()
        stopRight()
        stopJumping()
        stopSprinting()
        stopSneaking()
    }

    fun clearLeftRight() {
        stopLeft()
        stopRight()
    }

    fun swapLeftRight() {
        if (left) {
            stopLeft()
            startRight()
        } else if (right) {
            stopRight()
            startLeft()
        }
        Mouse.blockAim(RandomUtils.randomIntInRange(1, 2))
    }

    fun forward(): Boolean {
        return forward
    }

    fun backward(): Boolean {
        return backward
    }

    fun left(): Boolean {
        return left
    }

    fun right(): Boolean {
        return right
    }

    fun jumping(): Boolean {
        return jumping
    }

    fun sprinting(): Boolean {
        return sprinting
    }

    fun sneaking(): Boolean {
        return sneaking
    }

}