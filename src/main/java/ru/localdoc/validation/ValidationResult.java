package ru.localdoc.validation;

import java.util.List;

public record ValidationResult(Status status,String code,String reason,List<String> warnings) {
    public enum Status { ACCEPT, REJECT, ERROR }
    public ValidationResult { warnings=List.copyOf(warnings); }
    public static ValidationResult accept(List<String> warnings) { return new ValidationResult(Status.ACCEPT,"ACCEPT","Проверка пройдена.",warnings); }
    public static ValidationResult reject(String code,String reason) { return new ValidationResult(Status.REJECT,code,reason,List.of()); }
    public static ValidationResult error(String code,String reason) { return new ValidationResult(Status.ERROR,code,reason,List.of()); }
}
