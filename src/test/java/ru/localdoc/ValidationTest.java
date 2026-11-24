package ru.localdoc;

import org.junit.jupiter.api.Test;
import ru.localdoc.domain.*;
import ru.localdoc.llm.ChatTransport;
import ru.localdoc.processing.WorkUnit;
import ru.localdoc.validation.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    FormValidator validator=new FormValidator();
    ValidationResult check(String action,String source,String candidate,String finish) {
        return validator.validate(TestSupport.action(action),new WorkUnit(0,0,source.length(),source,source),new ChatTransport.Candidate(candidate,finish),new CancellationToken());
    }
    @Test void rejectsServiceCommentInsteadOfSource() {
        assertEquals("META_ONLY_RESPONSE",check("correct_errors","В этом тексте есть ашибка.","Текст не содержит орфографических и пунктуационных ошибок.","stop").code());
    }
    @Test void unchangedCorrectTextAndLiteralCommentInSourceAreAccepted() {
        assertEquals(ValidationResult.Status.ACCEPT,check("correct_errors","Текст верен.","Текст верен.","stop").status());
        String text="Текст не содержит орфографических и пунктуационных ошибок.";
        assertEquals(ValidationResult.Status.ACCEPT,check("correct_errors",text,text,"stop").status());
        assertEquals(ValidationResult.Status.ACCEPT,check("correct_errors","В документе есть фраза: «Текст не содержит ошибок».","В документе есть фраза: «Текст не содержит ошибок».","stop").status());
    }
    @Test void refusesEmptyTruncatedAndWrappedReplies() {
        assertEquals("EMPTY_RESPONSE",check("correct_errors","Исходник"," \n ","stop").code());
        assertEquals("TRUNCATED_RESPONSE",check("correct_errors","Исходник","Начало ответа","length").code());
        assertEquals("RESPONSE_WRAPPER",check("correct_errors","Текст","Вот исправленный текст: Текст","stop").code());
        String markdown="```\nкод\n```";
        assertEquals(ValidationResult.Status.ACCEPT,check("correct_errors",markdown,markdown,"stop").status());
    }
    @Test void largeLengthChangeIsWarningNotProofOfFailure() {
        var result=check("simplify","Сложное предложение. ".repeat(20),"Кратко.","stop");
        assertEquals(ValidationResult.Status.ACCEPT,result.status());assertFalse(result.warnings().isEmpty());
        assertTrue(check("correct_errors","Да.","Верно.","stop").warnings().isEmpty());
    }
    @Test void continuationMustNotEchoItsContext() {
        assertEquals("SOURCE_ECHO",check("continue","Конец документа.","Конец документа.","stop").code());
        assertEquals(ValidationResult.Status.ACCEPT,check("continue","Конец документа.","Затем началась новая история.","stop").status());
    }
    @Test void rejectsJsonEnvelopeButAllowsOriginalJsonDocument() {
        String json="{\"targetText\":\"Исправленный текст\",\"action\":\"Исправление ошибок\"}";
        assertEquals("RESPONSE_WRAPPER",check("correct_errors","Исходный текст",json,"stop").code());
        assertEquals("RESPONSE_WRAPPER",check("correct_errors","```\nКод\n```",json,"stop").code());
        assertEquals(ValidationResult.Status.ACCEPT,check("correct_errors",json,json,"stop").status());
    }
}
