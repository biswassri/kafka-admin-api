package org.bf2.admin.kafka.admin;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.header.Header;
import org.bf2.admin.kafka.admin.handlers.AdminClientFactory;
import org.bf2.admin.kafka.admin.model.Types;
import org.jboss.logging.Logger;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RequestScoped
public class RecordOperations {

    private static final Logger log = Logger.getLogger(RecordOperations.class);

    @Inject
    AdminClientFactory clientFactory;

    public Types.PagedResponse<Types.Record> consumeRecords(String topicName,
                                              Integer partition,
                                              Integer offset,
                                              String timestamp,
                                              Integer limit,
                                              List<String> include) {

        try (Consumer<String, String> consumer = clientFactory.createConsumer(limit)) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topicName);

            if (partitions.isEmpty()) {
                throw new UnknownTopicOrPartitionException("No such topic");
            }

            List<TopicPartition> assignments = partitions.stream()
                .filter(p -> partition == null || partition.equals(p.partition()))
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .collect(Collectors.toList());

            if (assignments.isEmpty()) {
                throw new InvalidPartitionsException(String.format("No such partition for topic %s: %d", topicName, partition));
            }

            consumer.assign(assignments);

            if (timestamp != null) {
                Long tsMillis = stringToTimestamp(timestamp);
                Map<TopicPartition, Long> timestampsToSearch =
                        assignments.stream().collect(Collectors.toMap(Function.identity(), p -> tsMillis));
                consumer.offsetsForTimes(timestampsToSearch)
                    .forEach((p, tsOffset) -> {
                        if (tsOffset != null) {
                            consumer.seek(p, tsOffset.offset());
                        } else {
                            /*
                             * No offset for the time-stamp (future date?), seek to
                             * end and return nothing for this partition.
                             */
                            consumer.seekToEnd(List.of(p));
                        }
                    });
            } else if (offset != null) {
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(assignments);
                assignments.forEach(p -> {
                    if (offset <= endOffsets.get(p)) {
                        consumer.seek(p, offset);
                    } else {
                        /*
                         * Requested offset is beyond the end of the partition,
                         * seek to end and return nothing for this partition.
                         */
                        consumer.seek(p, endOffsets.get(p));
                    }
                });
            }

            var records = consumer.poll(Duration.ofSeconds(2));

            List<Types.Record> items = StreamSupport.stream(records.spliterator(), false)
                .map(rec -> {
                    Types.Record item = new Types.Record();

                    setProperty(Types.Record.PROP_PARTITION, include, rec::partition, item::setPartition);
                    setProperty(Types.Record.PROP_OFFSET, include, rec::offset, item::setOffset);
                    setProperty(Types.Record.PROP_TIMESTAMP, include, () -> timestampToString(rec.timestamp()), item::setTimestamp);
                    setProperty(Types.Record.PROP_TIMESTAMP_TYPE, include, () -> rec.timestampType().name(), item::setTimestampType);
                    setProperty(Types.Record.PROP_KEY, include, rec::key, item::setKey);
                    setProperty(Types.Record.PROP_VALUE, include, rec::value, item::setValue);
                    setProperty(Types.Record.PROP_HEADERS, include,
                            () -> StreamSupport.stream(rec.headers().spliterator(), false)
                                .collect(Collectors.toMap(Header::key, h -> new String(h.value()))),
                            item::setHeaders);

                    return item;
                })
                .collect(Collectors.toList());

            return Types.PagedResponse.forItems(items).result();
        }
    }

    public CompletionStage<Types.Record> produceRecord(String topicName, Types.Record input) {
        String key = input.getKey();
        List<Header> headers = input.getHeaders() != null ? input.getHeaders()
            .entrySet()
            .stream()
            .map(h -> new Header() {
                @Override
                public String key() {
                    return h.getKey();
                }

                @Override
                public byte[] value() {
                    return h.getValue().getBytes();
                }
            })
            .collect(Collectors.toList()) : Collections.emptyList();

        CompletableFuture<Types.Record> promise = new CompletableFuture<>();
        Producer<String, String> producer = clientFactory.createProducer();
        ProducerRecord<String, String> request = new ProducerRecord<>(topicName, input.getPartition(), stringToTimestamp(input.getTimestamp()), key, input.getValue(), headers);

        producer.send(request, (meta, exception) -> {
            if (exception != null) {
                promise.completeExceptionally(exception);
            } else {
                Types.Record result = new Types.Record();
                result.setPartition(meta.partition());
                if (meta.hasOffset()) {
                    result.setOffset(meta.offset());
                }
                if (meta.hasTimestamp()) {
                    result.setTimestamp(timestampToString(meta.timestamp()));
                }
                result.setKey(input.getKey());
                result.setValue(input.getValue());
                result.setHeaders(input.getHeaders());
                promise.complete(result);
            }
        });

        return promise.whenComplete((result, exception) -> {
            try {
                producer.close(Duration.ZERO);
            } catch (Exception e) {
                log.warnf("Exception closing Kafka Producer", e);
            }
        });
    }

    <T> void setProperty(String fieldName, List<String> include, Supplier<T> source, java.util.function.Consumer<T> target) {
        if (include.isEmpty() || include.contains(fieldName)) {
            target.accept(source.get());
        }
    }

    String timestampToString(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toString();
    }

    Long stringToTimestamp(String value) {
        if (value == null) {
            return null;
        }

        return ZonedDateTime.parse(value).toInstant().toEpochMilli();
    }

}
