package org.jenkinsci.plugins.docker.workflow.client;

import com.google.common.base.Optional;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Node;
import hudson.os.WindowsUtil;
import hudson.util.ArgumentListBuilder;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WindowsDockerClient extends DockerClient {
    private static final Logger LOGGER = Logger.getLogger(WindowsDockerClient.class.getName());

    private final Launcher launcher;
    private final Node node;

    public WindowsDockerClient(@NonNull Launcher launcher, @CheckForNull Node node, @CheckForNull String toolName) {
        super(launcher, node, toolName);
        this.launcher = launcher;
        this.node = node;
    }

    private String getDockerFormattedPath(@NonNull String path){
        path=path.replaceAll(":", "");
        if(!path.startsWith("/")&&path.contains("/")){
            path="/"+path;
        }
        return path;
    }

    @Override
    public String run(@NonNull EnvVars launchEnv, @NonNull String image, @CheckForNull String args, @CheckForNull String workdir, @NonNull Map<String, String> volumes, @NonNull Collection<String> volumesFromContainers, @NonNull EnvVars containerEnv, @NonNull String user, @NonNull String... command) throws IOException, InterruptedException {
        ArgumentListBuilder argb = new ArgumentListBuilder("docker", "run", "-d", "-t");
        if (args != null) {
            argb.addTokenized(args);
        }

        if (workdir != null) {
            argb.add("-w", getDockerFormattedPath(workdir));
        }
        for (Map.Entry<String, String> volume : volumes.entrySet()) {
            argb.add("-v", getDockerFormattedPath(volume.getKey()) + ":" + getDockerFormattedPath(volume.getValue()));
        }
        for (String containerId : volumesFromContainers) {
            argb.add("--volumes-from", containerId);
        }
        for (Map.Entry<String, String> variable : containerEnv.entrySet()) {
            argb.add("-e");
            argb.addMasked(WindowsUtil.quoteArgument(variable.getKey() + "=" + variable.getValue()));
        }
        argb.add(image).add(command);

        LaunchResult result = launch(launchEnv, false, null, argb);
        if (result.getStatus() == 0) {
            return result.getOut();
        } else {
            throw new IOException(String.format("Failed to run image '%s'. Error: %s", image, result.getErr()));
        }
    }

    @Override
    public List<String> listProcess(@NonNull EnvVars launchEnv, @NonNull String containerId) throws IOException, InterruptedException {
        LaunchResult result = launch(launchEnv, false, null, "docker", "top", containerId);
        if (result.getStatus() != 0) {
            throw new IOException(String.format("Failed to run top '%s'. Error: %s", containerId, result.getErr()));
        }
        List<String> processes = new ArrayList<>();
        try (Reader r = new StringReader(result.getOut());
             BufferedReader in = new BufferedReader(r)) {
            String line;
            in.readLine(); // ps header
            while ((line = in.readLine()) != null) {
                final StringTokenizer stringTokenizer = new StringTokenizer(line, " ");
                if (stringTokenizer.countTokens() < 1) {
                    throw new IOException("Unexpected `docker top` output : "+line);
                }

                processes.add(stringTokenizer.nextToken()); // COMMAND
            }
        }
        return processes;
    }

    @Override
    public Optional<String> getContainerIdIfContainerized() throws IOException, InterruptedException {
        if (node == null ||
            launch(new EnvVars(), true, null, "sc.exe", "query", "cexecsvc").getStatus() != 0) {
            return Optional.absent();
        }

        LaunchResult getComputerName = launch(new EnvVars(), true, null, "hostname");
        if(getComputerName.getStatus() != 0) {
            throw new IOException("Failed to get hostname.");
        }

        String shortID = getComputerName.getOut().toLowerCase();
        LaunchResult getLongIdResult = launch(new EnvVars(), true, null, "docker", "inspect", shortID, "--format={{.Id}}");
        if(getLongIdResult.getStatus() != 0) {
            LOGGER.log(Level.INFO, "Running inside of a container but cannot determine container ID from current environment.");
            return Optional.absent();
        }

        return Optional.of(getLongIdResult.getOut());
    }

    @Override
    public String whoAmI() throws IOException, InterruptedException {
        try (ByteArrayOutputStream userId = new ByteArrayOutputStream()) {
            launcher.launch().cmds("whoami").quiet(true).stdout(userId).start().joinWithTimeout(CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());
            return userId.toString(Charset.defaultCharset().name()).trim();
        }
    }

    private LaunchResult launch(EnvVars env, boolean quiet, FilePath workDir, String... args) throws IOException, InterruptedException {
        String[] newArgs = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            newArgs[i]=getDockerFormattedPath(args[i]);
        }
        return launch(env, quiet, workDir, new ArgumentListBuilder(newArgs));
    }
    private LaunchResult launch(EnvVars env, boolean quiet, FilePath workDir, ArgumentListBuilder argb) throws IOException, InterruptedException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Executing command \"{0}\"", argb);
        }

        Launcher.ProcStarter procStarter = launcher.launch();
        if (workDir != null) {
            procStarter.pwd(workDir);
        }

        LaunchResult result = new LaunchResult();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        result.setStatus(procStarter.quiet(quiet).cmds(argb).envs(env).stdout(out).stderr(err).start().joinWithTimeout(CLIENT_TIMEOUT, TimeUnit.SECONDS, launcher.getListener()));
        final String charsetName = Charset.defaultCharset().name();
        result.setOut(out.toString(charsetName));
        result.setErr(err.toString(charsetName));

        return result;
    }
}
