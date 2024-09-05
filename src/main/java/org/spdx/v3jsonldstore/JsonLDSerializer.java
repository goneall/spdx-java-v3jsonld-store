/**
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) 2024 Source Auditor Inc.
 */
package org.spdx.v3jsonldstore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.core.CoreModelObject;
import org.spdx.core.IndividualUriValue;
import org.spdx.core.InvalidSPDXAnalysisException;
import org.spdx.core.ModelRegistry;
import org.spdx.core.TypedValue;
import org.spdx.library.ModelCopyManager;
import org.spdx.library.SpdxModelFactory;
import org.spdx.library.model.v2.license.AnyLicenseInfo;
import org.spdx.library.model.v3_0_1.SpdxConstantsV3;
import org.spdx.library.model.v3_0_1.core.CreationInfo;
import org.spdx.library.model.v3_0_1.core.Element;
import org.spdx.library.model.v3_0_1.core.SpdxDocument;
import org.spdx.storage.IModelStore;
import org.spdx.storage.IModelStore.IModelStoreLock;
import org.spdx.storage.IModelStore.IdType;
import org.spdx.storage.PropertyDescriptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import net.jimblackler.jsonschemafriend.GenerationException;

/**
 * @author Gary O'Neall
 *
 * Serializer to serialize a model store containing SPDX Spec version 3 elements
 * 
 * The <code>serialize()</code> method will serialize the <code>@graph</code> for all SPDX elements
 * stored in the model store.
 * 
 * The <code>serialize(SpdxElement element)</code> will serialize a single element
 * 
 */
public class JsonLDSerializer {
	
	static final Logger logger = LoggerFactory.getLogger(JsonLDSerializer.class);
	
	static final Comparator<JsonNode> NODE_COMPARATOR = new Comparator<JsonNode>() {

		@Override
		public int compare(JsonNode arg0, JsonNode arg1) {
			if (Objects.isNull(arg0)) {
				return Objects.isNull(arg1) ? 0 : 1;
			}
			if (Objects.isNull(arg1)) {
				return -1;
			}
			if (arg0.isTextual()) {
				return arg1.isTextual() ? arg0.asText().compareTo(arg1.asText()) : 1;
			} else if (arg0.isObject()) {
				return arg1.isObject() ? compareObject(arg0, arg1) : 1;
			} else if (arg0.isArray()) {
				if (!arg1.isArray()) {
					return 1;
				}
				if (arg0.size() > arg1.size()) {
					return 1;
				} else if (arg0.size() < arg1.size()) {
					return -1;
				} else {
					List<JsonNode> list0 = new ArrayList<>();
					arg0.spliterator().forEachRemaining(list0::add);
					list0.sort(NODE_COMPARATOR);
					List<JsonNode> list1 = new ArrayList<>();
					arg1.spliterator().forEachRemaining(list1::add);
					list1.sort(NODE_COMPARATOR);
					for (int i = 0; i < list0.size(); i++) {
						int retval = compare(list0.get(i), list1.get(i));
						if (retval != 0) {
							return retval;
						}
					}
					return 0;
				}
			} else {
				return Integer.compare(arg0.hashCode(), arg1.hashCode());
			}
		}

		private int compareObject(JsonNode arg0, JsonNode arg1) {
			if (!arg1.isObject()) {
				return 1;
			}
			JsonNode spdxId0 = arg0.get(JsonLDDeserializer.SPDX_ID_PROP);
			if (Objects.nonNull(spdxId0)) {
				JsonNode spdxId1 = arg1.get(JsonLDDeserializer.SPDX_ID_PROP);
				if (Objects.isNull(spdxId1)) {
					return 1;
				}
				return arg0.asText().compareTo(arg1.asText());
			}
			
			//TODO: Add any special classes for sorting other than by fields
			// If no SPDX ID, sort by properties
			List<String> fieldNames = new ArrayList<>();
			arg0.fieldNames().forEachRemaining(fieldNames::add);
			Collections.sort(fieldNames);
			int retval = 0;
			for (String fieldName:fieldNames) {
				JsonNode value0 = arg0.get(fieldName);
				JsonNode value1 = arg1.get(fieldName);
				retval = compare(value0, value1);
				if (retval != 0) {
					return retval;
				}
			}
			return retval;
		}
		
	};

