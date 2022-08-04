package io.coffeebeans.connector.sink;

import static io.coffeebeans.connector.sink.config.AzureBlobSinkConfig.FILE_FORMAT_CONF_KEY;
import static io.coffeebeans.connector.sink.config.AzureBlobSinkConfig.TOPIC_SCHEMA_URL_SUFFIX;
import static org.apache.kafka.connect.sink.SinkTask.TOPICS_CONFIG;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Splitter;
import io.coffeebeans.connector.sink.config.AzureBlobSinkConfig;
import io.coffeebeans.connector.sink.exception.SchemaNotFoundException;
import io.coffeebeans.connector.sink.format.FileFormat;
import io.coffeebeans.connector.sink.format.RecordWriter;
import io.coffeebeans.connector.sink.format.RecordWriterProvider;
import io.coffeebeans.connector.sink.format.SchemaStore;
import io.coffeebeans.connector.sink.partitioner.Partitioner;
import io.coffeebeans.connector.sink.storage.StorageManager;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.connect.sink.ErrantRecordReporter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This context class is different from ConnectorContext & SinkTaskContext.
 * Its usability is inside the scope of AzureBlobSinkConnector.
 */
public class AzureBlobSinkConnectorContext {
    private final Logger log = LoggerFactory.getLogger(AzureBlobSinkConnectorContext.class);

    private final StorageManager storageManager;
    private final SchemaStore schemaStore;
    private final Partitioner partitioner;
    private final AzureBlobSinkConfig config;
    private final SinkTaskContext sinkTaskContext;
    private ErrantRecordReporter errantRecordReporter;
    private final RecordWriterProvider recordWriterProvider;

    private final Map<String, String> configProps;

    /**
     * Private constructor to be used by builder class.
     * Builder classes make testing simpler.
     *
     * @param builder Builder object
     * @throws IOException IOException
     * @throws SchemaNotFoundException SchemaNotFoundException
     */
    private AzureBlobSinkConnectorContext(Builder builder) throws
            IOException, SchemaNotFoundException {

        this.config = builder.config;
        this.storageManager = builder.storageManager;
        this.schemaStore = builder.schemaStore;
        this.partitioner = builder.partitioner;
        this.sinkTaskContext = builder.sinkTaskContext;
        this.recordWriterProvider = builder.recordWriterProvider;

        if (sinkTaskContext != null) {
            this.errantRecordReporter = this.sinkTaskContext.errantRecordReporter();
        }

        this.configProps = builder.configProps;

        if (isSchemaStoreRecommended(configProps)) {
            try {
                loadSchema(configProps, schemaStore);

            } catch (IOException | SchemaNotFoundException e) {
                log.error("Failed to initialize context: Error loading schema");
                throw e;
            }
        }
    }

    /**
     * Check if using schema store recommended.
     * It will check the file format specified by the user. As of now we are
     * only using schema store for writing parquet files, so it will recommend
     * using schema store only if the file format is parquet.
     *
     * @param configProps Configuration map
     * @return True if schema store usage is recommended or else false
     */
    private boolean isSchemaStoreRecommended(Map<String, String> configProps) {
        FileFormat fileFormat = FileFormat.valueOf(
                configProps.get(FILE_FORMAT_CONF_KEY)
        );
        return FileFormat.PARQUET.equals(fileFormat);
    }

    /**
     * Get the user configured list of topics, split it and get the configured
     * schema url for that topic.
     *
     * <p>Register that schema with the schema store.
     *
     * @param schemaStore Schema store where schema will be registered
     */
    private void loadSchema(Map<String, String> configProps, SchemaStore schemaStore)
            throws IOException, SchemaNotFoundException {

        String topics = configProps.get(TOPICS_CONFIG);

        List<String> topicList = Splitter.on(',')
                .trimResults()
                .omitEmptyStrings()
                .splitToList(topics);

        for (String topic : topicList) {
            String schemaUrl = getSchemaUrl(configProps, topic)
                    .orElseThrow(() -> new SchemaNotFoundException("Schema url not configured for topic: " + topic));

            loadSchema(schemaStore, topic, schemaUrl);
        }
    }

    /**
     * Register the schema to the provided SchemaStore.
     *
     * @param schemaStore SchemaStore to store the TopicPartition and it's respective Schema
     * @param topic Topic as key for storing the schema
     * @param schemaUrl Schema as the value to be stored in the Schema store
     * @throws IOException Thrown if encounters any error while registering the schema like
     *      malformed URL, stream closed, etc.
     */
    private void loadSchema(SchemaStore schemaStore, String topic, String schemaUrl)
            throws IOException {

        schemaStore.register(topic, schemaUrl);
    }

    /**
     * Returns the configured schema url for the given topic.
     *
     * @param configProps Map of config provided by the user
     * @param topic kafka topic
     * @return Schema URL for the topic if present or null
     */
    private Optional<String> getSchemaUrl(Map<String, String> configProps, String topic) {

        String topicSchemaUrlConfig = topic + TOPIC_SCHEMA_URL_SUFFIX;
        return Optional.ofNullable(
                configProps.get(topicSchemaUrlConfig)
        );
    }

    /**
     * Returns the schema store which stores the map of topic and its configured schema.
     *
     * @return schema store
     */
    public SchemaStore getSchemaStore() {
        return this.schemaStore;
    }

