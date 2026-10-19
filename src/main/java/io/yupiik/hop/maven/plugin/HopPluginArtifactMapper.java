/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.maven.plugin;

import static java.util.Map.entry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Maps a Hop plugin id (transform or action) to the Maven artifact that ships it.
 * <p>
 * The default rule is {@code hop-transform-<id.toLowerCase()>} / {@code hop-action-<id.toLowerCase()>}.
 * Some artifacts bundle several plugins (for example {@code hop-transform-textfile} contains
 * {@code CsvInput}, {@code TextFileInput2} and {@code TextFileOutput}) and some plugin ids do not
 * match their artifact name ({@code Sequence} lives in {@code hop-transform-addsequence}): those
 * exceptions are stored in the built-in tables below.
 * <p>
 * User provided mappings ({@code transform2ArtifactMapping} / {@code action2ArtifactMapping}) take
 * precedence over the built-in tables, which makes the class easily extensible without a release.
 */
public class HopPluginArtifactMapper {

    /**
     * Transforms whose id does not follow the {@code hop-transform-<id>} convention or which share
     * an artifact with other transforms. Key = transform plugin id, value = full artifactId.
     */
    private static final Map<String, String> TRANSFORM_ARTIFACTS = Map.ofEntries(
            entry("Sequence", "hop-transform-addsequence"),
            entry("DataStreamInput", "hop-transform-datastream"),
            entry("DataStreamOutput", "hop-transform-datastream"),
            entry("MappingInput", "hop-transform-mapping"),
            entry("MultiMapping", "hop-transform-mapping"),
            entry("MappingOutput", "hop-transform-mapping"),
            entry("SimpleMapping", "hop-transform-mapping"),
            entry("DBJoin", "hop-transform-databasejoin"),
            entry("JsonOutput", "hop-transform-json"),
            entry("JsonInput", "hop-transform-json"),
            entry("EnhancedJsonOutput", "hop-transform-json"),
            entry("JsonNormalizeInput", "hop-transform-json"),
            entry("SplitFieldToRows3", "hop-transform-splitfieldtorows"),
            entry("RuleAccumulator", "hop-transform-drools"),
            entry("RuleExecutor", "hop-transform-drools"),
            entry("FileMetadataPlugin", "hop-transform-filemetadata"),
            entry("JdbcMetadata", "hop-transform-jdbc-metadata"),
            entry("ODataInput", "hop-transform-odata"),
            entry("SurefireReportOutput", "hop-transform-surefirereport"),
            entry("KafkaConsumer", "hop-transform-kafka"),
            entry("KafkaProducerOutput", "hop-transform-kafka"),
            entry("JmsProducer", "hop-transform-jms"),
            entry("JmsConsumer", "hop-transform-jms"),
            entry("PGPDecryptStream", "hop-transform-pgp"),
            entry("PGPEncryptStream", "hop-transform-pgp"),
            entry("ClosureGenerator", "hop-transform-closure"),
            entry("MemoryGroupBy", "hop-transform-memgroupby"),
            entry("TransformsMetrics", "hop-transform-transformsmetrics"),
            entry("StepsMetrics", "hop-transform-transformsmetrics"),
            entry("MetadataInput", "hop-transform-metadata"),
            entry("SplunkInput", "hop-transform-splunk"),
            entry("Unique", "hop-transform-uniquerows"),
            entry("SortRows", "hop-transform-sort"),
            entry("ExcelInput", "hop-transform-excel"),
            entry("TypeExitExcelWriterTransform", "hop-transform-excel"),
            entry("StanfordSimpleNlp", "hop-transform-stanfordnlp"),
            entry("StreamSchema", "hop-transform-streamschemamerge"),
            entry("CubeOutput", "hop-transform-cube"),
            entry("CubeInput", "hop-transform-cube"),
            entry("Flattener", "hop-transform-flattener"),
            entry("Flatterner", "hop-transform-flattener"),
            entry("ScriptValueMod", "hop-transform-javascript"),
            entry("JavaFilter", "hop-transform-janino"),
            entry("UserDefinedJavaClass", "hop-transform-janino"),
            entry("ExecSql", "hop-transform-sql"),
            entry("TypeExitEdi2XmlTransform", "hop-transform-edi2xml"),
            entry("SystemInfo", "hop-transform-systemdata"),
            entry("RecordsFromStream", "hop-transform-rowsfromresult"),
            entry("WebServiceLookup", "hop-transform-webservices"),
            entry("CSVInput", "hop-transform-textfile"),
            entry("TextFileInput2", "hop-transform-textfile"),
            entry("TextFileOutput", "hop-transform-textfile"),
            entry("MultiwayMergeJoin", "hop-transform-multimerge"),
            entry("VCardOutput", "hop-transform-vcard"),
            entry("VCardInput", "hop-transform-vcard"),
            entry("SuperScript", "hop-transform-script"),
            entry("LDAPOutput", "hop-transform-ldap"),
            entry("LDAPInput", "hop-transform-ldap"),
            entry("TransformMetaStructure", "hop-transform-metastructure"),
            entry("GetFilesRowsCount", "hop-transform-getfilesrowcount"),
            entry("XSLT", "hop-transform-xml"),
            entry("AdvancedXMLOutput", "hop-transform-xml"),
            entry("XMLInputStream", "hop-transform-xml"),
            entry("getXMLData", "hop-transform-xml"),
            entry("XMLOutput", "hop-transform-xml"),
            entry("XMLJoin", "hop-transform-xml"),
            entry("XSDValidator", "hop-transform-xml"),
            entry("AddXML", "hop-transform-xml"),
            entry("DbImpactInput", "hop-transform-dbimpact"),
            entry("TokenReplacementPlugin", "hop-transform-tokenreplacement"),
            entry("DBLookup", "hop-transform-databaselookup"),
            entry("GetServerStatus", "hop-transform-serverstatus"));

