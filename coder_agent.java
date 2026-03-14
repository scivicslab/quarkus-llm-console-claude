//usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JBang launcher for quarkus-coder-agent-claude.
 * Locates the Quarkus runner jar and starts the HTTP server.
 *
 * Usage:
 *   jbang coder_agent.java
 *   jbang coder_agent.java --port=9090
 *   jbang coder_agent.java --working-dir=/home/user/projects
 */
class coder_agent {

    private static final String VERSION = "1.0.0";
    private static final int DEFAULT_PORT = 8090;

    public static void main(String[] args) throws Exception {
        File jarFile = locateJar();
        int port = DEFAULT_PORT;
        String workingDir = null;
        List<String> extraProps = new ArrayList<>();

        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--working-dir=")) {
                workingDir = arg.substring("--working-dir=".length());
            } else if (arg.startsWith("-D")) {
                extraProps.add(arg);
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                return;
            }
        }

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-Dquarkus.http.port=" + port);
        if (workingDir != null) {
            command.add("-Dcoder-agent.llm.working-dir=" + workingDir);
        }
        command.addAll(extraProps);
        command.add("-jar");
        command.add(jarFile.getAbsolutePath());

        System.out.println("Starting quarkus-coder-agent-claude on http://localhost:" + port + "/");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();

        int exitCode = process.waitFor();
        System.exit(exitCode);
    }

    private static void printUsage() {
        System.out.println("Usage: jbang coder_agent.java [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --port=PORT          HTTP port (default: " + DEFAULT_PORT + ")");
        System.out.println("  --working-dir=DIR    Working directory for tool execution");
        System.out.println("  -Dkey=value          Pass arbitrary Quarkus/app properties");
        System.out.println("  -h, --help           Show this help");
    }

    private static File locateJar() {
        List<Path> candidates = new ArrayList<>();

        String envPath = System.getenv("CODER_AGENT_JAR");
        if (envPath != null && !envPath.isBlank()) {
            candidates.add(Paths.get(envPath));
        }

        // Project build output
        candidates.add(Paths.get(System.getProperty("user.home"),
                                 "works", "quarkus-coder-agent-claude", "target",
                                 "quarkus-app", "quarkus-run.jar"));

        // Maven local repository
        candidates.add(Paths.get(System.getProperty("user.home"),
                                 ".m2", "repository", "com", "github", "oogasawa",
                                 "quarkus-coder-agent-claude",
                                 VERSION, "quarkus-coder-agent-claude-" + VERSION + ".jar"));

        for (Path candidate : candidates) {
            File file = candidate.toFile();
            if (file.exists()) {
                return file;
            }
        }

        String message = "quarkus-coder-agent-claude jar not found.\nChecked locations:\n"
            + candidates.stream()
                        .map(Path::toString)
                        .collect(Collectors.joining("\n - ", " - ", ""))
            + "\nRun `cd ~/works/quarkus-coder-agent-claude && mvn install` "
            + "or set CODER_AGENT_JAR to the jar path.";
        throw new IllegalStateException(message);
    }
}
