package ch.sla.eeaplugin.core.configurator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.m2e.jdt.IClasspathDescriptor;
import org.eclipse.m2e.jdt.IClasspathEntryDescriptor;
import org.eclipse.m2e.jdt.IJavaProjectConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M2E Project configurator to set external null annotations.
 *
 * @author Sylvain LAURENT - original implementation, with one EEA for all CP containers (i.e. JRE & M2E's, top level)
 * @author Michael Vorburger - support multiple individual EEA JARs, per M2E entry (not container); and JRE
 */
public class ClasspathConfigurator extends AbstractProjectConfigurator implements IJavaProjectConfigurator {

    private static final MavenGAV JAVA_GAV = MavenGAV.of("java", "java");
    private static final String EEA_FOR_GAV_FILENAME = "eea-for-gav";
    private static final String M2E_JDT_ANNOTATIONPATH = "m2e.jdt.annotationpath";

    private final static Logger LOGGER = LoggerFactory.getLogger(ClasspathConfigurator.class);

    @Override
    public void configureClasspath(IMavenProjectFacade mavenProjectFacade, IClasspathDescriptor classpath,
            IProgressMonitor monitor) throws CoreException {
        dump("configureClasspath", classpath);
        List<IPath> classpathEntryPaths = classpath.getEntryDescriptors().stream().map(cpEntry -> cpEntry.getPath()).collect(Collectors.toList());
        Map<MavenGAV, IPath> mapping = getExternalAnnotationMapping(classpathEntryPaths);
        for (IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
            mapping.entrySet().stream()
                .filter(e -> e.getKey().matches(cpEntry.getArtifactKey())).findFirst()
                .ifPresent(e -> {
                    setExternalAnnotationsPath(cpEntry, e.getValue().toString());
                 });
        }
        setJREsEEA(mapping, mavenProjectFacade.getProject());
    }

    private void setJREsEEA(Map<MavenGAV, IPath> mapping, IProject project) throws CoreException {
        IPath javaEEAPath = mapping.get(JAVA_GAV);
        if (javaEEAPath != null) {
            // TODO This does not actually work (because RuntimeClasspathEntry.updateClasspathEntry() does nothing for container, only Archive & Variable
            JavaRuntime.computeJREEntry(JavaCore.create(project)).setExternalAnnotationsPath(javaEEAPath);
            LOGGER.info("Setting External Annotations of JRE to {}", javaEEAPath);
        }
    }

    private void setExternalAnnotationsPath(IClasspathEntryDescriptor cpEntry, String path) {
        cpEntry.setClasspathAttribute("annotationpath", path);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Setting External Annotations of {} to {}", toString(cpEntry), path);
        }
    }

    private Map<MavenGAV, IPath> getExternalAnnotationMapping(List<IPath> classpathEntryPaths) {
        Map<MavenGAV, IPath> mapping = new HashMap<>();
        for (IPath cpEntryPath : classpathEntryPaths) {
            List<String> gavLines = getPath(cpEntryPath, EEA_FOR_GAV_FILENAME);
            gavLines.forEach(line -> {
                try {
                    MavenGAV gav = MavenGAV.parse(line);
                    LOGGER.info("Found EEA for {} in {}", gav, cpEntryPath);
                    mapping.put(gav, cpEntryPath);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Bad line in " + EEA_FOR_GAV_FILENAME + " of " + cpEntryPath + ": " + line, e);
                }
            });
        }
        return mapping;
    }

    private List<String> getPath(IPath iPath, String fileName) {
        Optional<File> optionalFileOrDirectory = toFile(iPath);
        if (!optionalFileOrDirectory.isPresent()) {
            return Collections.emptyList();
        }
        File fileOrDirectory = optionalFileOrDirectory.get();
        if (!fileOrDirectory.exists()) {
            return Collections.emptyList();
        }
        if (fileOrDirectory.isDirectory()) {
            File file = new File(fileOrDirectory, fileName);
            return readFileLines(file.toPath());
        } else if (fileOrDirectory.isFile()) {
            Path jarFilePath = Paths.get(fileOrDirectory.toURI());
            URI jarEntryURI = URI.create("jar:file:" + jarFilePath.toUri().getPath() + "!/" + fileName);
            try (FileSystem zipfs = FileSystems.newFileSystem(jarEntryURI, Collections.emptyMap())) {
                Path jarEntryPath = Paths.get(jarEntryURI);
                return readFileLines(jarEntryPath);
            } catch (IOException e) {
                LOGGER.error("IOException from ZipFileSystemProvider for: {}", jarEntryURI, e);
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    private Optional<File> toFile(IPath iPath) {
        // iPath.toFile() is NOT what we want..
        // https://wiki.eclipse.org/FAQ_What_is_the_difference_between_a_path_and_a_location%3F
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        IResource resource = root.findMember(iPath);
        if (resource != null) {
            IPath location = resource.getLocation();
            return Optional.ofNullable(location.toFile());
        } else {
            // In this case iPath likely was an absolute FS / non-WS path to being with (or a closed project), so try:
            return Optional.ofNullable(iPath.toFile());
        }
    }

    private List<String> readFileLines(Path path) {
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }
        try (Stream<String> linesStream = Files.lines(path, StandardCharsets.UTF_8)) {
            return linesStream
                    .map(t -> t.trim())
                    .filter(t -> !t.isEmpty())
                    .filter(t -> !t.startsWith("#"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOGGER.error("IOException while reading from: {}", path, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void configureRawClasspath(ProjectConfigurationRequest request, IClasspathDescriptor classpath,
            IProgressMonitor monitor) throws CoreException {
        dump("configureRawClasspath", classpath);
        String annotationPath = getAnnotationPath(request.getMavenProjectFacade());
        if (annotationPath != null && !annotationPath.isEmpty()) {
            for (IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
                if (cpEntry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
                    setExternalAnnotationsPath(cpEntry, annotationPath);
                }
            }
        } else {
            IProject iProject = request.getProject();
            // Note that we must use computeDefaultRuntimeClassPath() instead of
            // classpath.getEntryDescriptors() here, because in this configureRawClasspath(),
            // contrary to configureClasspath(), we get the JRE & M2E Container, not entries
            // (but to have the java:java EEA we need all entries)
            List<IRuntimeClasspathEntry> allEntries = computeDefaultRuntimeClassPath(iProject);
            List<IPath> classpathEntryPaths = allEntries.stream().map(cpEntry -> cpEntry.getPath()).collect(Collectors.toList());
            Map<MavenGAV, IPath> mapping = getExternalAnnotationMapping(classpathEntryPaths);
            // TODO if setJREsEEA worked we could do:
            //   setJREsEEA(mapping, iProject);
            // but for now let's just:
            IPath javaEEAPath = mapping.get(JAVA_GAV);
            if (javaEEAPath != null) {
                for (IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
                    if (cpEntry.getPath().toString().startsWith("org.eclipse.jdt.launching.JRE_CONTAINER")) {
                        setExternalAnnotationsPath(cpEntry, javaEEAPath.toString());
                    }
                }
            }
        }
    }

    private List<IRuntimeClasspathEntry> computeDefaultRuntimeClassPath(IProject project) throws CoreException {
        IJavaProject jProject = JavaCore.create(project);
        if (jProject == null) {
            Collections.emptyList();
        }
        // strongly inspired by org.eclipse.jdt.launching.JavaRuntime.computeDefaultRuntimeClassPath(IJavaProject)
        IRuntimeClasspathEntry[] unresolved = JavaRuntime.computeUnresolvedRuntimeClasspath(jProject);
        List<IRuntimeClasspathEntry> resolved = new ArrayList<>(unresolved.length);
        for (IRuntimeClasspathEntry entry : unresolved) {
            if (entry.getClasspathProperty() == IRuntimeClasspathEntry.USER_CLASSES) {
                IRuntimeClasspathEntry[] entries = JavaRuntime.resolveRuntimeClasspathEntry(entry, jProject);
                for (IRuntimeClasspathEntry entrie : entries) {
                    resolved.add(entrie);
                }
            }
        }
        return resolved;
    }

    private String getAnnotationPath(IMavenProjectFacade mavenProjectFacade) {
        if (mavenProjectFacade == null) {
            return null;
        }
        MavenProject mavenProject = mavenProjectFacade.getMavenProject();
        if (mavenProject == null) {
            return null;
        }
        Properties properties = mavenProject.getProperties();
        if (properties == null) {
            return null;
        }
        String property = properties.getProperty(M2E_JDT_ANNOTATIONPATH);
        if (property == null) {
            return null;
        } else {
            return property.trim();
        }
    }

    @Override
    public void configure(ProjectConfigurationRequest projectConfigurationRequest, IProgressMonitor monitor) throws CoreException {
        Plugin plugin = projectConfigurationRequest.getMavenProject().getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (plugin == null) {
            return;
        }
        Xpp3Dom configurationDom = (Xpp3Dom) plugin.getConfiguration();
        if (configurationDom == null) {
            return;
        }
        Xpp3Dom compilerArgumentsDom = configurationDom.getChild("compilerArguments");
        if (compilerArgumentsDom == null) {
            return;
        }
        Xpp3Dom propertiesDom = compilerArgumentsDom.getChild("properties");
        String configurationCompilerArgumentsPropertiesPath = propertiesDom.getValue();
        if (configurationCompilerArgumentsPropertiesPath == null) {
            return;
        }

        File configurationCompilerArgumentsFile = new File(configurationCompilerArgumentsPropertiesPath);
        if (!configurationCompilerArgumentsFile.exists()) {
            return;
        }
        if (configurationCompilerArgumentsFile.getPath().replace('\\', '/').contains("/.settings/")) {
            return;
        }

        Properties configurationCompilerArgumentsProperties = new Properties();
        try (FileInputStream inputStream = new FileInputStream(configurationCompilerArgumentsFile)) {
            configurationCompilerArgumentsProperties.load(inputStream);
        } catch (IOException e) {
            LOGGER.error("IOException while reading File: {}", configurationCompilerArgumentsFile, e);
            return;
        }
        if (configurationCompilerArgumentsProperties.isEmpty()) {
            return;
        }

        IJavaProject jProject = JavaCore.create(projectConfigurationRequest.getProject());
        if (jProject == null) {
            return;
        }
        jProject.setOptions(fromProperties(configurationCompilerArgumentsProperties));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    Map<String, String> fromProperties(Properties properties) {
        Map configurationCompilerArgumentsPropertiesAsMap = properties;
        Map<String, String> map = new HashMap<String, String>(configurationCompilerArgumentsPropertiesAsMap);
        return map;
    }

    @Override
    public void mavenProjectChanged(MavenProjectChangedEvent event, IProgressMonitor monitor) throws CoreException {
        String newAnnotationpath = getAnnotationPath(event.getMavenProject());
        String oldAnnotationpath = getAnnotationPath(event.getOldMavenProject());
        if (newAnnotationpath == oldAnnotationpath
                || newAnnotationpath != null && newAnnotationpath.equals(oldAnnotationpath)) {
            return;
        }

        // TODO : warn the user that the config should be updated in full
    }

    private void dump(String methodName, IClasspathDescriptor classpath) {
        if (LOGGER.isDebugEnabled()) {
            for (IClasspathEntryDescriptor entry : classpath.getEntryDescriptors()) {
                LOGGER.debug("{}: {} ", methodName, toString(entry));
            }
        }
    }

    private String toString(IClasspathEntryDescriptor entry) {
        StringBuilder sb = new StringBuilder("IClasspathEntryDescriptor[");
        if (entry.getArtifactKey() != null) {
            sb.append(entry.getArtifactKey().toString()).append(" - ");
        }
        sb.append(entry.getEntryKind());
        sb.append(": ");
        sb.append(entry.getPath());
        sb.append("]");
        return sb.toString();
    }

}
