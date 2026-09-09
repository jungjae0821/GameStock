package com.gamestock.backend.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.FileInputStream;

@Component
public class FirebaseConfig {
    @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}") private String serviceAccountPath;
    @PostConstruct public void initialize() throws Exception {
        if (serviceAccountPath.isBlank() || !FirebaseApp.getApps().isEmpty()) return;
        try (var input = new FileInputStream(serviceAccountPath)) {
            FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(input)).build());
        }
    }
}
