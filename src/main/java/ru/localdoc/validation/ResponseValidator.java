package ru.localdoc.validation;

import ru.localdoc.config.ActionDefinition;
import ru.localdoc.domain.CancellationToken;
import ru.localdoc.llm.ChatTransport.Candidate;
import ru.localdoc.processing.WorkUnit;

public interface ResponseValidator {
    ValidationResult validate(ActionDefinition action, WorkUnit unit, Candidate candidate, CancellationToken token);
}
