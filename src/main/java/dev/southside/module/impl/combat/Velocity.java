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
   public final Slider counts = new Slider("Attack Counts", 3, 1, 5, 1);
   public final Slider sprintTicks = new Slider("Sprint Ticks", 3, 1, 5, 1);
   public final Slider maxDelay = new Slider("Max Delay", 1000, 0, 1000, 50);
   public static boolean gotKB;
   public boolean jump;
   int coolDownTicks;
   static int attack;
   Entity target;
   Vec3d motion = Vec3d.ZERO;
   EntityVelocityUpdateS2CPacket kbPacket = null;
      private int aimSkipTicks;
   private ProgressBarOverlay.ProgressHandle progressHandle;

   // ==== Reduce 模式状态（Starain 风格纯延迟放包） ====
   public static int attackQueue;
   public static int sprintQueue;
   public static boolean delay;
   private static float yaw;
   private final Queue<Packet<?>> packets = new ConcurrentLinkedQueue<>();
   private long lag;
   private long startDelay;

   public Velocity() {
      super(Category.Combat, "Velocity", "Reduce KnockBack");
   }

   @Override
   public void initPostRunnable() {
      super.initPostRunnable();
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
      delay = false;
      attackQueue = 0;
      sprintQueue = 0;
      this.jump = false;
      yaw = 0.0F;
      this.packets.clear();
      this.lag = 0L;
      this.startDelay = 0L;
      if (ClientUtils.nullCheck() || mc.player != null) {
         this.setSuffix("");
      }
   }

   private boolean isReduceMode() {
      return mode.isMode("Reduce");
   }

   private static float yawFromMotion(double motionX, double motionZ) {
      float y = (float) (Math.toDegrees(Math.atan2(motionZ, motionX)) + 90.0);
      return MathHelper.wrapDegrees(y);
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

   // ==== Reduce 模式：收包（照搬 Starain 逻辑，改为 Southside Yarn 映射） ====
   private void onPacketReduce(PacketEvent event, Packet<?> packet) {
      try {
         if (this.coolDownTicks > 0) {
            return;
         }

         // delay 阶段拦截移动/速度相关包并自行入队
         if (packet instanceof EntityVelocityUpdateS2CPacket
            || packet instanceof EntityPositionSyncS2CPacket
            || packet instanceof EntityS2CPacket
            || packet instanceof CommonPingS2CPacket
            || packet instanceof PlayerPositionLookS2CPacket) {
            if (delay) {
               event.setCancelled(true);
               this.packets.add(packet);
            }
         }

         // 服务器活跃度检测：位置/爆炸包刷新 lag 时间戳
         if (packet instanceof PlayerPositionLookS2CPacket) {
            this.lag = System.currentTimeMillis();
         }

         if (packet instanceof EntityVelocityUpdateS2CPacket velocityPacket
            && velocityPacket.getEntityId() == mc.player.getId()) {
            long now = System.currentTimeMillis();
            if (now - this.lag >= 100L) {
               boolean checkFall = velocityPacket.getVelocityY() > 0.0
                  && (velocityPacket.getVelocityX() != 0.0 || velocityPacket.getVelocityZ() != 0.0);
               if (checkFall && !delay) {
                  delay = true;
                  event.setCancelled(true);
                  this.packets.add(packet);
                  this.startDelay = now;
                  gotKB = true;
                  this.kbPacket = velocityPacket;
                  this.motion = new Vec3d(velocityPacket.getVelocityX(), velocityPacket.getVelocityY(), velocityPacket.getVelocityZ());
               }
               yaw = yawFromMotion(velocityPacket.getVelocityX(), velocityPacket.getVelocityZ());
            }
         }
      } catch (Exception ignored) {
      }
   }

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
         if (delay && (mc.player.isOnGround()
            || System.currentTimeMillis() - this.lag < 100L
            || System.currentTimeMillis() - this.startDelay >= this.maxDelay.getValue().intValue())) {
            this.flush();
            if (System.currentTimeMillis() - this.lag >= 100L) {
               attackQueue = this.counts.getValue().intValue();
               sprintQueue = this.sprintTicks.getValue().intValue();
            }
         }

         if (sprintQueue >= 1 && (KillAura.targets == null || KillAura.targets.isEmpty())) {
            RotationUtils.setRotation(new Rotation(yaw, RotationUtils.getRotationSafely().pitch), 180);
         }
      } catch (Exception ignored) {
      }
   }

   @EventHandler(
      priority = -200
   )
   public void onRotation(RotationEvent event) {
      if (this.isReduceMode()) {
         if (this.debugHud.getValue() && delay) {
            this.setSuffix(System.currentTimeMillis() - this.startDelay + "ms");
         } else if (!delay) {
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

   // ==== Reduce 模式：攻击队列处理（PlayerTick 风格，借用 RotationAppliedEvent tick 时机） ====
   private void handleAttackQueue() {
      if (attackQueue >= 1) {
         if (mc.crosshairTarget instanceof EntityHitResult entityHitResult
            && entityHitResult.getEntity() instanceof PlayerEntity pl
            && !dev.southside.module.impl.misc.AntiBot.isBot(pl)) {
            mc.interactionManager.attackEntity(mc.player, pl);
            mc.player.swingHand(Hand.MAIN_HAND);
         }
         attackQueue--;
      }
   }

   private Entity findTarget() {
      return KillAura.target != null && Southside.INSTANCE.getModuleManager().get(KillAura.class).inRaycast(KillAura.target) ? KillAura.target : null;
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

   // ==== Reduce 模式：移动输入（照搬 Starain onMoveInput） ====
   private void onMoveReduce(MovementInputEvent event) {
      if (mc.player == null) {
         return;
      }

      long sinceLag = System.currentTimeMillis() - this.lag;

      if (delay && mc.player.fallDistance == 0.0F
         && mc.crosshairTarget instanceof EntityHitResult entityHitResult
         && entityHitResult.getEntity() instanceof PlayerEntity pl
         && !dev.southside.module.impl.misc.AntiBot.isBot(pl)) {
         if (sinceLag >= 100L) {
            event.setForward(true);
            event.setRight(false);
            event.setLeft(false);
            event.setBack(false);
            event.setSprint(true);
         }
      }

      if (this.jump && mc.player.isOnGround()) {
         if (sinceLag >= 100L) {
            event.setJump(true);
            event.setForward(true);
            event.setRight(false);
            event.setLeft(false);
            event.setBack(false);
         }
         this.jump = false;
      }

      if (sprintQueue-- >= 1
         && (KillAura.targets == null || KillAura.targets.isEmpty())
         && sinceLag >= 100L) {
         event.setForward(true);
         event.setRight(false);
         event.setLeft(false);
         event.setBack(false);
         event.setSprint(true);
      }
   }

   // ==== Reduce 模式：放包 ====
   private void flush() {
      try {
         if (!delay) {
            return;
         }

         while (!this.packets.isEmpty()) {
            Packet<?> packet = this.packets.poll();
            if (packet != null && mc.getNetworkHandler() != null) {
               PacketUtils.handlePacket(packet);
            }
         }

         delay = false;
         this.jump = true;
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