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

@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.3.9", "3.5.0", "3.6.0"})
@SuppressWarnings({"JUnitTestNG", "PublicField"})
public class CheckerIntegrationTest
{
    @Rule
    public final TestResources resources = new TestResources();

    public final MavenRuntime maven;

    public CheckerIntegrationTest(MavenRuntimeBuilder mavenBuilder)
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
                .execute("verify")
                .assertErrorFreeLog();
    }

    @Test
    public void testInvalidExtraProvided()
            throws Exception
    {
        File basedir = resources.getBasedir("invalid-extra");
        maven.forProject(basedir)
                .execute("verify")
                .assertLogText("[ERROR] Presto plugin dependency com.google.guava:guava must not have scope 'provided'.");
    }

    @Test
    public void testExcludedExtraProvided()
            throws Exception
    {
        File basedir = resources.getBasedir("excluded-extra");
        maven.forProject(basedir)
                .execute("verify")
                .assertErrorFreeLog();
    }

    @Test
    public void testMultipleExcludedExtraProvided()
            throws Exception
    {
        File basedir = resources.getBasedir("two-excluded-extra");
        maven.forProject(basedir)
                .execute("verify")
                .assertErrorFreeLog();
    }

    @Test
    public void testInvalidAndExcludedExtraProvided()
            throws Exception
    {
        File basedir = resources.getBasedir("invalid-and-excluded-extra");
        maven.forProject(basedir)
                .execute("verify")
                .assertNoLogText("dependency com.google.guava:guava must")
                .assertLogText("[ERROR] Presto plugin dependency org.scala-lang:scala-library must not have scope 'provided'.");
    }

    @Test
    public void testInvalidMissingProvided()
            throws Exception
    {
        File basedir = resources.getBasedir("invalid-missing");
        maven.forProject(basedir)
                .execute("verify")
                .assertLogText("[ERROR] Presto plugin dependency io.airlift:units must have scope 'provided'.");
    }

    @Test
    public void testSkip()
            throws Exception
    {
        File basedir = resources.getBasedir("invalid-skipped");
        maven.forProject(basedir)
                .execute("verify")
                .assertErrorFreeLog()
                .assertLogText("[INFO] Skipping SPI dependency checks");
    }
}
