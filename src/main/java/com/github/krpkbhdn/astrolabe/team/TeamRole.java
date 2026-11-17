package com.github.krpkbhdn.astrolabe.team;

import com.github.krpkbhdn.astrolabe.project.ModelSelection;

/** A stored role; the lead is the main session, every other role becomes a native Claude Code subagent. */
public record TeamRole(long id, RoleDraft draft, int position) {

    public String name() {
        return draft.name();
    }

    public boolean lead() {
        return draft.lead();
    }

    public ModelSelection selection() {
        return new ModelSelection(draft.model(), draft.effort());
    }
}
