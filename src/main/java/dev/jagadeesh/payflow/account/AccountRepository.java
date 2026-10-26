package dev.jagadeesh.payflow.account;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** SELECT ... FOR UPDATE: the row stays locked until the transfer commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") UUID id);

    Optional<Account> findFirstByTypeAndCurrency(Account.Type type, String currency);

    @Query("select coalesce(sum(a.balance), 0) from Account a")
    BigDecimal sumOfAllBalances();
}
