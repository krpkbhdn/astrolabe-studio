package dev.ccm.jdtls.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.buildship.core.InitializationContext;
import org.eclipse.buildship.core.ProjectConfigurator;
import org.eclipse.buildship.core.ProjectContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.FrameworkUtil;

/**
 * Moves the source and default output folders of Gradle-imported Java projects to build/eclipse-out,
 * so the language server never writes into the folders Gradle itself builds into.
 */
public class OutputConfigurator implements ProjectConfigurator {
    private static final String BUNDLE_ID = "dev.ccm.jdtls.output";
    private static final String OUTPUT_ROOT = "build/eclipse-out/";
    private static final String MARKER_FILE = "redirected";

    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    @Override
    public void init(InitializationContext context, IProgressMonitor monitor) {
    }

    @Override
    public void unconfigure(ProjectContext context, IProgressMonitor monitor) {
    }

    @Override
    public void configure(ProjectContext context, IProgressMonitor monitor) {
        IProject project = context.getProject();
        try {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) {
                return;
            }
            redirect(JavaCore.create(project), monitor);
            record(project, "OK build/eclipse-out");
        } catch (Exception failure) {
            record(project, "FAILED " + failure.getClass().getName());
            Platform.getLog(getClass()).log(new Status(IStatus.ERROR, BUNDLE_ID, "output redirect failed", failure));
        }
    }

    private void redirect(IJavaProject javaProject, IProgressMonitor monitor) throws Exception {
        IPath projectPath = javaProject.getProject().getFullPath();
        List<IClasspathEntry> entries = new ArrayList<>();
        for (IClasspathEntry entry : javaProject.getRawClasspath()) {
            IPath output = entry.getOutputLocation();
            if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE && output != null) {
                entry = JavaCore.newSourceEntry(entry.getPath(), entry.getInclusionPatterns(),
                        entry.getExclusionPatterns(), projectPath.append(OUTPUT_ROOT + output.lastSegment()),
                        entry.getExtraAttributes());
            }
            entries.add(entry);
        }
        javaProject.setRawClasspath(entries.toArray(new IClasspathEntry[0]), projectPath.append(OUTPUT_ROOT + "default"), monitor);
    }

    /** Writes one line per project so the app can tell which projects were redirected. */
    private void record(IProject project, String result) {
        if (project.getLocation() == null) {
            return;
        }
        try {
            Path directory = Platform.getStateLocation(FrameworkUtil.getBundle(getClass())).toFile().toPath();
            synchronized (RESULTS) {
                RESULTS.put(project.getLocation().toOSString(), result);
                StringBuilder lines = new StringBuilder();
                RESULTS.forEach((location, outcome) -> lines.append(location).append(' ').append(outcome).append('\n'));
                Files.writeString(directory.resolve(MARKER_FILE), lines, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException failure) {
            Platform.getLog(getClass()).log(new Status(IStatus.ERROR, BUNDLE_ID, "marker write failed", failure));
        }
    }
}
