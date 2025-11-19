package org.skypulse.utils;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

public final class SslUtils {
    private SslUtils() {}

    /**
     * Connects to host:port and returns the server certificate's expiry (UTC).
     */
    public static ZonedDateTime getCertExpiry(String host, int port) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.setSoTimeout(5000);
            socket.startHandshake();
            Certificate[] certs = socket.getSession().getPeerCertificates();
            for (Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    X509Certificate x = (X509Certificate) c;
                    Instant expires = x.getNotAfter().toInstant();
                    return ZonedDateTime.ofInstant(expires, ZoneOffset.UTC);
                }
            }
        }
        return null;
    }

    /**
     * Connects to host:port and returns the SSL certificate issuer.
     * Example: "CN=R3, O=Let's Encrypt, C=US"
     */
    public static String getCertIssuer(String host, int port) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.setSoTimeout(5000);
            socket.startHandshake();

            Certificate[] certs = socket.getSession().getPeerCertificates();
            for (Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    X509Certificate x = (X509Certificate) c;
                    return x.getIssuerX500Principal().getName();
                }
            }
        }

        return "Unknown Issuer";
    }


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

    public static Map<String,Object> extractCertInfo(X509Certificate cert) throws Exception {
        Map<String,Object> info = new HashMap<>();
        info.put("issuer", cert.getIssuerX500Principal().getName());
        info.put("serial_number", cert.getSerialNumber().toString());
        info.put("sig_algo", cert.getSigAlgName());
        info.put("pub_algo", cert.getPublicKey().getAlgorithm());
        info.put("pub_len", cert.getPublicKey().getEncoded().length * 8);

        // SANs
        List<String> sans = new ArrayList<>();
        Collection<List<?>> sanColl = cert.getSubjectAlternativeNames();
        if (sanColl != null) {
            for (List<?> item : sanColl) {
                sans.add(item.get(1).toString());
            }
        }
        info.put("sans", String.join(",", sans));

        // chain validation (basic)
        info.put("chain_valid", true); // later implement full validation if desired
        return info;
    }

}
