package com.zer0drv.blog.auth.service;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TotpCryptoTest {
    @Test void rfc6238Sha1VectorsIncludingBeyond2038() {
        String secret = TotpCrypto.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        long[] seconds = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < seconds.length; i++) assertEquals(expected[i], TotpCrypto.code(secret, seconds[i] / 30, 8));
        assertEquals(1, TotpCrypto.match(secret, "287082", 59, -1));
        assertEquals(-1, TotpCrypto.match(secret, "287082", 59, 1));
        assertEquals(-1, TotpCrypto.match(secret, "287082", 120, -1));
    }
    @Test void encryptionAuthenticatesOwnerAndRequiresExplicitKey() {
        var crypto = new TotpCrypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String secret = TotpCrypto.newSecret();
        String encrypted = crypto.encrypt(1, secret);
        assertNotEquals(secret, encrypted);
        assertNotEquals(encrypted, crypto.encrypt(1, secret));
        assertEquals(secret, crypto.decrypt(1, encrypted));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(2, encrypted));
        assertThrows(IllegalStateException.class, () -> new TotpCrypto("").decrypt(1, encrypted));
        assertThrows(IllegalArgumentException.class, () -> new TotpCrypto("YQ=="));
    }
}
