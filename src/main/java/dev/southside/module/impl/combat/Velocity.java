package dev.southside.module.impl.combat;

import cc.polyfrost.oneconfig.config.options.impl.Dropdown;
import cc.polyfrost.oneconfig.config.options.impl.Slider;
import cc.polyfrost.oneconfig.config.options.impl.Switch;
import dev.southside.Southside;
import dev.southside.event.Stage;
import dev.southside.event.TransferOrigin;
import dev.southside.event.events.ItemUseEvent;
import dev.southside.event.events.MovementInputEvent;
import dev.southside.event.events.PacketEvent;
import dev.southside.event.events.RotationAppliedEvent;
import dev.southside.event.events.RotationEvent;
import dev.southside.event.events.TickEvent;
import dev.southside.event.events.WorldEvent;
import dev.southside.manager.CPSManager;
import dev.southside.manager.ServerPacketManager;
import dev.southside.module.Category;
import dev.southside.module.Module;
import dev.southside.module.impl.misc.AutoGApple;
import dev.southside.module.impl.movement.NoSlowTest;
import dev.southside.orbit.EventHandler;
import dev.southside.render.ProgressBarOverlay;
import dev.southside.utils.ChatUtils;
import dev.southside.utils.misc.ClientUtils;
import dev.southside.utils.network.PacketUtils;
import dev.southside.utils.player.BoundBoxUtils;
import dev.southside.utils.player.TargetUtils;
import dev.southside.utils.raytrace.ClientRayTraceUtil;
import dev.southside.utils.rotation.RotationUtils;
import dev.southside.utils.types.Rotation;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.util.Hand;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;

public class Velocity extends Module {
   public static final Dropdown mode = new Dropdown("Mode", "Desync", "Desync", "Reduce");
   public final Switch debug = new Switch("Debug", false);
   public final Switch debugHud = new Switch("Delay HUD", false);
   public final Switch fireballCooldown = new Switch("Fireball Cooldown", true);
   public final Switch modernCombat = new Switch("1.9+", false);
   public final Switch testFull = new Switch("Test Mode", true);
   public final Switch delayTillGround = new Switch("Delay Till Ground", false);
   public final Slider targetMotion = new Slider("Target Motion", 0.15, 0.05, 0.5, 0.05);
   public final Switch multiTargetFix = new Switch("Multi Target", true);
   public final Slider releaseDistance = new Slider("Release Distance", 1.6, 0.1, 3.0, 0.1);
   public final Slider attackCount = new Slider("Attack Count", 3, 0, 20, 1);
   public final Slider lagTargetRangeStart = new Slider("Lag Target Range Start", 2.0, 0.0, 20.0, 0.5);
   public final Slider lagTargetRangeEnd = new Slider("Lag Target Range End", 6.0, 1.0, 20.0, 0.5);
   public final Slider lagMaxDelay = new Slider("Lag Max Delay", 10, 1, 1000, 1);
   public final Switch lagRequireKillAura = new Switch("Lag Require KillAura", false);
   public final Slider horizontal = new Slider("Horizontal", 0.6, 0.0, 1.0, 0.05);
   public final Slider vertical = new Slider("Vertical", 1.0, 0.0, 1.0, 0.05);
   public static boolean gotKB;
   public boolean jump;
   int coolDownTicks;
   static int attack;
   Entity target;
   Vec3d motion = Vec3d.ZERO;
   EntityVelocityUpdateS2CPacket kbPacket = null;
      private int aimSkipTicks;
   private ProgressBarOverlay.ProgressHandle progressHandle;

   // ==== Reduce 模式状态（LiquidX 风格：受击+速度判定→冲刺攻击×(horizontal,vertical)减速，lag 队列放包） ====
   private final Queue<Packet<?>> packets = new ConcurrentLinkedQueue<>();
   private Entity reduceTarget;
   private int remainingAttackCount;
   private boolean receiveDamage;
   private int lagTicks = -1;
   private long currentGameTick;
   private long forwardInputAttackGameTick = -1L;

