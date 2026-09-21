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
            "Prepare or calculate an explicit ordered recipe process using Minecraft's authoritative native data. "
                    + "For automatic source-to-target route discovery, call find_production_routes instead.",
            schema()
    );

    private final Executor clientExecutor;
    private final RecipeLookup resolver;
    private final ProductionPresentationCollector presentations;
    private final ProductionQuantityPlanner quantityPlanner = new ProductionQuantityPlanner();

    ShowProcessTool(Minecraft minecraft, RecipeLookup resolver, ProductionPresentationCollector presentations) {
        this(minecraft::execute, resolver, presentations);
    }

    ShowProcessTool(Executor clientExecutor, RecipeLookup resolver, ProductionPresentationCollector presentations) {
        this.clientExecutor = clientExecutor;
        this.resolver = resolver;
        this.presentations = presentations;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation) {
        ProductionQuantityRequest.ParseResult quantity = ProductionQuantityRequest.parseOptional(arguments);
        if (!quantity.valid()) {
            return completed("No production process was created: " + quantity.error() + ".");
        }

        JsonNode rawSteps = arguments.get("steps");
        boolean hasExplicitSteps = rawSteps != null && !rawSteps.isNull();
        List<RequestedStep> requestedSteps = new ArrayList<>();
        if (hasExplicitSteps) {
            if (!rawSteps.isArray() || rawSteps.size() < 2 || rawSteps.size() > MAX_STEPS) {
                return completed("No production process was created: steps must contain between 2 and "
                        + MAX_STEPS + " recipes.");
            }
            for (int index = 0; index < rawSteps.size(); index++) {
                JsonNode rawStep = rawSteps.get(index);
                String recipeId = rawStep.path("recipe_id").asText("").trim();
                if (Identifier.tryParse(recipeId) == null) {
                    return completed("No production process was created: step " + (index + 1)
                            + " has an invalid namespaced recipe_id.");
                }
                String methodValue = rawStep.path("method").asText("").trim();
                Optional<ProductionMethod> method = ProductionMethod.parse(methodValue);
                if (method.isEmpty() || !method.get().isRecipe()) {
                    return completed("No production process was created: step " + (index + 1)
                            + " has an unsupported recipe method. Use one of: " + supportedMethods() + ".");
                }
                requestedSteps.add(new RequestedStep(recipeId, method.get()));
            }
        }

        String sourceItemId = arguments.path("source_item_id").asText("").trim();
        String targetItemId = arguments.path("target_item_id").asText("").trim();
        String finalMethodValue = arguments.path("final_method").asText("").trim();
        Optional<ProductionMethod> finalMethod = ProductionMethod.parse(finalMethodValue);
        if (!finalMethodValue.isEmpty() && (finalMethod.isEmpty() || !finalMethod.get().isRecipe())) {
            return completed("No production process was created: final_method must be one of: "
                    + supportedMethods() + ".");
        }
        if (!hasExplicitSteps) {
            return completed("No production process was created: explicit steps are required. Call "
                    + "find_production_routes for automatic source-to-target planning.");
        } else if (!targetItemId.isEmpty() && Identifier.tryParse(targetItemId) == null) {
            return completed("No production process was created: target_item_id was invalid.");
        } else if (!sourceItemId.isEmpty() && Identifier.tryParse(sourceItemId) == null) {
            return completed("No production process was created: source_item_id was invalid.");
        }
        String normalizedTargetItemId = targetItemId.isEmpty()
                ? "" : Identifier.tryParse(targetItemId).toString();
        String normalizedSourceItemId = sourceItemId.isEmpty()
                ? "" : Identifier.tryParse(sourceItemId).toString();

        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        clientExecutor.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                executeExplicit(requestedSteps, normalizedSourceItemId, normalizedTargetItemId,
                        finalMethod, quantity.request(),
                        cancellation, result);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void executeExplicit(
            List<RequestedStep> requestedSteps,
            String sourceItemId,
            String targetItemId,
            Optional<ProductionMethod> finalMethod,
            Optional<ProductionQuantityRequest> quantity,
            CancellationToken cancellation,
            CompletableFuture<ToolExecutionResult> result
    ) {
        List<ProductionCardData> cards = new ArrayList<>();
        for (int index = 0; index < requestedSteps.size(); index++) {
            cancellation.throwIfCancelled();
            RequestedStep step = requestedSteps.get(index);
            RecipeLookupResult lookup = resolver.resolve(step.recipeId(), Optional.of(step.method()));
            switch (lookup) {
                case RecipeLookupResult.Found found -> cards.add(found.card());
                case RecipeLookupResult.Ambiguous ambiguous -> {
                    result.complete(ToolExecutionResult.text("Production step " + (index + 1)
                            + " is ambiguous. Call show_process again using one exact recipe_id from: "
                            + candidates(ambiguous) + "."));
                    return;
                }
                case RecipeLookupResult.Missing missing -> {
                    result.complete(ToolExecutionResult.text("No production process was created: step "
                            + (index + 1) + " could not be displayed: " + missing.reason()
                            + ". Do not claim that a process card is available."));
                    return;
                }
            }
        }
        ProductionCardData finalCard = cards.getLast();
        if (finalMethod.isPresent() && finalCard.method() != finalMethod.get()) {
            result.complete(ToolExecutionResult.text("No production process was created: the explicit final step "
                    + "does not use final_method " + finalMethod.get().toolValue() + "."));
            return;
        }
        if (!targetItemId.isEmpty()
                && !ProductionQuantityPlanner.itemId(finalCard.result().primary()).equals(targetItemId)) {
            result.complete(ToolExecutionResult.text("No production process was created: the explicit final step "
                    + "does not produce " + targetItemId + "."));
            return;
        }
        if (quantity.isPresent()) {
            publishPlan(cards, quantity.get(), optionalText(sourceItemId), cancellation, result);
            return;
        }
        presentations.setSequence(cards);
        result.complete(ToolExecutionResult.text("A native " + cards.size()
                + "-step production process is ready. Briefly tell the player to use the Show "
                + cards.size() + " Steps button."));
    }

    private void publishPlan(
            List<ProductionCardData> cards,
            ProductionQuantityRequest quantity,
            Optional<String> sourceItemId,
            CancellationToken cancellation,
            CompletableFuture<ToolExecutionResult> result
    ) {
        switch (quantityPlanner.plan(cards, quantity, sourceItemId, cancellation)) {
            case ProductionQuantityPlanner.Result.Success success -> {
                presentations.setPlanned(success.plan());
                result.complete(plannedResult(success.plan()));
            }
            case ProductionQuantityPlanner.Result.Failure failure -> result.complete(ToolExecutionResult.text(
                    "No quantity plan was created: " + failure.reason()
                            + ". Do not calculate totals yourself."
            ));
        }
    }

    private static ToolExecutionResult plannedResult(ProductionPlan plan) {
        return ToolExecutionResult.terminal(
                "The native quantity plan is ready. The mod will display its authoritative summary.",
                plan.summary()
        );
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static CompletionStage<ToolExecutionResult> completed(String message) {
        return CompletableFuture.completedFuture(ToolExecutionResult.text(message));
    }

    private static ObjectNode schema() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode step = json.objectNode();
        step.put("type", "object");
        ObjectNode stepProperties = json.objectNode();
        stepProperties.set("recipe_id", json.objectNode().put("type", "string")
                .put("description", "Exact namespaced recipe ID or output item ID"));
        stepProperties.set("method", recipeMethodSchema(json, "Production method for this step"));
        step.set("properties", stepProperties);
        step.set("required", json.arrayNode().add("recipe_id").add("method"));
        step.put("additionalProperties", false);

        ObjectNode schema = json.objectNode();
        schema.put("type", "object");
        ObjectNode properties = json.objectNode();
        properties.set("steps", json.objectNode().put("type", "array").put("minItems", 2)
                .put("maxItems", MAX_STEPS)
                .put("description", "Explicit ordered route")
                .set("items", step));
        properties.set("target_quantity", RecipeCardRequestTool.targetQuantitySchema(json));
        properties.set("source_item_id", json.objectNode().put("type", "string")
                .put("description", "Namespaced starting material used to validate and total the explicit steps"));
        properties.set("target_item_id", json.objectNode().put("type", "string")
                .put("description", "Namespaced final output used to validate the explicit steps"));
        properties.set("final_method", recipeMethodSchema(json,
                "Optional required method for the final operation, when the player named one"));
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode recipeMethodSchema(JsonNodeFactory json, String description) {
        ObjectNode method = json.objectNode().put("type", "string").put("description", description);
        var methods = json.arrayNode();
        for (ProductionMethod value : ProductionMethod.values()) {
            if (value.isRecipe()) {
                methods.add(value.toolValue());
            }
        }
        method.set("enum", methods);
        return method;
    }

    private static String supportedMethods() {
        return java.util.Arrays.stream(ProductionMethod.values()).filter(ProductionMethod::isRecipe)
                .map(ProductionMethod::toolValue).collect(Collectors.joining(", "));
    }

    private static String candidates(RecipeLookupResult.Ambiguous ambiguous) {
        String joined = ambiguous.candidates().stream().limit(8)
                .map(candidate -> candidate.recipeId() + " (" + candidate.method().toolValue() + ")")
                .collect(Collectors.joining(", "));
        return ambiguous.candidates().size() > 8 ? joined + ", and more" : joined;
    }

    private record RequestedStep(String recipeId, ProductionMethod method) {
    }
}
