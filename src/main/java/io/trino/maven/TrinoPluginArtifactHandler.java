package io.trino.maven;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.artifact.handler.DefaultArtifactHandler;

@Named("trino-plugin")
@Singleton
public class TrinoPluginArtifactHandler
        extends DefaultArtifactHandler
{
    public TrinoPluginArtifactHandler()
    {
        super("trino-plugin");
        setExtension("zip");
        setLanguage("java");
        setAddedToClasspath(false);
    }
}
