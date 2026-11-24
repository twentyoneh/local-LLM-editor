package ru.localdoc.llm;

import ru.localdoc.config.AppConfig;
import ru.localdoc.domain.CancellationToken;

public interface ChatTransport extends AutoCloseable {
    record Candidate(String text, String finishReason) { }
    Candidate complete(AppConfig.Model model, String system, String user, CancellationToken token);
    /** Request JSON output; the caller must still validate its schema. */
    default Candidate completeJson(AppConfig.Model model, String system, String user, CancellationToken token) {
        return complete(model, system, user, token);
    }
    @Override default void close() { }
}
