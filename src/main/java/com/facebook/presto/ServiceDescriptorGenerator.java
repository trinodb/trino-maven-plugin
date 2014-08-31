package com.facebook.presto;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;

import com.facebook.presto.spi.Plugin;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

/**
 * Mojo that generates the default service descriptor for Presto plugins to META-INF/services/com.facebook.presto.spi.Plugin.
 * 
 * @author Jason van Zyl
 *
 */
@Mojo(name = "generate-service-descriptor", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class ServiceDescriptorGenerator extends AbstractMojo {

  private final static String LS = System.getProperty("line.separator");
  
  @Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/services/com.facebook.presto.spi.Plugin")
  private File servicesFile;

  @Parameter(defaultValue = "${project.build.outputDirectory}")
  private File classesDirectory;

  @Parameter(defaultValue = "${project}")
  private MavenProject project;

  public void execute() throws MojoExecutionException, MojoFailureException {
    // If users have already provided their own service file then we will not overwrite it
    if (servicesFile.exists()) {
      return;
    }
    if (!servicesFile.getParentFile().exists()) {
      servicesFile.getParentFile().mkdirs();
    }
    List<Class<?>> pluginClasses;
    try {
      URLClassLoader loader = createClassloaderFromCompileTimeDependencies();
      pluginClasses = findImplementationsOf(Plugin.class, loader);
    } catch (Exception e1) {
      throw new MojoExecutionException(String.format("%n%nError scanning for classes implementing %s.", Plugin.class.getName()));
    }
    if (pluginClasses.size() == 0) {
      throw new MojoExecutionException(String.format("%n%nYou must have at least one class that implements %s.", Plugin.class.getName()));
    }
    if (pluginClasses.size() > 1) {
      StringBuffer sb = new StringBuffer();
      for (Class<?> pluginClass : pluginClasses) {
        sb.append(pluginClass.getName()).append(LS);
      }
      throw new MojoExecutionException(String.format("%n%nYou have more than one class that implements %s:%n%n%s%nYou can only have one per plugin project.", Plugin.class.getName(), sb.toString()));
    }
    try {
      Class<?> pluginClass = pluginClasses.get(0);
      Files.write(pluginClass.getName().getBytes(Charsets.UTF_8), servicesFile);
      getLog().info(String.format("Wrote META-INF/services/com.facebook.presto.spi.Plugin with %s", pluginClass.getName()));
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to write service descriptor.", e);
    }
  }

  private URLClassLoader createClassloaderFromCompileTimeDependencies() throws Exception {
    List<URL> urls = Lists.newArrayList();
    urls.add(classesDirectory.toURI().toURL());
    for (Artifact artifact : project.getArtifacts()) {
      if (artifact.getFile() != null) {
        urls.add(artifact.getFile().toURI().toURL());
      }
    }
    return new URLClassLoader(urls.toArray(new URL[urls.size()]));
  }

  private List<Class<?>> findImplementationsOf(Class<?> implementationTemplate, URLClassLoader searchRealm) throws IOException {
    List<Class<?>> implementations = Lists.newArrayList();
    List<String> classes = FileUtils.getFileNames(classesDirectory, "**/*.class", null, false);
    for (String classPath : classes) {
      String className = classPath.substring(0, classPath.length() - 6).replace('/', '.');
      try {
        Class<?> implementation = searchRealm.loadClass(implementationTemplate.getName());
        Class<?> clazz = searchRealm.loadClass(className);
        if (implementation.isAssignableFrom(clazz)) {
          implementations.add(clazz);
        }
      } catch (ClassNotFoundException e) {
        // won't happen
      }
    }
    return implementations;
  }
}
