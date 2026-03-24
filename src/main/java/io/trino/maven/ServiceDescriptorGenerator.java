/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.maven;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Mojo that generates the service descriptor JAR for Trino plugins.
 */
@Mojo(
        name = "generate-service-descriptor",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true)
public class ServiceDescriptorGenerator extends AbstractMojo {
    private static final String LS = System.lineSeparator();

    @Parameter(defaultValue = "io.trino.spi.Plugin")
    private String pluginClassName;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}-services.jar")
    private String servicesJar;

    @Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/services")
    private String servicesDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private String classesDirectory;

    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        Path servicesFile = Path.of(servicesDirectory, pluginClassName);
        if (Files.exists(servicesFile)) {
            throw new MojoExecutionException(
                    format("%n%nExisting service descriptor for %s found in output directory.", pluginClassName));
        }

        List<String> pluginClasses;
        try {
            pluginClasses = findPluginImplementations();
        } catch (IOException e) {
            throw new MojoExecutionException(
                    format("%n%nError scanning for classes implementing %s.", pluginClassName), e);
        }
        if (pluginClasses.isEmpty()) {
            throw new MojoExecutionException(
                    format("%n%nYou must have at least one class that implements %s.", pluginClassName));
        }

        if (pluginClasses.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (String name : pluginClasses) {
                sb.append(name).append(LS);
            }
            throw new MojoExecutionException(format(
                    "%n%nYou have more than one class that implements %s:%n%n%s%nYou can only have one per plugin project.",
                    pluginClassName, sb));
        }

        String implementationName = pluginClasses.get(0);
        byte[] servicesFileData = (implementationName + "\n").getBytes(UTF_8);
        try (OutputStream out = Files.newOutputStream(Path.of(servicesJar));
                JarOutputStream jar = new JarOutputStream(out)) {

            JarEntry jarEntry = new JarEntry("META-INF/services/" + pluginClassName);
            if (outputTimestamp != null && !outputTimestamp.isBlank()) {
                jarEntry.setTimeLocal(parseOutputTimestamp(outputTimestamp));
            }

            jar.putNextEntry(jarEntry);
            jar.write(servicesFileData);
            jar.closeEntry();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write services JAR file.", e);
        }
        getLog().info(format("Wrote %s to %s", implementationName, servicesJar));
    }

    private List<String> findPluginImplementations() throws IOException {
        Path classesRoot = Path.of(classesDirectory);
        if (!Files.isDirectory(classesRoot)) {
            return List.of();
        }

        String pluginInternalName = pluginClassName.replace('.', '/');

        // Open dependency JARs once and keep them open for the duration of the scan
        List<JarFile> dependencyJars = new ArrayList<>();
        try {
            for (Artifact artifact : project.getArtifacts()) {
                if (artifact.getFile() != null && artifact.getFile().isFile()) {
                    dependencyJars.add(new JarFile(artifact.getFile()));
                }
            }

            // Scan all local class files and extract hierarchy info from bytecode headers
            Map<String, ClassInfo> classInfoMap = new HashMap<>();
            Set<String> localClasses = new HashSet<>();
            try (Stream<Path> walk = Files.walk(classesRoot)) {
                for (Path classFile : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                    ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
                    String className = reader.getClassName();
                    classInfoMap.put(className, new ClassInfo(reader.getAccess(), reader.getSuperName(), reader.getInterfaces()));
                    localClasses.add(className);
                }
            }

            // Find concrete local classes that implement the plugin interface
            List<String> implementations = new ArrayList<>();
            for (String className : localClasses) {
                ClassInfo info = classInfoMap.get(className);
                if ((info.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
                    continue;
                }
                if (implementsInterface(className, pluginInternalName, classInfoMap, dependencyJars)) {
                    implementations.add(className.replace('/', '.'));
                }
            }
            return implementations;
        } finally {
            for (JarFile jar : dependencyJars) {
                try {
                    jar.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private static boolean implementsInterface(
            String className,
            String targetInternalName,
            Map<String, ClassInfo> classInfoMap,
            List<JarFile> dependencyJars)
            throws IOException {
        Set<String> visited = new HashSet<>();
        Queue<String> toCheck = new ArrayDeque<>();
        toCheck.add(className);

        while (!toCheck.isEmpty()) {
            String current = toCheck.poll();
            if (!visited.add(current)) {
                continue;
            }

            ClassInfo info = classInfoMap.get(current);
            if (info == null) {
                // Resolve from dependency JARs on demand
                info = resolveFromDependencies(current, dependencyJars);
                if (info != null) {
                    classInfoMap.put(current, info);
                } else {
                    continue;
                }
            }

            if (info.superName != null) {
                if (info.superName.equals(targetInternalName)) {
                    return true;
                }
                toCheck.add(info.superName);
            }
            for (String iface : info.interfaces) {
                if (iface.equals(targetInternalName)) {
                    return true;
                }
                toCheck.add(iface);
            }
        }
        return false;
    }

    private static ClassInfo resolveFromDependencies(String internalName, List<JarFile> dependencyJars)
            throws IOException {
        String entryName = internalName + ".class";
        for (JarFile jarFile : dependencyJars) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry != null) {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(is);
                    return new ClassInfo(reader.getAccess(), reader.getSuperName(), reader.getInterfaces());
                }
            }
        }
        return null;
    }

    private static LocalDateTime parseOutputTimestamp(String outputTimestamp) {
        try {
            return OffsetDateTime.parse(outputTimestamp)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid project.build.outputTimestamp value '" + outputTimestamp + "'", e);
        }
    }

    private static class ClassInfo {
        final int access;
        final String superName;
        final String[] interfaces;

        ClassInfo(int access, String superName, String[] interfaces) {
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces;
        }
    }
}
