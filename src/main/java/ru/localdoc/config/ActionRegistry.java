package ru.localdoc.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.localdoc.domain.AppException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ActionRegistry {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public List<ActionDefinition> load(Path directory) {
        List<ActionDefinition> actions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    ActionDefinition a = mapper.readValue(path.toFile(), ActionDefinition.class);
                    validate(a);
                    if (!ids.add(a.id())) throw new IllegalArgumentException("Повторяется id=" + a.id());
                    actions.add(a);
                } catch (IOException | IllegalArgumentException ex) {
                    throw new AppException("ACTION_CONFIG", "Ошибка действия " + path.getFileName() + ": " + ex.getMessage(), ex);
                }
            }
        } catch (IOException ex) { throw new AppException("ACTION_CONFIG", "Не удалось прочитать каталог действий: " + directory, ex); }
        if (actions.isEmpty()) throw new AppException("ACTION_CONFIG", "В каталоге нет действий: " + directory);
        actions.sort(Comparator.comparing(a -> switch (a.id()) { case "correct_errors" -> "0"; case "simplify" -> "1"; case "continue" -> "2"; default -> "3" + a.label(); }));
        return List.copyOf(actions);
    }
    private static void validate(ActionDefinition a) {
        if (a.schemaVersion() != 1) throw new IllegalArgumentException("schemaVersion должен быть 1");
        if (a.id() == null || !a.id().matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Некорректный id");
        if (a.label() == null || a.label().isBlank() || a.mode() == null || a.generationPrompt() == null || a.generationPrompt().isBlank() || a.verificationPrompt() == null || a.verificationPrompt().isBlank())
            throw new IllegalArgumentException("Нужны label, mode, generationPrompt и verificationPrompt");
        for (var rule : a.formRules()) {
            if (rule.type() == null) throw new IllegalArgumentException("Не задан тип правила");
            if (rule.type() == ActionDefinition.RuleType.LENGTH_RATIO && (rule.min() == null || rule.max() == null || !Double.isFinite(rule.min()) || !Double.isFinite(rule.max()) || rule.min() < 0 || rule.max() < rule.min() || rule.minSourceChars() == null || rule.minSourceChars() < 1 || !"WARNING".equals(rule.severity())))
                throw new IllegalArgumentException("LENGTH_RATIO: нужны min ≤ max, minSourceChars > 0 и severity=WARNING");
            if (rule.type() == ActionDefinition.RuleType.MAX_CHARS && (rule.value() == null || rule.value() < 1))
                throw new IllegalArgumentException("MAX_CHARS: value должен быть > 0");
        }
    }
}
