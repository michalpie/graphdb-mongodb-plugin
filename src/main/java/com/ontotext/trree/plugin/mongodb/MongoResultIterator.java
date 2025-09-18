package com.ontotext.trree.plugin.mongodb;

import static com.apicatalog.jsonld.lang.Keywords.*;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.loader.DocumentLoaderOptions;
import com.mongodb.MongoSecurityException;
import com.mongodb.client.*;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationAlternate;
import com.mongodb.client.model.CollationCaseFirst;
import com.mongodb.client.model.CollationMaxVariable;
import com.mongodb.client.model.CollationStrength;
import com.ontotext.rio.jsonld.GraphDBJSONLD11ParserFactory;
import com.ontotext.rio.jsonld.settings.GraphDBJSONLDSettings;
import com.ontotext.trree.sdk.Entities;
import com.ontotext.trree.sdk.Entities.Scope;
import com.ontotext.trree.sdk.PluginException;
import com.ontotext.trree.sdk.StatementIterator;

import org.apache.commons.io.IOUtils;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.*;
import org.eclipse.rdf4j.rio.helpers.ParseErrorLogger;

import jakarta.json.JsonString;
import jakarta.json.JsonStructure;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MongoResultIterator extends StatementIterator {

	private static final String CUSTOM_NODE = "custom";

	private String query, projection, hint, database, collection;
	private Collation collation;
	private List<Document> aggregation = null;
	protected long searchSubject;
	// custom graph id, if not present should equal to indexId
	private long graphId;
	// the id of the index, could be shared among multiple iterators
	private long indexId;
	protected boolean initialized = false;
	protected boolean initializedByEntityIterator = false;
	private boolean searchDone = false;
	private MongoClient client;
	private MongoDatabase db;
	private MongoCollection<Document> coll;
	protected MongoCursor<Document> iter;
	protected Model currentRDF;
	protected Entities entities;
	private MongoDBPlugin plugin;
	private RequestCache cache;

	private final ParserConfig jsonLdParserConfig;
	private final CachingDocumentLoader documentLoader;

	private boolean contextFirst = false;
	private boolean cloned = false;
	private boolean entityIteratorCreated = false;
	private boolean modelIteratorCreated = false;
	protected boolean interrupted = false;
	private boolean closed = false;
	// Indicates that a :find or :aggregate predicate has been seen for this iterator, even if the
	// actual query/aggregation value is not yet bound (e.g. supplied later via BIND). Used to
	// distinguish between "query not yet bound" (defer) and "query never supplied" (error when
	// :entity is evaluated).
	private boolean queryExpected = false;

	public void setQueryExpected() { this.queryExpected = true; }
	public boolean isQueryExpected() { return queryExpected; }
	// if some of the query components are constructed with a function
	// and set using bind the first time they are visited will be null. If we have setter with null
	// then we can expect the value to be set later on, but the original iterator would be closed
	// this property prevents the iterator to be closed the first time if any of the
	// set components are null (query, hint, projection, collation, aggregation)
	private boolean closeable = true;

	// Flag indicating an initialization attempt was deferred because query/aggregation not yet bound
	private boolean pendingInitialization = false;

	private boolean batched = false;
	private boolean batchedLoading = false;
	private int documentsLimit;
	private BatchDocumentStore batchDocumentStore;
	private LongIterator storeIterator;

	// Note: Previous deferred model priming / wrapper logic removed. Model iterator
	// creation is now always eager; late query binding is handled at :find pattern
	// level (see FindBindingIterator in MongoDBPlugin) which triggers initialization
	// once the query literal is available.

  static {
		GraphDBJSONLD11ParserFactory jsonldFactory = new GraphDBJSONLD11ParserFactory();
		RDFParserRegistry.getInstance().add(jsonldFactory);
	}

	public MongoResultIterator(MongoDBPlugin plugin, MongoClient client, String database, String collection, RequestCache cache, long searchsubject) {
		this.cache = cache;
		this.plugin = plugin;
		this.client = client;
		this.database = database;
		this.collection = collection;
		this.searchSubject = searchsubject;

		// use different loader for the parser config for the current session
		// this way we would not accumulate a lot of documents over time
		jsonLdParserConfig = new ParserConfig();
		documentLoader = new CachingDocumentLoader();
		jsonLdParserConfig.set(GraphDBJSONLDSettings.DOCUMENT_LOADER, documentLoader);
	}

	// Indicates we have emitted the initial binding row for the search subject
	private boolean initialBindingEmitted = false;

	@Override
	public boolean next() {
		if (!initialBindingEmitted) {
			// Provide the search subject binding to the engine; actual Mongo initialization
			// will occur lazily when model/entity iterators are consumed.
			subject = searchSubject;
			initialBindingEmitted = true;
			return true;
		} else if (initializedByEntityIterator && !searchDone) {
			// Graph pattern appeared before the search, entity iterator performed the search.
			subject = searchSubject;
			searchDone = true;
			return true;
		}
		return false;
	}

	protected boolean initialize() {
		// Defer if neither query nor aggregation has been provided yet. This allows patterns
		// (e.g. entity/model) that appear before :find/:aggregate to keep the branch alive.
		if (query == null && aggregation == null) {
			pendingInitialization = true;
			return false; // defer initialization
		}

		try {
			if (client != null) {
				db = client.getDatabase(database);
				coll = db.getCollection(collection);

				// If aggregation is used it will take precedence over query + projection
				if (aggregation != null) {
					AggregateIterable<Document> res = cache.getAggregation(coll, database, collection, aggregation);
					if (hint != null) {
						res.hint(Document.parse(hint));
					}
					if (collation != null) {
						res.collation(collation);
					}
					iter = res.iterator();
				} else {
					FindIterable<Document> res = cache.getFind(coll, database, collection, Document.parse(query));
					if (projection != null) {
						res = res.projection(Document.parse(projection));
					}
					if (hint != null) {
						res.hint(Document.parse(hint));
					}
					if (collation != null) {
						res.collation(collation);
					}

					iter = res.iterator();
				}
				initialized = true;
			}
		} catch (MongoSecurityException ex) {
			plugin.getLogger().error("Could not connect to mongo", ex);
			throw new PluginException("Could not connect to MongoDB. Please make sure you are using correct authentication. " + ex.getMessage());
		}
		if (batched) {
			if (iter != null && iter.hasNext()) {
				batchDocumentStore = new BatchDocumentStore();
				loadBatchedData();
				storeIterator = batchDocumentStore.getIterator();
				this.currentRDF = batchDocumentStore.getData();
			}
			return batchDocumentStore.size() > 0;
		}
		return iter != null && iter.hasNext();
	}

	private void loadBatchedData() {
		Model[] data = new Model[1];
		batchedLoading = true;
		try {
			while (hasSolution() && batchDocumentStore.size() < getDocumentsLimit()) {
				long docId = readNextDocument(current -> data[0] = current);
				if (docId != 0) {
					batchDocumentStore.addDocument(docId, data[0]);
				}
			}
		} finally {
			batchedLoading = false;
		}
	}

	@Override
	public void close() {
		if (!closeable) {
			// prevent closing the iterator if not fully configured
			closeable = true;
			return;
		}

		closed = true;
		if (iter != null)
			iter.close();
		iter = null;
		coll = null;
		db = null;
		client = null;

		interrupted = true;
		if (currentRDF != null) {
			currentRDF.clear();
			currentRDF = null;
		}
		initialized = false;
		initializedByEntityIterator = false;

		IOUtils.closeQuietly((Closeable) jsonLdParserConfig.get(GraphDBJSONLDSettings.DOCUMENT_LOADER));

		if (batchDocumentStore != null) {
			batchDocumentStore.clear();
			batchDocumentStore = null;
		}
	}

	public void setQuery(String query) {
		this.query = query;
		closeable &= query != null;
		if (pendingInitialization && !initialized && query != null) {
			initialize();
		}
	}

	public StatementIterator singletonIterator(long predicate, long object) {
		return StatementIterator.create(getSearchSubject(), predicate, object, 0);
	}

	public void setProjection(String projectionString) {
		this.projection = projectionString;
		closeable &= projection != null;
	}

	public StatementIterator createEntityIter(long pred) {
		setEntityIteratorCreated(true);
		return new StatementIterator() {
			boolean initializedE = false;

			@Override
			public boolean next() {
				if (!initializedE) {
					if (!isQuerySet()) {
						// If a query/aggregation was never even declared for this iterator, raise the original
						// contract error. Otherwise (query expected but not yet bound) just defer.
						if (!isQueryExpected()) {
							throw new PluginException("There is no search query for Mongo");
						}
						return false; // waiting for query literal binding
					}
					if (!initialize()) {
						return false; // defensive safeguard
					}
					initializedE = true;
				}
				if (hasSolution()) {
					this.subject = searchSubject;
					this.predicate = pred;
					advance();
					this.object = MongoResultIterator.this.object;
					return true;
				}
				return false;
			}

			@Override
			public void close() {
			}

		};
	}

	protected void advance() {
		if (batched) {
			object = storeIterator.next();
		} else {
			object = readNextDocument(doc -> currentRDF = doc);
		}
	}

	protected long readNextDocument(Consumer<Model> dataAccumulator) {
		Document doc = iter.next();

		if (interrupted) {
			return 0;
		}
		
		String entity = null;
		if (doc.containsKey(GRAPH)) {
			Object item = doc.get(GRAPH);
			Document graphDoc;
			if (item != null) {
				if (item instanceof List<?> listItem) {
					if (!listItem.isEmpty() && listItem.getFirst() instanceof Document document) {
						graphDoc = document;
						entity = graphDoc.getString(ID);
						if (listItem.size() > 1) {
							plugin.getLogger().warn("Multiple graphs in mongo document. Selecting the first one for entity:	" + entity);
						}
					} else {
						plugin.getLogger().warn("Value of @graph must be a valid document in mongo document.");
					}
				} else if (item instanceof Document document) {
					graphDoc = document;
					entity = graphDoc.getString(ID);
				} else {
					plugin.getLogger().warn("@graph must be a document or list of documents in mongo document.");
				}
			}
		}
		if (entity == null) {
			// the document didn't contain @graph node or the node didn't have correct structure
			// or @id has not been found
			entity = doc.getString(ID);
		}
		String docBase = null;
		// if the current document contains a local @ use it to resolve the relative entities
		// otherwise resolve the @base from the given context if present
		if (doc.containsKey(BASE)) {
			Object baseValue = doc.get(BASE);
			if (baseValue instanceof String) {
				docBase = baseValue.toString();
			} else if (baseValue != null) {
				plugin.getLogger().warn("@base must be a string but got: {}", baseValue);
			}
		} else if (doc.containsKey(CONTEXT)) {
			Object ctxValue = doc.get(CONTEXT);
			docBase = resolveDocumentBase(ctxValue);
		}
		// if the @base is not defined at all, use a default in order not to break during json-ld parsing
		docBase = Objects.toString(docBase, "http://base.org");

		try {
			//Relaxed mode Json conversion is needed for canonical MongoDB v2 Json document values
			JsonWriterSettings jsonWriterSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
			EncoderWrapper encoderWrapper = new EncoderWrapper(new DocumentCodec());
			String json = doc.toJson(jsonWriterSettings, encoderWrapper);
			StringReader reader = new StringReader(json);

			currentRDF = Rio.parse(reader, docBase, RDFFormat.JSONLD, jsonLdParserConfig,
							SimpleValueFactory.getInstance(), new ParseErrorLogger());

			Resource v = null;
			if (entity != null) {
				try {
					v = plugin.vf.createIRI(entity);
				} catch (IllegalArgumentException e) {
					Object contextValue = doc.get(CONTEXT);
					String base = resolveDocumentBase(contextValue);
					if (base != null) {
						try {
							v = plugin.vf.createIRI(base, entity);
						} catch (IllegalArgumentException e2) {
							// ignore the exception
						}
					} else {
						// the context is missing, not defined/used or is external one
						// in this case get the subject of any statement and this should be our id
						// it's either fully resolved IRI or a BNode
						Iterator<Statement> it = currentRDF.getStatements(null, null, null).iterator();
						if (it.hasNext()) {
							v = it.next().getSubject();
						} else {
							v = plugin.vf.createBNode();
						}
					}

					if (v == null) {
						throw e;
					}
				}
			} else {
				v = plugin.vf.createBNode();
			}
			long id = entities.resolve(v);
			if (id == 0) {
				id = entities.put(v, Scope.REQUEST);
			}
			Object customNode = doc.get(CUSTOM_NODE);
			if (customNode instanceof Document document) {
				for (Map.Entry<String, Object> val : document.entrySet()) {
					currentRDF.add(v, plugin.vf.createIRI(MongoDBPlugin.NAMESPACE_INST, val.getKey()), plugin.vf.createLiteral(val.getValue().toString()));
				}
			}
			dataAccumulator.accept(currentRDF);
			return id;
		} catch (RDFParseException | UnsupportedRDFormatException | IOException e) {
			iter.close();
			plugin.getLogger().error("Could not parse mongo document", e);
			return 0;
		}
	}

	private String resolveDocumentBase(Object context) {
		return resolveDocumentBase(context, true);
	}

	private String resolveDocumentBase(Object context, boolean allowRemoteContext) {
		if (context instanceof Map<?, ?> contextMap) {
			return Objects.toString(contextMap.get(BASE), null);
		} else if (context instanceof String) {
			if (!allowRemoteContext) {
				plugin.getLogger().error("Attempted to load the remote context '{}' from a remote context", context);
				return null;
			}
			try {
				SimpleValueFactory.getInstance().createIRI(context.toString());
			} catch (IllegalArgumentException e2) {
				plugin.getLogger().warn("Context value must be an absolute URI got: {}", context);
				// not valid IRI so should not even attempt to load the external context
				return null;
			}
			try {
				JsonStructure jsonStructure = ((JsonDocument) documentLoader
						.loadDocument(URI.create(context.toString()), new DocumentLoaderOptions()))
						.getJsonContent()
						.orElse(null);
				if (jsonStructure instanceof Map<?, ?> jsonMap) {
					// When parsing JSON-LD documents using hasmac's library, string values are
					// wrapped inside 'JsonString' objects, which include extra surrounding double quotes
					Object contextValue = removeExtraQuotes(jsonMap.get(CONTEXT));
					// do not allow loading a remote context from a remote context
					// as this could be malicious
					return resolveDocumentBase(contextValue, false);
				}
			} catch (JsonLdError je) {
				// cannot load the remote context
				plugin.getLogger().warn("Could not load external context: {}", je.getMessage());
            }
        } else if (context instanceof Collection) {
			String baseFromRemoteContext = null;
			for (Object ctxItem : (Collection<?>) context) {
				if (ctxItem instanceof Map) {
					// local context
					// if we have overridden base in the local context it's with higher priority
					// so use it directly
					String base = resolveDocumentBase(ctxItem, allowRemoteContext);
					if (base != null) {
						return base;
					}
				} else if (ctxItem instanceof String) {
					// remote context will be used only if there is no base in a local context
					baseFromRemoteContext = resolveDocumentBase(ctxItem, allowRemoteContext);
				} else if (ctxItem != null) {
					plugin.getLogger()
									.warn("Unsupported @context type. Expected document or remote URI, got : {}",
													ctxItem);
				}
			}
			if (baseFromRemoteContext != null) {
				return baseFromRemoteContext;
			}
		}
		if (context != null) {
			plugin.getLogger().warn("Unsupported @context type. Expected document or remote URI, got : {}", context);
		}
		return null;
	}

	protected boolean hasSolution() {
		if (batched && !batchedLoading) {
			return !interrupted && storeIterator != null && storeIterator.hasNext();
		}
		return !interrupted && iter != null && iter.hasNext();
	}

	@SuppressWarnings("unchecked")
	private Object removeExtraQuotes(Object value) {
		if (value instanceof JsonString) {
			// remove surrounding double quotes
			return value.toString().replaceAll("^\"+|\"+$", "");
		} else if (value instanceof Map) {
			Map<String, Object> map = (Map<String, Object>) value;
			return map.entrySet().stream()
					.collect(Collectors.toMap(Map.Entry::getKey, e -> removeExtraQuotes(e.getValue())));
		} else if (value instanceof List) {
			List<Object> list = (List<Object>) value;
			return list.stream()
					.map(this::removeExtraQuotes)
					.collect(Collectors.toList());
		}
		return value;
	}

	public StatementIterator getModelIterator(final long subject, final long predicate, final long object) {
		setModelIteratorCreated(true);
		return createStreamingModelIterator(subject, predicate, object);
	}

	private StatementIterator createStreamingModelIterator(final long subject, final long predicate, final long object) {
		Resource sub = subject == 0 ? null : (Resource) entities.get(subject);
		IRI p = predicate == 0 ? null : (IRI) entities.get(predicate);
		Value o = object == 0 ? null : entities.get(object);
		if (sub == null && batched) {
			sub = (Resource) entities.get(this.object);
			if (sub != null && sub.equals(o)) {
				sub = null;
			}
		}
		Resource s = sub;
		return new StatementIterator() {
			Iterator<Statement> local = null;
			@Override public boolean next() {
				if (currentRDF == null) {
					if (!initialized && !initializedByEntityIterator) {
						initializedByEntityIterator = true;
						if (initialize()) {
							advance();
						} else {
							return false;
						}
					} else if (hasSolution()) {
						advance();
					} else {
						return false;
					}
				}
				if (local == null) {
					Resource localSub = s;
					if (localSub == null && batched) {
						localSub = (Resource) entities.get(MongoResultIterator.this.object);
						if (localSub != null && localSub.equals(o)) {
							localSub = null;
						}
					}
					Collection<Statement> all = currentRDF.filter(localSub, p, o);
					local = all.iterator();
				}
				if (!local.hasNext()) return false;
				Statement st = local.next();
				this.subject = resolveOrPut(st.getSubject());
				this.predicate = resolveOrPut(st.getPredicate());
				this.object = resolveOrPut(st.getObject());
				this.context = MongoResultIterator.this.graphId;
				return true;
			}
			private long resolveOrPut(Value v) {
				long id = entities.resolve(v);
				if (id == 0) id = entities.put(v, Scope.REQUEST);
				return id;
			}
			@Override public void close() {}
		};
	}


	public void setAggregation(List<Document> aggregation) {
		this.aggregation = aggregation;
		closeable &= aggregation != null;
		if (pendingInitialization && !initialized && aggregation != null) {
			initialize();
		}
	}

	public void setGraphId(long graphId) {
		this.graphId = graphId;
	}

	public long getQueryIdentifier() {
		long gid = getGraphId();
		return gid != 0 ? gid : getIndexId();
	}

	public long getGraphId() {
		return graphId;
	}

	public long getIndexId() {
		return indexId;
	}

	public void setIndexId(long indexId) {
		this.indexId = indexId;
	}

	public void setDocumentsLimit(int documentsLimit) {
		if (documentsLimit > 0) {
			batched = true;
		}
		this.documentsLimit = documentsLimit;
	}

	public int getDocumentsLimit() {
		return documentsLimit;
	}

	public long getSearchSubject() {
		return searchSubject;
	}

	public void setEntities(Entities entities) {
		this.entities = entities;
	}

	public void setHint(String hintString) {
		this.hint = hintString;
		closeable &= hint != null;
	}

	public void setCollation(String collationString){
		closeable &= collationString != null;
		if (collationString != null) {
			setCollation(createCollation(collationString));
		}
	}

	public Collation getCollation() {
		return collation;
	}

	private Collation createCollation(String collationString) {
		Document doc = Document.parse(collationString);
		Collation.Builder builder = Collation.builder();
		builder.locale(doc.getString("locale"));

		if (doc.containsKey("caseLevel")){
			builder.caseLevel(doc.getBoolean("caseLevel"));
		}

		if (doc.containsKey("caseFirst")){
			builder.collationCaseFirst(CollationCaseFirst.fromString(doc.getString("caseFirst")));
		}

		if (doc.containsKey("strength")){
			builder.collationStrength(CollationStrength.fromInt(doc.getInteger("strength")));
		}

		if (doc.containsKey("numericOrdering")) {
			builder.numericOrdering(doc.getBoolean("numericOrdering"));
		}

		if (doc.containsKey("alternate")) {
			builder.collationAlternate(
					CollationAlternate.fromString(doc.getString("alternate")));
		}

		if (doc.containsKey("maxVariable")) {
			builder.collationMaxVariable(
					CollationMaxVariable.fromString(doc.getString("maxVariable")));
		}

		if (doc.containsKey("normalization")) {
			builder.normalization(doc.getBoolean("normalization"));
		}

		if (doc.containsKey("backwards")) {
			builder.backwards(doc.getBoolean("backwards"));
		}

		return builder.build();
	}

	public boolean isQuerySet() {
		return query != null || aggregation != null;
	}

	public boolean isContextFirst() {
		return contextFirst;
	}

	public void setContextFirst(boolean contextFirst) {
		this.contextFirst = contextFirst;
	}

	public void setModelIteratorCreated(boolean modelIteratorCreated) {
		this.modelIteratorCreated = modelIteratorCreated;
	}

	public void setEntityIteratorCreated(boolean entityIteratorCreated) {
		this.entityIteratorCreated = entityIteratorCreated;
	}

	public boolean isEntityIteratorCreated() {
		return entityIteratorCreated;
	}

	public boolean isModelIteratorCreated() {
		return modelIteratorCreated;
	}

	public String getQuery() {
		return query;
	}

	public String getProjection() {
		return projection;
	}

	public String getHint() {
		return hint;
	}

	public void setCollation(Collation collation) {
		this.collation = collation;
	}

	public List<Document> getAggregation() {
		return aggregation;
	}

	public boolean isClosed() {
		return closed;
	}

	protected void reset() {
		query = null;
		aggregation = null;
		projection = null;
		hint = null;
		modelIteratorCreated = false;
		entityIteratorCreated = false;
		if (batched) {
			batchDocumentStore.clear();
		}
	}

	public boolean isCloned() {
		return cloned;
	}

	public void setCloned(boolean cloned) {
		this.cloned = cloned;
	}
}
