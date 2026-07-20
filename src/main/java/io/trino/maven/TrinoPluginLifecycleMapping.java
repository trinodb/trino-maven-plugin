package io.trino.maven;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.lifecycle.mapping.DefaultLifecycleMapping;
import org.apache.maven.lifecycle.mapping.Lifecycle;
import org.apache.maven.lifecycle.mapping.LifecycleMojo;
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
        phases.put("validate", phase("io.trino:trino-maven-plugin:check-spi-dependencies"));
        phases.put("process-resources", phase("org.apache.maven.plugins:maven-resources-plugin:resources"));
        phases.put("compile", phase("org.apache.maven.plugins:maven-compiler-plugin:compile"));
        phases.put("process-classes", phase("io.trino:trino-maven-plugin:generate-service-descriptor"));
        phases.put("process-test-resources", phase("org.apache.maven.plugins:maven-resources-plugin:testResources"));
        phases.put("test-compile", phase("org.apache.maven.plugins:maven-compiler-plugin:testCompile"));
        phases.put("test", phase("org.apache.maven.plugins:maven-surefire-plugin:test"));
        phases.put("package", phase("org.apache.maven.plugins:maven-jar-plugin:jar", "io.trino:trino-maven-plugin:package-trino-plugin"));
        phases.put("install", phase("org.apache.maven.plugins:maven-install-plugin:install"));
        phases.put("deploy", phase("org.apache.maven.plugins:maven-deploy-plugin:deploy"));

        Lifecycle lifecycle = new Lifecycle();
        lifecycle.setId("default");
        lifecycle.setLifecyclePhases(unmodifiableMap(phases));
        return lifecycle;
    }

    private static LifecyclePhase phase(String ...goals)
    {
        LifecyclePhase phase = new LifecyclePhase();
        List<LifecycleMojo> mojos = new ArrayList<>();

        for (String goal : goals) {
            LifecycleMojo lifecycleMojo = new LifecycleMojo();
            lifecycleMojo.setGoal(goal);
            mojos.add(lifecycleMojo);
        }

        phase.setMojos(unmodifiableList(mojos));
        return phase;
    }
}
