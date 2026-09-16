package com.gamestock.backend.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FirebaseConfig {
    /**
     * Local development historically supplied a path through this variable.
     * Railway can safely supply the service-account JSON itself as a secret, so
     * both forms are accepted for a smooth local-to-cloud transition.
     */
    @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}") private String serviceAccountJsonOrPath;
    @Value("${FIREBASE_SERVICE_ACCOUNT_PATH:}") private String serviceAccountPath;

    @PostConstruct public void initialize() throws Exception {
        if (!FirebaseApp.getApps().isEmpty()) return;

        String configured = !serviceAccountPath.isBlank()
                ? serviceAccountPath.trim()
                : serviceAccountJsonOrPath.trim();
        if (configured.isBlank()) return;

        try (InputStream input = openCredentials(configured)) {
            FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(input)).build());
        }
    }

    private InputStream openCredentials(String configured) throws Exception {
        if (configured.startsWith("{")) {
            return new ByteArrayInputStream(configured.getBytes(StandardCharsets.UTF_8));
        }

        Path path = Path.of(configured);
        if (Files.isRegularFile(path)) {
            return new FileInputStream(path.toFile());
        }
        throw new IllegalStateException(
                "Firebase service account 설정은 JSON 문자열 또는 존재하는 파일 경로여야 합니다.");
    }
}
