package org.jenkinsci.plugins.systemconfigdsl;

import hudson.PluginManager.FailedPlugin;
import hudson.PluginWrapper;
import hudson.lifecycle.Lifecycle;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Root closure delegate that supports pseudo global functions
 * as well as configuring the singleton {@link Jenkins} instance.
 *
 * @author Kohsuke Kawaguchi
 */
public class JenkinsSurrogate extends Surrogate {
    private final Jenkins jenkins;
    private final List<PluginRecipe> recipes = new ArrayList<PluginRecipe>();

    class PluginRecipe {
        final String name;
        /**
         * Null if no explicit version is specified.
         */
        final VersionNumber version;


        public PluginRecipe(String name, VersionNumber version) {
            this.name = name;
            this.version = version;
        }

        /**
         * Ensures that the plugin requested is present by installing it if necessary
         */
        public void perform() throws Exception {
            if (requiresInstallation()) {
                install();
            } else {
                // if we are not installing/updating a plugin, it better be functioning
                for (FailedPlugin f : jenkins.getPluginManager().getFailedPlugins()) {
                    if (f.name.equals(name)) {
                        throw new Error("Plugin "+name+" failed to start", f.cause);
                    }
                }
            }
        }

        /**
         * Checks if we need to install a plugin here
         */
        private boolean requiresInstallation() {
            PluginWrapper n = jenkins.getPluginManager().getPlugin(name);
            // plugin doesn't exist at all?
            if (n==null)    return true;

            // plugin exists and user is happy with any version?
            if (version==null)  return false;

            // the current version is older than what's specified?
            return n.getVersionNumber().isOlderThan(version);
        }

        /**
         * Synchronously installs a plugin and throws an exception if the installation fails.
         */
        private void install() throws Exception {
            jenkins.getUpdateCenter().getPlugin(name).deploy(true).get();
        }
    }

    public JenkinsSurrogate(Jenkins target) {
        super(target);
        this.jenkins = target;
    }

    /**
     * Installs the latest version of a plugin if it's not present
     */
    public void plugin(String name) {
        plugin(name,null);
    }

    /**
     * Updates a plugin if it's older than the specified version.
     */
    public void plugin(String name, String minimumRequiredVersion) {
        this.recipes.add(new PluginRecipe(name, new VersionNumber(minimumRequiredVersion)));
    }

    /**
     * Run the given closure and configure {@link Jenkins}
     */
    /*package*/ void runWith(ConfigScript s) throws Exception {
        s.setDelegate(this);
        s.run();
    }

    @Override
    public void assign() throws Exception {
        for (PluginRecipe p : recipes) {
            p.perform();
        }
        if (jenkins.getUpdateCenter().isRestartRequiredForCompletion()) {
            Lifecycle l = Lifecycle.get();
            if (l.canRestart()) {
                // restart Jenkins to load up new plugins
                l.restart();
                return;
            } else {
                LOGGER.severe("Need to start again to have the newly installed plugins take effect. Quitting");
                System.exit(1);
            }
        }

        super.assign();
    }

    private static final Logger LOGGER = Logger.getLogger(JenkinsSurrogate.class.getName());
}
