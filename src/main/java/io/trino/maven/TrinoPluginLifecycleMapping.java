package io.trino.maven;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.lifecycle.mapping.DefaultLifecycleMapping;
import org.apache.maven.lifecycle.mapping.Lifecycle;
import org.apache.maven.lifecycle.mapping.LifecyclePhase;

@Named("trino-plugin")
@Singleton
public class TrinoPluginLifecycleMapping
        extends DefaultLifecycleMapping
{
    public TrinoPluginLifecycleMapping()
    {
        super(List.of(defaultLifecycle()));
    }

    private static Lifecycle defaultLifecycle()
    {
        Map<String, LifecyclePhase> phases = new LinkedHashMap<>();
        phases.put("validate", new LifecyclePhase("io.trino:trino-maven-plugin:check-spi-dependencies"));
        phases.put("process-resources", new LifecyclePhase("org.apache.maven.plugins:maven-resources-plugin:resources"));
        phases.put("compile", new LifecyclePhase("org.apache.maven.plugins:maven-compiler-plugin:compile"));
        phases.put("process-classes", new LifecyclePhase("io.trino:trino-maven-plugin:generate-service-descriptor"));
        phases.put("process-test-resources", new LifecyclePhase("org.apache.maven.plugins:maven-resources-plugin:testResources"));
        phases.put("test-compile", new LifecyclePhase("org.apache.maven.plugins:maven-compiler-plugin:testCompile"));
        phases.put("test", new LifecyclePhase("org.apache.maven.plugins:maven-surefire-plugin:test"));
        phases.put("package", new LifecyclePhase(
                "org.apache.maven.plugins:maven-jar-plugin:jar,"
                        + "io.trino:trino-maven-plugin:package-trino-plugin"));
        phases.put("install", new LifecyclePhase("org.apache.maven.plugins:maven-install-plugin:install"));
        phases.put("deploy", new LifecyclePhase("org.apache.maven.plugins:maven-deploy-plugin:deploy"));

        Lifecycle lifecycle = new Lifecycle();
        lifecycle.setId("default");
        lifecycle.setLifecyclePhases(Collections.unmodifiableMap(phases));
        return lifecycle;
    }
}
