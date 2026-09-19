package dev.zubinjha.minecraftassistant.mediawiki;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record WikiPage(String title, String sourceUrl, String historyUrl, String lead, String text, List<Section> sections) {
    private static final Pattern HEADING = Pattern.compile("(?m)^(={2,6})\\s*(.*?)\\s*\\1\\s*$");

    WikiPage {
        sections = List.copyOf(sections);
    }

    static WikiPage parse(String title, String sourceUrl, String extract) {
        String normalized = normalize(extract);
        Matcher matcher = HEADING.matcher(normalized);
        List<Marker> markers = new ArrayList<>();
        while (matcher.find()) {
            markers.add(new Marker(
                    markers.size() + 1,
                    matcher.group(1).length(),
                    matcher.group(2).trim(),
                    matcher.start(),
                    matcher.end()
            ));
        }
        String lead = markers.isEmpty() ? normalized : normalized.substring(0, markers.get(0).start()).trim();
        List<Section> sections = new ArrayList<>();
        for (int index = 0; index < markers.size(); index++) {
            Marker current = markers.get(index);
            int end = normalized.length();
            for (int later = index + 1; later < markers.size(); later++) {
                if (markers.get(later).level() <= current.level()) {
                    end = markers.get(later).start();
                    break;
                }
            }
            sections.add(new Section(
                    current.index(),
                    current.title(),
                    current.level(),
                    normalized.substring(current.contentStart(), end).trim()
            ));
        }
        String historyUrl = sourceUrl.isBlank() ? "" : sourceUrl + (sourceUrl.contains("?") ? "&" : "?")
                + "action=history";
        return new WikiPage(title, sourceUrl, historyUrl, lead, normalized, sections);
    }

    Section section(int index) {
        if (index == 0) {
            return new Section(0, "Lead", 1, lead);
        }
        return sections.stream()
                .filter(section -> section.index() == index)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("section " + index + " was not found"));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    record Section(int index, String title, int level, String content) {
    }

    private record Marker(int index, int level, String title, int start, int contentStart) {
    }
}
