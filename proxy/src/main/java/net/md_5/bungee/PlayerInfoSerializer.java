package net.md_5.bungee;

import com.google.gson.*;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.protocol.ProtocolConstants;

import java.lang.reflect.Type;
import java.util.UUID;

public class PlayerInfoSerializer implements JsonSerializer<ServerPing.PlayerInfo>, JsonDeserializer<ServerPing.PlayerInfo> {


    private final int protocol;

    public PlayerInfoSerializer() {
        this.protocol = ProtocolConstants.MINECRAFT_1_7_6;
    }

    public PlayerInfoSerializer(int protocol) {
        this.protocol = protocol;
    }

    @Override
    public ServerPing.PlayerInfo deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject js = json.getAsJsonObject();
        ServerPing.PlayerInfo info = new ServerPing.PlayerInfo(js.get("name").getAsString(), (UUID) null);
        String id = js.get("id").getAsString();
        if (ProtocolConstants.isBeforeOrEq(protocol, ProtocolConstants.MINECRAFT_1_7_2) || !id.contains("-")) // Travertine
        {
            info.setId(id);
        } else {
            info.setUniqueId(UUID.fromString(id));
        }
        return info;
    }

    @Override
    public JsonElement serialize(ServerPing.PlayerInfo src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject out = new JsonObject();
        out.addProperty("name", src.getName());

        if (ProtocolConstants.isBeforeOrEq(protocol, ProtocolConstants.MINECRAFT_1_7_2)) {
            out.addProperty("id", src.getId());
        } else {
            out.addProperty("id", src.getUniqueId().toString());
        }
        // Travertine end
        return out;
    }
}
