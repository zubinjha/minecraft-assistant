package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record BenchmarkSuite(int version, String name, List<Model> models, List<Case> cases) {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Path DEFAULT_PATH = locateDefaultSuite();

    BenchmarkSuite {
        models = List.copyOf(models);
        cases = List.copyOf(cases);
    }

    static BenchmarkSuite load(Path path) throws IOException {
        BenchmarkSuite suite = JSON.readValue(path.toFile(), BenchmarkSuite.class);
        suite.validate();
        return suite;
    }

    void validate() {
        if (version != 1 || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Benchmark suite must have version 1 and a name");
        }
        if (cases.size() != 20) {
            throw new IllegalArgumentException("Benchmark suite must contain exactly 20 cases");
        }
        Set<String> caseIds = new HashSet<>();
        for (Case scenario : cases) {
            if (!caseIds.add(requireText(scenario.id, "case id"))) {
                throw new IllegalArgumentException("Duplicate benchmark case: " + scenario.id);
            }
            requireText(scenario.category, "category");
            requireText(scenario.question, "question");
            requireText(scenario.fixture, "fixture");
            requireText(scenario.criteria, "criteria");
        }
        if (models.isEmpty()) {
            throw new IllegalArgumentException("Benchmark suite must contain models");
        }
        Set<String> modelVariants = new HashSet<>();
        for (Model model : models) {
            requireText(model.id, "model id");
            if (!modelVariants.add(model.key())) {
                throw new IllegalArgumentException("Duplicate benchmark model variant: " + model.key());
            }
            if (!Set.of("low", "high").contains(model.reasoningEffort)) {
                throw new IllegalArgumentException("Benchmark reasoning must be low or high");
            }
        }
    }

    Case caseById(String id) {
        return cases.stream().filter(value -> value.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown benchmark question: " + id));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Path locateDefaultSuite() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("benchmarks").resolve("suite-v1.json");
            if (candidate.toFile().isFile()) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return Path.of("benchmarks", "suite-v1.json");
    }

    record Model(String id, @JsonProperty("reasoning_effort") String reasoningEffort) {
        String key() {
            return id + "@" + reasoningEffort;
        }
    }

    record Case(
            String id,
            String category,
            String question,
            String fixture,
            @JsonProperty("expected_tools")
            List<String> expectedTools,
            String criteria,
            List<History> history
    ) {
        Case {
            expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
            history = history == null ? List.of() : List.copyOf(history);
        }

        List<ConversationMessage> conversationHistory() {
            return history.stream().map(History::toMessage).toList();
        }
    }

    record History(String role, String text) {
        ConversationMessage toMessage() {
            return switch (role.toLowerCase(Locale.ROOT)) {
                case "user" -> new ConversationMessage.User(text);
                case "assistant" -> new ConversationMessage.Assistant(text, List.of());
                default -> throw new IllegalArgumentException("Unsupported history role: " + role);
            };
        }
    }
}
