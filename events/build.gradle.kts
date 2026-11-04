// Plain Java records with no dependencies: the shape of every message that
// crosses Kafka. Both services depend on this module, so a field added here is
// seen by the producer and the consumer in the same commit.
plugins {
    `java-library`
}
