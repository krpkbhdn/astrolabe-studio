package com.github.krpkbhdn.astrolabe.ui.state

import com.github.krpkbhdn.astrolabe.claude.session.SubagentSnapshot
import com.github.krpkbhdn.astrolabe.claude.session.TranscriptEntry

/** The subagent whose log is open; [entries] is null while the transcript loads. */
data class AgentLog(val astrolabeSessionId: String, val agent: SubagentSnapshot, val entries: List<TranscriptEntry>?)
