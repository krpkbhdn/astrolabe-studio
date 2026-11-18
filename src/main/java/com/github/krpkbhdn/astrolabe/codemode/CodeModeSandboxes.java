package com.github.krpkbhdn.astrolabe.codemode;

import com.github.krpkbhdn.astrolabe.codemode.isolation.IsolationBackend;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ContainerSandbox;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.GraalJsSandbox;
import com.github.krpkbhdn.astrolabe.codemode.sandbox.ScriptSandbox;
import com.github.krpkbhdn.astrolabe.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** The shared GraalJS engine and one container sandbox per runtime; their own timeout is the hard cap, the script timeout is enforced by {@link CodeModeService}. */
@Component
public class CodeModeSandboxes {

    private static final int INSTANCE_ID_HEX_CHARS = 16;

    private final CodeModeProperties properties;
    private final JsonMapper mapper;
    private final String instanceId;
    private final GraalJsSandbox graalJs;
    private final Map<IsolationBackend, ContainerSandbox> containers = new ConcurrentHashMap<>();

    public CodeModeSandboxes(CodeModeProperties properties, JsonMapper mapper, AppProperties app) {
        this.properties = properties.toBuilder().timeout(properties.maxScriptTime()).build();
        this.mapper = mapper;
        this.instanceId = instanceIdOf(app);
        this.graalJs = new GraalJsSandbox(this.properties);
    }

    /** Labels this app's containers (and only those are swept after a crash): a stable hash of the data folder, so two apps never kill each other's. */
    public String instanceId() {
        return instanceId;
    }

    public ScriptSandbox forBackend(IsolationBackend backend) {
        return backend == IsolationBackend.GRAALJS ? graalJs
                : containers.computeIfAbsent(backend, runtime -> new ContainerSandbox(runtime, properties, mapper, instanceId));
    }

    @PreDestroy
    void close() {
        graalJs.close();
    }

    static String instanceIdOf(AppProperties app) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(app.dataDir().toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, INSTANCE_ID_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
