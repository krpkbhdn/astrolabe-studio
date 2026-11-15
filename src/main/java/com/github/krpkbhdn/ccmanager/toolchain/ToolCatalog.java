package com.github.krpkbhdn.ccmanager.toolchain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The bundled catalog of language servers and their prerequisites. */
@Component
public class ToolCatalog {

    static final String RESOURCE = "/toolchain/tools.json";

    private record Document(List<ToolDefinition> tools) {}

    private final List<ToolDefinition> tools;

    @Autowired
    public ToolCatalog() {
        this(RESOURCE);
    }

    ToolCatalog(String resource) {
        try (InputStream in = ToolCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing toolchain catalog " + resource);
            }
            tools = List.copyOf(JsonMapper.builder().build().readValue(in, new TypeReference<Document>() {}).tools());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read toolchain catalog " + resource, e);
        }
        ToolCatalogValidator.validate(tools);
    }

    public List<ToolDefinition> all() {
        return tools;
    }

    public Optional<ToolDefinition> find(String id) {
        return tools.stream().filter(tool -> tool.id().equals(id)).findFirst();
    }
}
