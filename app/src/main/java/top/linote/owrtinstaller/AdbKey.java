package top.linote.owrtinstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The app's own ADB authorization key (RSA-2048), generated on first use and kept
 * in app-private storage. It is only used to authenticate to adbd, exactly like the
 * ~/.android/adbkey of desktop adb; it is unrelated to APK signing.
 */
final class AdbKey {
    private static final int WORDS = 64;              // 2048-bit modulus
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    final RSAPrivateCrtKey key;

    private AdbKey(RSAPrivateCrtKey key) { this.key = key; }

    static synchronized AdbKey loadOrCreate(File file) throws IOException {
        try {
            if (file.isFile() && file.length() > 0 && file.length() < 8192) {
                byte[] der = new byte[(int) file.length()];
                try (RandomAccessFile r = new RandomAccessFile(file, "r")) { r.readFully(der); }
                return new AdbKey((RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(der)));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            RSAPrivateCrtKey k = (RSAPrivateCrtKey) g.generateKeyPair().getPrivate();
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(k.getEncoded()); }
            if (!tmp.renameTo(file)) throw new IOException("cannot save ADB key");
            return new AdbKey(k);
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IOException("ADB key: " + e.getMessage(), e);
        }
    }

    /** PKCS#1 v1.5 signature of the 20-byte token treated as a SHA-1 digest (adbd's RSA_verify). */
    byte[] signToken(byte[] token) {
        int len = WORDS * 4;
        byte[] em = new byte[len];
        int tLen = SHA1_DIGEST_INFO.length + token.length;
        em[0] = 0;
        em[1] = 1;
        for (int i = 2; i < len - tLen - 1; i++) em[i] = (byte) 0xff;
        em[len - tLen - 1] = 0;
        System.arraycopy(SHA1_DIGEST_INFO, 0, em, len - tLen, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, em, len - token.length, token.length);
        BigInteger m = new BigInteger(1, em);
        // CRT exponentiation.
        BigInteger p = key.getPrimeP(), q = key.getPrimeQ();
        BigInteger m1 = m.modPow(key.getPrimeExponentP(), p);
        BigInteger m2 = m.modPow(key.getPrimeExponentQ(), q);
        BigInteger h = key.getCrtCoefficient().multiply(m1.subtract(m2)).mod(p);
        BigInteger s = m2.add(h.multiply(q));
        return toFixed(s, len);
    }

    /** Android adb public key: base64(mincrypt RSAPublicKey) + " name" + NUL. */
    byte[] publicKeyPayload(String name) {
        return (publicKeyString() + " " + name + "\0").getBytes(StandardCharsets.UTF_8);
    }

    String publicKeyString() {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(WORDS * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(WORDS);
        b.putInt(n0inv.intValue());
        putWords(b, n);
        putWords(b, rr);
        b.putInt(key.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array());
    }

    /** Short fingerprint for display (like the dialog adbd shows: MD5 of the key blob). */
    String fingerprint() {
        try {
            byte[] blob = Base64.getDecoder().decode(publicKeyString());
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(blob);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < d.length; i++) { if (i > 0) s.append(':'); s.append(String.format("%02X", d[i] & 0xff)); }
            return s.toString();
        } catch (GeneralSecurityException e) {
            return "?";
        }
    }

    private static void putWords(ByteBuffer b, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < WORDS; i++) {
            b.putInt(v.and(mask).intValue());
            v = v.shiftRight(32);
        }
    }

    private static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        if (raw.length > len) System.arraycopy(raw, raw.length - len, out, 0, len);
        else System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        return out;
    }
}
