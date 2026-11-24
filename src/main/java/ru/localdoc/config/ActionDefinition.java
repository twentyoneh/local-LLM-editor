package ru.localdoc.config;

import java.util.List;

public record ActionDefinition(int schemaVersion, String id, String label, Mode mode,
                               String generationPrompt, String verificationPrompt, List<FormRule> formRules) {
    public enum Mode { REPLACE, APPEND }
    public enum RuleType { NON_EMPTY, NOT_TRUNCATED, NO_RESPONSE_WRAPPER, NO_META_ONLY_RESPONSE, LENGTH_RATIO, NOT_EXACT_SOURCE_COPY, MAX_CHARS }
    public record FormRule(RuleType type, Boolean unlessPresentInSource, Boolean compareWithSource,
                           Double min, Double max, Integer minSourceChars, String severity, Integer value) { }
    public ActionDefinition { formRules = List.copyOf(formRules); }
    @Override public String toString() { return label; }
}
