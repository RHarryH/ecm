package com.avispa.ecm.service.rendition;

import com.avispa.ecm.model.content.Content;
import com.avispa.ecm.model.content.ContentRepository;
import com.avispa.ecm.model.document.Document;
import com.avispa.ecm.model.document.DocumentRepository;
import com.avispa.ecm.model.filestore.FileStore;
import com.avispa.ecm.model.format.Format;
import com.avispa.ecm.model.format.FormatRepository;
import com.avispa.ecm.util.exception.EcmException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Rafał Hiszpański
 */
@SpringBootTest(properties = "jodconverter.local.existing-process-action=connect_or_kill")
@ActiveProfiles(profiles = {"test"})
class RenditionServiceIntegrationTest {
    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("avispa.ecm.file-store.path", () ->
            Path.of(Path.of("target").toAbsolutePath().toString(), "rendition-test").toString());
    }

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private FileStore fileStore;

    @Autowired
    private FormatRepository formatRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private RenditionService renditionService;

    @AfterEach
    public void tearDown() throws IOException {
        FileUtils.cleanDirectory(new File(fileStore.getRootPath()));
    }

    @Test
    void givenTestFile_whenGeneratingPdf_thenPdfAndContentExist() throws ExecutionException, InterruptedException, TimeoutException {
        // given
        Document document = getDocument();
        documentRepository.save(document);
        Content content = getInputContent(document, "test.odt");

        // when
        var result = renditionService.generate(content);
        Content rendition = result.get();

        // then
        assertTrue(rendition.isPdf());
        assertTrue(new File(rendition.getFileStorePath()).exists());
        assertNotNull(contentRepository.getReferenceById(rendition.getId()));
    }

    @Test
    void givenCorruptedTestFile_whenGeneratingPdf_thenFileSystemAndDBAreRolledBack() throws IOException {
        // given
        Document document = getDocument();
        documentRepository.save(document);
        Content content = getInputContent(document, "unsupported-test.zip");

        // when
        var result = renditionService.generate(content);

        // then
        var exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof EcmException);
        assertNull(contentRepository.findByRelatedEntityIdAndFormat(document.getId(), formatRepository.findByExtension(Format.PDF)));
        assertTrue(isEmpty(Path.of(fileStore.getRootPath())));
    }

    @Test
    void givenTestFileAndIncorrectDBStructure_whenGeneratingPdf_thenFileSystemAndDBAreRolledBack() throws IOException {
        // given
        Document document = getDocument();
        Content content = getInputContent(document, "test.odt");

        // when
        var result = renditionService.generate(content);

        // then
        var exception = assertThrows(ExecutionException.class, result::get);
        assertTrue(exception.getCause() instanceof EcmException);
        assertNull(contentRepository.findByRelatedEntityIdAndFormat(document.getId(), formatRepository.findByExtension(Format.PDF)));
        assertTrue(isEmpty(Path.of(fileStore.getRootPath())));
    }

    private Document getDocument() {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setObjectName("Document");
        return document;
    }

    private Content getInputContent(Document document, String fileName) {
        Content content = new Content();
        content.setId(UUID.randomUUID());
        content.setObjectName("Content");
        content.setFormat(formatRepository.findByExtension(Format.ODT));
        content.setRelatedEntity(document);
        content.setFileStorePath("src/test/resources/document/" + fileName);
        return content;
    }

    public boolean isEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                return entries.findFirst().isEmpty();
            }
        }

        return false;
    }
}
