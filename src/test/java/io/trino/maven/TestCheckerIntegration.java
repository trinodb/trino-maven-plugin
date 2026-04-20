package io.trino.maven;

import io.takari.maven.testing.TestResources5;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenPluginTest;
import java.io.File;
import org.junit.jupiter.api.extension.RegisterExtension;

@MavenVersions({"3.9.1", "3.9.14"})
class TestCheckerIntegration {
    @RegisterExtension
    final TestResources5 resources = new TestResources5();

    private final MavenRuntime maven;

    TestCheckerIntegration(MavenRuntimeBuilder mavenBuilder) throws Exception {
        String javaVersion = System.getProperty("java.specification.version");
        this.maven = mavenBuilder
                .withCliOptions(
                        "-B", "-U", "-Dmaven.compiler.source=" + javaVersion, "-Dmaven.compiler.target=" + javaVersion)
                .build();
    }

    @MavenPluginTest
    void testBasic() throws Exception {
        File basedir = resources.getBasedir("basic");
        maven.forProject(basedir).execute("verify").assertErrorFreeLog();
    }

    @MavenPluginTest
    void testAbstractPluginClass() throws Exception {
        File basedir = resources.getBasedir("abstract-plugin-class");
        maven.forProject(basedir).execute("verify").assertErrorFreeLog();
    }

    @MavenPluginTest
    void testInterfacePluginClass() throws Exception {
        File basedir = resources.getBasedir("interface-plugin-class");
        maven.forProject(basedir).execute("verify").assertErrorFreeLog();
    }

    @MavenPluginTest
    void testInvalidExtraProvided() throws Exception {
        File basedir = resources.getBasedir("invalid-extra");
        maven.forProject(basedir)
                .execute("verify")
                .assertLogText("[ERROR] Trino plugin dependency io.airlift:units must not have scope 'provided'.");
    }

    @MavenPluginTest
    void testExcludedExtraProvided() throws Exception {
        File basedir = resources.getBasedir("excluded-extra");
        maven.forProject(basedir).execute("verify").assertErrorFreeLog();
    }

    @MavenPluginTest
    void testMultipleExcludedExtraProvided() throws Exception {
        File basedir = resources.getBasedir("two-excluded-extra");
        maven.forProject(basedir).execute("verify").assertErrorFreeLog();
    }

    @MavenPluginTest
    void testInvalidAndExcludedExtraProvided() throws Exception {
        File basedir = resources.getBasedir("invalid-and-excluded-extra");
        maven.forProject(basedir)
                .execute("verify")
                .assertNoLogText("dependency io.airlift:units must")
                .assertLogText(
                        "[ERROR] Trino plugin dependency org.scala-lang:scala-library must not have scope 'provided'.");
    }

    @MavenPluginTest
    void testInvalidMissingProvided() throws Exception {
        File basedir = resources.getBasedir("invalid-missing");
        maven.forProject(basedir)
                .execute("verify")
                .assertLogText("[ERROR] Trino plugin dependency io.airlift:slice must have scope 'provided'.");
    }

    @MavenPluginTest
    void testSkip() throws Exception {
        File basedir = resources.getBasedir("invalid-skipped");
        maven.forProject(basedir)
                .execute("verify")
                .assertErrorFreeLog()
                .assertLogText("[INFO] Skipping SPI dependency checks");
    }
}
