package net.md_5.bungee.netty;

import com.google.common.base.Preconditions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.compress.PacketCompressor;
import net.md_5.bungee.compress.PacketDecompressor;
import net.md_5.bungee.protocol.MinecraftDecoder;
import net.md_5.bungee.protocol.MinecraftEncoder;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.packet.Kick;

import java.net.SocketAddress;

public class ChannelWrapper {

    private final Channel ch;
    @Getter
    @Setter
    private SocketAddress remoteAddress;
    @Getter
    private volatile boolean closed;
    @Getter
    private volatile boolean closing;

    public ChannelWrapper(ChannelHandlerContext ctx) {
        this.ch = ctx.channel();
        this.remoteAddress = (this.ch.remoteAddress() == null) ? this.ch.parent().localAddress() : this.ch.remoteAddress();
    }

    public void setProtocol(Protocol protocol) {
        ch.pipeline().get(MinecraftDecoder.class).setProtocol(protocol);
        ch.pipeline().get(MinecraftEncoder.class).setProtocol(protocol);
    }

    public void setVersion(int protocol) {
        ch.pipeline().get(MinecraftDecoder.class).setProtocolVersion(protocol);
        ch.pipeline().get(MinecraftEncoder.class).setProtocolVersion(protocol);
    }

    public void write(Object packet) {
        if (!closed) {
            if (packet instanceof PacketWrapper) {
                ((PacketWrapper) packet).setReleased(true);
                ch.writeAndFlush(((PacketWrapper) packet).buf, ch.voidPromise());
            } else {
                ch.writeAndFlush(packet, ch.voidPromise());
            }
        }
    }

    public void markClosed() {
        closed = closing = true;
    }

    public void close() {
        close(null);
    }

    public void close(Object packet) {
        if (!closed) {
            closed = closing = true;

            if (packet != null && ch.isActive()) {
                // FlameCord - Remove the firing of exceptions on failure
                ch.writeAndFlush(packet).addListeners(ChannelFutureListener.CLOSE);
            } else {
                // FlameCord - Don't flush just close
                ch.close();
            }
        }
    }

    @Deprecated
    public void delayedClose(final Kick kick) {
        close(kick);
    }

    public void addBefore(String baseName, String name, ChannelHandler handler) {
        Preconditions.checkState(ch.eventLoop().inEventLoop(), "cannot add handler outside of event loop");
        // FlameCord - Don't flush if not necessary
        ch.pipeline().addBefore(baseName, name, handler);
    }

    public Channel getHandle() {
        return ch;
    }

    public void setCompressionThreshold(int compressionThreshold) {
        if (ch.pipeline().get(PacketCompressor.class) == null && compressionThreshold != -1) {
            addBefore(PipelineUtils.PACKET_ENCODER, "compress", new PacketCompressor());
        }
        if (compressionThreshold != -1) {
            ch.pipeline().get(PacketCompressor.class).setThreshold(compressionThreshold);
        } else {
            ch.pipeline().remove("compress");
        }

        if (ch.pipeline().get(PacketDecompressor.class) == null && compressionThreshold != -1) {
            addBefore(PipelineUtils.PACKET_DECODER, "decompress", new PacketDecompressor());
        }
        if (compressionThreshold == -1) {
            ch.pipeline().remove("decompress");
        }
    }
}
