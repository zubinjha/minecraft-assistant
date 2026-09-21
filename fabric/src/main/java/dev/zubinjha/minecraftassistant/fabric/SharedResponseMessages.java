package dev.zubinjha.minecraftassistant.fabric;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

final class SharedResponseMessages {
    static final int MAX_CHAT_LENGTH = 256;
    static final String PREFIX = "[Minecraft Assistant] ";
    static final String FIRST_PREFIX = "[Minecraft Assistant 1/2] ";
    static final String SECOND_PREFIX = "[Minecraft Assistant 2/2] ";
    private static final String SEPARATOR = " | ";
    private static final Pattern RECIPE_METADATA = Pattern.compile(
            "(?:^| \\| )Recipe: ([a-z0-9_.-]+:[a-z0-9_./-]+) \\(([a-z_]+)\\)$"
    );
    private static final Pattern SOURCE_METADATA = Pattern.compile("(?:^| \\| )Source: (\\S+)$");

    private SharedResponseMessages() {
    }

    static List<String> format(SharedResponse response) {
        String answer = normalize(response.answer());
        List<String> metadataParts = metadataParts(response);
        String metadata = String.join(SEPARATOR, metadataParts);
        String content = join(answer, metadata);
        if (PREFIX.length() + content.length() <= MAX_CHAT_LENGTH) {
            return List.of(PREFIX + content);
        }

        int firstCapacity = MAX_CHAT_LENGTH - FIRST_PREFIX.length();
        int secondCapacity = MAX_CHAT_LENGTH - SECOND_PREFIX.length();
        metadata = metadataThatFits(metadataParts, secondCapacity);
        int metadataCost = metadata.isEmpty() ? 0 : metadata.length() + SEPARATOR.length();
        int answerCapacity = firstCapacity + Math.max(0, secondCapacity - metadataCost);
        String limitedAnswer = ellipsize(answer, answerCapacity);
        int secondAnswerCapacity = Math.max(0, secondCapacity - metadataCost);

        if (limitedAnswer.length() <= firstCapacity) {
            if (metadata.isEmpty()) {
                return List.of(PREFIX + limitedAnswer);
            }
            return checked(List.of(
                    FIRST_PREFIX + limitedAnswer,
                    SECOND_PREFIX + metadata
            ));
        }

        int minimumFirstLength = Math.max(1, limitedAnswer.length() - secondAnswerCapacity);
        int split = splitPoint(limitedAnswer, minimumFirstLength, firstCapacity);
        String first = limitedAnswer.substring(0, split).stripTrailing();
        String secondAnswer = limitedAnswer.substring(split).stripLeading();
        String second = join(secondAnswer, metadata);
        return checked(List.of(FIRST_PREFIX + first, SECOND_PREFIX + second));
    }

    static Optional<ParsedMetadata> parse(String signedContent) {
        String content;
        if (signedContent.startsWith(PREFIX)) {
            content = signedContent.substring(PREFIX.length());
        } else if (signedContent.startsWith(SECOND_PREFIX)) {
            content = signedContent.substring(SECOND_PREFIX.length());
        } else {
            return Optional.empty();
        }

        Optional<SharedResponse.Recipe> recipe = Optional.empty();
        Matcher recipeMatcher = RECIPE_METADATA.matcher(content);
        if (recipeMatcher.find()) {
            Optional<ProductionMethod> method = ProductionMethod.parse(recipeMatcher.group(2));
            if (Identifier.tryParse(recipeMatcher.group(1)) != null
                    && method.filter(ProductionMethod::isRecipe).isPresent()) {
                recipe = Optional.of(new SharedResponse.Recipe(recipeMatcher.group(1), method.orElseThrow()));
                content = content.substring(0, recipeMatcher.start()).stripTrailing();
            }
        }

        Optional<URI> source = Optional.empty();
        Matcher sourceMatcher = SOURCE_METADATA.matcher(content);
        if (sourceMatcher.find()) {
            source = SharedResponse.sourceFrom("Source: " + sourceMatcher.group(1));
        }
        return source.isEmpty() && recipe.isEmpty()
                ? Optional.empty()
                : Optional.of(new ParsedMetadata(source, recipe));
    }

    private static List<String> metadataParts(SharedResponse response) {
        List<String> parts = new ArrayList<>(2);
        response.source().ifPresent(source -> parts.add("Source: " + source));
        response.recipe().ifPresent(recipe -> parts.add(recipe.metadata()));
        return List.copyOf(parts);
    }

    private static String metadataThatFits(List<String> parts, int capacity) {
        List<String> accepted = new ArrayList<>(parts.size());
        for (String part : parts) {
            String candidate = String.join(SEPARATOR, accepted.isEmpty()
                    ? List.of(part)
                    : append(accepted, part));
            if (candidate.length() <= capacity) {
                accepted.add(part);
            }
        }
        return String.join(SEPARATOR, accepted);
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String join(String answer, String metadata) {
        if (answer.isEmpty()) {
            return metadata;
        }
        return metadata.isEmpty() ? answer : answer + SEPARATOR + metadata;
    }

    private static String ellipsize(String value, int maximumLength) {
        if (value.length() <= maximumLength) {
            return value;
        }
        if (maximumLength <= 1) {
            return "…".substring(0, Math.max(0, maximumLength));
        }
        int limit = safeBoundary(value, maximumLength - 1);
        int wordBoundary = lastWhitespace(value, limit);
        int end = wordBoundary >= Math.max(1, limit / 2) ? wordBoundary : limit;
        return value.substring(0, safeBoundary(value, end)).stripTrailing() + "…";
    }

    private static int splitPoint(String value, int minimum, int maximum) {
        int safeMaximum = safeBoundary(value, Math.min(maximum, value.length()));
        for (int index = safeMaximum; index >= minimum; index--) {
            if (Character.isWhitespace(value.charAt(index - 1))) {
                return index - 1;
            }
        }
        return safeMaximum;
    }

    private static int lastWhitespace(String value, int before) {
        for (int index = before; index > 0; index--) {
            if (Character.isWhitespace(value.charAt(index - 1))) {
                return index - 1;
            }
        }
        return -1;
    }

    private static int safeBoundary(String value, int boundary) {
        int result = Math.max(0, Math.min(boundary, value.length()));
        if (result > 0 && result < value.length()
                && Character.isHighSurrogate(value.charAt(result - 1))
                && Character.isLowSurrogate(value.charAt(result))) {
            return result - 1;
        }
        return result;
    }

    private static List<String> checked(List<String> messages) {
        if (messages.stream().anyMatch(message -> message.length() > MAX_CHAT_LENGTH)) {
            throw new IllegalStateException("shared response exceeded Minecraft's chat limit");
        }
        return List.copyOf(messages);
    }

    record ParsedMetadata(
            Optional<URI> source,
            Optional<SharedResponse.Recipe> recipe
    ) {
    }
}
