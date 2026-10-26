package dev.jagadeesh.payflow.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OpenAccountRequest(
        @NotBlank @Size(max = 120) String ownerName,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR") String currency,
        @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal openingDeposit) {
}
