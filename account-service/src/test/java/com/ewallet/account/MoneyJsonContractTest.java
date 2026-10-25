package com.ewallet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ewallet.account.configuration.MoneyJsonConfiguration;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.service.AccountUseCases;
import com.ewallet.common.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

@JsonTest
@Import(MoneyJsonConfiguration.class)
class MoneyJsonContractTest {
    @Autowired
    ObjectMapper mapper;

    @Test
    void transactionMoneyIsAStringWithAllNumeric19_4Digits() throws Exception {
        var transaction = new WalletTransaction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("999999999999999.9999"), "USD", TransactionStatus.COMPLETED, "key",
            UUID.randomUUID(), Instant.now(), Instant.now(), true, null, null);
        var json = mapper.readTree(mapper.writeValueAsBytes(transaction));
        assertThat(json.get("amount").isTextual()).isTrue();
        assertThat(json.get("amount").asText()).isEqualTo("999999999999999.9999");
        assertThat(json.get("compensated").isBoolean()).isTrue();
    }

    @Test
    void nestedMonetaryValuesUsePlainDecimalStringsWithoutExponents() throws Exception {
        String json = mapper.writeValueAsString(Map.of("balance", new BigDecimal("1E+12")));
        assertThat(mapper.readTree(json).get("balance").asText()).isEqualTo("1000000000000");
        assertThat(mapper.readTree(json).get("balance").isTextual()).isTrue();
    }

    @Test
    void moneyRequestsRejectNumbersAndBooleansInsteadOfCoercingThemToStrings() throws Exception {
        for (String value : new String[] {"100", "0.1", "true"}) {
            assertThatThrownBy(() -> mapper.readValue("{\"amount\":" + value + "}", AccountUseCases.MoneyRequest.class))
                .isInstanceOf(MismatchedInputException.class);
        }
        assertThat(mapper.readValue("{\"amount\":\"100\"}", AccountUseCases.MoneyRequest.class).amount()).isEqualTo("100");
    }
}
