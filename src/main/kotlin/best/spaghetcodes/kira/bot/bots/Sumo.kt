package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.atan2

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ========== Configuration Optimisée ==========
    
    // Timing du saut initial (corrigé pour éviter le bug de spawn)
    private val initialJumpDelay = 180L        // Délai avant le premier saut (ms) - augmenté pour être sûr
    private val initialJumpDuration = 150      // Durée du saut initial
    
    // Stratégie de prise du centre
    private val centerRushSpeed = 1.18f        // Multiplicateur de vitesse pour rush centre
    private val centerControlRadius = 2.2f     // Rayon de contrôle du centre
    private val centerPriorityDuration = 2500L // Durée de priorité au centre (ms)
    
    // Combat et engagement
    private val attackStartDist = 4.1f         // Distance pour commencer l'attaque
    private val attackMaxDist = 4.8f           // Distance max d'attaque
    private val attackLatchMs = 200L           // Maintien de l'attaque
    private val prefireStartDist = 4.7f        // Distance de pré-fire
    private val prefireLatchMs = 140L          // Durée du pré-fire
    
    // W-Tap fixe optimal (comme Boxing)
    private val W_TAP_DURATION = 80            // Durée fixe optimale du W-tap
    private val BACK_TAP_DURATION = 65         // Durée du back-tap après coup réussi
    private val BACK_TAP_CHANCE = 0.45         // Chance de faire un back-tap (45%)
    
    // Jump stratégique
    private val engageJumpMinDist = 5.5f       // Distance min pour jump d'engagement
    private val engageJumpMaxDist = 6.8f       // Distance max pour jump d'engagement
    private val jumpCooldownMs = 650L          // Cooldown entre les jumps
    private val comboJumpThreshold = 3         // Combo requis pour jump offensif
    private val comboJumpDist = 3.2f           // Distance pour combo jump
    
    // Strafe intelligent
    private val strafeBaseInterval = 400..600  // Intervalle de changement de strafe
    private val strafeNearEdgeBoost = 14       // Boost de priorité près du bord
    private val strafeCombatWeight = 8         // Poids du strafe en combat
    private val strafeMinDist = 2.5f           // Distance min pour strafe
    private val strafeMaxDist = 8.5f           // Distance max pour strafe
    private val microStrafeInterval = 150..250 // Micro-strafe en close combat
    
    // Détection du vide
    private val edgeDetectClose = 1.3f         // Détection proche du vide
    private val edgeDetectMid = 1.8f           // Détection moyenne
    private val edgeDetectFar = 2.4f           // Détection lointaine
    private val edgeSafetyMargin = 0.7f        // Marge de sécurité
    
    // Anti-stagnation
    private val stagnationThreshold = 0.04f    // Seuil de mouvement pour détecter stagnation
    private val stagnationTimeout = 350L       // Temps avant action anti-stagnation
    private val stagnationJumpChance = 0.3     // Chance de jump pour débloquer
    
    // Distance management
    private val stopForwardDist = 1.1f         // Distance pour arrêter d'avancer
    private val resumeForwardDist = 1.8f       // Distance pour reprendre l'avance
    private val tooCloseDist = 0.9f            // Distance trop proche (recul nécessaire)
    
    // ========== Variables d'État ==========
    
    private var gameStartTime = 0L
    private var hasInitialJumped = false
    private var centerX = 0.0
    private var centerZ = 0.0
    private var arenaRadius = 0.0
    
    private var lastDistance = -1f
    private var lastOpponentX = 0.0
    private var lastOpponentZ = 0.0
    private var velocityHistory = mutableListOf<Float>()
    private var distanceHistory = mutableListOf<Float>()
    
    private var strafeDirection = 1
    private var lastStrafeChange = 0L
    private var microStrafeActive = false
    private var lastMicroStrafe = 0L
    private var stagnantSince = 0L
    
    private var canJump = true
    private var lastJumpTime = 0L
    
    private var isAttacking = false
    private var attackUntil = 0L
    private var isPrefiring = false
    
    private var tapping = false
    private var tapEndTime = 0L
    private var backTapping = false
    private var backTapEndTime = 0L
    
    private var comboLockUntil = 0L
    private var lastHitTime = 0L
    
    // ========== Méthodes Utilitaires ==========
    
    private fun distanceToCenter(x: Double, z: Double): Double {
        val dx = x - centerX
        val dz = z - centerZ
        return sqrt(dx * dx + dz * dz)
    }
    
    private fun angleToCenter(): Float {
        val p = mc.thePlayer ?: return 0f
        val angleToCenter = atan2(centerZ - p.posZ, centerX - p.posX)
        val playerYaw = Math.toRadians(p.rotationYaw.toDouble())
        var diff = angleToCenter - playerYaw
        
        while (diff > Math.PI) diff -= 2 * Math.PI
        while (diff < -Math.PI) diff += 2 * Math.PI
        
        return diff.toFloat()
    }
    
    private fun isNearEdge(distance: Float): Boolean {
        val p = mc.thePlayer ?: return false
        return WorldUtils.airInFront(p, distance) || 
               WorldUtils.airOnLeft(p, distance) || 
               WorldUtils.airOnRight(p, distance) ||
               WorldUtils.airInBack(p, distance)
    }
    
    private fun getEdgeDirection(): Int {
        val p = mc.thePlayer ?: return 0
        
        // Vérifier tous les côtés
        val frontVoid = WorldUtils.airInFront(p, edgeDetectMid)
        val leftVoid = WorldUtils.airOnLeft(p, edgeDetectMid)
        val rightVoid = WorldUtils.airOnRight(p, edgeDetectMid)
        val backVoid = WorldUtils.airInBack(p, edgeDetectMid)
        
        // Si vide à gauche, aller à droite
        if (leftVoid && !rightVoid) return 1
        // Si vide à droite, aller à gauche
        if (rightVoid && !leftVoid) return -1
        
        // Si vide devant ou derrière, préférer le côté vers le centre
        if (frontVoid || backVoid) {
            val angle = angleToCenter()
            return if (angle > 0) 1 else -1
        }
        
        return 0
    }
    
    private fun calculateApproachVelocity(opp: Any): Float {
        if (lastOpponentX == 0.0 && lastOpponentZ == 0.0) {
            lastOpponentX = EntityUtils.getX(opp)
            lastOpponentZ = EntityUtils.getZ(opp)
            return 0f
        }
        
        val currentX = EntityUtils.getX(opp)
        val currentZ = EntityUtils.getZ(opp)
        val p = mc.thePlayer ?: return 0f
        
        val oldDist = sqrt((lastOpponentX - p.posX) * (lastOpponentX - p.posX) + 
                          (lastOpponentZ - p.posZ) * (lastOpponentZ - p.posZ))
        val newDist = sqrt((currentX - p.posX) * (currentX - p.posX) + 
                          (currentZ - p.posZ) * (currentZ - p.posZ))
        
        lastOpponentX = currentX
        lastOpponentZ = currentZ
        
        return (oldDist - newDist).toFloat()
    }
    
    // ========== Événements Principaux ==========
    
    override fun onGameStart() {
        // Initialisation de base
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.clearAll()
        Combat.stopRandomStrafe()
        
        // Déterminer le centre de l'arène
        val p = mc.thePlayer
        if (p != null) {
            centerX = p.posX
            centerZ = p.posZ
            arenaRadius = 8.0  // Rayon approximatif de l'arène Sumo
        }
        
        // Reset des variables
        gameStartTime = System.currentTimeMillis()
        hasInitialJumped = false
        lastDistance = -1f
        lastOpponentX = 0.0
        lastOpponentZ = 0.0
        velocityHistory.clear()
        distanceHistory.clear()
        
        strafeDirection = if (RandomUtils.randomBool()) 1 else -1
        lastStrafeChange = 0L
        microStrafeActive = false
        lastMicroStrafe = 0L
        stagnantSince = 0L
        
        canJump = true
        lastJumpTime = 0L
        
        isAttacking = false
        attackUntil = 0L
        isPrefiring = false
        
        tapping = false
        tapEndTime = 0L
        backTapping = false
        backTapEndTime = 0L
        
        comboLockUntil = 0L
        lastHitTime = 0L
        
        // Démarrage immédiat du sprint et avance
        Movement.startSprinting()
        Movement.startForward()
        
        // Planifier le saut initial (avec délai pour éviter le bug de spawn)
        TimeUtils.setTimeout({
            if (!hasInitialJumped && mc.thePlayer?.onGround == true) {
                Movement.singleJump(initialJumpDuration)
                hasInitialJumped = true
            }
        }, initialJumpDelay)
    }
    
    override fun onGameEnd() {
        Mouse.stopLeftAC()
        val stopInterval = TimeUtils.setInterval(Mouse::stopLeftAC, 50, 100)
        
        TimeUtils.setTimeout({
            stopInterval?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(150, 300))
    }
    
    override fun onAttack() {
        if (tapping || backTapping) return
        
        // W-Tap avec valeur fixe optimale (comme Boxing)
        Combat.wTap(W_TAP_DURATION)
        tapping = true
        tapEndTime = System.currentTimeMillis() + W_TAP_DURATION
        
        TimeUtils.setTimeout({ 
            tapping = false 
            
            // Back-tap occasionnel après le W-tap pour plus de knockback
            if (RandomUtils.randomDoubleInRange(0.0, 1.0) < BACK_TAP_CHANCE && !isNearEdge(1.5f)) {
                Movement.startBackward()
                backTapping = true
                backTapEndTime = System.currentTimeMillis() + BACK_TAP_DURATION
                
                TimeUtils.setTimeout({
                    Movement.stopBackward()
                    backTapping = false
                    // Reprendre l'avance si pas trop proche
                    val p = mc.thePlayer
                    val opp = opponent()
                    if (p != null && opp != null) {
                        val dist = EntityUtils.getDistanceNoY(p, opp)
                        if (dist > resumeForwardDist) {
                            Movement.startForward()
                        }
                    }
                }, BACK_TAP_DURATION)
            }
        }, W_TAP_DURATION + 20)
        
        // Enregistrer le hit pour le combo lock
        lastHitTime = System.currentTimeMillis()
        if (combo >= 2) {
            comboLockUntil = System.currentTimeMillis() + 600L
        }
    }
    
    // ========== Logique Principale ==========
    
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return
        val now = System.currentTimeMillis()
        
        // Maintenir le sprint si possible
        if (!p.isSprinting && !isNearEdge(edgeSafetyMargin)) {
            Movement.startSprinting()
        }
        
        Mouse.startTracking()
        
        // Calculs de distance et vitesse
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approachVelocity = calculateApproachVelocity(opp)
        velocityHistory.add(approachVelocity)
        if (velocityHistory.size > 5) velocityHistory.removeAt(0)
        
        distanceHistory.add(distance)
        if (distanceHistory.size > 10) distanceHistory.removeAt(0)
        
        val avgVelocity = velocityHistory.average().toFloat()
        val isApproachingFast = avgVelocity > 0.12f
        val isRetreating = avgVelocity < -0.08f
        
        // Phase de rush initial vers le centre (premiers 2.5 secondes)
        val isInitialPhase = (now - gameStartTime) < centerPriorityDuration
        val inComboLock = combo >= 2 || now < comboLockUntil
        
        // ========== Gestion du Centre (début de partie) ==========
        if (isInitialPhase && !hasInitialJumped) {
            val distFromCenter = distanceToCenter(p.posX, p.posZ)
            if (distFromCenter > centerControlRadius) {
                // Orienter agressivement vers le centre
                val angleOff = angleToCenter()
                if (abs(angleOff) > 0.25) {
                    if (angleOff > 0) {
                        Movement.startRight()
                        Movement.stopLeft()
                    } else {
                        Movement.startLeft()
                        Movement.stopRight()
                    }
                } else {
                    // On est bien orienté, foncer tout droit
                    Movement.clearLeftRight()
                }
            }
        }
        
        // ========== Gestion de l'Attaque (uniquement si kiraHit activé) ==========
        if (kira.config?.kiraHit == true) {
            val shouldAttack = distance <= attackStartDist && 
                              distance <= attackMaxDist && 
                              !Mouse.isUsingPotion() && 
                              !Mouse.isUsingProjectile()
            
            val shouldPrefire = distance <= prefireStartDist && 
                               distance > attackStartDist && 
                               isApproachingFast
            
            when {
                shouldAttack -> {
                    if (!isAttacking) {
                        isAttacking = true
                        attackUntil = now + attackLatchMs
                        Mouse.startLeftAC()
                    } else if (now < attackUntil) {
                        Mouse.startLeftAC()
                    }
                }
                shouldPrefire && !isAttacking -> {
                    if (!isPrefiring) {
                        isPrefiring = true
                        attackUntil = now + prefireLatchMs
                        Mouse.startLeftAC()
                    }
                }
                else -> {
                    if (now >= attackUntil) {
                        isAttacking = false
                        isPrefiring = false
                        Mouse.stopLeftAC()
                    }
                }
            }
        } else {
            // Si kiraHit n'est pas activé, on stop l'AC
            Mouse.stopLeftAC()
        }
        
        // ========== Gestion des Sauts ==========
        val canDoJump = canJump && p.onGround && !tapping && !backTapping && (now - lastJumpTime > jumpCooldownMs)
        
        // Jump d'engagement stratégique
        if (canDoJump && 
            distance in engageJumpMinDist..engageJumpMaxDist &&
            !isNearEdge(2.8f) &&
            !isInitialPhase) {
            
            Movement.singleJump(RandomUtils.randomIntInRange(130, 170))
            canJump = false
            lastJumpTime = now
            TimeUtils.setTimeout({ canJump = true }, jumpCooldownMs)
        }
        
        // Jump de combo (plus agressif)
        else if (canDoJump && 
                combo >= comboJumpThreshold && 
                distance >= comboJumpDist &&
                !isNearEdge(2.2f)) {
            
            Movement.singleJump(RandomUtils.randomIntInRange(110, 150))
            canJump = false
            lastJumpTime = now
            TimeUtils.setTimeout({ canJump = true }, jumpCooldownMs + 200)
        }
        
        // ========== Gestion du Mouvement et Strafe ==========
        val movePriority = arrayListOf(0, 0)
        var useRandomStrafe = false
        var clearMovement = false
        
        // Détection des bords
        val nearEdgeClose = isNearEdge(edgeDetectClose)
        val nearEdgeMid = isNearEdge(edgeDetectMid)
        val nearEdgeFar = isNearEdge(edgeDetectFar)
        val frontVoid = WorldUtils.airInFront(p, edgeDetectClose)
        
        // Gestion critique du vide devant
        if (frontVoid) {
            Movement.stopForward()
            if (p.onGround) {
                Movement.startSneaking()
            }
        } else {
            Movement.stopSneaking()
        }
        
        // Si près d'un bord, forcer le strafe dans la direction opposée
        if (nearEdgeClose || nearEdgeMid) {
            val edgeDir = getEdgeDirection()
            if (edgeDir != 0) {
                val weight = if (nearEdgeClose) strafeNearEdgeBoost else strafeNearEdgeBoost / 2
                if (edgeDir > 0) {
                    movePriority[1] += weight  // Aller à droite
                    Movement.stopLeft()
                } else {
                    movePriority[0] += weight  // Aller à gauche
                    Movement.stopRight()
                }
            }
        }
        
        // Strafe stratégique en combat
        if (!isInitialPhase && !nearEdgeClose && distance in strafeMinDist..strafeMaxDist) {
            
            // Micro-strafe en close combat avec combo
            if (inComboLock && distance < 2.5f) {
                if (!microStrafeActive || now - lastMicroStrafe > RandomUtils.randomIntInRange(microStrafeInterval.first, microStrafeInterval.last)) {
                    microStrafeActive = true
                    strafeDirection = -strafeDirection
                    lastMicroStrafe = now
                }
                if (strafeDirection > 0) {
                    movePriority[1] += 4
                } else {
                    movePriority[0] += 4
                }
            } else {
                microStrafeActive = false
                
                // Strafe normal avec changements périodiques
                if (now - lastStrafeChange > RandomUtils.randomIntInRange(strafeBaseInterval.first, strafeBaseInterval.last)) {
                    strafeDirection = -strafeDirection
                    lastStrafeChange = now
                }
                
                // Anti-stagnation
                if (lastDistance > 0) {
                    val deltaMove = abs(distance - lastDistance)
                    if (deltaMove < stagnationThreshold) {
                        if (stagnantSince == 0L) {
                            stagnantSince = now
                        } else if (now - stagnantSince > stagnationTimeout) {
                            strafeDirection = -strafeDirection
                            lastStrafeChange = now
                            stagnantSince = 0L
                            // Micro-jump occasionnel pour débloquer
                            if (p.onGround && !nearEdgeClose && RandomUtils.randomDoubleInRange(0.0, 1.0) < stagnationJumpChance) {
                                Movement.singleJump(90)
                            }
                        }
                    } else {
                        stagnantSince = 0L
                    }
                }
                
                if (strafeDirection > 0) {
                    movePriority[1] += strafeCombatWeight
                } else {
                    movePriority[0] += strafeCombatWeight
                }
                
                useRandomStrafe = (distance >= 4.0f && distance <= 7.5f && !nearEdgeFar)
            }
        }
        
        // Gestion de la distance avant/arrière
        if (!tapping && !backTapping && !frontVoid) {
            when {
                distance < tooCloseDist && !isRetreating -> {
                    // Trop proche, petit recul
                    Movement.stopForward()
                    if (!nearEdgeClose) {
                        Movement.startBackward()
                        TimeUtils.setTimeout({ Movement.stopBackward() }, 100)
                    }
                }
                distance < stopForwardDist -> {
                    Movement.stopForward()
                    if (inComboLock) clearMovement = true
                }
                distance > resumeForwardDist -> {
                    Movement.stopBackward()
                    Movement.startForward()
                }
                else -> {
                    // Zone neutre, garder la position actuelle
                    if (!Movement.forward() && distance > 2.0f) {
                        Movement.startForward()
                    }
                }
            }
        }
        
        // Application du mouvement
        if (!tapping && !backTapping) {
            if (clearMovement) {
                Combat.stopRandomStrafe()
                Movement.clearLeftRight()
            } else if (useRandomStrafe && !nearEdgeClose) {
                Combat.startRandomStrafe(800, 1400)
            } else {
                Combat.stopRandomStrafe()
                if (movePriority[0] > movePriority[1]) {
                    Movement.stopRight()
                    Movement.startLeft()
                } else if (movePriority[1] > movePriority[0]) {
                    Movement.stopLeft()
                    Movement.startRight()
                } else if (!Movement.left() && !Movement.right() && !isInitialPhase) {
                    // Strafe par défaut basé sur la direction actuelle
                    if (strafeDirection > 0) {
                        Movement.startRight()
                    } else {
                        Movement.startLeft()
                    }
                }
            }
        }
        
        // Sécurité supplémentaire pour les bords latéraux
        if (Movement.left() && WorldUtils.airOnLeft(p, 1.0f)) {
            Movement.stopLeft()
            if (!Movement.right()) Movement.startRight()
        }
        if (Movement.right() && WorldUtils.airOnRight(p, 1.0f)) {
            Movement.stopRight()
            if (!Movement.left()) Movement.startLeft()
        }
        
        // Si dos au vide, forcer l'avance
        if (WorldUtils.airInBack(p, 2.0f) && p.onGround) {
            Movement.clearLeftRight()
            Combat.stopRandomStrafe()
            if (!tapping && !frontVoid) {
                Movement.startForward()
            }
        }
        
        // Mise à jour pour le prochain tick
        lastDistance = distance
        
        // Handle final avec les priorités calculées
        handle(clearMovement, useRandomStrafe, movePriority)
    }
}
