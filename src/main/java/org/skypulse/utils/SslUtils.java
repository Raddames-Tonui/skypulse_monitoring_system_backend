package org.skypulse.utils;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;

public final class SslUtils {
    private SslUtils() {}

    /**
     * Fetch server certificate (peer leaf cert).
     */
    public static X509Certificate getServerCert(String host, int port) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.setSoTimeout(5000);
            socket.startHandshake();
            Certificate[] certs = socket.getSession().getPeerCertificates();
            for (Certificate c : certs) {
                if (c instanceof X509Certificate) return (X509Certificate) c;
            }
        }
        return null;
    }

    /**
     * Extract full certificate metadata needed for database logging.
     */
    public static Map<String, Object> extractCertInfo(X509Certificate cert) throws Exception {
        Map<String, Object> info = new HashMap<>();

        // Identity
        info.put("issuer", cert.getIssuerX500Principal().getName());
        info.put("subject", cert.getSubjectX500Principal().getName());
        info.put("serial_number", cert.getSerialNumber().toString());
        info.put("sig_algo", cert.getSigAlgName());

        // Dates
        info.put("issued_date", cert.getNotBefore());
        info.put("expiry_date", cert.getNotAfter());

        // Public key
        var pk = cert.getPublicKey();
        info.put("pub_algo", pk.getAlgorithm());

        int keySize;
        if (pk instanceof java.security.interfaces.RSAPublicKey rsa) {
            keySize = rsa.getModulus().bitLength();
        } else if (pk instanceof java.security.interfaces.ECPublicKey ec) {
            keySize = ec.getParams().getCurve().getField().getFieldSize();
        } else {
            keySize = pk.getEncoded().length * 8;
        }
        info.put("pub_len", keySize);

        // SANs
        List<String> sans = new ArrayList<>();
        Collection<List<?>> sanColl = cert.getSubjectAlternativeNames();
        if (sanColl != null) {
            for (List<?> item : sanColl) {
                if (item.size() > 1 && item.get(1) != null) {
                    sans.add(item.get(1).toString());
                }
            }
        }
        info.put("sans", String.join(",", sans));

        // Placeholder for chain validation logic
        info.put("chain_valid", true);

        // Fingerprint SHA-256
        info.put("fingerprint", sha256Fingerprint(cert));

        return info;
    }

    private static String sha256Fingerprint(X509Certificate cert) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(cert.getEncoded());

        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02X:", b));
        }
        sb.setLength(sb.length() - 1); // remove trailing colon
        return sb.toString();
    }
}
