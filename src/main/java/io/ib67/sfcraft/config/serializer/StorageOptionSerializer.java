package io.ib67.sfcraft.config.serializer;

import com.google.gson.*;
import io.ib67.sfcraft.config.StorageOption;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Type;
import java.util.Map;

@RequiredArgsConstructor
@Getter
public class StorageOptionSerializer implements JsonDeserializer<StorageOption>, JsonSerializer<StorageOption> {
    protected final Map<String, Class<? extends StorageOption>> subTypes;

    @Override
    public StorageOption deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (!(json instanceof JsonObject jo)) throw new JsonParseException("Storage option must be a json object");
        var type = jo.get("type").getAsString().toLowerCase();
        return context.deserialize(json, subTypes.get(type));
    }

    @Override
    public JsonElement serialize(StorageOption src, Type typeOfSrc, JsonSerializationContext context) {
        var jo = context.serialize(src).getAsJsonObject();
        jo.addProperty("type", src.type());
        return jo;
    }
}
