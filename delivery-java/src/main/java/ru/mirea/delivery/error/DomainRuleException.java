package ru.mirea.delivery.error;

import java.util.Map;

/** Нарушено правило предметной области: «доставил» до «забрал», смена без учётной записи — 422. */
public class DomainRuleException extends AppException {
    public DomainRuleException(String message) {
        this(message, null);
    }

    public DomainRuleException(String message, Map<String, Object> details) {
        super(message, details);
    }

    @Override
    public int httpStatus() {
        return 422;
    }

    @Override
    public String code() {
        return "DOMAIN_RULE_VIOLATED";
    }
}
