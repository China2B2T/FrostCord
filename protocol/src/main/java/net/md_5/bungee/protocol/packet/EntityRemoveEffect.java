package net.md_5.bungee.protocol.packet;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.md_5.bungee.protocol.AbstractPacketHandler;
import net.md_5.bungee.protocol.PortablePacket;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EntityRemoveEffect extends PortablePacket {

    private int entityId;
    private int effectId;

    @Override
    public void read(ByteBuf buf) {
        this.entityId = readVarInt(buf);
        this.effectId = buf.readUnsignedByte();
    }

    @Override
    protected void v17Read(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.effectId = buf.readUnsignedByte();
    }

    @Override
    public void write(ByteBuf buf) {
        writeVarInt(entityId, buf);
        buf.writeByte(effectId);
    }

    @Override
    public void handle(AbstractPacketHandler handler) throws Exception {
        handler.handle(this);
    }

    @Override
    protected void v17Write(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeByte(effectId);
    }
}
