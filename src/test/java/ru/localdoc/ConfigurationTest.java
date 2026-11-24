package ru.localdoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.localdoc.config.*;
import ru.localdoc.domain.AppException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path dir;
    Path config(String change,String replacement) throws Exception {
        // Tests must not depend on GUI formatting or the user's model settings.
        String text="""
            generation.apiType=OPENAI_COMPATIBLE
            generation.baseUrl=http://localhost:11434/v1
            generation.model=qwen2.5:3b
            generation.contextWindowTokens=8192
            generation.maxOutputTokens=2048
            generation.temperature=0.0
            verification.mode=BOTH
            verification.apiType=OPENAI_COMPATIBLE
            verification.baseUrl=http://localhost:11434/v1
            verification.model=gemma3:4b
            verification.contextWindowTokens=8192
            verification.maxOutputTokens=256
            verification.temperature=0.0
            verification.protocolRetries=1
            processing.maxRegenerationRetries=3
            processing.enableChunking=true
            processing.contextParagraphs=1
            processing.tokenSafetyFactor=1.3
            processing.maxFileSizeMb=20
            http.connectTimeoutSeconds=10
            http.requestTimeoutSeconds=120
            http.maxTransportRetries=0
            documents.defaultTxtEncoding=UTF-8
            documents.outputTxtEncoding=UTF-8
            documents.defaultExportFormat=SOURCE
            actions.directory=%s
            """.formatted(Path.of("actions").toAbsolutePath().toString().replace("\\","\\\\"));
        assertTrue(text.contains(change),"Unknown configuration test mutation");
        Path file=dir.resolve("config.properties");Files.writeString(file,text.replace(change,replacement));return file;
    }
    @Test void readsExternalUtf8ConfigurationAndNormalizesBaseUrl() throws Exception {
        var config=AppConfig.load(config("generation.model=qwen2.5:3b","generation.model=модель\ngeneration.baseUrl=http://localhost:11434/v1///"));
        assertEquals("модель",config.generator().name());assertEquals("http://localhost:11434/v1",config.generator().baseUrl().toString());assertEquals(3,config.maxRegenerationRetries());
    }
    @Test void refusesNegativeRetriesUnknownModeAndInvalidUrl() throws Exception {
        for (String[] pair:new String[][]{{"processing.maxRegenerationRetries=3","processing.maxRegenerationRetries=-1"},{"verification.mode=BOTH","verification.mode=SKIP"},{"generation.baseUrl=http://localhost:11434/v1","generation.baseUrl=file:///tmp/model"},{"http.maxTransportRetries=0","http.maxTransportRetries=1"}})
            assertEquals("CONFIG",assertThrows(AppException.class,() -> AppConfig.load(config(pair[0],pair[1]))).code());
    }
    @Test void newActionAppearsWithoutChangingCodeAndDuplicateIdIsRejected() throws Exception {
        String action=Files.readString(Path.of("actions/correct_errors.json")).replace("correct_errors","business_style").replace("Исправление ошибок","Деловой стиль");
        Files.writeString(dir.resolve("business.json"),action);
        assertEquals("business_style",new ActionRegistry().load(dir).getFirst().id());
        Files.writeString(dir.resolve("duplicate.json"),action);
        assertEquals("ACTION_CONFIG",assertThrows(AppException.class,() -> new ActionRegistry().load(dir)).code());
    }
    @Test void unknownRuleAndMalformedJsonAreRejected() throws Exception {
        String source=Files.readString(Path.of("actions/correct_errors.json"));
        Files.writeString(dir.resolve("action.json"),source.replace("NON_EMPTY","MAGIC_RULE"));
        assertThrows(AppException.class,() -> new ActionRegistry().load(dir));
        Files.writeString(dir.resolve("action.json"),source+" trailing");assertThrows(AppException.class,() -> new ActionRegistry().load(dir));
    }
}
