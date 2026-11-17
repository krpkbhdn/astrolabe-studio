package com.github.krpkbhdn.astrolabe.author;

import com.github.krpkbhdn.astrolabe.team.RoleDraft;
import java.util.List;

public record TeamTemplate(String name, List<RoleDraft> roles) {

    public TeamTemplate {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