	private static final String GENERATED_SERIALIZED_ID_PREFIX = "https://generated-prefix/";

	private static final String CONTEXT_URI = "https://spdx.org/rdf/%s/spdx-context.jsonld";

	private static final String NON_URI_WARNING = "SPDX element has a non-URI ID: {}.  Converting to URI {}.";

	private static final String CONTEXT_PROP = "@context";
	
	private IModelStore modelStore;
	private ObjectMapper jsonMapper;
	private boolean pretty;
	private String specVersion;
	private JsonLDSchema jsonLDSchema;
	private boolean useExternalListedElements;

	/**
	 * @param jsonMapper mapper to use for serialization
	 * @param pretty true if the format is to be more verbose
	 * @param useExternalListedElements if true, don't serialize any listed licenses or exceptions - treat them as external 
	 * @param specVersion SemVer representation of the SPDX spec version
	 * @param modelStore store where the SPDX elements are stored
	 * @throws GenerationException  if the JSON schema is not found or is not valid
	 */
	public JsonLDSerializer(ObjectMapper jsonMapper, boolean pretty, boolean useExternalListedElements, String specVersion,
			IModelStore modelStore) throws GenerationException {
		Objects.requireNonNull(jsonMapper, "JSON Mapper is a required field");
		Objects.requireNonNull(modelStore, "Model store is a required field");
		Objects.requireNonNull(specVersion, "Spec version store is a required field");
		this.jsonMapper = jsonMapper;
		this.pretty = pretty;
		this.modelStore = modelStore;
		this.specVersion = specVersion;
		this.useExternalListedElements = useExternalListedElements;
		jsonLDSchema = new JsonLDSchema(String.format("schema-v%s.json",  specVersion),
				String.format("spdx-context-v%s.jsonld",  specVersion),
				String.format("spdx-model-v%s.jsonld",  specVersion));
	}

	/**
	 * @param objectToSerialize optional SPDX Document or single element to serialize 
	 * @return the root node of the JSON serialization
	 * @throws InvalidSPDXAnalysisException on errors retrieveing the information for serialization
	 */
	public JsonNode serialize(@Nullable CoreModelObject objectToSerialize) throws InvalidSPDXAnalysisException {
		if (Objects.isNull(objectToSerialize)) {
			return serializeAllObjects();
		} else if (objectToSerialize instanceof SpdxDocument) {
			return serializeSpdxDocument((SpdxDocument)objectToSerialize);
		} else if (objectToSerialize instanceof Element) {
			return serializeElement((Element)objectToSerialize);
		} else {
			logger.error("Unsupported type to serialize: {}", objectToSerialize.getClass());
			throw new InvalidSPDXAnalysisException("Unsupported type to serialize: "+objectToSerialize.getClass());
		}
		
	}

