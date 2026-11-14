package com.github.krpkbhdn.ccmanager.codemode.sandbox;

import com.github.krpkbhdn.ccmanager.codemode.CodeModeProperties;
import com.github.krpkbhdn.ccmanager.codemode.HostBridge;
import com.github.krpkbhdn.ccmanager.codemode.ScriptResult;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Default sandbox: one fresh GraalJS context per script on a shared engine, no host access except the bridge, bounded by a statement limit.
 * shortcut: the community edition has no heap limit, so a heap-filling script is caught only if Truffle raises OutOfMemoryError; use the container for untrusted scripts.
 */
public class GraalJsSandbox implements ScriptSandbox, AutoCloseable {

    private static final String PRELUDE_RESOURCE = "/code-mode/prelude.js";

    private final CodeModeProperties properties;
    private final String prelude = loadPrelude();
    private Engine engine;

    public GraalJsSandbox(CodeModeProperties properties) {
        this.properties = properties;
    }

    @Override
    public ScriptResult run(String code, HostBridge bridge, SessionContext session) {
        CappedOutput output = new CappedOutput(properties.maxOutputChars());
        Context context = newContext();
        FutureTask<ScriptResult> task = new FutureTask<>(() -> execute(context, code, bridge, output));
        Thread worker = Thread.ofPlatform().name("code-mode-script").daemon().start(task);
        try {
            return task.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandon(worker, context);
            return ScriptResult.failed("The script hit the hard cap of " + properties.timeout().toSeconds() + " s (cc-manager.code-mode.max-script-time)", output.render());
        } catch (InterruptedException e) {
            abandon(worker, context);
            Thread.currentThread().interrupt();
            return ScriptResult.failed("script was cancelled", output.render());
        } catch (ExecutionException e) {
            return ScriptResult.failed("The sandbox failed: " + e.getCause().getClass().getSimpleName(), output.render());
        }
    }

    /** The worker may sit in a blocked host call that cancelling the context cannot reach, so it is interrupted and left behind. */
    private static void abandon(Thread worker, Context context) {
        worker.interrupt();
        Thread.ofPlatform().name("code-mode-cancel").daemon().start(() -> context.close(true));
    }

    @Override
    public synchronized void close() {
        if (engine != null) {
            engine.close(true);
        }
    }

    private ScriptResult execute(Context context, String code, HostBridge bridge, CappedOutput output) {
        try {
            context.getBindings("js").putMember("__bridge", new Bridge(bridge, output));
            var index = bridge.serverIndexJson();
            if (index != null) {
                context.getBindings("js").putMember("__servers", index);
            }
            Value finisher = context.eval("js", prelude);
            return finish(context, finisher, code, output);
        } catch (PolyglotException e) {
            return ScriptResult.failed(failureMessage(e), output.render());
        } catch (OutOfMemoryError e) {
            return ScriptResult.failed("memory limit", output.render());
        } finally {
            context.close(true);
        }
    }

    /** The prelude hands back its finisher as a value, so no global of the script's reach can be used to reach or replace it. */
    private ScriptResult finish(Context context, Value finisher, String code, CappedOutput output) {
        Value promise = context.eval("js", "(async () => {" + code + "\n})()");
        Value outcome = finisher.execute(promise);
        context.eval("js", "0");
        String status = outcome.getMember("status").asString();
        if ("pending".equals(status)) {
            return ScriptResult.failed("script did not finish: a promise never settled", output.render());
        }
        String value = outcome.getMember("value").asString();
        if ("err".equals(status)) {
            return ScriptResult.failed(value, output.render());
        }
        if (!value.isEmpty()) {
            output.appendLine(value);
        }
        return ScriptResult.ok(output.render(), output.truncated());
    }

    private static String failureMessage(PolyglotException e) {
        if (e.isResourceExhausted()) {
            return String.valueOf(e.getMessage()).toLowerCase().contains("statement") ? "statement limit exceeded" : "memory limit";
        }
        if (e.isCancelled()) {
            return "script was cancelled";
        }
        return String.valueOf(e.getMessage());
    }

    private Context newContext() {
        return Context.newBuilder("js").engine(sharedEngine())
                .allowAllAccess(false).allowIO(IOAccess.NONE).allowNativeAccess(false)
                .allowCreateThread(false).allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .option("js.print", "false").option("js.load", "false")
                .allowHostAccess(HostAccess.EXPLICIT).allowHostClassLookup(className -> false)
                .resourceLimits(ResourceLimits.newBuilder().statementLimit(properties.statementLimit(), null).build())
                .build();
    }

    private synchronized Engine sharedEngine() {
        if (engine == null) {
            engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false")
                    .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream()).build();
        }
        return engine;
    }

    private static String loadPrelude() {
        try (var stream = GraalJsSandbox.class.getResourceAsStream(PRELUDE_RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The only object a script can reach; collects log lines in order next to forwarding them. */
    public static final class Bridge {

        private final HostBridge host;
        private final CappedOutput output;

        Bridge(HostBridge host, CappedOutput output) {
            this.host = host;
            this.output = output;
        }

        @HostAccess.Export
        public String call(String server, String tool, String argsJson) {
            return host.callTool(server, tool, argsJson);
        }

        @HostAccess.Export
        public void log(String line) {
            output.appendLine(line);
            host.log(line);
        }
    }
}
