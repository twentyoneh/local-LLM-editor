package ru.localdoc;

import ru.localdoc.config.*;
import ru.localdoc.domain.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public final class TestSupport {
    public static AppConfig config(AppConfig.VerificationMode mode,int retries) { return config(mode,retries,2048); }
    public static AppConfig config(AppConfig.VerificationMode mode,int retries,int output) {
        return new AppConfig(Path.of("config/config.properties"),model("generator",8192,output),
            mode==AppConfig.VerificationMode.BOTH?model("checker",8192,256):null,mode,retries,1,true,1,1.3,20*1024*1024,
            Duration.ofSeconds(2),Duration.ofSeconds(3),StandardCharsets.UTF_8,StandardCharsets.UTF_8,Path.of("actions"));
    }
    public static AppConfig.Model model(String name,int context,int output) { return new AppConfig.Model(URI.create("http://127.0.0.1:11434/v1"),name,context,output,0); }
    public static ActionDefinition action(String id) { return new ActionRegistry().load(Path.of("actions")).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(); }
    public static SourceDocument source(String text) { return new SourceDocument(Path.of("example.txt"),DocumentFormat.TXT,DocumentModel.fromText(text),StandardCharsets.UTF_8,"snapshot",java.util.List.of()); }
}
