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
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.codehaus.plexus.util.IOUtil.toByteArray;
import static org.junit.Assert.assertNotNull;

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

        File mainJarFile = new File(basedir, "target/basic-1.0.jar");
        assertThat(mainJarFile).isFile();

        try (JarFile jar = new JarFile(mainJarFile)) {
            assertThat(list(jar.entries()))
                    .extracting(ZipEntry::getName)
                    .doesNotContain(DESCRIPTOR);
        }

        File servicesJarFile = new File(basedir, "target/basic-1.0-services.jar");
        assertThat(servicesJarFile).isFile();

        try (JarFile jar = new JarFile(servicesJarFile)) {
            JarEntry entry = jar.getJarEntry(DESCRIPTOR);
            assertNotNull(entry);
            try (InputStream in = jar.getInputStream(entry)) {
                String contents = new String(toByteArray(in), UTF_8);
                assertThat(contents).isEqualTo("its.BasicPlugin\n");
            }
        }

        File pluginZipFile = new File(basedir, "target/basic-1.0.zip");
        assertThat(pluginZipFile).isFile();

        try (ZipFile zip = new ZipFile(pluginZipFile)) {
            assertThat(list(zip.entries()))
                    .extracting(ZipEntry::getName)
                    .contains("basic-1.0/basic-1.0.jar")
                    .contains("basic-1.0/basic-1.0-services.jar");
        }
    }
}