	/**
	 * Serialize SPDX document metadata and ALL elements listed in the root + all elements listed in the elements list
	 * all references to SPDX elements not in the root or elements lists will be externa
	 * @param spdxDocument SPDX document to utilize
	 * @return the root node of the JSON serialization
	 * @throws InvalidSPDXAnalysisException on errors retrieveing the information for serialization
	 */
	private JsonNode serializeSpdxDocument(SpdxDocument spdxDocument) throws InvalidSPDXAnalysisException {
		ObjectNode root = jsonMapper.createObjectNode();
		root.put(CONTEXT_PROP, String.format(CONTEXT_URI, specVersion));
		
		Map<String, String> idToSerializedId = new HashMap<>();
		List<JsonNode> graph = new ArrayList<>();
		Set<Element> elementsToCopy = new HashSet<>();
		IModelStoreLock lock = modelStore.enterCriticalSection(false);
		try {
			// Collect all the elements we want to copy
			spdxDocument.getRootElements().forEach(elementsToCopy::add);
			spdxDocument.getElements().forEach(elementsToCopy::add);
			// collect all the creation infos
			Set<CreationInfo> creationInfos = new HashSet<>();
			for (Element element:elementsToCopy) {
				creationInfos.add(element.getCreationInfo());
			}
			int creationIndex = 0;
			for (CreationInfo creationInfo:creationInfos) {
				String serializedId = "_:creationInfo_" + creationIndex++;
				idToSerializedId.put(creationInfo.getObjectUri(), serializedId);
				graph.add(modelObjectToJsonNode(creationInfo, serializedId, idToSerializedId));
			}
			// Serialize only what we need of the SPDX document
			String documentSpdxId = spdxDocument.getObjectUri();
			if (modelStore.isAnon(documentSpdxId)) {
				String anonId = documentSpdxId;
				documentSpdxId = GENERATED_SERIALIZED_ID_PREFIX + UUID.randomUUID() + "#" + modelStore.getNextId(IdType.SpdxId);
				idToSerializedId.put(anonId, documentSpdxId);
				logger.warn(NON_URI_WARNING, spdxDocument.getObjectUri(), documentSpdxId);
			}
			graph.add(spdxDocumentToJsonNode(spdxDocument, documentSpdxId, idToSerializedId));
			for (String type:jsonLDSchema.getElementTypes()) {
				for (Element element:elementsToCopy) {
					if (type.equals(element.getType())) {
						String serializedId = element.getObjectUri();
						if (modelStore.isAnon(serializedId)) {
							String anonId = serializedId;
							serializedId = GENERATED_SERIALIZED_ID_PREFIX + UUID.randomUUID() + "#" + modelStore.getNextId(IdType.SpdxId);
							idToSerializedId.put(anonId, serializedId);
							logger.warn(NON_URI_WARNING, element.getObjectUri(), serializedId);
						}
						if (!(useExternalListedElements && element.getObjectUri().startsWith(SpdxConstantsV3.SPDX_LISTED_LICENSE_NAMESPACE))) {
							graph.add(modelObjectToJsonNode(element, serializedId, idToSerializedId));
						}
					}
				}
			}
			graph.sort(NODE_COMPARATOR);
			ArrayNode graphNodes = jsonMapper.createArrayNode();
			graphNodes.addAll(graph);
			root.set("@graph", graphNodes);
			return root;
		} finally {
			modelStore.leaveCriticalSection(lock);
		}
	}

	/**
	 * Serialize on the required portions of the SPDX document
	 * @param spdxDocument SPDX document to serialize
	 * @param serializedId ID used in the serialization
	 * @param idToSerializedId partial Map of IDs in the modelStore to the IDs used in the serialization
	 * @return a JSON node representation of the spdxDocument
	 * @throws InvalidSPDXAnalysisException on any SPDX related error
	 */
	private JsonNode spdxDocumentToJsonNode(SpdxDocument spdxDocument,
			String serializedId, Map<String, String> idToSerializedId) throws InvalidSPDXAnalysisException {
		ObjectNode retval = jsonMapper.createObjectNode();
		retval.set(JsonLDDeserializer.SPDX_ID_PROP, new TextNode(serializedId));
		retval.set("type", new TextNode(typeToJsonType(SpdxConstantsV3.CORE_SPDX_DOCUMENT)));
		for (PropertyDescriptor prop:spdxDocument.getPropertyValueDescriptors()) {
			if (SpdxConstantsV3.PROP_ELEMENT.equals(prop)) {
				// skip the elements property - it will in the elements in the graph
			} else if (SpdxConstantsV3.PROP_NAMESPACE_MAP.equals(prop)) {
				// TODO: Add this to the context in the future once it is supported in the schema
			} else {
				if (spdxDocument.getModelStore().isCollectionProperty(spdxDocument.getObjectUri(), prop)) {
					ArrayNode an = jsonMapper.createArrayNode();
					Iterator<Object> iter = spdxDocument.getModelStore().listValues(spdxDocument.getObjectUri(), prop);
					while (iter.hasNext()) {
						an.add(objectToJsonNode(iter.next(), spdxDocument.getModelStore(), idToSerializedId));
					}
					retval.set(propertyToJsonLdPropName(prop), an);
				} else {
					Optional<Object> val = spdxDocument.getModelStore().getValue(spdxDocument.getObjectUri(), prop);
					if (val.isPresent()) {
						retval.set(propertyToJsonLdPropName(prop), objectToJsonNode(val.get(), spdxDocument.getModelStore(), idToSerializedId));
					}
				}
			}
		}
		return retval;
	}

