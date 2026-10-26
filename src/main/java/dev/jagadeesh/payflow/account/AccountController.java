package dev.jagadeesh.payflow.account;

import dev.jagadeesh.payflow.ledger.LedgerEntry;
import jakarta.validation.Valid;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account open(@Valid @RequestBody OpenAccountRequest request) {
        return service.open(request);
    }

    @GetMapping
    public List<Account> list() {
        return service.customers();
    }

    @GetMapping("/{id}")
    public Account get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/statement")
    public PagedModel<LedgerEntry> statement(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return new PagedModel<>(service.statement(id, page, size));
    }

    @PostMapping("/{id}/freeze")
    public Account freeze(@PathVariable UUID id) {
        return service.freeze(id);
    }
}
