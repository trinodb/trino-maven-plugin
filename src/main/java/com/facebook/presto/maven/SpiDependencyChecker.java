package com.facebook.presto.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
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

import java.util.Set;

import static java.lang.String.format;
import static java.util.stream.Collectors.toSet;

@Mojo(name = "check-spi-dependencies",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class SpiDependencyChecker
        extends AbstractMojo
{
    private static final String SPI_GROUP = "com.facebook.presto";
    private static final String SPI_ARTIFACT = "presto-spi";
    private static final String SPI_NAME = SPI_GROUP + ":" + SPI_ARTIFACT;

    @Parameter(defaultValue = "false")
    private boolean skipCheckSpiDependencies;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repositorySession;

    @Component
    private RepositorySystem repositorySystem;

    @Override
    public void execute()
            throws MojoExecutionException, MojoFailureException
    {
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
                    throw new MojoExecutionException(format("%n%nPresto plugin dependency %s must have type 'jar'.", name));
                }
                if (artifact.getClassifier() != null) {
                    throw new MojoExecutionException(format("%n%nPresto plugin dependency %s must not have a classifier.", name));
                }
                if (!"provided".equals(artifact.getScope())) {
                    throw new MojoExecutionException(format("%n%nPresto plugin dependency %s must have scope 'provided'. It is part of the SPI and will be provided at runtime.", name));
                }
            }
            else if ("provided".equals(artifact.getScope())) {
                throw new MojoExecutionException(format("%n%nPresto plugin dependency %s must not have scope 'provided'. It is not part of the SPI and will not be available at runtime.", name));
            }
        }
    }

    private Set<String> getSpiDependencies()
            throws MojoExecutionException
    {
        return getArtifactDependencies(getSpiDependency())
                .getRoot().getChildren().stream()
                .map(DependencyNode::getArtifact)
                .map(artifact -> artifact.getGroupId() + ":" + artifact.getArtifactId())
                .collect(toSet());
    }

    private CollectResult getArtifactDependencies(Artifact artifact)
            throws MojoExecutionException
    {
        try {
            Dependency dependency = new Dependency(aetherArtifact(artifact), null);
            return repositorySystem.collectDependencies(repositorySession, new CollectRequest(dependency, null));
        }
        catch (DependencyCollectionException e) {
            throw new MojoExecutionException("Failed to resolve dependencies.", e);
        }
    }

    private Artifact getSpiDependency()
            throws MojoExecutionException
    {
        for (Artifact artifact : project.getArtifacts()) {
            if (isSpiArtifact(artifact)) {
                if (!"provided".equals(artifact.getScope())) {
                    throw new MojoExecutionException(format("%n%nPresto plugin dependency %s must have scope 'provided'.", SPI_NAME));
                }
                return artifact;
            }
        }
        throw new MojoExecutionException(format("%n%nPresto plugin must depend on %s.", SPI_NAME));
    }

    private static boolean isSpiArtifact(Artifact artifact)
    {
        return SPI_GROUP.equals(artifact.getGroupId()) &&
                SPI_ARTIFACT.equals(artifact.getArtifactId()) &&
                "jar".equals(artifact.getType()) &&
                (artifact.getClassifier() == null);
    }

    private static org.eclipse.aether.artifact.Artifact aetherArtifact(Artifact artifact)
    {
        return new DefaultArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getClassifier(),
                artifact.getType(),
                artifact.getVersion());
    }
}
