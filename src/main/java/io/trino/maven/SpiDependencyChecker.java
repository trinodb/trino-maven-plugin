package io.trino.maven;

import static java.lang.String.format;
import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;

@Mojo(
        name = "check-spi-dependencies",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public class SpiDependencyChecker extends AbstractMojo {
    @Parameter(defaultValue = "io.trino")
    private String spiGroupId;

    @Parameter(defaultValue = "trino-spi")
    private String spiArtifactId;

    @Parameter(defaultValue = "false")
    private boolean skipCheckSpiDependencies;

    @Parameter
    private final Set<String> allowedProvidedDependencies = new HashSet<>();

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repositorySession;

    @Component
    private RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException {
        if (skipCheckSpiDependencies) {
            getLog().info("Skipping SPI dependency checks");
            return;
        }

        Set<String> spiDependencies = getSpiDependencies();
        getLog().debug("SPI dependencies: " + spiDependencies);

        for (Artifact artifact : project.getArtifacts()) {
            if (isSpiArtifact(artifact)) {
                continue;
            }
            String name = artifact.getGroupId() + ":" + artifact.getArtifactId();
            if (spiDependencies.contains(name)) {
                if (!"jar".equals(artifact.getType())) {
                    throw new MojoExecutionException(
                            format("%n%nTrino plugin dependency %s must have type 'jar'.", name));
                }
                if (artifact.getClassifier() != null) {
                    throw new MojoExecutionException(
                            format("%n%nTrino plugin dependency %s must not have a classifier.", name));
                }
                if (!"provided".equals(artifact.getScope())) {
                    throw new MojoExecutionException(format(
                            "%n%nTrino plugin dependency %s must have scope 'provided'. It is part of the SPI and will be provided at runtime.",
                            name));
                }
            } else if ("provided".equals(artifact.getScope()) && !allowedProvidedDependencies.contains(name)) {
                throw new MojoExecutionException(format(
                        "%n%nTrino plugin dependency %s must not have scope 'provided'. It is not part of the SPI and will not be available at runtime.",
                        name));
            }
        }
    }

    private Set<String> getSpiDependencies() throws MojoExecutionException {
        return getArtifactDependencies(getSpiDependency()).getRoot().getChildren().stream()
                .filter(node -> !node.getDependency().isOptional())
                .map(DependencyNode::getArtifact)
                .map(artifact -> artifact.getGroupId() + ":" + artifact.getArtifactId())
                .collect(toSet());
    }

    private CollectResult getArtifactDependencies(Artifact artifact) throws MojoExecutionException {
        try {
            Dependency dependency = new Dependency(aetherArtifact(artifact), null);
            return repositorySystem.collectDependencies(repositorySession, new CollectRequest(dependency, null));
        } catch (DependencyCollectionException e) {
            throw new MojoExecutionException("Failed to resolve dependencies.", e);
        }
    }

    private Artifact getSpiDependency() throws MojoExecutionException {
        for (Artifact artifact : project.getArtifacts()) {
            if (isSpiArtifact(artifact)) {
                if (!"provided".equals(artifact.getScope())) {
                    throw new MojoExecutionException(
                            format("%n%nTrino plugin dependency %s must have scope 'provided'.", spiName()));
                }
                return artifact;
            }
        }
        throw new MojoExecutionException(format("%n%nTrino plugin must depend on %s.", spiName()));
    }

    private boolean isSpiArtifact(Artifact artifact) {
        return spiGroupId.equals(artifact.getGroupId())
                && spiArtifactId.equals(artifact.getArtifactId())
                && "jar".equals(artifact.getType())
                && (artifact.getClassifier() == null);
    }

    private String spiName() {
        return spiGroupId + ":" + spiArtifactId;
    }

    private static org.eclipse.aether.artifact.Artifact aetherArtifact(Artifact artifact) {
        return new DefaultArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getClassifier(),
                artifact.getType(),
                artifact.getVersion());
    }
}
