package com.github.krpkbhdn.ccmanager.codemode.sandbox;

import com.github.krpkbhdn.ccmanager.codemode.HostBridge;
import com.github.krpkbhdn.ccmanager.codemode.ScriptResult;
import com.github.krpkbhdn.ccmanager.codemode.SessionContext;

/** Runs one JavaScript snippet with the host bridge as its only way out; limits come from CodeModeProperties. */
public interface ScriptSandbox {

    ScriptResult run(String code, HostBridge bridge, SessionContext session);
}
