package net.md_5.bungee.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class Varint21FrameDecoder extends ByteToMessageDecoder {

    private AtomicLong lastEmptyPacket = new AtomicLong(0); // Travertine
    private static boolean DIRECT_WARNING;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // If we decode an invalid packet and an exception is thrown (thus triggering a close of the connection),
        // the Netty ByteToMessageDecoder will continue to frame more packets and potentially call fireChannelRead()
        // on them, likely with more invalid packets. Therefore, check if the connection is no longer active and if so
        // sliently discard the packet.
        if (!ctx.channel().isActive()) {
            in.skipBytes(in.readableBytes());
            return;
        }

        in.markReaderIndex();

        final byte[] buf = new byte[3];
        for (int i = 0; i < buf.length; i++) {
            if (!in.isReadable()) {
                in.resetReaderIndex();
                return;
            }

            buf[i] = in.readByte();
            if (buf[i] >= 0) {
                int length = DefinedPacket.readVarInt(Unpooled.wrappedBuffer(buf));
                if (false && length == 0) // Waterfall - ignore
                {
                    // vanilla 1.7 client sometimes sends empty packets.
                    long currentTime = System.currentTimeMillis();
                    long lastEmptyPacket = this.lastEmptyPacket.getAndSet(currentTime);

                    if (currentTime - lastEmptyPacket < 50L) {
                        throw new CorruptedFrameException("Too many empty packets");
                    }
                }

                if (in.readableBytes() < length) {
                    in.resetReaderIndex();
                } else {
                    if (in.hasMemoryAddress()) {
                        out.add(in.slice(in.readerIndex(), length).retain());
                        in.skipBytes(length);
                    } else {
                        if (!DIRECT_WARNING) {
                            DIRECT_WARNING = true;
                            System.out.println("Netty is not using direct IO buffers.");
                        }

                        // See https://github.com/SpigotMC/BungeeCord/issues/1717
                        ByteBuf dst = ctx.alloc().directBuffer(length);
                        in.readBytes(dst);
                        out.add(dst);
                    }
                }
                return;
            }
        }

        throw new CorruptedFrameException("length wider than 21-bit");
    }
}
