package com.xiyiyun.shop.persistence.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.persistence.CardCipherService;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestCardFixtureGeneratorTest {
    @Test
    void generatesSixHundredUniqueCardsThatRoundTripThroughCardCipherService() throws Exception {
        String secret = "test-v101-fixture-secret";
        StringWriter output = new StringWriter();

        TestCardFixtureGenerator.generate(output, secret, TestCardFixtureGenerator.CARD_COUNT);

        String[] lines = output.toString().split("\n");
        assertThat(lines).hasSize(TestCardFixtureGenerator.CARD_COUNT + 1);
        assertThat(lines[0]).isEqualTo(TestCardFixtureGenerator.HEADER);
        CardCipherService cipherService = new CardCipherService(secret);
        Set<String> hashes = new HashSet<>();
        Set<String> nonces = new HashSet<>();
        Map<Long, Integer> goodsCounts = new HashMap<>();
        Map<Long, Integer> cardKindCounts = new HashMap<>();
        Map<String, Integer> statusCounts = new HashMap<>();
        for (int sequence = 1; sequence <= TestCardFixtureGenerator.CARD_COUNT; sequence++) {
            String[] columns = lines[sequence].split("\t", -1);
            assertThat(columns).hasSize(14);
            assertThat(columns[0]).isEqualTo(String.valueOf(sequence));
            int goodsIndex = (sequence - 1) / 10;
            long goodsId = TestCardFixtureGenerator.FIRST_GOODS_ID + goodsIndex * 3L;
            long cardKindId = TestCardFixtureGenerator.FIRST_CARD_KIND_ID + goodsIndex % 6;
            assertThat(columns[1]).isEqualTo(String.valueOf(goodsId));
            assertThat(columns[2]).isEqualTo(String.valueOf(cardKindId));
            assertThat(columns[3]).isEqualTo("TEST-V101-BATCH-%02d".formatted(goodsIndex + 1));
            assertThat(columns[6]).isEqualTo("v1");
            assertThat(columns[8]).startsWith("TEST-V101-****");
            if ("LOCKED".equals(columns[9])) {
                assertThat(columns[10]).isEqualTo(String.valueOf(91_200_000L + goodsIndex * 3L + 1L));
                assertThat(columns[11]).isEqualTo("\\N");
                assertThat(columns[12]).isEqualTo("\\N");
            } else if ("SOLD".equals(columns[9])) {
                assertThat(columns[10]).isEqualTo("\\N");
                assertThat(columns[11]).isEqualTo(String.valueOf(91_200_000L + goodsIndex * 3L + 1L));
                assertThat(columns[12]).isNotEqualTo("\\N");
            } else {
                assertThat(columns[10]).isEqualTo("\\N");
                assertThat(columns[11]).isEqualTo("\\N");
                assertThat(columns[12]).isEqualTo("\\N");
            }

            byte[] ciphertext = HexFormat.of().parseHex(columns[4]);
            byte[] nonce = HexFormat.of().parseHex(columns[5]);
            String plaintext = TestCardFixtureGenerator.plaintext(sequence);
            assertThat(nonce).hasSize(12);
            assertThat(cipherService.decrypt(ciphertext, nonce)).isEqualTo(plaintext);
            assertThat(columns[7]).isEqualTo(cipherService.hash(plaintext));
            assertThat(hashes.add(columns[7])).isTrue();
            assertThat(nonces.add(columns[5])).isTrue();
            goodsCounts.merge(goodsId, 1, Integer::sum);
            cardKindCounts.merge(cardKindId, 1, Integer::sum);
            statusCounts.merge(columns[9], 1, Integer::sum);
        }
        assertThat(goodsCounts).hasSize(TestCardFixtureGenerator.GOODS_COUNT);
        assertThat(goodsCounts.values()).allMatch(count -> count == 10);
        assertThat(cardKindCounts).hasSize(6);
        assertThat(cardKindCounts.values()).allMatch(count -> count == 100);
        assertThat(statusCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
            "UNSOLD", 480,
            "LOCKED", 60,
            "SOLD", 60
        ));
    }
}
