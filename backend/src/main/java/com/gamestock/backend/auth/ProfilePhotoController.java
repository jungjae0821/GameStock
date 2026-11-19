package com.gamestock.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class ProfilePhotoController {
    private final AuthService auth;
    private final ProfilePhotoService photos;

    public ProfilePhotoController(AuthService auth, ProfilePhotoService photos) {
        this.auth = auth;
        this.photos = photos;
    }

    @PostMapping(value = "/profile/photo", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    public AuthService.Profile upload(HttpServletRequest request,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) throws IOException {
        long userId = auth.requireMember(authorization).id();
        // 한도보다 1바이트 더 읽어 큰 파일을 끝까지 받지 않고 거절한다.
        byte[] body = request.getInputStream().readNBytes(ProfilePhotoService.MAX_BYTES + 1);
        return photos.upload(userId, body);
    }

    @GetMapping("/profile-photos/{id}.jpg")
    public ResponseEntity<byte[]> photo(@PathVariable String id) {
        byte[] data = id.matches("[0-9a-f]{32}") ? photos.read(id) : null;
        if (data == null) return ResponseEntity.notFound().build();
        // 사진마다 주소가 새로 생기므로 한 번 받은 사진은 바뀌지 않는다.
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .body(data);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidPhoto(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", error.getMessage()));
    }
}