    /**
     * Actions whose id does not follow the {@code hop-action-<id>} convention or which share an
     * artifact with other actions. Key = action plugin id, value = full artifactId.
     */
    private static final Map<String, String> ACTION_ARTIFACTS = Map.ofEntries(
            entry("WEBSERVICE_AVAILABLE", "hop-action-webserviceavailable"),
            entry("FILE_COMPARE", "hop-action-filecompare"),
            entry("WRITE_TO_FILE", "hop-action-writetofile"),
            entry("EndRepeat", "hop-action-repeat"),
            entry("EVAL_FILES_METRICS", "hop-action-evalfilesmetrics"),
            entry("FOLDER_IS_EMPTY", "hop-action-folderisempty"),
            entry("SEND_NAGIOS_PASSIVE_CHECK", "hop-action-sendnagiospassivecheck"),
            entry("TABLE_EXISTS", "hop-action-tableexists"),
            entry("WRITE_TO_LOG", "hop-action-writetolog"),
            entry("SET_VARIABLES", "hop-action-setvariables"),
            entry("MOVE_FILES", "hop-action-movefiles"),
            entry("WAIT_FOR_FILE", "hop-action-waitforfile"),
            entry("FOLDERS_COMPARE", "hop-action-folderscompare"),
            entry("CHECK_FILES_LOCKED", "hop-action-checkfilelocked"),
            entry("XSD_VALIDATOR", "hop-action-xml"),
            entry("DTD_VALIDATOR", "hop-action-xml"),
            entry("XSLT", "hop-action-xml"),
            entry("XML_WELL_FORMED", "hop-action-xml"),
            entry("ADD_RESULT_FILENAMES", "hop-action-addresultfilenames"),
            entry("FILES_EXIST", "hop-action-filesexist"),
            entry("EVAL_TABLE_CONTENT", "hop-action-evaluatetablecontent"),
            entry("DELETE_FOLDERS", "hop-action-deletefolders"),
            entry("DOS_UNIX_CONVERTER", "hop-action-dostounix"),
            entry("CREATE_FILE", "hop-action-createfile"),
            entry("CREATE_FOLDER", "hop-action-createfolder"),
            entry("DELETE_RESULT_FILENAMES", "hop-action-deleteresultfilenames"),
            entry("SET_PASSWORD_ENCODER", "hop-action-setpasswordencoder"),
            entry("SNMP_TRAP", "hop-action-snmptrap"),
            entry("SIMPLE_EVAL", "hop-action-simpleeval"),
            entry("COLUMNS_EXIST", "hop-action-columnsexist"),
            entry("PGP_DECRYPT_FILES", "hop-action-pgpfiles"),
            entry("PGP_VERIFY_FILES", "hop-action-pgpfiles"),
            entry("PGP_ENCRYPT_FILES", "hop-action-pgpfiles"),
            entry("DELETE_FILE", "hop-action-deletefile"),
            entry("COPY_MOVE_RESULT_FILENAMES", "hop-action-copymoveresultfilenames"),
            entry("DELETE_FILES", "hop-action-deletefiles"),
            entry("ZIP_FILE", "hop-action-zipfile"),
            entry("CHECK_DB_CONNECTIONS", "hop-action-checkdbconnection"),
            entry("FILE_EXISTS", "hop-action-fileexists"),
            entry("TRUNCATE_TABLES", "hop-action-truncatetables"),
            entry("WAIT_FOR_SQL", "hop-action-waitforsql"),
            entry("COPY_FILES", "hop-action-copyfiles"));

