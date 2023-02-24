package com.avispa.ecm.model.content;

import com.avispa.ecm.model.EcmEntity;
import com.avispa.ecm.model.EcmObject;
import com.avispa.ecm.model.filestore.FileStore;
import com.avispa.ecm.model.format.Format;
import com.avispa.ecm.model.format.FormatNotFoundException;
import com.avispa.ecm.model.format.FormatRepository;
import com.avispa.ecm.util.exception.EcmException;
import com.avispa.ecm.util.exception.RepositoryCorruptionError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static com.avispa.ecm.model.format.Format.PDF;

/**
 * @author Rafał Hiszpański
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentService {
    private final ContentRepository contentRepository;
    private final FormatRepository formatRepository;
    private final FileStore fileStore;
    private final ResourceLoader resourceLoader;

    /**
     * Loads any content from provided path to the object
     * Works with classpath: and file: pseudo protocols
     * @param relatedObject object to which we want to attach the content
     * @param sourceFileLocation location of the file
     */
    @Transactional
    public void loadContentOf(EcmEntity relatedObject, String sourceFileLocation) {
        loadContentOf(relatedObject, resourceLoader.getResource(sourceFileLocation));
    }

    @Transactional
    public void loadContentOf(EcmEntity relatedObject, Resource resource) {
        if (!existsByRelatedObjectId(relatedObject.getId())) {
            String fileName = resource.getFilename();
            String extension = FilenameUtils.getExtension(fileName);

            Path fileStorePath = saveToFileStore(resource);
            createNewContent(extension, relatedObject, fileStorePath);
        } else {
            log.error("'{}' object already has its content", relatedObject.getObjectName());
        }
    }

    private boolean existsByRelatedObjectId(UUID id) {
        return contentRepository.existsByRelatedEntityId(id);
    }

    private Path saveToFileStore(Resource resource) {
        Path fullFileStorePath = Paths.get(fileStore.getRootPath(), UUID.randomUUID().toString());

        try {
            Files.copy(resource.getInputStream(), fullFileStorePath);
        } catch(IOException e) {
            try {
                log.error("Unable to copy content from '{}' to '{}'", resource.getURL(), fullFileStorePath, e);
            } catch (IOException ex) {
                log.error("Can't get urls content path", ex);
            }
            throw new EcmException("Can't save the content in the file store", e);
        }

        return fullFileStorePath;
    }

    @Transactional
    public Content createNewContent(String extension, EcmEntity relatedObject, Path fileStorePath) {
        Format format = formatRepository.findByExtension(extension);

        Content content = new Content();
        content.setObjectName(relatedObject.getObjectName().replace("/","_") + "." + extension);
        content.setFormat(format);
        content.setRelatedEntity(relatedObject);
        content.setFileStorePath(fileStorePath.toString());

        relatedObject.addContent(content);

        return contentRepository.save(content);
    }

    public Content findPdfRenditionByDocumentId(UUID id) {
        try {
            return contentRepository.findByRelatedEntityIdAndFormat(id, formatRepository.findByExtensionOrThrowException(PDF));
        } catch (FormatNotFoundException e) {
            throw new RepositoryCorruptionError("PDF Format not found in ECM Repository. Probably it is corrupted.");
        }
    }

    public void deleteByRelatedObject(EcmObject object) {
        contentRepository.deleteByRelatedEntity(object);
    }
}
