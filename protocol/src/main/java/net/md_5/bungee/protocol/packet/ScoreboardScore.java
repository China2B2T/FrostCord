package net.md_5.bungee.protocol.packet;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.md_5.bungee.protocol.AbstractPacketHandler;
import net.md_5.bungee.protocol.PortablePacket;
import net.md_5.bungee.protocol.ProtocolConstants;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ScoreboardScore extends PortablePacket {

    private String itemName;
    /**
     * 0 = create / update, 1 = remove.
     */
    private byte action;
    private String scoreName;
    private int value;


    @Override
    public void v17Read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        itemName = readString(buf);
        action = buf.readByte();
        if (action != 1) {
            scoreName = readString(buf);
            value = buf.readInt();
        }
    }

    @Override
    public void read(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        itemName = readString(buf);
        action = buf.readByte();
        scoreName = readString(buf);
        if (action != 1) {
            value = readVarInt(buf);
        }
    }


    @Override
    public void v17Write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        writeString(itemName, buf);
        buf.writeByte(action);
        if (action != 1) {
            writeString(scoreName, buf);
            buf.writeInt(value);
        }
    }

    @Override
    public void write(ByteBuf buf, ProtocolConstants.Direction direction, int protocolVersion) {
        writeString(itemName, buf);
        buf.writeByte(action);
        writeString(scoreName, buf);
        if (action != 1) {
            writeVarInt(value, buf);
        }
    }

    @Override
    public void handle(AbstractPacketHandler handler) throws Exception {
        handler.handle(this);
    }
}
