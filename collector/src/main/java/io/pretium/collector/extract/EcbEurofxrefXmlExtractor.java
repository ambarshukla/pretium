package io.pretium.collector.extract;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the ECB's {@code eurofxref} XML: a {@code Cube time="..."} element per day containing a
 * {@code Cube currency="..." rate="..."} element per currency, all quoted against the euro.
 */
@Component
public class EcbEurofxrefXmlExtractor implements Extractor {

  @Override
  public SourceSpec.Format format() {
    return SourceSpec.Format.ECB_EUROFXREF_XML;
  }

  @Override
  public String extension() {
    return "xml";
  }

  @Override
  public int extract(SourceSpec spec, Path file, Context context, ObservationSink sink)
      throws ExtractionException {
    Document doc = parse(file);
    NodeList cubes = doc.getElementsByTagNameNS("*", "Cube");
    int count = 0;
    for (int i = 0; i < cubes.getLength(); i++) {
      Element day = (Element) cubes.item(i);
      if (!day.hasAttribute("time")) {
        continue;
      }
      LocalDate date;
      try {
        date = LocalDate.parse(day.getAttribute("time"));
      } catch (DateTimeParseException e) {
        throw new ExtractionException(
            "unparseable time attribute '" + day.getAttribute("time") + "'", e);
      }
      NodeList rates = day.getElementsByTagNameNS("*", "Cube");
      for (int j = 0; j < rates.getLength(); j++) {
        Element rate = (Element) rates.item(j);
        if (!rate.hasAttribute("currency")) {
          continue;
        }
        sink.rate(
            new ReferenceRateObserved(
                context.runId(),
                spec.id(),
                "EUR",
                rate.getAttribute("currency"),
                date,
                Numbers.decimal(rate.getAttribute("rate")),
                context.evidenceSha256()));
        count++;
      }
    }
    if (count == 0) {
      throw new ExtractionException("no Cube elements with time, currency and rate attributes");
    }
    return count;
  }

  /** A parser with external entities and DTDs switched off: never trust a downloaded file. */
  static Document parse(Path file) throws ExtractionException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(file.toFile());
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new ExtractionException("not well-formed XML: " + e.getMessage(), e);
    }
  }
}
