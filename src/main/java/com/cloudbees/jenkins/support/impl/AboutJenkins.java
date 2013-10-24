/*
 * Copyright © 2013 CloudBees, Inc.
 * This is proprietary code. All rights reserved.
 */

package com.cloudbees.jenkins.support.impl;

import com.cloudbees.jenkins.support.SupportPlugin;
import com.cloudbees.jenkins.support.api.Component;
import com.cloudbees.jenkins.support.api.Container;
import com.cloudbees.jenkins.support.api.PrintedContent;
import com.cloudbees.jenkins.support.api.SupportProvider;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.stats.Snapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.PluginManager;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.lifecycle.Lifecycle;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TopLevelItem;
import hudson.remoting.Callable;
import hudson.remoting.VirtualChannel;
import hudson.security.Permission;
import hudson.util.IOUtils;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;

import javax.servlet.ServletContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contributes basic information about Jenkins.
 *
 * @author Stephen Connolly
 */
@Extension
public class AboutJenkins extends Component {

    private final Logger logger = Logger.getLogger(AboutJenkins.class.getName());

    @NonNull
    @Override
    public Set<Permission> getRequiredPermissions() {
        return Collections.singleton(Jenkins.READ);
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "About Jenkins";
    }

    @Override
    public void addContents(@NonNull Container container) {
        container.add(new PrintedContent("about.md") {
            @Override
            protected void printTo(PrintWriter out) throws IOException {
                out.println("Jenkins");
                out.println("=======");
                out.println();
                out.println("Version details");
                out.println("---------------");
                out.println();
                out.println("  * Version: `" + Jenkins.getVersion().toString().replaceAll("`", "&#96;") + "`");
                File jenkinsWar = Lifecycle.get().getHudsonWar();
                if (jenkinsWar == null) {
                    out.println("  * Mode:    Webapp Directory");
                } else {
                    out.println("  * Mode:    WAR");
                }
                try {
                    final ServletContext servletContext = Stapler.getCurrent().getServletContext();
                    out.println("  * Servlet container");
                    out.println("      - Specification: " + servletContext.getMajorVersion() + "." + servletContext
                            .getMinorVersion());
                    out.println(
                            "      - Name:          `" + servletContext.getServerInfo().replaceAll("`", "&#96;") + "`");
                } catch (NullPointerException e) {
                    // pity Stapler.getCurrent() throws an NPE when outside of a request
                }
                out.print(new GetJavaInfo("  *", "      -").call());
                out.println();
                SupportPlugin supportPlugin = SupportPlugin.getInstance();
                if (supportPlugin != null) {
                    SupportProvider supportProvider = supportPlugin.getSupportProvider();
                    if (supportProvider != null) {
                        try {
                            supportProvider.printAboutJenkins(out);
                        } catch (Throwable e) {
                            // ignore as the customer may have an issue specific to the support provider
                        }
                    }
                }
                out.println("Active Plugins");
                out.println("--------------");
                out.println();
                PluginManager pluginManager = Jenkins.getInstance().getPluginManager();
                List<PluginWrapper> plugins = new ArrayList<PluginWrapper>(pluginManager.getPlugins());
                Collections.sort(plugins, new Comparator<PluginWrapper>() {
                    public int compare(PluginWrapper o1, PluginWrapper o2) {
                        return o1.getShortName().compareTo(o2.getShortName());
                    }
                });
                for (PluginWrapper w : plugins) {
                    if (w.isActive()) {
                        out.println("  * " + w.getShortName() + ":" + w.getVersion() + (w.hasUpdate()
                                ? " *(update available)*"
                                : "") + " '" + w.getLongName() + "'");
                    }
                }
                if (supportPlugin != null) {
                    out.println();
                    out.println("Node statistics");
                    out.println("---------------");
                    out.println();
                    out.println("  * Total number of nodes");
                    printHistogram(out, supportPlugin.getJenkinsNodeTotalCount());
                    out.println("  * Total number of nodes online");
                    printHistogram(out, supportPlugin.getJenkinsNodeOnlineCount());
                    out.println("  * Total number of executors");
                    printHistogram(out, supportPlugin.getJenkinsExecutorTotalCount());
                    out.println("  * Total number of executors in use");
                    printHistogram(out, supportPlugin.getJenkinsExecutorUsedCount());
                    out.println();
                }
                out.println();
                out.println("Job statistics");
                out.println("--------------");
                out.println();
                Map<Descriptor<TopLevelItem>, Stats> stats = new HashMap<Descriptor<TopLevelItem>, Stats>();
                Stats total = new Stats();
                for (Descriptor<TopLevelItem> d : Jenkins.getInstance().getDescriptorList(TopLevelItem.class)) {
                    if (Job.class.isAssignableFrom(d.clazz)) {
                        stats.put(d, new Stats());
                    }
                }
                // RunMap.createDirectoryFilter protected, so must do it by hand:
                DateFormat BUILD_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
                for (Job<?, ?> j : Jenkins.getInstance().getAllItems(Job.class)) {
                    // too expensive: int builds = j.getBuilds().size();
                    int builds = 0;
                    // protected access: File buildDir = j.getBuildDir();
                    File buildDir = Jenkins.getInstance().getBuildDirFor(j);
                    File[] buildDirs = buildDir.listFiles();
                    if (buildDirs != null) {
                        for (File d : buildDirs) {
                            if (d.isDirectory()) {
                                try {
                                    BUILD_FORMAT.parse(d.getName());
                                    builds++;
                                } catch (ParseException x) {
                                    // symlink etc., ignore
                                }
                            }
                        }
                    }
                    total.add(builds);
                    for (Map.Entry<Descriptor<TopLevelItem>, Stats> entry : stats.entrySet()) {
                        if (entry.getKey().clazz.isInstance(j)) {
                            entry.getValue().add(builds);
                        }
                    }
                }
                out.println("  * All jobs");
                out.println("      - Number of jobs: " + total.n());
                out.println("      - Number of builds per job: " + total);
                for (Map.Entry<Descriptor<TopLevelItem>, Stats> entry : stats.entrySet()) {
                    out.println("  * Jobs that `" + entry.getKey().getDisplayName().replaceAll("`", "&#96;") + "`");
                    out.println("      - Number of jobs: " + entry.getValue().n());
                    out.println("      - Number of builds per job: " + entry.getValue());
                }
                out.println();
                out.println("Container statistics");
                out.println("--------------------");
                out.println();
                total = new Stats();
                stats.clear();
                for (Descriptor<TopLevelItem> d : Jenkins.getInstance().getDescriptorList(TopLevelItem.class)) {
                    if (ItemGroup.class.isAssignableFrom(d.clazz)) {
                        stats.put(d, new Stats());
                    }
                }
                for (Item j : Jenkins.getInstance().getAllItems(Item.class)) {
                    if (j instanceof ItemGroup) {
                        ItemGroup i = (ItemGroup) j;
                        int items = i.getItems().size();
                        total.add(items);
                        for (Map.Entry<Descriptor<TopLevelItem>, Stats> entry : stats.entrySet()) {
                            if (entry.getKey().clazz.isInstance(j)) {
                                entry.getValue().add(items);
                            }
                        }
                    }
                }
                out.println("  * All containers");
                out.println("      - Number of containers: " + total.n());
                out.println("      - Number of items per container: " + total);
                for (Map.Entry<Descriptor<TopLevelItem>, Stats> entry : stats.entrySet()) {
                    out.println(
                            "  * Container type: `" + entry.getKey().getDisplayName().replaceAll("`", "&#96;") + "`");
                    out.println("      - Number of containers: " + entry.getValue().n());
                    out.println("      - Number of items per container: " + entry.getValue());
                }
                out.println();
                out.println("Build Nodes");
                out.println("------------");
                out.println();
                out.println("  * master (Jenkins)");
                out.println("      - Description:    `" + Jenkins.getInstance().getNodeDescription()
                        .replaceAll("`", "&#96;") + "`");
                out.println("      - Executors:      " + Jenkins.getInstance().getNumExecutors());
                out.println("      - Remote FS root: `" + Jenkins.getInstance().getRootPath().getRemote()
                        .replaceAll("`", "&#96;") + "`");
                out.println("      - Labels:         " + Jenkins.getInstance().getLabelString());
                out.println("      - Usage:          " + Jenkins.getInstance().getMode().getDescription());
                out.print(new GetJavaInfo("      -", "          +").call());
                out.println();
                for (Node node : Jenkins.getInstance().getNodes()) {
                    out.println("  * " + node.getDisplayName() + " (" + node.getDescriptor().getDisplayName() + ")");
                    out.println("      - Description:    `" + node.getNodeDescription().replaceAll("`", "&#96;") + "`");
                    out.println("      - Executors:      " + node.getNumExecutors());
                    FilePath rootPath = node.getRootPath();
                    if (rootPath != null) {
                        out.println("      - Remote FS root: `" + rootPath.getRemote().replaceAll("`", "&#96;")
                                + "`");
                    } else if (node instanceof Slave) {
                        out.println("      - Remote FS root: `" + Slave.class.cast(node).getRemoteFS()
                                .replaceAll("`", "&#96;") + "`");
                    }
                    out.println("      - Labels:         " + node.getLabelString());
                    out.println("      - Usage:          " + node.getMode().getDescription());
                    if (node instanceof Slave) {
                        Slave slave = (Slave) node;
                        out.println(
                                "      - Launch method:  " + slave.getLauncher().getDescriptor().getDisplayName());
                        out.println("      - Availability:   " + slave.getRetentionStrategy().getDescriptor()
                                .getDisplayName());
                    }
                    VirtualChannel channel = node.getChannel();
                    if (channel == null) {
                        out.println("      - Status:         off-line");
                    } else {
                        out.println("      - Status:         on-line");
                        try {
                            out.println("      - Version:        " + channel.call(new GetSlaveVersion()));
                        } catch (IOException e) {
                            logger.log(Level.WARNING,
                                    "Could not get slave.jar version for " + node.getDisplayName(), e);
                        } catch (InterruptedException e) {
                            logger.log(Level.WARNING,
                                    "Could not get slave.jar version for " + node.getDisplayName(), e);
                        }
                        try {
                            out.print(channel.call(new GetJavaInfo("      -", "          +")));
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Could not get java info for " + node.getDisplayName(), e);
                        } catch (InterruptedException e) {
                            logger.log(Level.WARNING, "Could not get java info for " + node.getDisplayName(), e);
                        }
                    }
                    out.println();
                }
            }
        });
        container.add(new PrintedContent("plugins/active.txt") {
            @Override
            protected void printTo(PrintWriter out) throws IOException {
                PluginManager pluginManager = Jenkins.getInstance().getPluginManager();
                List<PluginWrapper> plugins = pluginManager.getPlugins();
                for (PluginWrapper w : plugins) {
                    if (w.isActive()) {
                        out.println(w.getShortName() + ":" + w.getVersion());
                    }
                }
            }
        });
        container.add(new PrintedContent("plugins/disabled.txt") {
            @Override
            protected void printTo(PrintWriter out) throws IOException {
                PluginManager pluginManager = Jenkins.getInstance().getPluginManager();
                List<PluginWrapper> plugins = pluginManager.getPlugins();
                for (PluginWrapper w : plugins) {
                    if (!w.isEnabled()) {
                        out.println(w.getShortName() + ":" + w.getVersion());
                    }
                }
            }
        });
        container.add(new PrintedContent("plugins/failed.txt") {
            @Override
            protected void printTo(PrintWriter out) throws IOException {
                PluginManager pluginManager = Jenkins.getInstance().getPluginManager();
                List<PluginManager.FailedPlugin> plugins = pluginManager.getFailedPlugins();
                for (PluginManager.FailedPlugin w : plugins) {
                    out.println(w.name + " -> " + w.cause);
                }
            }
        });
        container.add(new PrintedContent("nodes/master/checksums.md5") {
            @Override
            protected void printTo(PrintWriter out) throws IOException {
                File jenkinsWar = Lifecycle.get().getHudsonWar();
                if (jenkinsWar != null) {
                    try {
                        out.println(Util.getDigestOf(new FileInputStream(jenkinsWar)) + "  jenkins.war");
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Could not compute MD5 of jenkins.war", e);
                    }
                }
                Stapler stapler = null;
                try {
                    stapler = Stapler.getCurrent();
                } catch (NullPointerException e) {
                    // the method is not always safe :-(
                }
                if (stapler != null) {
                    final ServletContext servletContext = stapler.getServletContext();
                    Set<String> resourcePaths = (Set<String>) servletContext.getResourcePaths("/WEB-INF/lib");
                    for (String resourcePath : new TreeSet<String>(resourcePaths)) {
                        try {
                            out.println(
                                    Util.getDigestOf(servletContext.getResourceAsStream(resourcePath)) + "  war"
                                            + resourcePath);
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Could not compute MD5 of war" + resourcePath, e);
                        }
                    }
                    for (String resourcePath : Arrays.asList(
                            "/WEB-INF/slave.jar",
                            "/WEB-INF/remoting.jar",
                            "/WEB-INF/jenkins-cli.jar",
                            "/WEB-INF/web.xml")) {
                        try {
                            InputStream resourceAsStream = servletContext.getResourceAsStream(resourcePath);
                            if (resourceAsStream == null) {
                                continue;
                            }
                            out.println(
                                    Util.getDigestOf(resourceAsStream) + "  war"
                                            + resourcePath);
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Could not compute MD5 of war" + resourcePath, e);
                        }
                    }
                    resourcePaths = (Set<String>) servletContext.getResourcePaths("/WEB-INF/update-center-rootCAs");
                    for (String resourcePath : new TreeSet<String>(resourcePaths)) {
                        try {
                            out.println(
                                    Util.getDigestOf(servletContext.getResourceAsStream(resourcePath)) + "  war"
                                            + resourcePath);
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Could not compute MD5 of war" + resourcePath, e);
                        }
                    }
                }
                for (File file : new File(Jenkins.getInstance().getRootDir(), "plugins").listFiles()) {
                    if (file.isFile()) {
                        try {
                            out.println(Util.getDigestOf(new FileInputStream(file)) + "  plugins/" + file
                                    .getName());
                        } catch (IOException e) {
                            logger.log(Level.WARNING, "Could not compute MD5 of war/" + file, e);
                        }
                    }
                }
            }
        }
        );
        for (final Node node : Jenkins.getInstance().getNodes()) {
            container.add(
                    new PrintedContent("nodes/slave/" + node.getDisplayName() + "/checksums.md5") {
                        @Override
                        protected void printTo(PrintWriter out) throws IOException {
                            try {
                                out.println(getSlaveDigest(node));
                            } catch (IOException e) {
                                logger.log(Level.WARNING,
                                        "Could not compute checksums on slave " + node.getDisplayName(), e);
                            } catch (InterruptedException e) {
                                logger.log(Level.WARNING,
                                        "Could not compute checksums on slave " + node.getDisplayName(), e);
                            }
                        }
                    }
            );
        }
    }

