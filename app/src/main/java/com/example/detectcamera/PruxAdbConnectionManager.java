package com.example.detectcamera;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedFile;
import androidx.security.crypto.MasterKey;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Owns Prux's ADB identity.
 *
 * <p>The identity (RSA key pair + self-signed X.509 certificate) is generated
 * exactly once, then persisted so that a successful manual pairing survives
 * reboots. The private key is stored encrypted with {@link EncryptedFile}
 * (AES-256-GCM with an Android Keystore master key); the certificate is
 * public and stored as a plain PEM file.
 */
public final class PruxAdbConnectionManager extends AbsAdbConnectionManager {

    private static final String CERT_FILE = "prux_adb_cert.pem";
    private static final String KEY_FILE  = "prux_adb_private.key";

    private static final String CERT_ALGORITHM = "SHA512withRSA";
    private static final int    KEY_SIZE_BITS  = 2048;
    private static final long   VALIDITY_MS    = 3650L * 24L * 60L * 60L * 1000L; // ~10 years

    /** MIME-style Base64 encoder that wraps at 64 chars per line (PEM standard). */
    private static final Base64.Encoder PEM_ENCODER =
            Base64.getMimeEncoder(64, new byte[]{'\n'});

    private static volatile PruxAdbConnectionManager instance;

    private final PrivateKey  privateKey;
    private final Certificate certificate;

    // ---------------------------------------------------------------------
    // Singleton
    // ---------------------------------------------------------------------

    public static PruxAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (instance == null) {
            synchronized (PruxAdbConnectionManager.class) {
                if (instance == null) {
                    // Do not publish the instance until the constructor completes
                    // successfully, to avoid leaking a half-initialized object.
                    PruxAdbConnectionManager created =
                            new PruxAdbConnectionManager(context.getApplicationContext());
                    instance = created;
                }
            }
        }
        return instance;
    }

    private PruxAdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);

        PrivateKey  pk = readPrivateKey(context);
        Certificate ct = readCertificate(context);

        if (pk == null || ct == null) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(KEY_SIZE_BITS, new SecureRandom());
            KeyPair pair = kpg.generateKeyPair();

            pk = pair.getPrivate();
            ct = createCertificate(pair.getPublic(), pk);

            writePrivateKey(context, pk);
            writeCertificate(context, ct);
        }

        this.privateKey  = pk;
        this.certificate = ct;
    }

    // ---------------------------------------------------------------------
    // AbsAdbConnectionManager contract
    // ---------------------------------------------------------------------

    @NonNull @Override
    protected PrivateKey  getPrivateKey()  { return privateKey; }

    @NonNull @Override
    protected Certificate getCertificate() { return certificate; }

    @NonNull @Override
    protected String      getDeviceName()  { return "Prux"; }

    // ---------------------------------------------------------------------
    // Certificate generation (BouncyCastle)
    // ---------------------------------------------------------------------

    private static Certificate createCertificate(PublicKey publicKey, PrivateKey privateKey)
            throws Exception {

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L);      // 1 minute clock-skew slack
        Date notAfter  = new Date(now + VALIDITY_MS);

        X500Name subject = new X500Name("CN=Prux");
        BigInteger serial = new BigInteger(64, new SecureRandom()).abs();

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, publicKey);

        // Subject Key Identifier — helps ADB tie the cert to the pubkey.
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        builder.addExtension(
                Extension.subjectKeyIdentifier,
                /* critical = */ false,
                extUtils.createSubjectKeyIdentifier(publicKey));

        ContentSigner signer = new JcaContentSignerBuilder(CERT_ALGORITHM)
                .build(privateKey);

        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
    }

    // ---------------------------------------------------------------------
    // Certificate I/O (plain PEM, public data)
    // ---------------------------------------------------------------------

    @Nullable
    private static Certificate readCertificate(Context context) throws Exception {
        File f = new File(context.getFilesDir(), CERT_FILE);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static void writeCertificate(Context context, Certificate cert)
            throws IOException, CertificateEncodingException {

        File f = new File(context.getFilesDir(), CERT_FILE);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write("-----BEGIN CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII));
            out.write(PEM_ENCODER.encode(cert.getEncoded()));
            out.write("\n-----END CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    // ---------------------------------------------------------------------
    // Private key I/O (encrypted with Android Keystore-backed master key)
    // ---------------------------------------------------------------------

    @Nullable
    private static PrivateKey readPrivateKey(Context context) throws Exception {
        File f = new File(context.getFilesDir(), KEY_FILE);
        if (!f.exists()) return null;

        byte[] bytes;
        try (InputStream in = openEncrypted(context, KEY_FILE).openFileInput()) {
            bytes = readAll(in);
        }
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static void writePrivateKey(Context context, PrivateKey key) throws Exception {
        try (OutputStream out = openEncrypted(context, KEY_FILE).openFileOutput()) {
            out.write(key.getEncoded());
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Wraps {@code fileName} (inside {@code filesDir}) in an {@link EncryptedFile}
     * backed by an AES-256-GCM master key stored in the Android Keystore.
     */
    private static EncryptedFile openEncrypted(Context context, String fileName) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        return new EncryptedFile.Builder(
                new File(context.getFilesDir(), fileName),
                context,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_HMAC)
                .build();
    }

    /** Reads everything from {@code in}. Works on all API levels. */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
