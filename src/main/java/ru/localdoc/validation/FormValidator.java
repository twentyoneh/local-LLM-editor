package ru.localdoc.validation;

import ru.localdoc.config.ActionDefinition;
import ru.localdoc.domain.CancellationToken;
import ru.localdoc.llm.ChatTransport.Candidate;
import ru.localdoc.processing.WorkUnit;
import java.util.*;

public final class FormValidator implements ResponseValidator {
    @Override public ValidationResult validate(ActionDefinition action,WorkUnit unit,Candidate candidate,CancellationToken token) {
        token.check();
        String answer=candidate.text().strip(); String source=unit.target().strip();
        if (answer.isEmpty()) return ValidationResult.reject("EMPTY_RESPONSE","Получен пустой ответ вместо текста.");
        if ("length".equals(candidate.finishReason()) || "max_tokens".equals(candidate.finishReason())) return ValidationResult.reject("TRUNCATED_RESPONSE","Ответ оборвался из-за лимита генерации.");
        List<String> warnings=new ArrayList<>();
        for (var rule:action.formRules()) {
            switch (rule.type()) {
                case NON_EMPTY, NOT_TRUNCATED -> { }
                case NO_META_ONLY_RESPONSE -> {
                    if (!normalize(answer).equals(normalize(source)) && answer.length()<600 && isMeta(answer))
                        return ValidationResult.reject("META_ONLY_RESPONSE","Вместо обработанного текста получен служебный комментарий.");
                }
                case NO_RESPONSE_WRAPPER -> {
                    String original=action.mode()==ActionDefinition.Mode.APPEND?unit.context().strip():source;
                    int answerType=wrapperType(answer);
                    if (answerType!=0 && !(Boolean.TRUE.equals(rule.unlessPresentInSource()) && wrapperType(original)==answerType))
                        return ValidationResult.reject("RESPONSE_WRAPPER","Нужен только результат действия, без вводных фраз и внешних блоков кода.");
                }
                case LENGTH_RATIO -> {
                    int sourceLength=source.codePointCount(0,source.length());
                    if (sourceLength>=rule.minSourceChars()) {
                        double ratio=answer.codePointCount(0,answer.length())/(double)sourceLength;
                        if (ratio<rule.min() || ratio>rule.max()) warnings.add("SUSPICIOUS_LENGTH: отношение длины ответа к источнику = " + String.format(Locale.ROOT,"%.2f",ratio));
                    }
                }
                case NOT_EXACT_SOURCE_COPY -> {
                    String comparison=unit.context().isBlank()?source:unit.context();
                    if (normalize(answer).equals(normalize(comparison))) return ValidationResult.reject("SOURCE_ECHO","Вместо продолжения повторён исходный текст.");
                }
                case MAX_CHARS -> {
                    if (answer.codePointCount(0,answer.length())>rule.value()) return ValidationResult.reject("MAX_CHARS","Ответ превышает установленный размер.");
                }
            }
        }
        return ValidationResult.accept(warnings);
    }
    private static String normalize(String text) { return text.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
    private static boolean isMeta(String text) {
        return normalize(text).matches("(?s)(?:исходный )?текст (?:не содержит (?:орфографических |пунктуационных |грамматических |и )*ошибок|не требует исправлени[йя]|написан (?:верно|правильно|грамотно))[.! ]*")
            || normalize(text).matches("(?s)(?:ошибок (?:не найдено|не обнаружено)|ошибки отсутствуют|исправления не требуются)[.! ]*");
    }
    private static int wrapperType(String text) {
        String lower=normalize(text);
        if (text.startsWith("```") && text.endsWith("```")) return 1;
        if (lower.matches("(?s)(?:вот |ниже )?(?:исправленный|упрощённый|упрощенный|обработанный) текст\\s*:.*")) return 2;
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                var parsed=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
                if (parsed!=null && parsed.isContainerNode()) return 3;
            } catch (java.io.IOException ignored) { }
        }
        return 0;
    }
}