    private final Map<String, String> transformArtifacts;
    private final Map<String, String> actionArtifacts;
    private final Map<String, String> transformOverrides;
    private final Map<String, String> actionOverrides;
    private final Consumer<String> debugLog;

    public HopPluginArtifactMapper(
            final Map<String, String> transform2ArtifactMapping,
            final Map<String, String> action2ArtifactMapping,
            final Consumer<String> debugLog) {
        this.transformOverrides = emptyIfNull(transform2ArtifactMapping);
        this.actionOverrides = emptyIfNull(action2ArtifactMapping);
        this.transformArtifacts = merge(TRANSFORM_ARTIFACTS, this.transformOverrides);
        this.actionArtifacts = merge(ACTION_ARTIFACTS, this.actionOverrides);
        this.debugLog = debugLog == null ? ignored -> {} : debugLog;
    }

    /**
     * @return the artifactId shipping the given transform plugin id, applying the user overrides
     * then the built-in table then the default {@code hop-transform-<id>} rule.
     */
    public String transformArtifactId(final String transformPluginId) {
        return resolve(
                transformPluginId,
                transformArtifacts,
                TRANSFORM_ARTIFACTS,
                transformOverrides,
                "hop-transform-",
                "transform");
    }

    /**
     * @return the artifactId shipping the given action plugin id, applying the user overrides
     * then the built-in table then the default {@code hop-action-<id>} rule.
     */
    public String actionArtifactId(final String actionPluginId) {
        return resolve(actionPluginId, actionArtifacts, ACTION_ARTIFACTS, actionOverrides, "hop-action-", "action");
    }

    private String resolve(
            final String pluginId,
            final Map<String, String> mappings,
            final Map<String, String> defaults,
            final Map<String, String> overrides,
            final String prefix,
            final String type) {
        final var explicit = mappings.get(pluginId);
        if (explicit != null) {
            final var source = overrides.containsKey(pluginId)
                    ? "user override"
                    : defaults.containsKey(pluginId) ? "built-in table" : "default convention";
            debugLog.accept("Mapping " + type + " '" + pluginId + "' -> '" + explicit + "' (" + source + ")");
            return explicit;
        }
        final var fallback = prefix + pluginId.toLowerCase(Locale.ROOT);
        debugLog.accept("Mapping " + type + " '" + pluginId + "' -> '" + fallback + "' (default convention)");
        return fallback;
    }

    private static Map<String, String> emptyIfNull(final Map<String, String> map) {
        return map == null ? Map.of() : map;
    }

    private static Map<String, String> merge(final Map<String, String> defaults, final Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return defaults;
        }
        return overrides.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> right,
                        () -> new LinkedHashMap<>(defaults)));
    }
}
