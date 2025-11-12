### 🔐 Protecting Sensitive Fields in Your Database

#### 1. Which Fields Deserve Encryption

The following fields in your schema contain personally identifiable or sensitive data:

* `users.user_email`
* `user_contacts.value` (phone, telegram, etc.)
* `users.password_hash` (already hashed)
* Possibly: `ip_address`, `session_token`, `device_name`

If you store information that can identify a person (PII), you should encrypt it at rest.


#### 2. Hashing vs. Encryption

* **Hashing (BCrypt, Argon2)** → One-way, irreversible. Best for passwords.
* **Encryption (AES, RSA)** → Reversible. Best for fields you must later display or search.

Use **encryption** for emails, phone numbers, and tokens.


#### 3. Where to Encrypt — Backend vs Database

| Option                                      | Description                                         | Recommendation                                                          |
| ------------------------------------------- | --------------------------------------------------- | ----------------------------------------------------------------------- |
| **Java-side encryption (BouncyCastle/JCE)** | Encrypt/decrypt in your app before saving/fetching. | ✅ Best practice — portable, secure, and under full application control. |
| **PostgreSQL-side (pgcrypto)**              | Use SQL functions for encryption/decryption.        | ⚙️ Simpler but less secure (key exposure risk).                         |

> ✅ **Recommended:** Encrypt in **Java** using BouncyCastle or Java’s built-in `javax.crypto`. Store only ciphertext in PostgreSQL. This ensures sensitive fields (emails, phones, IPs) never exist in plain form inside your database.

---

#### 4. Example: AES Encryption in Java (BouncyCastle)

```java
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.Security;
import java.util.Base64;

public class CryptoUtil {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private static final String KEY = "1234567890123456"; // 16-char key (move to config)
    private static final String ALGO = "AES/ECB/PKCS5Padding";

    public static String encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGO, "BC");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(), "AES"));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes()));
    }

    public static String decrypt(String cipherText) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGO, "BC");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes(), "AES"));
        return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)));
    }
}
```

Use this utility when saving or retrieving sensitive fields (email, phone, etc.).

---

#### 5. PostgreSQL Alternative Using pgcrypto

If you prefer SQL-side encryption:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Encrypt
INSERT INTO user_contacts (value)
VALUES (pgp_sym_encrypt('0700111222', 'your-secret-key'));

-- Decrypt
SELECT pgp_sym_decrypt(value::bytea, 'your-secret-key') FROM user_contacts;
```

This works, but embedding the key in SQL is riskier in production.

---

#### 6. Key Rotation and Secure Key Management

To ensure long-term security, apply these practices:

* **Key Rotation:** Regularly change encryption keys (e.g., every 6–12 months). Use versioned keys: `KEY_V1`, `KEY_V2`, etc.
* **Secure Storage:** Keep encryption keys in environment variables, Vault, AWS KMS, or GCP Secret Manager — never in code.
* **Access Control:** Restrict who can view or modify encryption keys.
* **Audit Logs:** Log key usage (not values) to monitor potential misuse.
* **Transition Strategy:** When rotating keys, decrypt using the old key and re-encrypt with the new one gradually.

---

#### ✅ Final Recommendations

* Keep `pgcrypto` enabled (safe and useful).
* Use **BCrypt** only for passwords.
* Use **AES encryption in Java** for PII such as emails, phone numbers, and IP addresses.
* Store only ciphertext in PostgreSQL.
* Rotate and secure keys properly.
* Mask data in API responses (e.g., `m*******@gmail.com`).
