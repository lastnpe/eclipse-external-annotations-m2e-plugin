package ch.sla.eeaplugin.core.configurator;

import java.util.Properties;

import org.apache.maven.project.MavenProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.m2e.jdt.IClasspathDescriptor;
import org.eclipse.m2e.jdt.IClasspathEntryDescriptor;
import org.eclipse.m2e.jdt.IJavaProjectConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClasspathConfigurator extends AbstractProjectConfigurator implements IJavaProjectConfigurator {

	private static final String M2E_JDT_ANNOTATIONPATH = "m2e.jdt.annotationpath";
	private final static Logger LOGGER = LoggerFactory.getLogger(ClasspathConfigurator.class);

	@Override
	public void configureRawClasspath(ProjectConfigurationRequest request, IClasspathDescriptor classpath,
			IProgressMonitor monitor) throws CoreException {
		String annotationpath = getAnnotationPath(request.getMavenProject());
		if (annotationpath != null && !annotationpath.isEmpty()) {
			for (IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
				if (cpEntry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
					cpEntry.setClasspathAttribute("annotationpath", annotationpath);
					LOGGER.info("Setting annotationpath to {} for {}", annotationpath, cpEntry);
				}
			}
		}

	}

	String getAnnotationPath(MavenProject mavenProject) {
		if (mavenProject == null) {
			return null;
		}
		Properties properties = mavenProject.getProperties();
		if (properties == null) {
			return null;
		}
		return properties.getProperty(M2E_JDT_ANNOTATIONPATH);
	}

	@Override
	public void configureClasspath(IMavenProjectFacade arg0, IClasspathDescriptor arg1, IProgressMonitor arg2)
			throws CoreException {

	}

	@Override
	public void configure(ProjectConfigurationRequest arg0, IProgressMonitor arg1) throws CoreException {

	}

	public void mavenProjectChanged(MavenProjectChangedEvent event, IProgressMonitor monitor) throws CoreException {
		String newAnnotationpath = getAnnotationPath(event.getMavenProject().getMavenProject());
		String oldAnnotationpath = getAnnotationPath(event.getOldMavenProject().getMavenProject());
		if (newAnnotationpath == oldAnnotationpath
				|| (newAnnotationpath != null && newAnnotationpath.equals(oldAnnotationpath))) {
			return;
		}

		// TODO : warn the user that the config should be updated in full
	}

}
