package net.md_5.bungee.protocol.packet;

import io.netty.buffer.ByteBuf;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.md_5.bungee.protocol.AbstractPacketHandler;
import net.md_5.bungee.protocol.PortablePacket;
import net.md_5.bungee.protocol.ProtocolConstants;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class TabCompleteRequest extends PortablePacket {

    private int transactionId;
    private String cursor;
    private boolean assumeCommand;
    private boolean hasPositon;
    private long position;

    public TabCompleteRequest(int transactionId, String cursor) {
        this.transactionId = transactionId;
        this.cursor = cursor;
    }

    public TabCompleteRequest(String cursor, boolean assumeCommand, boolean hasPosition, long position) {
        this.cursor = cursor;
        this.assumeCommand = assumeCommand;
        this.hasPositon = hasPosition;
        this.position = position;
    }

    @Override
    public void v17Read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        cursor = readString(buf);
    }

    @Override
    public void read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        if (protocolVersion >= ProtocolConstants.MINECRAFT_1_13) {
            transactionId = readVarInt(buf);
        }
        cursor = readString(buf, (protocolVersion > ProtocolConstants.MINECRAFT_1_13 ? 32500 : (protocolVersion == ProtocolConstants.MINECRAFT_1_13 ? 256 : 32767)));

        if (protocolVersion < ProtocolConstants.MINECRAFT_1_13) {
            if (protocolVersion >= ProtocolConstants.MINECRAFT_1_9) {
                assumeCommand = buf.readBoolean();
            }

            if (hasPositon = buf.readBoolean()) {
                position = buf.readLong();
            }
        }
    }


    @Override
    public void v17Write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        writeString(cursor, buf);
    }

    @Override
    public void write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        if (protocolVersion >= ProtocolConstants.MINECRAFT_1_13) {
            writeVarInt(transactionId, buf);
        }
        writeString(cursor, buf);

        if (protocolVersion < ProtocolConstants.MINECRAFT_1_13) {
            if (protocolVersion >= ProtocolConstants.MINECRAFT_1_9) {
                buf.writeBoolean(assumeCommand);
            }

            buf.writeBoolean(hasPositon);
            if (hasPositon) {
                buf.writeLong(position);
            }
        }
    }

    @Override
    public void handle(AbstractPacketHandler handler) throws Exception {
        handler.handle(this);
    }
}
