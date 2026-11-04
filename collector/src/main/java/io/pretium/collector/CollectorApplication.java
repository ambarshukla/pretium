package io.pretium.collector;

import io.pretium.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CollectorApplication {

  public static void main(String[] args) {
    SpringApplication.run(CollectorApplication.class, args);
  }

  // The producer declares the topics it writes. The broker has automatic topic
  // creation switched off, so a misspelt topic fails loudly instead of quietly
  // creating an empty one.
  @Bean
  NewTopic collectionRuns() {
    return TopicBuilder.name(Topics.COLLECTION_RUNS).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic referenceRates() {
    return TopicBuilder.name(Topics.REFERENCE_RATES).partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic providerQuotes() {
    return TopicBuilder.name(Topics.PROVIDER_QUOTES).partitions(3).replicas(1).build();
  }
}
