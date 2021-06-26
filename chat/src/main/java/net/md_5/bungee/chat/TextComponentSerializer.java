package net.md_5.bungee.chat;

import com.google.gson.*;
import net.md_5.bungee.api.chat.TextComponent;

import java.lang.reflect.Type;

public class TextComponentSerializer extends BaseComponentSerializer implements JsonSerializer<TextComponent>, JsonDeserializer<TextComponent> {

    @Override
    public TextComponent deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        TextComponent component = new TextComponent();
        JsonObject object = json.getAsJsonObject();
        if (!object.has("text")) {
            throw new JsonParseException("Could not parse JSON: missing 'text' property");
        }
        component.setText(object.get("text").getAsString());
        deserialize(object, component, context);
        return component;
    }

    @Override
    public JsonElement serialize(TextComponent src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject object = new JsonObject();
        serialize(object, src, context);
        object.addProperty("text", src.getText());
        return object;
    }
}
