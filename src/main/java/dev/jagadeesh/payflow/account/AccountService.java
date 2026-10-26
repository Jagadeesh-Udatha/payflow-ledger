package dev.jagadeesh.payflow.account;

import dev.jagadeesh.payflow.common.BusinessRuleException;
import dev.jagadeesh.payflow.common.NotFoundException;
import dev.jagadeesh.payflow.ledger.LedgerEntry;
import dev.jagadeesh.payflow.ledger.LedgerEntryRepository;
import dev.jagadeesh.payflow.payment.PaymentService;
import dev.jagadeesh.payflow.payment.TransferRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final PaymentService payments;

    public AccountService(AccountRepository accounts, LedgerEntryRepository ledger, PaymentService payments) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.payments = payments;
    }

    /** Opens an account; an opening deposit is a normal ledger transfer from the currency's settlement account. */
    @Transactional
    public Account open(OpenAccountRequest request) {
        Account account = accounts.save(Account.openCustomer(request.ownerName(), request.currency()));
        BigDecimal deposit = request.openingDeposit();
        if (deposit != null && deposit.signum() > 0) {
            Account settlement = accounts.findFirstByTypeAndCurrency(Account.Type.SETTLEMENT, request.currency())
                    .orElseThrow(() -> new BusinessRuleException("UNSUPPORTED_CURRENCY",
                            "No settlement account for " + request.currency()));
            accounts.flush();
            payments.transfer("open-" + account.getId(),
                    new TransferRequest(settlement.getId(), account.getId(), deposit, "Opening deposit"));
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account %s not found".formatted(id)));
    }

    @Transactional(readOnly = true)
    public List<Account> customers() {
        return accounts.findAll().stream().filter(a -> a.getType() == Account.Type.CUSTOMER).toList();
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntry> statement(UUID id, int page, int size) {
        get(id);
        return ledger.findByAccountIdOrderByCreatedAtDesc(id, PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional
    public Account freeze(UUID id) {
        Account account = accounts.lockById(id).orElseThrow(() -> new NotFoundException("Account %s not found".formatted(id)));
        account.freeze();
        return account;
    }
}
