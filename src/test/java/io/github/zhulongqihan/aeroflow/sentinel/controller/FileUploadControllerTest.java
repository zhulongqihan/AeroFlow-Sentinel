package io.github.zhulongqihan.aeroflow.sentinel.controller;

import io.github.zhulongqihan.aeroflow.sentinel.config.FileUploadConfig;
import io.github.zhulongqihan.aeroflow.sentinel.service.SafeUploadStorage;
import io.github.zhulongqihan.aeroflow.sentinel.service.VectorIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileUploadControllerTest {
    @Test
    void relativeUploadConfigurationRetainsIndexSourceAndResponseContractAfterSafeWrite() throws Exception {
        String directoryName = "upload-contract-" + UUID.randomUUID();
        String configuredPath = "./target/" + directoryName + "/.";
        Path actualRoot = Path.of(configuredPath).toAbsolutePath().normalize();
        assertFalse(Files.exists(actualRoot));
        String expectedSource = Path.of("target", directoryName, "guide.md").toString();
        FileUploadConfig configuration = new FileUploadConfig();
        configuration.setPath(configuredPath);
        configuration.setAllowedExtensions("md");
        VectorIndexService indexer = mock(VectorIndexService.class);
        doAnswer(call -> {
            Path indexedFile = Path.of(call.getArgument(0, String.class));
            assertEquals("new content", Files.readString(indexedFile), "indexing must follow the successful safe write");
            return null;
        }).when(indexer).indexSingleFile(anyString());
        FileUploadController controller = new FileUploadController();
        ReflectionTestUtils.setField(controller, "fileUploadConfig", configuration);
        ReflectionTestUtils.setField(controller, "uploadStorage", new SafeUploadStorage());
        ReflectionTestUtils.setField(controller, "vectorIndexService", indexer);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try {
            MockMultipartFile file = new MockMultipartFile("file", "guide.md", "text/markdown",
                    "new content".getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart("/api/upload").file(file))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.fileName").value("guide.md"))
                    .andExpect(jsonPath("$.data.filePath").value(expectedSource));
            verify(indexer).indexSingleFile(expectedSource);
            assertEquals("new content", Files.readString(actualRoot.resolve("guide.md")));
        } finally {
            // These are the only paths created by this test, in its new random private directory.
            Files.deleteIfExists(actualRoot.resolve("guide.md"));
            Files.deleteIfExists(actualRoot);
        }
    }
}
