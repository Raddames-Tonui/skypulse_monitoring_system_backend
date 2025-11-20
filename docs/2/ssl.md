## ✅ SSL Logs Table Field Documentation

### ✅ Field-by-field explanation

### **1. **``

The hostname this certificate was issued for.
Example: `api.example.com`
Used to match the certificate to the host you're scanning.

---

### **2. **``

The Certificate Authority (CA) that issued the cert.
Example: `Let's Encrypt Authority X3`
Important for:

* Trust validation
* Identifying self-signed certs
* Auditing CA changes

---

### **3. **``

A globally unique ID assigned *per certificate* by the issuer.
Example: `04:AF:39:1C:...`
Used to detect:

* Certificate replacement
* Revocation (CRL/OCSP checks reference this)

---

### **4. **``

The algorithm used to sign the certificate.
Examples:

* `SHA256withRSA`
* `ECDSAwithSHA384`

Security relevance:

* Weak algorithms (like SHA1) = security red flag
* Good to track for compliance dashboards

---

### **5. **``

The type of public key.
Examples: `RSA`, `EC`, `DSA`
Defines the crypto method the server uses for TLS.

---

### **6. **``

Size of the public key.
Examples:

* `2048` (RSA)
* `4096` (RSA)
* `256` (EC P-256)

Security impact:

* Short RSA (<2048) is insecure
* Used for “certificate strength” rating in dashboards

---

### **7. **``

Comma-separated list of Subject Alternative Names.
Example:
`www.example.com, api.example.com, example.com`

SANs define *all* domains this certificate covers.
Browsers rely on SAN, not CN, for hostname validation.

---

### **8. **``

True if the certificate chain is complete and trusted.

Meaning of values:

* `true` → full CA chain provided, trustable
* `false` → missing intermediates, self-signed, or invalid chain

Used to detect:

* Misconfigured servers
* Broken TLS chains
* Certificates that will fail in real clients

---

### **9. **``

The certificate's “identity.”
Example:
`CN=api.example.com, O=Example Corp, C=US`

Contains:

* Common Name (CN)
* Organization (O)
* Location
  This is the certificate’s **self-claimed** identity.
  Modern systems use SAN instead of CN, but still important.

---

### **10. **``

A unique hash of the entire certificate.
Examples:

* SHA-1 fingerprint
* SHA-256 fingerprint (better)

Used for:

* Detecting certificate replacements
* Detecting MITM attacks
* Integrity verification

If the fingerprint changes, *the certificate definitely changed*, even if everything else looks similar.
