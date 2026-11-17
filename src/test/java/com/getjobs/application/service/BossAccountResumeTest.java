package com.getjobs.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class BossAccountResumeTest {
    @TempDir Path directory;
    @Test void uploadedImagesAndPreviewUrlsAreIsolatedAndLegacyPathIsRetained() throws Exception {
        var service=new BossResumeImageService();
        ReflectionTestUtils.setField(service,"storageDirectory",directory);
        var bytes=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var file=new MockMultipartFile("file","resume.png","image/png",bytes.toByteArray());
        Path original=service.save(file).path();
        assertEquals(directory.resolve("resume.png"),original);
        try (var scope=BossAccountContext.use(2)) {
            assertTrue(service.getCurrentImagePath().isEmpty());
            Path another=service.save(file).path();
            assertNotEquals(original,another); assertTrue(another.startsWith(directory.resolve("accounts/2")));
            assertTrue(service.getMetadata().get("previewUrl").toString().endsWith("accountId=2"));
        }
        assertEquals(original,service.getCurrentImagePath().orElseThrow());
    }
}