	/**
	 * Serializes a single SPDX element - all references to other elements will be external element references
	 * @param objectToSerialize object to serialize
	 * @return the root of the serialized form of the objectToSerialize
	 * @throws InvalidSPDXAnalysisException 
	 */
	private JsonNode serializeElement(Element objectToSerialize) throws InvalidSPDXAnalysisException {
		ObjectNode root = jsonMapper.createObjectNode();
		root.put(CONTEXT_PROP, String.format(CONTEXT_URI, specVersion));
		Map<String, String> idToSerializedId = new HashMap<>();
		// collect all the creation infos
		List<JsonNode> graph = new ArrayList<>();
		IModelStoreLock lock = modelStore.enterCriticalSection(true);
		try {
			String serializedId = objectToSerialize.getObjectUri();
			if (modelStore.isAnon(serializedId)) {
				String anonId = serializedId;
				serializedId = GENERATED_SERIALIZED_ID_PREFIX + UUID.randomUUID() + "#" + modelStore.getNextId(IdType.SpdxId);
				idToSerializedId.put(anonId, serializedId);
				logger.warn(NON_URI_WARNING, objectToSerialize.getObjectUri(), serializedId);
			}
			if (!(useExternalListedElements && objectToSerialize.getObjectUri().startsWith(SpdxConstantsV3.SPDX_LISTED_LICENSE_NAMESPACE))) {
				graph.add(modelObjectToJsonNode(objectToSerialize, serializedId, idToSerializedId));
			}
			graph.sort(NODE_COMPARATOR);
			ArrayNode graphNodes = jsonMapper.createArrayNode();
			graphNodes.addAll(graph);
			root.set("@graph", graphNodes);
			return root;
		} finally {
			modelStore.leaveCriticalSection(lock);
		}
	}

	/**
	 * Serialize all the objects stored in the model store
	 * @return the root node of the JSON serialization
	 * @throws InvalidSPDXAnalysisException on errors retrieveing the information for serialization
	 */
	private JsonNode serializeAllObjects() throws InvalidSPDXAnalysisException {
		ObjectNode root = jsonMapper.createObjectNode();
		root.put(CONTEXT_PROP, String.format(CONTEXT_URI, specVersion));
		
		Map<String, String> idToSerializedId = new HashMap<>();
		ModelCopyManager copyManager = new ModelCopyManager();
		List<JsonNode> graph = new ArrayList<>();
		IModelStoreLock lock = modelStore.enterCriticalSection(true);
		try {
			// collect all the creation infos
			@SuppressWarnings("unchecked")
			List<CreationInfo> allCreationInfos = (List<CreationInfo>) SpdxModelFactory.getSpdxObjects(modelStore, copyManager, 
					SpdxConstantsV3.CORE_CREATION_INFO, null, null).collect(Collectors.toList());
			
			for (int i = 0; i < allCreationInfos.size(); i++) {
				CreationInfo creationInfo = allCreationInfos.get(i);
				String serializedId = "_:creationInfo_" + i;
				idToSerializedId.put(creationInfo.getObjectUri(), serializedId);
				graph.add(modelObjectToJsonNode(creationInfo, serializedId, idToSerializedId));
			}
			for (String type:jsonLDSchema.getElementTypes()) {
				@SuppressWarnings("unchecked")
				List<Element> elements = (List<Element>) SpdxModelFactory.getSpdxObjects(modelStore, copyManager, 
						type, null, null).collect(Collectors.toList());
				for (Element element:elements) {
					String serializedId = element.getObjectUri();
					if (modelStore.isAnon(serializedId)) {
						String anonId = serializedId;
						serializedId = GENERATED_SERIALIZED_ID_PREFIX + UUID.randomUUID() + "#" + modelStore.getNextId(IdType.SpdxId);
						idToSerializedId.put(anonId, serializedId);
						logger.warn(NON_URI_WARNING, element.getObjectUri(), serializedId);
					}
					if (!(useExternalListedElements && element.getObjectUri().startsWith(SpdxConstantsV3.SPDX_LISTED_LICENSE_NAMESPACE))) {
						graph.add(modelObjectToJsonNode(element, serializedId, idToSerializedId));
					}
				}
			}
			graph.sort(NODE_COMPARATOR);
			ArrayNode graphNodes = jsonMapper.createArrayNode();
			graphNodes.addAll(graph);
			root.set("@graph", graphNodes);
			return root;
		} finally {
			modelStore.leaveCriticalSection(lock);
		}
	}