   public Velocity() {
      super(Category.Combat, "Velocity", "Reduce KnockBack");
   }

   @Override
   public void initPostRunnable() {
      super.initPostRunnable();
      // 按模式隐藏选项：Desync 专属设置只在 Desync 模式显示，Reduce 专属只在 Reduce 模式显示
      this.addModeVisibility(debug, false);
      this.addModeVisibility(fireballCooldown, false);
      this.addModeVisibility(modernCombat, false);
      this.addModeVisibility(testFull, false);
      this.addModeVisibility(delayTillGround, false);
      this.addModeVisibility(targetMotion, false);
      this.addModeVisibility(multiTargetFix, false);
      this.addModeVisibility(releaseDistance, false);
      this.addModeVisibility(attackCount, true);
      this.addModeVisibility(lagTargetRangeStart, true);
      this.addModeVisibility(lagTargetRangeEnd, true);
      this.addModeVisibility(lagMaxDelay, true);
      this.addModeVisibility(lagRequireKillAura, true);
      this.addModeVisibility(horizontal, true);
      this.addModeVisibility(vertical, true);
   }

   /** reduce=true: 仅 Reduce 模式显示; false: 仅 Desync 模式显示 */
   private void addModeVisibility(cc.polyfrost.oneconfig.config.options.Option<?> option, boolean reduce) {
      if (option.basicOption == null) {
         return;
      }

      option.basicOption.addHideCondition(() -> reduce ? !this.isReduceMode() : this.isReduceMode());
   }

   @Override
   public void onEnable() {
      this.reset();
   }

   @Override
   public void onDisable() {
      this.resetReduce();
      this.reset();
   }

   @EventHandler
   public void onWorld(WorldEvent event) {
      this.target = null;
      this.coolDownTicks = 0;
      gotKB = false;
      attack = 0;
      this.jump = false;
      this.kbPacket = null;
      this.aimSkipTicks = 0;
      this.resetReduce();
   }

   @Override
   public void reset() {
      this.resetReduce();
      if (gotKB) {
         this.target = null;
         if (!NoSlowTest.shouldEnable) {
            ServerPacketManager.reset(false);
         }

         gotKB = false;
         attack = 0;
         this.jump = false;
         this.kbPacket = null;
         this.aimSkipTicks = 0;
         this.cancelProgressBar();
      }
   }

   private void resetReduce() {
      this.reduceTarget = null;
      this.remainingAttackCount = 0;
      this.receiveDamage = false;
      this.lagTicks = -1;
      this.currentGameTick = 0L;
      this.forwardInputAttackGameTick = -1L;
      this.packets.clear();
      if (ClientUtils.nullCheck() || mc.player != null) {
         this.setSuffix("");
      }
   }

   private boolean isReduceMode() {
      return mode.isMode("Reduce");
   }


   @EventHandler
   public void onPacket(PacketEvent event) {
      if (!ClientUtils.nullCheck()) {
         if (!event.getOrigin().equals(TransferOrigin.SEND)) {
            Packet<?> packet = event.getPacket();

            if (this.isReduceMode()) {
               this.onPacketReduce(event, packet);
               return;
            }

            if (packet instanceof EntityVelocityUpdateS2CPacket wrapper
               && wrapper.getEntityId() == mc.player.getId()
               && this.coolDownTicks <= 0
               && this.shouldReduce(wrapper)
               && !NoSlowTest.shouldEnable) {
               gotKB = true;
               this.kbPacket = wrapper;
               this.motion = new Vec3d(wrapper.getVelocityX(), wrapper.getVelocityY(), wrapper.getVelocityZ());
               if (!ServerPacketManager.deSyncing) {
                  ServerPacketManager.setup();
               }
            }

            if (packet instanceof PlayerPositionLookS2CPacket && gotKB) {
               if (this.debug.getValue()) {
                  ChatUtils.warning("Flag Detected");
               }

               this.coolDownTicks = 2;
            }
         }
      }
   }

