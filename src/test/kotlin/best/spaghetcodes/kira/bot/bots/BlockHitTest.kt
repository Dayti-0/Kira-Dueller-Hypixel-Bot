package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.ChatUtils
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.TimeUtils
import io.mockk.*
import net.minecraft.client.Minecraft
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import kotlin.test.*

class BlockHitTest {

    @BeforeTest
    fun setup() {
        mockkStatic(Minecraft::class)
        val mc = mockk<Minecraft>(relaxed = true)
        every { Minecraft.getMinecraft() } returns mc

        mockkObject(ChatUtils)
        every { ChatUtils.info(any()) } just Runs

        mockkObject(Combat)
        every { Combat.wTap(any()) } just Runs

        mockkObject(Movement)
        every { Movement.clearLeftRight() } just Runs

        mockkObject(TimeUtils)
        every { TimeUtils.setTimeout(any(), any()) } returns null

        mockkObject(Mouse)
        every { Mouse.leftClick() } just Runs
        every { Mouse.rClick(any()) } just Runs

        mockkObject(EntityUtils)
        every { EntityUtils.getDistanceNoY(any(), any()) } returns 1f
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun blitzOnAttackTriggersLeftClick() {
        val bot = Blitz()
        bot.onAttack()
        verify(exactly = 1) { Mouse.leftClick() }
    }

    @Test
    fun boxingOnAttackTriggersLeftClick() {
        val bot = Boxing()
        bot.onAttack()
        verify(exactly = 1) { Mouse.leftClick() }
    }

    @Test
    fun opOnAttackTriggersLeftClick() {
        val bot = OP()

        val player = mockk<EntityPlayer>(relaxed = true)
        val sword = mockk<ItemStack>(relaxed = true)
        every { sword.unlocalizedName } returns "sword"
        every { player.heldItem } returns sword
        val mc = Minecraft.getMinecraft()
        every { mc.thePlayer } returns player

        val opp = mockk<EntityPlayer>(relaxed = true)
        val field = BotBase::class.java.getDeclaredField("opponent")
        field.isAccessible = true
        field.set(bot, opp)

        bot.onAttack()
        verify(exactly = 1) { Mouse.leftClick() }
    }
}
