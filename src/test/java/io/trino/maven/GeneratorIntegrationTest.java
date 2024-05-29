package io.trino.maven;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.codehaus.plexus.util.IOUtil.toByteArray;
import static org.junit.Assert.assertNotNull;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;
import java.io.File;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.6.3", "3.8.1"})
@SuppressWarnings({"JUnitTestNG", "PublicField"})
public class GeneratorIntegrationTest {
    private static final String DESCRIPTOR = "META-INF/services/io.trino.spi.Plugin";

    @Rule
    public final TestResources resources = new TestResources();

    public final MavenRuntime maven;

    public GeneratorIntegrationTest(MavenRuntimeBuilder mavenBuilder) throws Exception {
        String javaVersion = System.getProperty("java.specification.version");
        this.maven = mavenBuilder
                .withCliOptions(
                        "-B", "-U", "-Dmaven.compiler.source=" + javaVersion, "-Dmaven.compiler.target=" + javaVersion)
                .build();
    }

    @Test
    public void testBasic() throws Exception {
        testProjectPackaging("basic", "its.BasicPlugin");
    }

    @Test
    public void testAbstractPlugin() throws Exception {
        testProjectPackaging("abstract-plugin-class", "its.TestPlugin");
    }

    @Test
    public void testInterfacePlugin() throws Exception {
        testProjectPackaging("interface-plugin-class", "its.TestPlugin");
    }

    protected void testProjectPackaging(String projectId, String expectedPluginClass) throws Exception {
        File basedir = resources.getBasedir(projectId);
        maven.forProject(basedir).execute("package").assertErrorFreeLog();

        File mainJarFile = new File(basedir, format("target/%s-1.0.jar", projectId));
        assertThat(mainJarFile).isFile();

        try (JarFile jar = new JarFile(mainJarFile)) {
            assertThat(list(jar.entries())).extracting(ZipEntry::getName).doesNotContain(DESCRIPTOR);
        }

        File servicesJarFile = new File(basedir, format("target/%s-1.0-services.jar", projectId));
        assertThat(servicesJarFile).isFile();

        try (JarFile jar = new JarFile(servicesJarFile)) {
            JarEntry entry = jar.getJarEntry(DESCRIPTOR);
            assertNotNull(entry);
            try (InputStream in = jar.getInputStream(entry)) {
                String contents = new String(toByteArray(in), UTF_8);
                assertThat(contents).isEqualTo(expectedPluginClass + "\n");
            }
        }

        File pluginZipFile = new File(basedir, format("target/%s-1.0.zip", projectId));
        assertThat(pluginZipFile).isFile();

        try (ZipFile zip = new ZipFile(pluginZipFile)) {
            assertThat(list(zip.entries()))
                    .extracting(ZipEntry::getName)
                    .contains(format("%1$s-1.0/%1$s-1.0.jar", projectId))
                    .contains(format("%1$s-1.0/%1$s-1.0-services.jar", projectId));
        }
    }
}
