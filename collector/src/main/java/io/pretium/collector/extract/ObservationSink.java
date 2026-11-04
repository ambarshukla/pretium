package io.pretium.collector.extract;

import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;

/**
 * Where an extractor sends what it reads. Extractors stream into a sink rather than returning a
 * list: the remittance dataset is several hundred thousand records, and holding them all in memory
 * would set the service's heap size for no benefit.
 */
public interface ObservationSink {

  void rate(ReferenceRateObserved rate);

  void quote(ProviderQuoteObserved quote);
}
