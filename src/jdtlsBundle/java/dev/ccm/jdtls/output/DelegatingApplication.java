package dev.ccm.jdtls.output;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/** Runs the stock jdtls application so that this bundle is guaranteed to be loaded first. */
public class DelegatingApplication implements IApplication {
    private static final String APPLICATIONS_POINT = "org.eclipse.core.runtime.applications";
    private static final String JDTLS_APPLICATION = "org.eclipse.jdt.ls.core.id1";

    private volatile IApplication delegate;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        IExtension extension = Platform.getExtensionRegistry().getExtension(APPLICATIONS_POINT, JDTLS_APPLICATION);
        if (extension == null) {
            throw new IllegalStateException("application not found: " + JDTLS_APPLICATION);
        }
        IConfigurationElement[] applications = extension.getConfigurationElements();
        IConfigurationElement[] runs = applications.length == 0 ? applications : applications[0].getChildren("run");
        if (runs.length == 0) {
            throw new CoreException(Status.error("no run element in " + JDTLS_APPLICATION));
        }
        IApplication application = (IApplication) runs[0].createExecutableExtension("class");
        delegate = application;
        return application.start(context);
    }

    @Override
    public void stop() {
        IApplication application = delegate;
        if (application != null) {
            application.stop();
        }
    }
}
