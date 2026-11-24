package ru.localdoc.validation;

import com.fasterxml.jackson.databind.*;
import ru.localdoc.config.*;
import ru.localdoc.domain.*;
import ru.localdoc.llm.ChatTransport;
import ru.localdoc.processing.*;
import java.io.IOException;
import java.util.List;

public final class LlmValidator {
    private final ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final AppConfig config; private final ChatTransport transport;
    public LlmValidator(AppConfig config,ChatTransport transport) { this.config=config;this.transport=transport; }
    public ValidationResult validate(ActionDefinition action,WorkUnit unit,ChatTransport.Candidate candidate,List<String> warnings,CancellationToken token) {
        return validate(action,unit,candidate,warnings,token,null);
    }
    public ValidationResult validate(ActionDefinition action,WorkUnit unit,ChatTransport.Candidate candidate,List<String> warnings,CancellationToken token,RevisionContext revision) {
        String failure="";
        String system=PromptFactory.verificationSystem(action,revision);
        for (int i=0;i<=config.checkerProtocolRetries();i++) {
            token.check();
            String user=PromptFactory.verification(action,unit,candidate.text(),warnings,i>0,revision);
            if (ProcessingPlanner.estimate(system+user,config)+config.checker().outputTokens()>config.checker().contextTokens()) throw ProcessingPlanner.contextError();
            var answer=transport.completeJson(config.checker(),system,user,token);
            token.check();
            if ("length".equals(answer.finishReason()) || "max_tokens".equals(answer.finishReason())) {
                failure="Ответ обрезан лимитом токенов. Увеличьте verification.maxOutputTokens в конфигурации.";
                continue;
            }
            try {
                JsonNode json=mapper.readTree(answer.text());
                if (json!=null && json.isObject() && json.path("valid").isBoolean() && json.path("reason").isTextual()
                    && json.size()==2) {
                    String reason=PromptFactory.shortText(json.path("reason").asText(),512);
                    return json.path("valid").asBoolean() ? ValidationResult.accept(warnings) : ValidationResult.reject("LLM_REJECT",reason.isBlank()?"Проверяющая модель отклонила ответ.":reason);
                }
                failure="Ожидался объект с двумя полями: valid типа boolean и reason типа string.";
            } catch (IOException ignored) { failure="Ответ модели не является корректным JSON."; }
        }
        return ValidationResult.error("CHECKER_PROTOCOL_ERROR","Проверяющая модель не вернула корректный JSON-вердикт после разрешённых попыток. "+failure);
    }
}