    private void printHistogram(PrintWriter out, Histogram histogram) {
        out.println("      - Sample size:        " + histogram.count());
        out.println("      - Average:            " + histogram.mean());
        out.println("      - Standard deviation: " + histogram.stdDev());
        out.println("      - Minimum:            " + histogram.min());
        out.println("      - Maximum:            " + histogram.max());
        Snapshot snapshot = histogram.getSnapshot();
        out.println("      - 95th percentile:    " + snapshot.get95thPercentile());
        out.println("      - 99th percentile:    " + snapshot.get99thPercentile());
    }

    public static String getSlaveDigest(Node node)
            throws IOException, InterruptedException {
        VirtualChannel channel = node.getChannel();
        if (channel == null) {
            return "N/A";
        }
        return channel.call(new GetSlaveDigest(node.getRootPath()));
    }

    private static final class GetSlaveDigest implements Callable<String, RuntimeException> {
        private static final long serialVersionUID = 1L;
        private final String rootPathName;

        public GetSlaveDigest(FilePath rootPath) {
            this.rootPathName = rootPath.getRemote();
        }

        public String call() {
            StringBuilder result = new StringBuilder();
            final File rootPath = new File(this.rootPathName);
            for (File file : rootPath.listFiles()) {
                if (file.isFile()) {
                    try {
                        result.append(Util.getDigestOf(new FileInputStream(file)))
                                .append("  ")
                                .append(file.getName()).append('\n');
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
            return result.toString();
        }

    }

    private static class GetSlaveVersion implements Callable<String, RuntimeException> {
        private static final long serialVersionUID = 1L;

        @edu.umd.cs.findbugs.annotations.SuppressWarnings(
                value = {"NP_LOAD_OF_KNOWN_NULL_VALUE"},
                justification = "Findbugs mis-diagnosing closeQuietly's built-in null check"
        )
        public String call() throws RuntimeException {
            InputStream is = null;
            try {
                is = hudson.remoting.Channel.class.getResourceAsStream("/jenkins/remoting/jenkins-version.properties");
                if (is == null) {
                    return "N/A";
                }
                Properties properties = new Properties();
                try {
                    properties.load(is);
                    return properties.getProperty("version", "N/A");
                } catch (IOException e) {
                    return "N/A";
                }
            } finally {
                IOUtils.closeQuietly(is);
            }
        }
    }

    private static class GetJavaInfo implements Callable<String, RuntimeException> {
        private static final long serialVersionUID = 1L;
        private final String maj;
        private final String min;

        private GetJavaInfo(String majorBullet, String minorBullet) {
            this.maj = majorBullet;
            this.min = minorBullet;
        }

        public String call() throws RuntimeException {
            StringBuilder result = new StringBuilder();
            Runtime runtime = Runtime.getRuntime();
            result.append(maj).append(" Java\n");
            result.append(min).append(" Home:           `").append(System.getProperty("java.home").replaceAll("`",
                    "&#96;")).append("`\n");
            result.append(min).append(" Vendor:           ").append(System.getProperty("java.vendor")).append("\n");
            result.append(min).append(" Version:          ").append(System.getProperty("java.version")).append("\n");
            long maxMem = runtime.maxMemory();
            long allocMem = runtime.totalMemory();
            long freeMem = runtime.freeMemory();
            result.append(min).append(" Maximum memory:   ").append(humanReadableSize(maxMem)).append("\n");
            result.append(min).append(" Allocated memory: ").append(humanReadableSize(allocMem))
                    .append("\n");
            result.append(min).append(" Free memory:      ").append(humanReadableSize(freeMem)).append("\n");
            result.append(min).append(" In-use memory:    ").append(humanReadableSize(allocMem - freeMem)).append("\n");
            result.append(maj).append(" Java Runtime Specification\n");
            result.append(min).append(" Name:    ").append(System.getProperty("java.specification.name")).append("\n");
            result.append(min).append(" Vendor:  ").append(System.getProperty("java.specification.vendor"))
                    .append("\n");
            result.append(min).append(" Version: ").append(System.getProperty("java.specification.version"))
                    .append("\n");
            result.append(maj).append(" JVM Specification\n");
            result.append(min).append(" Name:    ").append(System.getProperty("java.vm.specification.name"))
                    .append("\n");
            result.append(min).append(" Vendor:  ").append(System.getProperty("java.vm.specification.vendor"))
                    .append("\n");
            result.append(min).append(" Version: ").append(System.getProperty("java.vm.specification.version"))
                    .append("\n");
            result.append(maj).append(" JVM Implementation\n");
            result.append(min).append(" Name:    ").append(System.getProperty("java.vm.name")).append("\n");
            result.append(min).append(" Vendor:  ").append(System.getProperty("java.vm.vendor")).append("\n");
            result.append(min).append(" Version: ").append(System.getProperty("java.vm.version")).append("\n");
            result.append(maj).append(" Operating system\n");
            result.append(min).append(" Name:         ").append(System.getProperty("os.name")).append("\n");
            result.append(min).append(" Architecture: ").append(System.getProperty("os.arch")).append("\n");
            result.append(min).append(" Version:      ").append(System.getProperty("os.version")).append("\n");
            RuntimeMXBean mBean = ManagementFactory.getRuntimeMXBean();
            String process = mBean.getName();
            Matcher processMatcher = Pattern.compile("^(-?[0-9]+)@.*$").matcher(process);
            if (processMatcher.matches()) {
                int processId = Integer.parseInt(processMatcher.group(1));
                result.append(maj).append(" Process ID: ").append(processId).append(" (0x")
                        .append(Integer.toHexString(processId)).append(")\n");
            }
            result.append(maj).append(" Process started: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ").format(new Date(mBean.getStartTime())))
                    .append('\n');
            result.append(maj).append(" Process uptime: ")
                    .append(Util.getTimeSpanString(mBean.getUptime())).append('\n');
            result.append(maj).append(" JVM startup parameters:\n");
            if (mBean.isBootClassPathSupported()) {
                result.append(min).append(" Boot classpath: `")
                        .append(mBean.getBootClassPath().replaceAll("`", "&#96;")).append("`\n");
            }
            result.append(min).append(" Classpath: `").append(mBean.getClassPath().replaceAll("`", "&#96;"))
                    .append("`\n");
            result.append(min).append(" Library path: `").append(mBean.getLibraryPath().replaceAll("`", "&#96;"))
                    .append("`\n");
            int count = 0;
            for (String arg : mBean.getInputArguments()) {
                result.append(min).append(" arg[").append(count++).append("]: `").append(arg.replaceAll("`", "&#96;"))
                        .append("`\n");
            }
            return result.toString();
        }

    }

    private static String humanReadableSize(long size) {
        String measure = "B";
        if (size < 1024) {
            return size + " " + measure;
        }
        double number = size;
        if (number >= 1024) {
            number = number / 1024;
            measure = "KB";
            if (number >= 1024) {
                number = number / 1024;
                measure = "MB";
                if (number >= 1024) {
                    number = number / 1024;
                    measure = "GB";
                }
            }
        }
        DecimalFormat format = new DecimalFormat("#0.00");
        return format.format(number) + " " + measure + " (" + size + ")";
    }

    private static class Stats {
        private int s0 = 0;
        private long s1 = 0;
        private long s2 = 0;

        public synchronized void add(int x) {
            s0++;
            s1 += x;
            s2 += x * (long) x;
        }

        public synchronized double x() {
            return s1 / (double) s0;
        }

        private static double roundToSigFig(double num, int sigFig) {
            if (num == 0) {
                return 0;
            }
            final double d = Math.ceil(Math.log10(num < 0 ? -num : num));
            final int pow = sigFig - (int) d;
            final double mag = Math.pow(10, pow);
            final long shifted = Math.round(num * mag);
            return shifted / mag;
        }

        public synchronized double s() {
            if (s0 >= 2) {
                double v = Math.sqrt((s0 * (double) s2 - s1 * (double) s1) / s0 / (s0 - 1));
                if (s0 <= 100) {
                    return roundToSigFig(v, 1); // 0.88*SD to 1.16*SD
                }
                if (s0 <= 1000) {
                    return roundToSigFig(v, 2); // 0.96*SD to 1.05*SD
                }
                return v;
            } else {
                return Double.NaN;
            }
        }

        public synchronized String toString() {
            if (s0 == 0) {
                return "N/A";
            }
            if (s0 == 1) {
                return Long.toString(s1) + " [n=" + s0 + "]";
            }
            return Double.toString(x()) + " [n=" + s0 + ", s=" + s() + "]";
        }

        public synchronized int n() {
            return s0;
        }
    }
}