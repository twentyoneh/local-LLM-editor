package ru.localdoc.processing;

import ru.localdoc.domain.AppException;
import java.util.Objects;

/** The previously generated text and the user's instructions for one revision job. */
public record RevisionContext(String previousResult,String userFeedback) {
    public RevisionContext {
        Objects.requireNonNull(previousResult);
        Objects.requireNonNull(userFeedback);
        userFeedback=userFeedback.strip();
        if (previousResult.isBlank() || userFeedback.isBlank())
            throw new AppException("REVISION_INPUT","Для доработки нужны предыдущий результат и непустое замечание.");
    }
}
