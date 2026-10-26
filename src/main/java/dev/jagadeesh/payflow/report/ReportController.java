package dev.jagadeesh.payflow.report;

import dev.jagadeesh.payflow.account.AccountRepository;
import dev.jagadeesh.payflow.ledger.LedgerEntry;
import dev.jagadeesh.payflow.ledger.LedgerEntryRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    public record TrialBalance(BigDecimal totalDebits, BigDecimal totalCredits, BigDecimal sumOfBalances,
                               boolean balanced) {
    }

    private final LedgerEntryRepository ledger;
    private final AccountRepository accounts;

    public ReportController(LedgerEntryRepository ledger, AccountRepository accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /**
     * Reconciliation check: total debits must equal total credits, and because deposits come from settlement
     * accounts, all balances together must sum to zero. Any other result means money was created or lost.
     */
    @GetMapping("/trial-balance")
    @Transactional(readOnly = true)
    public TrialBalance trialBalance() {
        BigDecimal debits = ledger.sumByDirection(LedgerEntry.Direction.DEBIT);
        BigDecimal credits = ledger.sumByDirection(LedgerEntry.Direction.CREDIT);
        BigDecimal balances = accounts.sumOfAllBalances();
        boolean balanced = debits.compareTo(credits) == 0 && balances.signum() == 0;
        return new TrialBalance(debits, credits, balances, balanced);
    }
}
