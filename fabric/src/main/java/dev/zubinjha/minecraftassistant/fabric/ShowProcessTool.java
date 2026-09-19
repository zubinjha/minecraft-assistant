package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class ShowProcessTool implements Tool {
    static final int MAX_STEPS = 6;
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "show_process",
            "Prepare an ordered multi-step production process using Minecraft's authoritative recipe data.",
            schema()
    );

    private final Executor clientExecutor;
    private final RecipeLookup resolver;
    private final ProductionPresentationCollector presentations;

    ShowProcessTool(
            Minecraft minecraft,
            RecipeLookup resolver,
            ProductionPresentationCollector presentations
    ) {
        this(minecraft::execute, resolver, presentations);
    }

    ShowProcessTool(
            Executor clientExecutor,
            RecipeLookup resolver,
            ProductionPresentationCollector presentations
    ) {
        this.clientExecutor = clientExecutor;
        this.resolver = resolver;
        this.presentations = presentations;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            JsonNode arguments,
            CancellationToken cancellation
    ) {
        JsonNode rawSteps = arguments.path("steps");
        if (!rawSteps.isArray() || rawSteps.size() < 2 || rawSteps.size() > MAX_STEPS) {
            return completed("No production process was created: steps must contain between 2 and "
                    + MAX_STEPS + " recipes.");
        }

        List<RequestedStep> requestedSteps = new ArrayList<>();
        for (int index = 0; index < rawSteps.size(); index++) {
            JsonNode rawStep = rawSteps.get(index);
            String recipeId = rawStep.path("recipe_id").asText("").trim();
            if (Identifier.tryParse(recipeId) == null) {
                return completed("No production process was created: step " + (index + 1)
                        + " has an invalid namespaced recipe_id.");
            }
            String methodValue = rawStep.path("method").asText("").trim();
            Optional<ProductionMethod> method = ProductionMethod.parse(methodValue);
            if (method.isEmpty()) {
                return completed("No production process was created: step " + (index + 1)
                        + " has an unsupported method. Use one of: " + supportedMethods() + ".");
            }
            if (!method.get().isRecipe()) {
                return completed("No production process was created: step " + (index + 1)
                        + " must use a recipe method. Use the matching workstation guide tool instead.");
            }
            requestedSteps.add(new RequestedStep(recipeId, method.get()));
        }

        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        clientExecutor.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                List<ProductionCardData> cards = new ArrayList<>();
                for (int index = 0; index < requestedSteps.size(); index++) {
                    RequestedStep step = requestedSteps.get(index);
                    RecipeLookupResult lookup = resolver.resolve(step.recipeId(), Optional.of(step.method()));
                    switch (lookup) {
                        case RecipeLookupResult.Found found -> cards.add(found.card());
                        case RecipeLookupResult.Ambiguous ambiguous -> {
                            result.complete(ToolExecutionResult.text(
                                    "Production step " + (index + 1) + " is ambiguous. Call show_process "
                                            + "again using one exact recipe_id from: "
                                            + candidates(ambiguous) + "."
                            ));
                            return;
                        }
                        case RecipeLookupResult.Missing missing -> {
                            result.complete(ToolExecutionResult.text(
                                    "No production process was created: step " + (index + 1)
                                            + " could not be displayed: " + missing.reason()
                                            + ". Do not claim that a process card is available."
                            ));
                            return;
                        }
                    }
                }
                presentations.setSequence(cards);
                result.complete(ToolExecutionResult.text(
                        "A native " + cards.size() + "-step production process is ready. "
                                + "Briefly tell the player to use the Show " + cards.size() + " Steps button."
                ));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static CompletionStage<ToolExecutionResult> completed(String message) {
        return CompletableFuture.completedFuture(ToolExecutionResult.text(message));
    }

    private static ObjectNode schema() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode step = json.objectNode();
        step.put("type", "object");
        ObjectNode stepProperties = json.objectNode();
        stepProperties.set("recipe_id", json.objectNode()
                .put("type", "string")
                .put("description", "Exact namespaced recipe ID or output item ID"));
        ObjectNode method = json.objectNode()
                .put("type", "string")
                .put("description", "Production method for this step");
        var methods = json.arrayNode();
        for (ProductionMethod value : ProductionMethod.values()) {
            if (value.isRecipe()) {
                methods.add(value.toolValue());
            }
        }
        method.set("enum", methods);
        stepProperties.set("method", method);
        step.set("properties", stepProperties);
        step.set("required", json.arrayNode().add("recipe_id").add("method"));
        step.put("additionalProperties", false);

        ObjectNode schema = json.objectNode();
        schema.put("type", "object");
        ObjectNode properties = json.objectNode();
        properties.set("steps", json.objectNode()
                .put("type", "array")
                .put("minItems", 2)
                .put("maxItems", MAX_STEPS)
                .set("items", step));
        schema.set("properties", properties);
        schema.set("required", json.arrayNode().add("steps"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String supportedMethods() {
        return java.util.Arrays.stream(ProductionMethod.values())
                .filter(ProductionMethod::isRecipe)
                .map(ProductionMethod::toolValue)
                .collect(Collectors.joining(", "));
    }

    private static String candidates(RecipeLookupResult.Ambiguous ambiguous) {
        String joined = ambiguous.candidates().stream()
                .limit(8)
                .map(candidate -> candidate.recipeId() + " (" + candidate.method().toolValue() + ")")
                .collect(Collectors.joining(", "));
        return ambiguous.candidates().size() > 8 ? joined + ", and more" : joined;
    }

    private record RequestedStep(String recipeId, ProductionMethod method) {
    }
}
