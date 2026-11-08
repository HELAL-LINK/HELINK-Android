package com.alihelal.core;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Ed25519 signature checking (RFC 8032, the way Go's crypto/ed25519 checks): enough to tell
 * that HELINK's update file was signed by whoever holds HELINK's private key. Plain Java with
 * BigInteger (the phones from Android 11 have no Ed25519 of their own); a check takes a
 * fraction of a second and happens once per look for an update.
 */
public final class Ed25519 {
    private Ed25519() {}

    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    // The order of the group: 2^252 + 27742317777372353535851937790883648493.
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] B; // the base point, extended coordinates

    static {
        BigInteger y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P);
        BigInteger x = recoverX(y, 0);
        B = new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    private static BigInteger inv(BigInteger z) {
        return z.modPow(P.subtract(TWO), P);
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        if (y.compareTo(P) >= 0) return null;
        BigInteger yy = y.multiply(y);
        BigInteger x2 = yy.subtract(BigInteger.ONE).multiply(inv(D.multiply(yy).add(BigInteger.ONE))).mod(P);
        if (x2.signum() == 0) return sign != 0 ? null : BigInteger.ZERO;
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null;
        if (x.testBit(0) != (sign != 0)) x = P.subtract(x);
        return x;
    }

    // Points as {X, Y, Z, T} with x = X/Z, y = Y/Z, x*y = T/Z.
    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(D2).mod(P).multiply(q[3]).mod(P);
        BigInteger d = p[2].shiftLeft(1).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] p) {
        BigInteger[] q = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = s.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (s.testBit(i)) q = add(q, p);
        }
        return q;
    }

    private static boolean same(BigInteger[] p, BigInteger[] q) {
        return p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0
                && p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0;
    }

    /** Little-endian bytes as a number. */
    private static BigInteger le(byte[] b, int off, int len) {
        byte[] be = new byte[len + 1]; // a leading zero: always positive
        for (int i = 0; i < len; i++) be[len - i] = b[off + i];
        return new BigInteger(be);
    }

    private static BigInteger[] decompress(byte[] b, int off) {
        BigInteger y = le(b, off, 32);
        int sign = y.testBit(255) ? 1 : 0;
        y = y.clearBit(255);
        BigInteger x = recoverX(y, sign);
        if (x == null) return null;
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    /** Whether sig (64 bytes) is publicKey's (32 bytes) signature of msg. */
    public static boolean verify(byte[] publicKey, byte[] msg, byte[] sig) {
        if (publicKey == null || publicKey.length != 32 || sig == null || sig.length != 64 || msg == null) return false;
        BigInteger[] a = decompress(publicKey, 0);
        if (a == null) return false;
        BigInteger[] r = decompress(sig, 0);
        if (r == null) return false;
        BigInteger s = le(sig, 32, 32);
        if (s.compareTo(L) >= 0) return false;
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
        sha.update(sig, 0, 32);
        sha.update(publicKey);
        sha.update(msg);
        byte[] hd = sha.digest();
        BigInteger h = le(hd, 0, 64).mod(L);
        return same(mul(s, B), add(r, mul(h, a)));
    }
}
