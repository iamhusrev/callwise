package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.ai.dto.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Discovers all {@link Tool} beans at startup and exposes them by name.
 *
 * <p>Auto-discovery (Spring injects the {@code List<Tool>}) means a new tool only needs the
 * {@code @Component} annotation — no manual registration is required.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> toolsByName;
    private final List<ToolDefinition> definitions;

    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool t : tools) {
            if (map.put(t.getName(), t) != null) {
                throw new IllegalStateException("Duplicate tool name: " + t.getName());
            }
        }
        this.toolsByName = Collections.unmodifiableMap(map);
        this.definitions = tools.stream().map(Tool::getDefinition).collect(Collectors.toUnmodifiableList());
        log.info("tool.registry registered={} names={}", toolsByName.size(), toolsByName.keySet());
    }

    public List<ToolDefinition> getAllDefinitions() {
        return definitions;
    }

    public boolean has(String name) {
        return toolsByName.containsKey(name);
    }

    /**
     * Execute a tool by name. Returns a JSON string (success result, or an error envelope).
     * Errors are returned as JSON rather than thrown so the AI can read the failure and adapt
     * (CLAUDE.md fallback behavior).
     */
    public String execute(String name, Map<String, Object> input) {
        Tool tool = toolsByName.get(name);
        if (tool == null) {
            log.warn("tool.unknown name={}", name);
            return "{\"error\":\"unknown_tool\",\"name\":\"" + name + "\"}";
        }
        try {
            String result = tool.execute(input == null ? Map.of() : input);
            log.info("tool.executed name={} chars={}", name, result == null ? 0 : result.length());
            return result == null ? "{}" : result;
        } catch (Exception e) {
            log.error("tool.failed name={} error={}", name, e.getMessage(), e);
            return "{\"error\":\"tool_execution_failed\",\"name\":\"" + name + "\",\"message\":\""
                    + safe(e.getMessage()) + "\"}";
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ");
    }
}
