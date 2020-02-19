package io.prestosql.maven;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllLines;
import static java.util.Collections.list;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.3.9", "3.5.4", "3.6.2"})
@SuppressWarnings({"JUnitTestNG", "PublicField"})
public class GeneratorIntegrationTest
{
    private static final String DESCRIPTOR = "META-INF/services/io.prestosql.spi.Plugin";

    @Rule
    public final TestResources resources = new TestResources();

    public final MavenRuntime maven;

    public GeneratorIntegrationTest(MavenRuntimeBuilder mavenBuilder)
            throws Exception
    {
        this.maven = mavenBuilder.withCliOptions("-B", "-U").build();
    }

    @Test
    public void testBasic()
            throws Exception
    {
        File basedir = resources.getBasedir("basic");
        maven.forProject(basedir)
                .execute("package")
                .assertErrorFreeLog();

        File output = new File(basedir, "target/classes/" + DESCRIPTOR);

        List<String> lines = readAllLines(output.toPath(), UTF_8);
        assertEquals(singletonList("its.BasicPlugin"), lines);

        File mainJarFile = new File(basedir, "target/basic-1.0.jar");
        assertThat(mainJarFile).isFile();

        try (JarFile jar = new JarFile(mainJarFile)) {
            assertThat(list(jar.entries()))
                    .extracting(ZipEntry::getName)
                    .contains(DESCRIPTOR);
        }

        File pluginZipFile = new File(basedir, "target/basic-1.0.zip");
        assertThat(pluginZipFile).isFile();

        try (ZipFile zip = new ZipFile(pluginZipFile)) {
            assertThat(list(zip.entries()))
                    .extracting(ZipEntry::getName)
                    .contains("basic-1.0/basic-1.0.jar");
        }
    }
}