   // ==== Reduce 模式：收包（LiquidX 逻辑：受击→速度包→lag 或进入攻击计数） ====
   private void onPacketReduce(PacketEvent event, Packet<?> packet) {
      try {
         // lag 期间拦截移动/速度相关包并自行入队，位置包视为服务器 FLAG 信号
         if (packet instanceof EntityVelocityUpdateS2CPacket
            || packet instanceof EntityPositionSyncS2CPacket
            || packet instanceof EntityS2CPacket
            || packet instanceof CommonPingS2CPacket
            || packet instanceof PlayerPositionLookS2CPacket) {
            if (this.lagTicks >= 0) {
               if (packet instanceof PlayerPositionLookS2CPacket) {
                  this.releaseReason = ReleaseReason.FLAG;
               }
               event.setCancelled(true);
               this.packets.add(packet);
            }
            return;
         }

         if (!(packet instanceof EntityDamageS2CPacket damagePacket
            || packet instanceof EntityVelocityUpdateS2CPacket velocityPacket)) {
            return;
         }

         // 受击判定：damage 包标记 receiveDamage，速度包确认（LiquidX isLocalPlayerDamage/isLocalPlayerVelocity）
         if (packet instanceof EntityDamageS2CPacket dp && dp.entityId() == mc.player.getId()) {
            this.receiveDamage = true;
         } else if (packet instanceof EntityVelocityUpdateS2CPacket vp
            && vp.getEntityId() == mc.player.getId()
            && this.receiveDamage) {
            this.receiveDamage = false;
            if (mc.player.isUsingItem() || Southside.INSTANCE.getModuleManager().isEnabled(dev.southside.module.impl.world.Scaffold.class)) {
               return;
            }

            this.findReduceTarget();

            boolean canLag = !this.lagRequireKillAura.getValue()
               || Southside.INSTANCE.getModuleManager().isEnabled(KillAura.class);
            if ((this.reduceTarget == null && canLag)
               || (this.reduceTarget != null && !mc.player.isSprinting())) {
               this.lagTicks = this.lagMaxDelay.getValue().intValue();
            } else if (this.reduceTarget != null) {
               this.remainingAttackCount = (int) this.attackCount.getValue().doubleValue();
            }
         }
      } catch (Exception ignored) {
      }
   }

   private enum ReleaseReason {
      TARGET_REACHED,
      FLAG,
      SPECTATOR,
      OUT_OF_RANGE,
      MAX_DELAY
   }

   private ReleaseReason releaseReason = null;

   private boolean shouldReduce(EntityVelocityUpdateS2CPacket packet) {
      return Southside.INSTANCE.getModuleManager().isEnabled(KillAura.class)
         && !AutoGApple.eating
         && new Vec3d(packet.getVelocityX(), 0.0, packet.getVelocityZ()).length() >= this.targetMotion.getDouble();
   }

   @EventHandler
   public void onTick(TickEvent event) {
      if (!this.isReduceMode() || event.getState() != Stage.PRE || mc.player == null) {
         return;
      }

      try {
         this.currentGameTick++;

         // LiquidX GameTick：攻击计数期间逐 tick 冲刺攻击 + 速度衰减
         if (this.remainingAttackCount > 0) {
            if (this.hasLostReduceTarget()) {
               this.remainingAttackCount = 0;
               this.reduceTarget = null;
               return;
            }

            mc.player.setSprinting(false);
            mc.interactionManager.attackEntity(mc.player, this.reduceTarget);
            mc.player.swingHand(Hand.MAIN_HAND);
            this.forwardInputAttackGameTick = this.currentGameTick;
            mc.player.setVelocity(mc.player.getVelocity().multiply(
               this.horizontal.getValue().doubleValue(),
               this.vertical.getValue().doubleValue(),
               this.horizontal.getValue().doubleValue()
            ));
            this.remainingAttackCount--;
            if (this.remainingAttackCount == 0) {
               this.reduceTarget = null;
            }
         }

         // LiquidX TickPacketProcess：releaseReason 到达时放包
         if (this.releaseReason != null && this.lagTicks >= 0) {
            this.flush();
            this.lagTicks = -1;
            if (this.releaseReason == ReleaseReason.TARGET_REACHED) {
               this.remainingAttackCount = (int) this.attackCount.getValue().doubleValue();
            }
            this.releaseReason = null;
         }
      } catch (Exception ignored) {
      }
   }

