package com.xiyiyun.shop.persistence.fixture;

import com.xiyiyun.shop.persistence.CardCipherService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;

public final class TestCardFixtureGenerator {
    static final int CARD_COUNT = 600;
    static final int GOODS_COUNT = 60;
    static final long FIRST_GOODS_ID = 91_100_001L;
    static final long FIRST_CARD_KIND_ID = 91_051_001L;
    static final String FIXTURE_PREFIX = "TEST-V101";
    private static final Set<Integer> LOCKED_GOODS = Set.of(1, 2, 5, 6, 10, 14);
    private static final Set<Integer> SOLD_GOODS = Set.of(3, 7, 11, 12, 16, 20);
    static final String HEADER = String.join("\t",
        "fixture_no",
        "goods_id",
        "card_kind_id",
        "batch_no",
        "card_ciphertext_hex",
        "card_nonce_hex",
        "card_key_version",
        "card_hash",
        "card_preview",
        "status",
        "locked_order_id",
        "sold_order_id",
        "sold_at",
        "created_at"
    );

    private TestCardFixtureGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: TestCardFixtureGenerator <output.tsv>");
        }
        String secret = System.getenv("XIYIYUN_CARD_ENCRYPTION_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("XIYIYUN_CARD_ENCRYPTION_SECRET is required");
        }

        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            generate(writer, secret, CARD_COUNT);
        }
        System.out.println("generated " + CARD_COUNT + " encrypted cards at " + output);
    }

    static void generate(Writer writer, String secret, int count) throws IOException {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("card encryption secret is required");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("card count must be greater than 0");
        }
        CardCipherService cipherService = new CardCipherService(secret);
        writer.write(HEADER);
        writer.write('\n');
        for (int sequence = 1; sequence <= count; sequence++) {
            int goodsIndex = (sequence - 1) / 10;
            long goodsId = FIRST_GOODS_ID + goodsIndex * 3L;
            long cardKindId = FIRST_CARD_KIND_ID + goodsIndex % 6;
            long orderId = 91_200_000L + goodsIndex * 3L + 1L;
            String status = SOLD_GOODS.contains(goodsIndex) ? "SOLD"
                : LOCKED_GOODS.contains(goodsIndex) ? "LOCKED" : "UNSOLD";
            String plaintext = plaintext(sequence);
            CardCipherService.EncryptedCard encrypted = cipherService.encrypt(plaintext);
            if (!plaintext.equals(cipherService.decrypt(encrypted.ciphertext(), encrypted.nonce()))) {
                throw new IllegalStateException("generated card did not decrypt at sequence " + sequence);
            }
            writer.write(String.join("\t",
                String.valueOf(sequence),
                String.valueOf(goodsId),
                String.valueOf(cardKindId),
                "%s-BATCH-%02d".formatted(FIXTURE_PREFIX, goodsIndex + 1),
                HexFormat.of().formatHex(encrypted.ciphertext()),
                HexFormat.of().formatHex(encrypted.nonce()),
                encrypted.keyVersion(),
                encrypted.hash(),
                preview(plaintext),
                status,
                "LOCKED".equals(status) ? String.valueOf(orderId) : "\\N",
                "SOLD".equals(status) ? String.valueOf(orderId) : "\\N",
                "SOLD".equals(status) ? "2026-07-27 11:30:00.000" : "\\N",
                "2026-07-27 10:00:00.000"
            ));
            writer.write('\n');
        }
    }

    static String plaintext(int sequence) {
        return "%s-CARD-%04d|PIN-%08d".formatted(FIXTURE_PREFIX, sequence, 31_415_926 + sequence);
    }

    private static String preview(String plaintext) {
        return FIXTURE_PREFIX + "-****" + plaintext.substring(plaintext.length() - 4);
    }
}
