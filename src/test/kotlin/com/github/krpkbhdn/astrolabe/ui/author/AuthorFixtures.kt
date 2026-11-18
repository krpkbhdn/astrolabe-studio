package com.github.krpkbhdn.astrolabe.ui.author

import com.github.krpkbhdn.astrolabe.author.AuthorArea
import com.github.krpkbhdn.astrolabe.author.AuthorEntry
import com.github.krpkbhdn.astrolabe.author.AuthorItem
import com.github.krpkbhdn.astrolabe.author.AuthorKind
import com.github.krpkbhdn.astrolabe.author.AuthorStatus

internal fun authorItem(
    id: String, area: AuthorArea = AuthorArea.CLAUDE_TOOLS, group: String = "Plugins", requires: List<String> = emptyList(),
    title: String = id, description: String = "", kind: AuthorKind = AuthorKind.PLUGIN,
) = AuthorItem(id, area, group, title, description, kind, 1, requires, null, id, null, null, null, null, null)

internal fun authorEntry(item: AuthorItem, status: AuthorStatus = AuthorStatus.NOT_INSTALLED, replaces: Boolean = false) =
    AuthorEntry(item, 0, status, emptyList(), replaces)
