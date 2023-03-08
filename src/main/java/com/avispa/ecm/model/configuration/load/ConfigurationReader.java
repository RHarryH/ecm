package com.avispa.ecm.model.configuration.load;

import com.avispa.ecm.model.configuration.load.dto.AutolinkDto;
import com.avispa.ecm.model.configuration.load.dto.AutonameDto;
import com.avispa.ecm.model.configuration.load.dto.ContextDto;
import com.avispa.ecm.model.configuration.load.dto.DictionaryDto;
import com.avispa.ecm.model.configuration.load.dto.EcmConfigDto;
import com.avispa.ecm.model.configuration.load.dto.PropertyPageDto;
import com.avispa.ecm.model.configuration.load.dto.TemplateDto;
import com.avispa.ecm.model.configuration.load.dto.UpsertDto;
import com.avispa.ecm.util.exception.EcmConfigurationException;
import com.avispa.ecm.util.json.JsonValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.CaseUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author Rafał Hiszpański
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ConfigurationReader {
    private static final int END_OF_ECM_PREFIX = 4;

    /**
     * List of supported configurations. Order of insertion matters as it defines the priority of configurations.
     * For example dictionary has to be loaded before property page as it is used in property pages
     */
    private static final List<ConfigurationType> CONFIG_TYPES = List.of(
            ConfigurationType.of("ecm_dictionary", DictionaryDto.class, false),
            ConfigurationType.of("ecm_property_page", PropertyPageDto.class, true),
            ConfigurationType.of("ecm_autolink", AutolinkDto.class, false),
            ConfigurationType.of("ecm_autoname", AutonameDto.class, false),
            ConfigurationType.of("ecm_upsert", UpsertDto.class, false),
            ConfigurationType.of("ecm_template", TemplateDto.class, true),
            ConfigurationType.of("ecm_context", ContextDto.class, false)
    );

    private final ObjectMapper objectMapper;

    private final JsonValidator jsonValidator;

    public Configuration read(Path zipConfigPath) {
        if(!Files.exists(zipConfigPath)) {
            throw new EcmConfigurationException("File '" + zipConfigPath + "' does not exist");
        }

        Configuration config = new Configuration();
        try(FileSystem fileSystem = FileSystems.newFileSystem(zipConfigPath, null)) {
            for(var configType : CONFIG_TYPES) {
                Path configPath = fileSystem.getPath(configType.getName());
                processDirectory(configType, config, configPath);
            }
        } catch (IOException e) {
            throw new EcmConfigurationException("Can't load '" + zipConfigPath + "' configuration file", e);
        }

        return config;
    }

    private void processDirectory(ConfigurationType configType, Configuration config, Path root) {
        try (Stream<Path> entries = Files.walk(root, 1)){
            entries.filter(Files::isRegularFile)
                    .filter(ConfigurationReader::isConfigurationDirectlyInFolder)
                    .forEach(path -> {
                        log.debug("Found '{}' file", path);
                        readConfig(configType, path, config);
                    });
        } catch (IOException e) {
            log.error("Error when traversing configuration file", e);
        }
    }

    /**
     * Valid configurations should be present only in folders /ecm_&lt;type&gt;.
     * Example:
     * - /ecm_upsert/Upsert.json
     * - /ecm_dictionary/Dictionary.json
     * @param path path to the file
     * @return
     */
    private static boolean isConfigurationDirectlyInFolder(Path path) {
        return path.getNameCount() == 2;
    }

    private void readConfig(ConfigurationType configType, Path configFilePath, Configuration config) {
        var configTypeName = configType.getName();
        log.debug("Found '{}' configuration type", configTypeName);

        var configItem = readConfig(configTypeName, configFilePath, configType);

        if(configType.isContentRequired()) {
            Path searchPath = configFilePath.getParent().resolve("content");
            if(!Files.exists(searchPath)) {
                throw new EcmConfigurationException("Content folder does not exist for required '" + configTypeName + "' configuration type.");
            }

            Path contentPath = findContent(searchPath, configItem.getName());
            addContent(config, contentPath);
        }

        config.addConfigDto(configItem);
    }

    /**
     * Validates configuration against JSON Schema and converts to DTO is validation is successful.
     * @param configTypeName
     * @param configFilePath
     * @param configType
     * @return
     */
    private EcmConfigDto readConfig(String configTypeName, Path configFilePath, ConfigurationType configType) {
        try {
            String configTypeNameWithoutPrefix = configTypeName.substring(END_OF_ECM_PREFIX);

            String schemaName = configTypeNameWithoutPrefix.replace("_", "-");
            Path schemaPath = Path.of("json-schemas", "configuration", schemaName + ".json");

            byte[] bytes = Files.readAllBytes(configFilePath);
            if (jsonValidator.validate(bytes, schemaPath.toString())) {
                String rootName = CaseUtils.toCamelCase(configTypeNameWithoutPrefix, false,'_');
                return objectMapper.readerFor(configType.getDto()).withRootName(rootName).readValue(bytes);
            } else {
                throw new EcmConfigurationException("'" + configFilePath + "' configuration is not valid");
            }
        } catch (IOException e) {
            throw new EcmConfigurationException("Can't parse '" + configTypeName + "' configuration from '" + configFilePath + "'");
        }
    }

    /**
     * Tries to find content file matching configuration item name. Because contents are required, when not found then
     * runtime exception is thrown.
     * @param searchPath path where content is expected to be found
     * @param configItemName name of the item for which the content should be found
     * @return
     */
    private static Path findContent(Path searchPath, String configItemName) {
        String pattern = configItemName + ".*";

        try(Stream<Path> contentPathsStream = Files.find(searchPath, 1,
                        (path, basicFileAttributes) -> path.getFileName().toString().matches(pattern))) {
            return contentPathsStream
                    .findFirst()
                    .orElseThrow(() -> new EcmConfigurationException("Can't find content file for '" + configItemName + "' configuration"));
        } catch (IOException e) {
            throw new EcmConfigurationException("Can't find content of '" + configItemName + "' configuration", e);
        }
    }

    /**
     * Unzips content to the temporal location (because after reading zip file system will be closed) and
     * registers file location for further processing.
     * @param config
     * @param contentPath
     */
    private static void addContent(Configuration config, Path contentPath) {
        try {
            Path tempPath = Files.createTempFile("ecm_content_", null);
            Files.write(tempPath, Files.readAllBytes(contentPath));
            config.addContent(FilenameUtils.getBaseName(contentPath.toString()), tempPath);
        } catch (IOException e) {
            throw new EcmConfigurationException("Can't unzip '" + contentPath + "' content", e);
        }
    }
}