	/**
	 * @param modelObject model object to serialize
	 * @param serializedId ID used in the serialization
	 * @param idToSerializedId partial Map of IDs in the modelStore to the IDs used in the serialization
	 * @return a JSON node representation of the modelObject
	 * @throws InvalidSPDXAnalysisException on any SPDX related error
	 */
	private JsonNode modelObjectToJsonNode(CoreModelObject modelObject,
			String serializedId,
			Map<String, String> idToSerializedId) throws InvalidSPDXAnalysisException {
		ObjectNode retval = jsonMapper.createObjectNode();
		retval.set(modelObject instanceof Element ? JsonLDDeserializer.SPDX_ID_PROP : "@id", new TextNode(serializedId));
		retval.set("type", new TextNode(typeToJsonType(modelObject.getType())));
		for (PropertyDescriptor prop:modelObject.getPropertyValueDescriptors()) {
			if (modelObject.getModelStore().isCollectionProperty(modelObject.getObjectUri(), prop)) {
				ArrayNode an = jsonMapper.createArrayNode();
				Iterator<Object> iter = modelObject.getModelStore().listValues(modelObject.getObjectUri(), prop);
				while (iter.hasNext()) {
					an.add(objectToJsonNode(iter.next(), modelObject.getModelStore(), idToSerializedId));
				}
				retval.set(propertyToJsonLdPropName(prop), an);
			} else {
				Optional<Object> val = modelObject.getModelStore().getValue(modelObject.getObjectUri(), prop);
				if (val.isPresent()) {
					retval.set(propertyToJsonLdPropName(prop), objectToJsonNode(val.get(), modelObject.getModelStore(), idToSerializedId));
				}
			}
		}
		return retval;
	}

	/**
	 * @param prop property
	 * @return JSON LD property name per the SPDX 3.X JSON LD spec
	 */
	private String propertyToJsonLdPropName(PropertyDescriptor prop) {
		String profile = prop.getNameSpace().substring(0, prop.getNameSpace().length()-1);
		profile = profile.substring(profile.lastIndexOf('/') + 1);
		if ("Core".equals(profile)) {
			return prop.getName();
		} else {
			return profile.toLowerCase() + "_" + prop.getName();
		}
	}

	/**
	 * @param object object to translate to a JSON node
	 * @param fromModelStore modelStore to retrieve the property information from
	 * @param idToSerializedId partial Map of IDs in the modelStore to the IDs used in the serialization
	 * @return object converted to a JSON node based on the SPDX 3.X schema
	 * @throws InvalidSPDXAnalysisException 
	 */
	private JsonNode objectToJsonNode(Object object, IModelStore fromModelStore, Map<String, String> idToSerializedId) throws InvalidSPDXAnalysisException {
		if (object instanceof TypedValue) {
			return typedValueToJsonNode((TypedValue)object, fromModelStore, idToSerializedId);
		} else if (object instanceof String) {
			return new TextNode((String)object);
		} else if (object instanceof Boolean) {
			return ((Boolean)object) ? BooleanNode.TRUE : BooleanNode.FALSE;
		} else if (object instanceof Integer) {
			return new IntNode((Integer)object);
		} else if (object instanceof IndividualUriValue) {
			// it's an Enum, Individual or external element
			String individualUri = ((IndividualUriValue)object).getIndividualURI();
			Enum<?> spdxEnum = SpdxModelFactory.uriToEnum(individualUri, specVersion);
			if (Objects.nonNull(spdxEnum)) {
				String enumName = individualUri.substring(individualUri.lastIndexOf('/') + 1);
				return new TextNode(enumName);
			} else {
				return new TextNode(individualUri); // should work for both individuals and external referenced SPDX elements
			}
		} else {
			throw new InvalidSPDXAnalysisException("Unknown class for object to json node: "+object.getClass());
		}
	}

