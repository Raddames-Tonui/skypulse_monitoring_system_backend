package org.skypulse.utils;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

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
}