   private Entity findTarget() {
      return KillAura.target != null && Southside.INSTANCE.getModuleManager().get(KillAura.class).inRaycast(KillAura.target) ? KillAura.target : null;
   }

   private boolean hasLostReduceTarget() {
      if (this.reduceTarget == null || this.reduceTarget.isRemoved()) {
         return true;
      }

      if (!Southside.INSTANCE.getModuleManager().isEnabled(KillAura.class)) {
         return false;
      }

      Entity auraTarget = KillAura.target;
      return auraTarget == null || auraTarget.getId() != this.reduceTarget.getId();
   }

   private void findReduceTarget() {
      boolean canLag = !this.lagRequireKillAura.getValue()
         || Southside.INSTANCE.getModuleManager().isEnabled(KillAura.class);
      Entity auraTarget = KillAura.target;
      if (auraTarget != null
         && Southside.INSTANCE.getModuleManager().isEnabled(KillAura.class)
         && !auraTarget.isRemoved()
         && auraTarget.isAlive()
         && auraTarget.squaredDistanceTo(mc.player) <= this.lagTargetRangeStart.getValue().doubleValue() * this.lagTargetRangeStart.getValue().doubleValue()) {
         this.reduceTarget = auraTarget;
         return;
      }

      this.reduceTarget = null;
      if (!canLag) {
         return;
      }

      if (mc.crosshairTarget instanceof EntityHitResult hit
         && hit.getEntity() instanceof LivingEntity living
         && !living.isRemoved()
         && living.isAlive()
         && !(living instanceof ArmorStandEntity)
         && living.squaredDistanceTo(mc.player) <= this.lagTargetRangeStart.getValue().doubleValue() * this.lagTargetRangeStart.getValue().doubleValue()) {
         this.reduceTarget = living;
         return;
      }

      // lag 期间回退：End 范围内最近的可攻击实体（LiquidX weightedMinByOrNullAtMost 简化版）
      double endSq = this.lagTargetRangeEnd.getValue().doubleValue() * this.lagTargetRangeEnd.getValue().doubleValue();
      Entity nearest = null;
      double nearestDist = Double.MAX_VALUE;
      for (Entity entity : mc.world.getEntities()) {
         if (entity instanceof LivingEntity living2
            && !living2.isRemoved()
            && living2.isAlive()
            && !(living2 instanceof ArmorStandEntity)
            && living2 != mc.player
            && !dev.southside.module.impl.misc.AntiBot.isBot(living2)) {
            double d = living2.squaredDistanceTo(mc.player);
            if (d <= endSq && d < nearestDist) {
               nearestDist = d;
               nearest = living2;
            }
         }
      }
      this.reduceTarget = nearest;
   }