	/**
	 * @param tv typed value to translate to a JSON node
	 * @param fromModelStore modelStore to retrieve the property information from
	 * @param idToSerializedId partial Map of IDs in the modelStore to the IDs used in the serialization
	 * @return a JSON node representation of a typed value based on the object type and SPDX 3.X serialization spec
	 * @throws InvalidSPDXAnalysisException on errors retrieving model store information
	 */
	private JsonNode typedValueToJsonNode(TypedValue tv, IModelStore fromModelStore, Map<String, String> idToSerializedId) throws InvalidSPDXAnalysisException {
		if (jsonLDSchema.getElementTypes().contains(tv.getType())) {
			// Just return the object URI since the element will be in the @graph
			return new TextNode(idToSerializedId.getOrDefault(tv.getObjectUri(), tv.getObjectUri()));
		} else if (SpdxConstantsV3.CORE_CREATION_INFO.equals(tv.getType()) && idToSerializedId.containsKey(tv.getObjectUri()))  {
			return new TextNode (idToSerializedId.getOrDefault(tv.getObjectUri(), tv.getObjectUri()));
		} else if (pretty && jsonLDSchema.getAnyLicenseInfoTypes().contains(tv.getType())) {
			AnyLicenseInfo licenseInfo = (AnyLicenseInfo)ModelRegistry.getModelRegistry().inflateModelObject(fromModelStore, tv.getObjectUri(), tv.getType(), new ModelCopyManager(), tv.getSpecVersion(), false, "");
			return new TextNode(licenseInfo.toString());
		} else {
			// we should inline to the object
			return inlinedJsonNode(tv, fromModelStore, idToSerializedId);
		}
	}
	
	/**
	 * @param tv typed value to translate to a JSON node
	 * @param fromModelStore modelStore to retrieve the property information from
	 * @param idToSerializedId partial Map of IDs in the modelStore to the IDs used in the serialization
	 * @return a JSON node representation of a typed value object with inlined property values
	 * @throws InvalidSPDXAnalysisException on errors retrieving model store information
	 */
	private JsonNode inlinedJsonNode(TypedValue tv, IModelStore fromModelStore,
			Map<String, String> idToSerializedId) throws InvalidSPDXAnalysisException {
		ObjectNode retval = jsonMapper.createObjectNode();
		retval.set("type", new TextNode(typeToJsonType(tv.getType())));
		for (PropertyDescriptor prop:fromModelStore.getPropertyValueDescriptors(tv.getObjectUri())) {
			if (fromModelStore.isCollectionProperty(tv.getObjectUri(), prop)) {
				ArrayNode an = jsonMapper.createArrayNode();
				Iterator<Object> iter = fromModelStore.listValues(tv.getObjectUri(), prop);
				while (iter.hasNext()) {
					an.add(objectToJsonNode(iter.next(), fromModelStore, idToSerializedId));
				}
				retval.set(propertyToJsonLdPropName(prop), an);
			} else {
				Optional<Object> val = fromModelStore.getValue(tv.getObjectUri(), prop);
				if (val.isPresent()) {
					retval.set(propertyToJsonLdPropName(prop), objectToJsonNode(val.get(), fromModelStore, idToSerializedId));
				}
			}
		}
		return retval;
	}

	/**
	 * @param type model type
	 * @return the JSON representation of the type
	 */
	private String typeToJsonType(String type) {
		String[] parts = type.split("\\.");
		if ("Core".equals(parts[0])) {
			return JsonLDSchema.REVERSE_JAVA_WORDS.getOrDefault(parts[1], parts[1]);
		} else {
			return parts[0].toLowerCase() + "_" + JsonLDSchema.REVERSE_JAVA_WORDS.getOrDefault(parts[1], parts[1]);
		}
	}

	/**
	 * @return JSON LD Schema
	 */
	public JsonLDSchema getSchema() {
		return this.jsonLDSchema;
	}
}
