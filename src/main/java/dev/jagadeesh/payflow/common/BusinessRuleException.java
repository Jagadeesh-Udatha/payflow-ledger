package dev.jagadeesh.payflow.common;

/** A request that is well formed but breaks a ledger rule (insufficient funds, frozen account, currency mismatch). */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
