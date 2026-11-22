package wiki.creeper.harbor.common.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/** Shared JSON converter (Gson). Serializes records directly and writes Instant/UUID as strings. */
public final class Json {

    public static final Gson GSON =
            new GsonBuilder()
                    .disableHtmlEscaping()
                    .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
                    .registerTypeAdapter(UUID.class, new UuidAdapter().nullSafe())
                    .create();

    private Json() {}

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }

    private static final class UuidAdapter extends TypeAdapter<UUID> {
        @Override
        public void write(JsonWriter out, UUID value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public UUID read(JsonReader in) throws IOException {
            return UUID.fromString(in.nextString());
        }
    }
}
