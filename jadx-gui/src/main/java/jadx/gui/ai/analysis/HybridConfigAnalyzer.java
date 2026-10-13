package jadx.gui.ai.analysis;

import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import jadx.gui.ai.analysis.FrameworkAnalysis.Kind;

/** Reads actual packaged configuration without external entities or fetching referenced URLs. */
final class HybridConfigAnalyzer {
	private HybridConfigAnalyzer() {
	}

	static void analyze(FrameworkAnalysis result, String source, String text, boolean truncated) {
		FrameworkAnalyzer.resourceConfiguration(result, source, text, truncated,
				"Packaged hybrid configuration read locally; configured plugins and pages are not runtime call chains.");
		if (!source.toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
			return;
		}
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			var parser = factory.newSAXParser();
			parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			parser.parse(new InputSource(new StringReader(text)), new DefaultHandler() {
				private Locator locator;
				private int depth;

				@Override
				public void setDocumentLocator(Locator value) {
					locator = value;
				}

				@Override
				public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
					if (++depth > 64) {
						throw new SAXException("Configuration depth limit");
					}
					String value = local.equals("content") ? attributes.getValue("src")
							: local.equals("feature") ? attributes.getValue("name")
							: local.equals("param") && "android-package".equals(attributes.getValue("name"))
									? attributes.getValue("value")
							: null;
					if (value == null || value.isBlank()) {
						return;
					}
					int line = locator == null ? 1 : locator.getLineNumber();
					int start = 0;
					for (int i = 1; i < line && start < text.length(); i++) {
						int next = text.indexOf('\n', start);
						start = next < 0 ? text.length() : next + 1;
					}
					int tag = text.lastIndexOf('<', Math.min(text.length() - 1,
							start + Math.max(0, locator == null ? 0 : locator.getColumnNumber() - 2)));
					start = Math.max(0, tag);
					int end = text.indexOf('>', start);
					FrameworkAnalyzer.add(result, local.equals("content") ? Kind.PAGES : Kind.NATIVE_BRIDGE, value,
							"Configuration declaration observed; plugin loading and page execution remain unverified.",
							"Locate this configured page or Android plugin class and inspect its actual implementation and entry points.",
							source, text, start, end < 0 ? text.length() : end + 1, truncated);
				}

				@Override
				public void endElement(String uri, String local, String name) {
					depth--;
				}
			});
			result.covered(Kind.PAGES);
			result.covered(Kind.NATIVE_BRIDGE);
		} catch (Exception e) {
			result.gap("configuration_parse_incomplete");
		}
	}
}
