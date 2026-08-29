package com.example.detectcamera;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Owns Prux's ADB identity. The identity is generated once and stored in the
 * app-private files directory so a successful manual pairing survives reboot.
 */
public final class PruxAdbConnectionManager extends AbsAdbConnectionManager {
    private static volatile PruxAdbConnectionManager instance;
    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static PruxAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (instance == null) {
            synchronized (PruxAdbConnectionManager.class) {
                if (instance == null) {
                    instance = new PruxAdbConnectionManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private PruxAdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        PrivateKey pk = readPrivateKey(context);
        Certificate cert = readCertificate(context);
        if (pk == null || cert == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair = generator.generateKeyPair();
            pk = pair.getPrivate();
            cert = createCertificate(pair.getPublic(), pk);
            writePrivateKey(context, pk);
            writeCertificate(context, cert);
        }
        privateKey = pk;
        certificate = cert;
    }

    @NonNull @Override
    protected PrivateKey getPrivateKey() { return privateKey; }

    @NonNull @Override
    protected Certificate getCertificate() { return certificate; }

    @NonNull @Override
    protected String getDeviceName() { return "Prux"; }

    @Nullable
    private static Certificate readCertificate(Context context) throws IOException, CertificateException {
        File f = new File(context.getFilesDir(), "prux_adb_cert.pem");
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    @Nullable
    private static PrivateKey readPrivateKey(Context context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File f = new File(context.getFilesDir(), "prux_adb_private.key");
        if (!f.exists()) return null;
        byte[] bytes = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static void writePrivateKey(Context context, PrivateKey key) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(context.getFilesDir(), "prux_adb_private.key"))) {
            out.write(key.getEncoded());
        }
    }

    private static void writeCertificate(Context context, Certificate certificate)
            throws IOException, CertificateEncodingException {
        File f = new File(context.getFilesDir(), "prux_adb_cert.pem");
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            encoder.encode(certificate.getEncoded(), out);
            out.write('\n');
            out.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Certificate createCertificate(PublicKey publicKey, PrivateKey privateKey) throws Exception {
        String algorithm = "SHA512withRSA";
        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 3650L * 24 * 60 * 60 * 1000);

        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X500Name name = new X500Name("CN=Prux");
        CertificateValidity validity = new CertificateValidity(notBefore, notAfter);
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", validity);
        info.set("extensions", extensions);

        X509CertImpl certificate = new X509CertImpl(info);
        certificate.sign(privateKey, algorithm);
        return certificate;
    }
}