   @EventHandler(
      priority = -200
   )
   public void onRotation(RotationEvent event) {
      if (this.isReduceMode()) {
         if (this.debugHud.getValue() && this.lagTicks >= 0) {
            this.setSuffix(this.lagTicks + "t");
         } else if (this.lagTicks < 0) {
            this.setSuffix("");
         }
         return;
      }

      if (mc.player == null) {
         this.reset();
      } else if (this.coolDownTicks > 0) {
         this.coolDownTicks--;
         this.reset();
      } else {
         if (gotKB) {
            if (ServerPacketManager.deSyncing) {
               if (this.debugHud.getValue()) {
                  ProgressBarOverlay overlay = Southside.get().getProgressBarOverlay();
                  if (overlay != null) {
                     if (this.progressHandle == null || !this.progressHandle.isActive()) {
                        this.progressHandle = overlay.show(40, "KB Delay");
                     }

                     this.progressHandle.update(ServerPacketManager.deSyncTick);
                  }
               }

               if (ServerPacketManager.deSyncTick >= 40) {
                  if (this.debug.getValue()) {
                     ChatUtils.error("Time Out!");
                  }

                  this.reset();
               }

               if (KillAura.target == null) {
                  this.reset();
                  if (this.debug.getValue()) {
                     ChatUtils.error("Reset, reason: aura no target.");
                  }

                  return;
               }

               if ((this.ready() || this.multiTargetFix.getValue() && KillAura.targets.size() > 1) && this.checkAsyncTargets()) {
                  int tick = ServerPacketManager.deSyncTick;
                  if (this.testFull.getValue()) {
                     ServerPacketManager.releaseUntil(Objects::isNull, false);
                  } else {
                     ServerPacketManager.reset(false);
                  }

                  gotKB = false;
                  this.kbPacket = null;
                  this.cancelProgressBar();
                  double strength = new Vec3d(mc.player.getVelocity().getX(), 0.0, mc.player.getVelocity().getZ()).length();
                  attack = this.calcAttacks(strength) + 1;
                  if (this.debug.getValue()) {
                     ChatUtils.info("Sync, ticks used: " + tick + " calculated attacks: " + attack + ".");
                  }
               }
            } else {
               this.reset();
               if (this.debug.getValue()) {
                  ChatUtils.error("Reset, reason: not desyncing.");
               }
            }
         }

         if (gotKB && attack > 0) {
            mc.options.sprintKey.setPressed(true);
         }
      }
   }

   @EventHandler
   public void onInput(RotationAppliedEvent event) {
      if (this.isReduceMode()) {
         return;
      }

      if (this.coolDownTicks <= 0) {
         if (gotKB
            && ServerPacketManager.deSyncing
            && this.ready()
            && KillAura.target != null
            && Southside.INSTANCE.getModuleManager().get(KillAura.class).inRaycast(KillAura.target)
            && BoundBoxUtils.getMinDistanceVec3dByBox(mc.player.getEyePos(), KillAura.target.getBoundingBox()).distanceTo(mc.player.getEyePos())
               <= this.releaseDistance.getValue().doubleValue()) {
            int tick = ServerPacketManager.deSyncTick;
            if (this.testFull.getValue()) {
               ServerPacketManager.releaseUntil(packet -> packet.equals(this.kbPacket), false);
            } else {
               ServerPacketManager.reset(false);
            }

            gotKB = false;
            this.kbPacket = null;
            this.cancelProgressBar();
            double strength = new Vec3d(mc.player.getVelocity().getX(), 0.0, mc.player.getVelocity().getZ()).length();
            attack = this.calcAttacks(strength) + 1;
            if (this.debug.getValue()) {
               ChatUtils.info("Sync, ticks used: " + tick + " calculated attacks: " + attack + ".");
            }
         }

         if (attack > 0) {
            Entity aimTarget = this.target != null ? this.target : this.findTarget();
            this.target = aimTarget;

            if (aimTarget == null || !this.serverAimValid(aimTarget)) {
               if (++this.aimSkipTicks > 5) {
                  this.aimSkipTicks = 0;
                  attack--;
               }

               return;
            }

            this.aimSkipTicks = 0;
            attack--;
            if (this.modernCombat.getValue()) {
               this.runReduceModern();
            } else {
               this.runReduce(1);
               this.jump = true;
            }
         }
      }
   }


