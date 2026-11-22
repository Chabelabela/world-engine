package io.github.chabelabela.worldengine.mixin.spike;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.chabelabela.worldengine.spike.client.SpikeClient;
import java.net.SocketAddress;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spike (E6), client only: counts each received packet's decoded size by type (custom payloads by payload id), to say
 * which part of the idle traffic is the ship's. Netty thread.
 */
@Mixin(PacketDecoder.class)
abstract class PacketDecoderSpikeMixin {
    @WrapOperation(method = "decode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/jfr/JvmProfiler;onPacketReceived(Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketType;Ljava/net/SocketAddress;I)V"))
    private void worldengine$count(JvmProfiler profiler, ConnectionProtocol protocol, PacketType<?> type, SocketAddress address,
                                   int bytes, Operation<Void> original, @Local Packet<?> packet) {
        original.call(profiler, protocol, type, address, bytes);
        SpikeClient.packetReceived(protocol, packet, bytes);
    }
}
