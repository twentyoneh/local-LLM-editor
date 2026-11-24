package ru.localdoc.config;

import ru.localdoc.domain.AppException;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

public record AppConfig(Path file, Model generator, Model checker, VerificationMode verificationMode,
                        int maxRegenerationRetries, int checkerProtocolRetries, boolean chunking,
                        int contextParagraphs, double tokenSafetyFactor, long maxFileBytes,
                        Duration connectTimeout, Duration requestTimeout, Charset inputEncoding,
                        Charset outputEncoding, Path actionsDirectory) {
    public enum VerificationMode { BOTH, FORM_ONLY }
    public record Model(URI baseUrl, String name, int contextTokens, int outputTokens, double temperature) { }

    public static AppConfig load(Path path) {
        Path file = path.toAbsolutePath().normalize();
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(in); }
        catch (IOException ex) { throw new AppException("CONFIG", "Не удалось прочитать конфигурацию: " + file, ex); }
        try {
            VerificationMode mode = VerificationMode.valueOf(required(p, "verification.mode"));
            Model generator = model(p, "generation");
            Model checker = mode == VerificationMode.BOTH ? model(p, "verification") : null;
            if (checker != null && checker.baseUrl().equals(generator.baseUrl()) && checker.name().equals(generator.name()))
                throw new IllegalArgumentException("В режиме BOTH выберите другую проверяющую модель или отдельный сервер.");
            if (!required(p, "generation.apiType").equals("OPENAI_COMPATIBLE") ||
                (checker != null && !required(p, "verification.apiType").equals("OPENAI_COMPATIBLE")))
                throw new IllegalArgumentException("Поддерживается apiType=OPENAI_COMPATIBLE.");
            if (integer(p, "http.maxTransportRetries", 0, 0) != 0) throw new IllegalArgumentException("Сетевые повторы отключены.");
            if (!p.getProperty("documents.defaultExportFormat", "SOURCE").equals("SOURCE"))
                throw new IllegalArgumentException("documents.defaultExportFormat должен быть SOURCE.");
            Path actions = file.getParent().resolve(required(p, "actions.directory")).normalize();
            if (!Files.isDirectory(actions)) throw new IllegalArgumentException("Не найден каталог действий: " + actions);
            return new AppConfig(file, generator, checker, mode,
                integer(p, "processing.maxRegenerationRetries", 0, 20), integer(p, "verification.protocolRetries", 0, 3),
                bool(p, "processing.enableChunking"), integer(p, "processing.contextParagraphs", 0, 5),
                decimal(p, "processing.tokenSafetyFactor", 1, 4),
                integer(p, "processing.maxFileSizeMb", 1, 100) * 1024L * 1024,
                Duration.ofSeconds(integer(p, "http.connectTimeoutSeconds", 1, 120)),
                Duration.ofSeconds(integer(p, "http.requestTimeoutSeconds", 1, 3600)),
                Charset.forName(required(p, "documents.defaultTxtEncoding")),
                Charset.forName(required(p, "documents.outputTxtEncoding")), actions);
        } catch (IllegalArgumentException ex) { throw new AppException("CONFIG", "Ошибка конфигурации " + file + ": " + ex.getMessage(), ex); }
    }
    private static Model model(Properties p, String prefix) {
        URI url = URI.create(required(p, prefix + ".baseUrl").replaceAll("/+$", ""));
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null)
            throw new IllegalArgumentException(prefix + ".baseUrl: ожидается HTTP/HTTPS URL без параметров и встроенного пароля.");
        int context = integer(p, prefix + ".contextWindowTokens", 1024, 2_000_000);
        int output = integer(p, prefix + ".maxOutputTokens", 1, context - 1);
        return new Model(url, required(p, prefix + ".model"), context, output, decimal(p, prefix + ".temperature", 0, 2));
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Не задано поле " + key);
        return value.trim();
    }
    private static int integer(Properties p, String key, int min, int max) {
        try {
            int n = Integer.parseInt(required(p, key));
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException(key + ": ожидается целое от " + min + " до " + max); }
    }
    private static double decimal(Properties p, String key, double min, double max) {
        try {
            double n = Double.parseDouble(required(p, key));
            if (!Double.isFinite(n) || n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException(key + ": ожидается число от " + min + " до " + max); }
    }
    private static boolean bool(Properties p, String key) {
        return switch (required(p,key).toLowerCase(Locale.ROOT)) {
            case "true" -> true; case "false" -> false;
            default -> throw new IllegalArgumentException(key + ": ожидается true или false");
        };
    }
}
