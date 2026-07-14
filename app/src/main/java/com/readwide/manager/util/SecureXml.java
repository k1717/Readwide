package com.readwide.manager.util;

import androidx.annotation.NonNull;

import org.xml.sax.InputSource;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Creates DOM parsers for untrusted document/archive metadata. */
public final class SecureXml {
    private static final String DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";
    private static final String ACCESS_EXTERNAL_DTD =
            "http://javax.xml.XMLConstants/property/accessExternalDTD";
    private static final String ACCESS_EXTERNAL_SCHEMA =
            "http://javax.xml.XMLConstants/property/accessExternalSchema";

    private SecureXml() {}

    /**
     * Feature availability varies between Android and desktop parsers. The entity
     * resolver is therefore installed as a final enforcement layer even when one
     * of the factory hardening flags is unavailable.
     */
    @NonNull
    public static DocumentBuilder newDocumentBuilder(boolean namespaceAware) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setExpandEntityReferences(false);
        setFeatureIfSupported(factory, DISALLOW_DOCTYPE, true);
        setFeatureIfSupported(factory, EXTERNAL_GENERAL_ENTITIES, false);
        setFeatureIfSupported(factory, EXTERNAL_PARAMETER_ENTITIES, false);
        setFeatureIfSupported(factory, LOAD_EXTERNAL_DTD, false);
        setAttributeIfSupported(factory, ACCESS_EXTERNAL_DTD, "");
        setAttributeIfSupported(factory, ACCESS_EXTERNAL_SCHEMA, "");
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
            // Some Android DOM factories do not implement XInclude configuration.
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new InputSource(new StringReader("")));
        return builder;
    }

    private static void setFeatureIfSupported(@NonNull DocumentBuilderFactory factory,
                                              @NonNull String feature,
                                              boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception | LinkageError ignored) {
            // The resolver still blocks every external lookup.
        }
    }

    private static void setAttributeIfSupported(@NonNull DocumentBuilderFactory factory,
                                                @NonNull String attribute,
                                                @NonNull Object value) {
        try {
            factory.setAttribute(attribute, value);
        } catch (IllegalArgumentException | LinkageError ignored) {
            // Older Android factories do not expose JAXP access restrictions.
        }
    }
}