      private boolean serverAimValid(Entity entity) {
      if (entity == null) {
         return false;
      }

      if (ClientRayTraceUtil.eyePos == null) {
         return true;
      }

      Rotation rotation;
      if (RotationUtils.getRotation() != null) {
         rotation = RotationUtils.getRotationSafely();
      } else {
         rotation = RotationUtils.getServerRotation();
      }

      if (rotation == null) {
         return true;
      }

      double range = Math.max(3.0, this.releaseDistance.getDouble() + 1.0);
      return ClientRayTraceUtil.didHitEntity(rotation.yaw, rotation.pitch, range, entity);
   }

   @EventHandler(
      priority = -200
   )
   public void onItemUse(ItemUseEvent event) {
      if (this.fireballCooldown.getValue()
         && !event.isCancelled()
         && mc.player != null
         && (mc.player.getOffHandStack().getItem().equals(Items.FIRE_CHARGE) || mc.player.getMainHandStack().getItem().equals(Items.FIRE_CHARGE))
         )
       {
         this.coolDownTicks = 10;
      }
   }

   private boolean checkAsyncTargets() {
      if (KillAura.target != null) {
         for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player) {
               Box box = entity.dimensions.getBoxAt(player.getPos());
               if (TargetUtils.isTarget(entity, true, true)
                  && BoundBoxUtils.getMinDistanceVec3dByBox(mc.player.getEyePos(), box).distanceTo(mc.player.getEyePos())
                     <= this.releaseDistance.getValue().doubleValue()) {
                  Rotation rotTo = RotationUtils.calculate(BoundBoxUtils.getMinDistanceVec3dByBox(ClientRayTraceUtil.eyePos, box));
                  if (ClientRayTraceUtil.overBox(rotTo.yaw, rotTo.pitch, 3.0, box)) {
                     KillAura.targets.add(entity);
                     KillAura.target = entity;
                     RotationUtils.setRotation(rotTo, 2);
                     return true;
                  }
               }
            }
         }
      }

      return false;
   }

   public void runReduce(int times) {
      if (attack == 0 && this.testFull.getValue() && !gotKB) {
         if (this.debug.getValue()) {
            ChatUtils.info("Hit complete.");
         }

         ServerPacketManager.reset(false);
      }

      if (times <= 0) {
         this.target = null;
      } else {
         if (this.target == null) {
            this.target = this.findTarget();
         }

         if (this.target != null) {
            if (mc.player.isSprinting()) {
               PacketUtils.sendPacket(PlayerInteractEntityC2SPacket.attack(this.target, mc.player.isSneaking()));
               mc.player.setVelocity(mc.player.getVelocity().multiply(0.6, 1.0, 0.6));
               mc.player.swingHand(Hand.MAIN_HAND);
               CPSManager.INSTANCE.click();
               KillAura.msTimer.reset();
               KillAura.lastAttackSprint = mc.player.lastSprinting;
               if (this.debug.getValue()) {
                  ChatUtils.info("Reduce.");
               }
            } else {
               ChatUtils.error("not sprinting.");
            }

            this.target = null;
         } else {
            Entity auraTarget = KillAura.target;
            double dist = auraTarget == null || ClientRayTraceUtil.eyePos == null
               ? -1.0
               : BoundBoxUtils.getMinDistanceVec3dByBox(ClientRayTraceUtil.eyePos, auraTarget.getBoundingBox()).distanceTo(ClientRayTraceUtil.eyePos);
            ChatUtils.error("target is null. dist: " + dist);
         }
      }
   }

   private void runReduceModern() {
      if (this.target == null) {
         this.target = this.findTarget();
      }

      if (this.target == null) {
         return;
      }

      if (mc.player.getAttackCooldownProgress(0.0F) < 1.0F) {
         attack = 1;
      } else {
         PacketUtils.sendPacket(PlayerInteractEntityC2SPacket.attack(this.target, false));
         PacketUtils.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
         mc.player.setVelocity(mc.player.getVelocity().multiply(0.6, 1.0, 0.6));
         CPSManager.INSTANCE.click();
         KillAura.msTimer.reset();
         if (this.debug.getValue()) {
            ChatUtils.info("1.9+ Reduced!");
         }

         this.target = null;
      }
   }

   @EventHandler
   public void onMove(MovementInputEvent event) {
      if (this.isReduceMode()) {
         this.onMoveReduce(event);
         return;
      }

      if (mc.currentScreen == null && (gotKB && ServerPacketManager.deSyncing || attack > 0)) {
         event.setForward(true);
         event.setRight(false);
         event.setLeft(false);
         event.setBack(false);
         event.setSprint(true);
      }

      if (attack > 0) {
         this.jump = false;
         event.setJump(false);
      }
   }

   // ==== Reduce 模式：移动输入（LiquidX MovementInput：lag 递减 + 放包原因判定） ====
   private void onMoveReduce(MovementInputEvent event) {
      if (mc.player == null) {
         return;
      }

      if (this.lagTicks > 0 && this.releaseReason == null) {
         this.lagTicks--;
         this.findTarget();

         if (mc.player.getAbilities().flying) {
            this.releaseReason = ReleaseReason.SPECTATOR;
         } else if (this.reduceTarget != null) {
            event.setForward(true);
            event.setRight(false);
            event.setLeft(false);
            event.setBack(false);
            event.setSprint(true);
            this.releaseReason = ReleaseReason.TARGET_REACHED;
         } else if (this.reduceTarget == null && this.noTargetInRange()) {
            this.releaseReason = ReleaseReason.OUT_OF_RANGE;
         } else if (this.lagTicks == 0) {
            this.releaseReason = ReleaseReason.MAX_DELAY;
         }
      }

      if (this.currentGameTick == this.forwardInputAttackGameTick) {
         event.setForward(true);
         event.setBack(false);
      }
   }

   // lag 中且 End 范围内无任何实体 → 超范围放包
   private boolean noTargetInRange() {
      double endSq = this.lagTargetRangeEnd.getValue().doubleValue() * this.lagTargetRangeEnd.getValue().doubleValue();
      for (Entity entity : mc.world.getEntities()) {
         if (entity instanceof LivingEntity living
            && !living.isRemoved()
            && living.isAlive()
            && !(living instanceof ArmorStandEntity)
            && living != mc.player
            && !dev.southside.module.impl.misc.AntiBot.isBot(living)
            && living.squaredDistanceTo(mc.player) <= endSq) {
            return false;
         }
      }
      return true;
   }

   // ==== Reduce 模式：放包 ====
   private void flush() {
      try {
         if (this.lagTicks < 0) {
            return;
         }

         while (!this.packets.isEmpty()) {
            Packet<?> packet = this.packets.poll();
            if (packet != null && mc.getNetworkHandler() != null) {
               PacketUtils.handlePacket(packet);
            }
         }
      } catch (Exception ignored) {
      }
   }

   private void cancelProgressBar() {
      if (this.progressHandle != null) {
         this.progressHandle.cancel();
         this.progressHandle = null;
      }
   }

   private int calcAttacks(double strength) {
      if (strength < this.targetMotion.getValue().doubleValue()) {
         return -1;
      } else {
         for (int i = 0; i < 8; i++) {
            strength *= 0.6;
            if (strength < this.targetMotion.getValue().doubleValue()) {
               return i;
            }
         }

         return 8;
      }
   }

   private boolean ready() {
      if (mc.player == null) {
         return false;
      } else {
         return mc.player.isUsingItem() ? false : this.checkSprint() && (mc.player.isOnGround() || !this.delayTillGround.getValue());
      }
   }

   private boolean checkSprint() {
      return mc.player.isSprinting();
   }
}