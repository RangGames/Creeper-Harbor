package wiki.creeper.harbor.core;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * 외부 라이브러리(HikariCP·MariaDB·Lettuce·Flyway)를 시작할 때 Maven에서 받아 적재한다. 플러그인 jar에 넣지 않아 크기와 충돌 위험이
 * 줄어든다. 목록(harbor-libraries.txt)은 빌드 때 버전 카탈로그에서 만들어진다.
 */
public final class HarborCoreLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(
                new RemoteRepository.Builder(
                                "central",
                                "default",
                                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
                        .build());
        try (InputStream in =
                getClass().getClassLoader().getResourceAsStream("harbor-libraries.txt")) {
            if (in == null) {
                throw new IllegalStateException("harbor-libraries.txt가 jar에 없습니다");
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(
                                coordinates ->
                                        resolver.addDependency(
                                                new Dependency(
                                                        new DefaultArtifact(coordinates), null)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        classpathBuilder.addLibrary(resolver);
    }
}