    /**
     * Returns the string representing a part of dir path where it will be stored.
     * Encoded partition is dependent on the partition strategy.
     *
     * <p>For e.g. partition=&lt;kafkaPartition&gt;
     *
     * @param record The record to be stored
     * @return encoded partition
     * @throws JsonProcessingException thrown if encounters any error while parsing
     */
    public String encodePartition(SinkRecord record) throws JsonProcessingException {

        return this.partitioner
                .encodePartition(record);
    }

    /**
     * Generate file name prefixed with full dir path (directory info.) including
     * encoded partition.
     *
     * <p>&lt;prefix&gt;/&lt;kafkaTopic&gt;/&lt;encodedPartition&gt;/&lt;kafkaTopic&gt;
     * +&lt;kafkaPartition&gt;+&lt;startOffset&gt;
     *
     * @param record The record to be stored
     * @param encodedPartition encoded partition
     * @param startingOffset The kafka starting offset
     * @return file name prefixed with its full path
     */
    public String generateFullPath(SinkRecord record, String encodedPartition, long startingOffset) {

        return this.partitioner
                .generateFullPath(record, encodedPartition, startingOffset);
    }

    /**
     * Get a new record writer to write the sink records to the output file from the record writer provider.
     *
     * @param kafkaTopic Kafka topic name, used to retrieve schema for specific topic
     * @param outputFileName Name of the output file where records are written
     * @return RecordWriter for writing sink records to the output file
     */
    public RecordWriter getRecordWriter(String kafkaTopic, String outputFileName) {

        return this.recordWriterProvider
                .getRecordWriter(config, storageManager, outputFileName, kafkaTopic);
    }

    /**
     * Write the record to the dead letter queue with the thrown exception.
     *
     * @param record The faulty record
     * @param e Thrown exception
     */
    public void sendToDeadLetterQueue(SinkRecord record, Exception e) {
        if (this.errantRecordReporter == null) {
            return;
        }
        this.errantRecordReporter.report(record, e);
    }

    /**
     * Get the errant record reporter.
     *
     * @return Errant record reporter
     */
    private ErrantRecordReporter getErrantRecordReporter() {
        return this.sinkTaskContext
                .errantRecordReporter();
    }

    /**
     * Returns the parsed config.
     *
     * @return config
     */
    public AzureBlobSinkConfig getConfig() {
        return this.config;
    }

    /**
     * Returns the storage class to interact with the external storage.
     *
     * @return Storage
     */
    public StorageManager getStorage() {
        return this.storageManager;
    }

    /**
     * Builder method.
     *
     * @param configProps Config properties
     * @return Builder
     */
    public static AzureBlobSinkConnectorContext.Builder builder(Map<String, String> configProps) {
        return new Builder(configProps);
    }

    /**
     * Builder class.
     */
    public static class Builder {

        private StorageManager storageManager;
        private SchemaStore schemaStore;
        private Partitioner partitioner;
        private AzureBlobSinkConfig config;
        private SinkTaskContext sinkTaskContext;
        private RecordWriterProvider recordWriterProvider;

        private final Map<String, String> configProps;

        private Builder(Map<String, String> configProps) {
            this.configProps = configProps;
        }

        /**
         * SchemaStore is used by RecordWriter in downstream.
         *
         * @param schemaStore Instance of SchemaStore
         * @return AzureBlobSinkConnectorContext.Builder Builder class
         */
        public Builder withSchemaStore(SchemaStore schemaStore) {
            this.schemaStore = schemaStore;
            return this;
        }

        /**
         * Will be used to generate encoded partition and full output file path.
         *
         * @param partitioner Instance of Partitioner
         * @return AzureBlobSinkConnectorContext.Builder Builder class
         */
        public Builder withPartitioner(Partitioner partitioner) {
            this.partitioner = partitioner;
            return this;
        }

        /**
         * Will be used to instantiate new objects.
         *
         * @param config AzureBlobSinkConfig
         * @return AzureBlobSinkConnectorContext.Builder Builder class
         */
        public Builder withParsedConfig(AzureBlobSinkConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Will be used to instantiate record writers.
         *
         * @param recordWriterProvider Instance of RecordWriterProvider
         * @return AzureBlobSinkConnectorContext.Builder Builder class
         */
        public Builder withRecordWriterProvider(RecordWriterProvider recordWriterProvider) {
            this.recordWriterProvider = recordWriterProvider;
            return this;
        }

        /**
         * Will be used by downstream objects.
         *
         * @param sinkTaskContext Sink task context
         * @return AzureBlobSinkConnectorContext.Builder Builder class
         */
        public Builder withSinkTaskContext(SinkTaskContext sinkTaskContext) {
            this.sinkTaskContext = sinkTaskContext;
            return this;
        }

        /**
         * Storage class to upload / append data to azure blob storage.
         *
         * @param storageManager Storage
         * @return AzureBlobSinkConnectorContext.Builder Builder class
         */
        public Builder withStorage(StorageManager storageManager) {
            this.storageManager = storageManager;
            return this;
        }

        /**
         * Build and return the new instance of AzureBlobSinkConnectorContext.
         *
         * @return AzureBlobSinkConnectorContext context object
         * @throws IOException If something went wrong while parsing schema
         * @throws SchemaNotFoundException If something went wrong while parsing schema
         */
        public AzureBlobSinkConnectorContext build() throws IOException, SchemaNotFoundException {
            return new AzureBlobSinkConnectorContext(this);
        }
    }
}