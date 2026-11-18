package wiki.creeper.harbor.common.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creeper CDN 업로드 (POST /api/web/files, multipart: file·namespace·version, 헤더 x-api-key). CDN은 파일
 * 이름을 내용 해시로 정하므로 같은 파일은 같은 주소, 다른 파일은 새 주소가 된다. API 키는 로그·예외 메시지에 넣지 않는다.
 */
public final class CdnClient {

    public static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9-]*");
    public static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+(\\.[0-9]+)?");

    private final URI base;
    private final String apiKey;
    private final HttpClient http;

    /**
     * @param base 예: https://cdn.creeper.wiki
     */
    public CdnClient(URI base, String apiKey) {
        this.base = base;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * 업로드 결과.
     *
     * @param url 내려받을 주소
     * @param hash CDN이 계산한 내용 해시
     */
    public record Uploaded(String url, String hash) {}

    /** 블로킹 (가상 스레드에서 부른다). */
    public Uploaded upload(Path file, String namespace, String version)
            throws IOException, InterruptedException {
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("CDN namespace 형식이 틀렸습니다: " + namespace);
        }
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("CDN version 형식이 틀렸습니다 (예: 1.0): " + version);
        }
        String boundary = "harbor-" + UUID.randomUUID();
        Path name = file.getFileName();
        byte[] body =
                multipart(
                        boundary,
                        namespace,
                        version,
                        name == null ? "pack.zip" : name.toString(),
                        Files.readAllBytes(file));
        HttpRequest request =
                HttpRequest.newBuilder(base.resolve("/api/web/files"))
                        .timeout(Duration.ofMinutes(5))
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException(
                    "CDN 업로드 실패 HTTP "
                            + response.statusCode()
                            + ": "
                            + abbreviate(response.body()));
        }
        return parse(response.body());
    }

    static Uploaded parse(String json) throws IOException {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new IOException("CDN 응답 형식이 틀렸습니다: " + abbreviate(json));
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement url = object.get("url");
        if (url == null || !url.isJsonPrimitive() || url.getAsString().isBlank()) {
            throw new IOException("CDN 응답에 url이 없습니다: " + abbreviate(json));
        }
        JsonElement hash = object.get("hash");
        return new Uploaded(url.getAsString(), hash == null ? "" : hash.getAsString());
    }

    static byte[] multipart(
            String boundary, String namespace, String version, String filename, byte[] file)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 1024);
        field(out, boundary, "namespace", namespace);
        field(out, boundary, "version", version);
        out.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + filename.replace("\"", "")
                                + "\"\r\nContent-Type: application/zip\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + name
                                + "\"\r\n\r\n"
                                + value
                                + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String abbreviate(String body) {
        String flat = body.replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
