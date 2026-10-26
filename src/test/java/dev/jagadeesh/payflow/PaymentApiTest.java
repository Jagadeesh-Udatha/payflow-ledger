package dev.jagadeesh.payflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordGets401() throws Exception {
        mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void auditorCanReadButCannotMoveMoney() throws Exception {
        String auditor = token("auditor", "auditor-dev-password");
        mvc.perform(get("/api/reports/trial-balance").header("Authorization", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true));
        mvc.perform(post("/api/accounts").header("Authorization", auditor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Eve\",\"currency\":\"INR\",\"openingDeposit\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createPaymentThenRetryReturnsReplay() throws Exception {
        String ops = token("ops", "ops-dev-password");
        String alice = openAccount(ops, "Alice", 500);
        String bob = openAccount(ops, "Bob", 0);
        String body = """
                {"sourceAccountId":"%s","targetAccountId":"%s","amount":120.00,"reference":"invoice-42"}
                """.formatted(alice, bob);
        String key = UUID.randomUUID().toString();

        mvc.perform(post("/api/payments").header("Authorization", ops).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"));
        mvc.perform(post("/api/payments").header("Authorization", ops).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        mvc.perform(get("/api/accounts/" + alice).header("Authorization", ops))
                .andExpect(jsonPath("$.balance").value(380.0));
        mvc.perform(get("/api/accounts/" + alice + "/statement").header("Authorization", ops))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"));
    }

    @Test
    void invalidBodyReturnsFieldErrors() throws Exception {
        String ops = token("ops", "ops-dev-password");
        mvc.perform(post("/api/payments").header("Authorization", ops).header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.amount").exists())
                .andExpect(jsonPath("$.fields.sourceAccountId").exists());
    }

    @Test
    void insufficientFundsIs422() throws Exception {
        String ops = token("ops", "ops-dev-password");
        String alice = openAccount(ops, "Alice", 10);
        String bob = openAccount(ops, "Bob", 0);
        mvc.perform(post("/api/payments").header("Authorization", ops).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"targetAccountId\":\"%s\",\"amount\":11}".formatted(alice, bob)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    private String openAccount(String token, String owner, int deposit) throws Exception {
        String response = mvc.perform(post("/api/accounts").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"%s\",\"currency\":\"INR\",\"openingDeposit\":%d}".formatted(owner, deposit)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    private String token(String user, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return "Bearer " + node.get("accessToken").asText();
    }
}
