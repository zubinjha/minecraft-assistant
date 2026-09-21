package dev.zubinjha.minecraftassistant.fabric;

import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;

final class ProductionGuidePublisher {
    private ProductionGuidePublisher() {
    }

    static ToolExecutionResult publishOne(
            RecipeRouteFinder.Candidate candidate,
            boolean quantityRequested,
            ProductionPresentationCollector presentations
    ) {
        if (quantityRequested) {
            presentations.setPlanned(candidate.plan());
            ProductionPresentation presentation = new ProductionPresentation.Plan(candidate.plan());
            return ToolExecutionResult.terminal(
                    "The exact native production plan is ready. Use the mod's authoritative summary.",
                    presentation.authoritativeSummary().orElseThrow()
            );
        }
        if (candidate.cards().size() == 1) {
            presentations.add(candidate.cards().getFirst());
            return ToolExecutionResult.text("The native recipe guide is ready.");
        }
        presentations.setSequence(candidate.cards());
        return ToolExecutionResult.text("The ordered native production guide is ready with "
                + candidate.cards().size() + " steps.");
    }

    static ToolExecutionResult publishSelected(
            List<ProductionRouteSearchStore.Route> selected,
            boolean quantityRequested,
            ProductionPresentationCollector presentations
    ) {
        if (!quantityRequested) {
            if (selected.size() != 1) {
                return ToolExecutionResult.text("No guide was created: choose one route for a request without "
                        + "a target quantity. You may describe other alternatives briefly in text.");
            }
            return publishOne(selected.getFirst().candidate(), false, presentations);
        }
        if (selected.size() == 1) {
            return publishOne(selected.getFirst().candidate(), true, presentations);
        }
        List<ProductionPresentation.Route> routes = new ArrayList<>(selected.size());
        for (ProductionRouteSearchStore.Route stored : selected) {
            RecipeRouteFinder.Candidate candidate = stored.candidate();
            routes.add(new ProductionPresentation.Route(
                    stored.id(),
                    candidate.plan().operations().getLast().method().displayName(),
                    candidate.plan()
            ));
        }
        presentations.setComparison(routes);
        ProductionPresentation presentation = new ProductionPresentation.Comparison(routes);
        return ToolExecutionResult.terminal(
                "The selected native route comparison is ready. Use the mod's authoritative summary.",
                presentation.authoritativeSummary().orElseThrow()
        );
    }
}
