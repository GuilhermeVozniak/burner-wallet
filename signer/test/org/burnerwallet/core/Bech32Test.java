package org.burnerwallet.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JUnit 4 tests for Bech32 (BIP173) encode/decode.
 * Test vectors from protocol/vectors/bip173-bech32.json.
 */
public class Bech32Test {

    // ---- Valid address decoding ----

    @Test
    public void decodeValidP2wpkhMainnet() throws CryptoError {
        // BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4
        // scriptpubkey: 0014751e76e8199196d454941c45d1b3a323f1433bd6
        //   00 = witness version 0, 14 = push 20 bytes, then 20 bytes of program
        byte[] result = Bech32.decode("bc", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4");
        assertEquals("witness version should be 0", 0, result[0] & 0xFF);
        byte[] program = new byte[result.length - 1];
        System.arraycopy(result, 1, program, 0, program.length);
        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", HexCodec.encode(program));
    }

    @Test
    public void decodeValidP2wpkhTestnet() throws CryptoError {
        // tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nczm9t8f
        // witness v0 + 20-byte program: 1863143c14c5166804bd19203356da136c985678
        byte[] result = Bech32.decode("tb", "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nczm9t8f");
        assertEquals("witness version should be 0", 0, result[0] & 0xFF);
        byte[] program = new byte[result.length - 1];
        System.arraycopy(result, 1, program, 0, program.length);
        assertEquals("1863143c14c5166804bd19203356da136c985678", HexCodec.encode(program));
    }

    @Test
    public void decodeValidP2wshTestnet() throws CryptoError {
        // tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy
        // scriptpubkey: 0020000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433
        byte[] result = Bech32.decode("tb",
                "tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy");
        assertEquals("witness version should be 0", 0, result[0] & 0xFF);
        byte[] program = new byte[result.length - 1];
        System.arraycopy(result, 1, program, 0, program.length);
        assertEquals("000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433",
                HexCodec.encode(program));
    }

    @Test
    public void decodeCaseInsensitiveLowercase() throws CryptoError {
        // Should accept lowercase version of the mainnet address
        byte[] result = Bech32.decode("bc", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");
        assertEquals(0, result[0] & 0xFF);
        byte[] program = new byte[result.length - 1];
        System.arraycopy(result, 1, program, 0, program.length);
        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", HexCodec.encode(program));
    }

    // ---- Encode tests ----

    @Test
    public void encodeP2wpkhMainnet() throws CryptoError {
        byte[] program = HexCodec.decode("751e76e8199196d454941c45d1b3a323f1433bd6");
        String address = Bech32.encode("bc", 0, program);
        // encode should produce lowercase
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", address);
    }

    @Test
    public void encodeP2wpkhTestnet() throws CryptoError {
        byte[] program = HexCodec.decode("1863143c14c5166804bd19203356da136c985678");
        String address = Bech32.encode("tb", 0, program);
        assertEquals("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nczm9t8f", address);
    }

    @Test
    public void encodeP2wshTestnet() throws CryptoError {
        byte[] program = HexCodec.decode(
                "000000c4a5cad46221b2a187905e5266362b99d5e91c6ce24d165dab93e86433");
        String address = Bech32.encode("tb", 0, program);
        assertEquals("tb1qqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesrxh6hy",
                address);
    }

    // ---- Encode-decode roundtrip ----

    @Test
    public void encodeDecodeRoundtrip() throws CryptoError {
        byte[] program = HexCodec.decode("751e76e8199196d454941c45d1b3a323f1433bd6");
        String address = Bech32.encode("bc", 0, program);
        byte[] decoded = Bech32.decode("bc", address);
        assertEquals("witness version roundtrip", 0, decoded[0] & 0xFF);
        byte[] decodedProgram = new byte[decoded.length - 1];
        System.arraycopy(decoded, 1, decodedProgram, 0, decodedProgram.length);
        assertArrayEquals(program, decodedProgram);
    }

    @Test
    public void encodeDecodeRoundtripTestnet() throws CryptoError {
        byte[] program = HexCodec.decode("1863143c14c5166804bd19203356da136c985678");
        String address = Bech32.encode("tb", 0, program);
        byte[] decoded = Bech32.decode("tb", address);
        assertEquals(0, decoded[0] & 0xFF);
        byte[] decodedProgram = new byte[decoded.length - 1];
        System.arraycopy(decoded, 1, decodedProgram, 0, decodedProgram.length);
        assertArrayEquals(program, decodedProgram);
    }

    // ---- Invalid addresses ----

    @Test(expected = CryptoError.class)
    public void decodeInvalidHrpMismatch() throws CryptoError {
        // "tc1..." — wrong HRP (tc instead of bc/tb)
        Bech32.decode("bc", "tc1qw508d6qejxtdg4y5r3zarvary0c5xw7kg3g4ty");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidChecksum() throws CryptoError {
        // Last character changed: t4 -> t5, corrupts checksum
        Bech32.decode("bc", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidWitnessVersion() throws CryptoError {
        // BC13W508... — witness version 3 is not 0, but more importantly
        // this vector has an invalid program length for v3
        Bech32.decode("bc", "BC13W508D6QEJXTDG4Y5R3ZARVARY0C5XW7KN40WF2");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidTooShortProgram() throws CryptoError {
        // bc1rw5uspcuh — too short witness program
        Bech32.decode("bc", "bc1rw5uspcuh");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidTooLong() throws CryptoError {
        // Over 90 characters total
        Bech32.decode("bc",
                "bc10w508d6qejxtdg4y5r3zarvary0c5xw7kw508d6qejxtdg4y5r3zarvary0c5xw7kw5rljs90");
    }

    @Test(expected = CryptoError.class)
    public void decodeInvalidProgramLength() throws CryptoError {
        // BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P — invalid program length for v0
        Bech32.decode("bc", "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P");
    }

    // ---- Edge cases ----

    @Test(expected = CryptoError.class)
    public void decodeEmptyAddress() throws CryptoError {
        Bech32.decode("bc", "");
    }

    @Test(expected = CryptoError.class)
    public void decodeNoSeparator() throws CryptoError {
        Bech32.decode("bc", "bcqw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");
    }

    @Test(expected = CryptoError.class)
    public void encodeTooShortProgram() throws CryptoError {
        // Witness program must be 2..40 bytes
        Bech32.encode("bc", 0, new byte[1]);
    }

    @Test(expected = CryptoError.class)
    public void encodeTooLongProgram() throws CryptoError {
        // Witness program must be 2..40 bytes
        Bech32.encode("bc", 0, new byte[41]);
    }

    @Test(expected = CryptoError.class)
    public void encodeV0WrongProgramLength() throws CryptoError {
        // For witness v0, program must be exactly 20 or 32 bytes
        Bech32.encode("bc", 0, new byte[25]);
    }
}
