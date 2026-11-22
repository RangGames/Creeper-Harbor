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
 * Downloads external libraries (HikariCP, MariaDB, Lettuce, Flyway) from Maven at startup and loads
 * them. Keeping them out of the plugin jar reduces its size and the risk of conflicts. The list
 * (harbor-libraries.txt) is generated from the version catalog at build time.
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
