/**
 * This file is hereby placed into the Public Domain. This means anyone is
 * free to do whatever they wish with this file.
 */
package mil.nga.giat.data.elasticsearch;

import static org.opengis.filter.sort.SortOrder.ASCENDING;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.geotools.data.FeatureReader;
import org.geotools.data.FilteringFeatureReader;
import org.geotools.data.Query;
import org.geotools.data.store.ContentEntry;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.sort.SortBy;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

/**
 * Provides access to a specific type within the Elasticsearch index described
 * by the associated data store.
 *
 */
@SuppressWarnings("unchecked")
public class ElasticFeatureSource extends ContentFeatureSource {

	private final static Logger LOGGER = Logging.getLogger(ElasticFeatureSource.class);

	private final static int DEFAULT_MAX_FEATURES = 10000;

	private Boolean filterFullySupported;

	public ElasticFeatureSource(ContentEntry entry, Query query) throws IOException {
		super(entry, query);
	}

	/**
	 * Access parent datastore
	 */
	public ElasticDataStore getDataStore() {
		return (ElasticDataStore) super.getDataStore();
	}

	/**
	 * Implementation that generates the total bounds
	 */
	@Override
	protected ReferencedEnvelope getBoundsInternal(Query query) throws IOException {
		LOGGER.fine("getBoundsInternal");
		final CoordinateReferenceSystem crs;
		crs = getSchema().getCoordinateReferenceSystem();
		final ReferencedEnvelope bounds;
		bounds = new ReferencedEnvelope(crs);

		try (FeatureReader<SimpleFeatureType, SimpleFeature> featureReader = getReaderInternal(query)) {
			while (featureReader.hasNext()) {
				final SimpleFeature feature = featureReader.next();
				bounds.include(feature.getBounds());
			}
		}
		return bounds;
	}

	@Override
	protected int getCountInternal(Query query) throws IOException {
		LOGGER.fine("getCountInternal");
		int hits = 0;
		try {
			final SearchRequestBuilder searchRequest = prepareSearchRequest(query, SearchType.COUNT);
			if (!filterFullySupported) {
				try (FeatureReader<SimpleFeatureType, SimpleFeature> reader = getReaderInternal(query)) {
					while (reader.hasNext()) {
						reader.next();
						hits++;
					}
				}
			} else {
				final SearchResponse sr = searchRequest.execute().get();
				final int totalHits = (int) sr.getHits().getTotalHits();
				final int size = getSize(query);
				final int from = getStartIndex(query);
				hits = Math.max(0, Math.min(totalHits - from, size));
			}
		} catch (InterruptedException | ExecutionException e) {
			throw new IOException("Error executing count search", e);
		}

		return hits;
	}

	@Override
	protected FeatureReader<SimpleFeatureType, SimpleFeature> getReaderInternal(Query query) throws IOException {
		LOGGER.fine("getReaderInternal");
		FeatureReader<SimpleFeatureType, SimpleFeature> reader = null;
		try {
			SearchType searchType = useSort(query) ? SearchType.DFS_QUERY_THEN_FETCH : SearchType.SCAN;
			final SearchRequestBuilder searchRequest = prepareSearchRequest(query, searchType);
			SearchResponse sr = searchRequest.execute().get();
			String scrollId = sr.getScrollId();
			if (scrollId == null) {
				reader = new ElasticFeatureReader(getState(), sr);
			} else {
				List<SearchHit> list = new ArrayList<>();
				float maxScore = -1;
				while (sr != null && scrollId != null) {

					LOGGER.fine("Scroll id = " + scrollId);

					sr = getDataStore().getClient().prepareSearchScroll(scrollId).execute().actionGet();
					scrollId = sr.getScrollId();
					if (sr != null && sr.getHits() != null) {
						maxScore = (maxScore < sr.getHits().maxScore()) ? sr.getHits().maxScore() : maxScore;
						list.addAll(Arrays.asList(sr.getHits().hits()));
						LOGGER.fine("Search hit list size = " + list.size());
					} else {
						LOGGER.severe("Expected result for scroll id = " + sr.getScrollId());
					}
				}
				reader = new ElasticFeatureReader(getState(), list.iterator(), maxScore);
			}
			if (!filterFullySupported) {
				reader = new FilteringFeatureReader<SimpleFeatureType, SimpleFeature>(reader, query.getFilter());
			}
		} catch (InterruptedException | ExecutionException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
			throw new IOException("Error executing query search", e);
		}
		return reader;
	}

	private SearchRequestBuilder prepareSearchRequest(Query query, SearchType searchType) throws IOException {
		SortOrder naturalSortOrder = SortOrder.ASC;
		final SearchRequestBuilder searchRequest;
		final ElasticDataStore dataStore = getDataStore();
		final String docType = dataStore.getDocType(entry.getName());

		// sort OR scan
		if (searchType!=SearchType.SCAN) {
			LOGGER.fine("Preparing " + docType + " (" + entry.getName() + ") " + searchType + " query");
			searchRequest = dataStore.getClient().prepareSearch(dataStore.getSearchIndices()).setTypes(docType)
			        .setSearchType(searchType);

			if (query.getSortBy()!=null){
				for (final SortBy sort : query.getSortBy()) {
					final SortOrder sortOrder = (sort.getSortOrder() == ASCENDING) ? SortOrder.ASC : SortOrder.DESC;
	
					if (sort.getPropertyName() != null) {
						final String name = sort.getPropertyName().getPropertyName();
						searchRequest.addSort(name, sortOrder);
					} else {
						naturalSortOrder = sortOrder;
					}
				}
			}

			// pagination
			searchRequest.setSize(getSize(query));
			searchRequest.setFrom(getStartIndex(query));			
		} else {
			LOGGER.fine("Preparing " + docType + " (" + entry.getName() + ") " + SearchType.SCAN + " query");
			searchRequest = dataStore.getClient().prepareSearch(dataStore.getSearchIndices()).setTypes(docType)
			        .setSearchType(SearchType.SCAN);
			if (dataStore.getScrollSize() != null) {
				searchRequest.setSize(dataStore.getScrollSize().intValue());
			}
			if (dataStore.getScrollTimeSeconds() != null) {
				searchRequest.setScroll(TimeValue.timeValueSeconds(dataStore.getScrollTimeSeconds()));
			}
		}

		// add fields
		setIncludes(searchRequest);

		// add query and post filter
		final FilterToElastic filterToElastic = new FilterToElastic();
		filterToElastic.setFeatureType(buildFeatureType());
		filterToElastic.encode(query);
		filterFullySupported = filterToElastic.getFullySupported();
		if (!filterFullySupported) {
			LOGGER.fine("Filter is not fully supported by nativeElasticsearch."
			        + " Additional post-query filtering will be performed.");
		}
		final QueryBuilder elasticQuery = filterToElastic.getQueryBuilder();
		final FilterBuilder postFilter = filterToElastic.getFilterBuilder();
		LOGGER.fine(String.format("postFilter: %s", postFilter.toString()));
		searchRequest.setQuery(elasticQuery).setPostFilter(postFilter);

		if (useSort(query) && elasticQuery.toString().equals(QueryBuilders.matchAllQuery().toString())) {
			searchRequest.addSort("_uid", naturalSortOrder);
		}

		LOGGER.info(searchRequest.toString());

		return searchRequest;
	}

	private void setIncludes(final SearchRequestBuilder searchRequest) throws IOException {
		final ElasticDataStore dataStore = getDataStore();
		final List<ElasticAttribute> attributes = dataStore.getElasticAttributes(entry.getName());
		List<String> sourceIncludes = new ArrayList<>();
		for (final ElasticAttribute attribute : attributes) {
			if (attribute.isUse() && attribute.isStored()) {
				searchRequest.addField(attribute.getName());
			} else if (attribute.isUse()) {
				sourceIncludes.add(attribute.getName());
			}
		}
		if (sourceIncludes.size() == 1) {
			searchRequest.setFetchSource(sourceIncludes.get(0), null);
		} else if (!sourceIncludes.isEmpty()) {
			final String[] includes;
			includes = sourceIncludes.toArray(new String[sourceIncludes.size()]);
			searchRequest.setFetchSource(includes, null);
		}
	}

	private boolean useSort(Query query) {
		return (query.getSortBy() != null && query.getSortBy().length > 0) ||
				query.getStartIndex()!=null;
	}

	private int getSize(Query query) {
		final int size;
		if (query.getMaxFeatures() < Integer.MAX_VALUE) {
			size = query.getMaxFeatures();
		} else {
			size = DEFAULT_MAX_FEATURES;
		}
		return size;
	}

	private int getStartIndex(Query query) {
		final int from;
		if (query.getStartIndex() != null) {
			from = query.getStartIndex();
		} else {
			from = 0;
		}
		return from;
	}

	@Override
	protected SimpleFeatureType buildFeatureType() throws IOException {
		ElasticDataStore ds = getDataStore();
		ElasticLayerConfiguration layerConfig;
		layerConfig = ds.getLayerConfigurations().get(entry.getTypeName());
		final List<ElasticAttribute> attributes;
		if (layerConfig != null) {
			attributes = layerConfig.getAttributes();
		} else {
			attributes = null;
		}

		final ElasticFeatureTypeBuilder typeBuilder;
		typeBuilder = new ElasticFeatureTypeBuilder(attributes, entry.getName());
		return typeBuilder.buildFeatureType();
	}

	@Override
	protected boolean canLimit() {
		return true;
	}

	@Override
	protected boolean canOffset() {
		return true;
	}

	@Override
	protected boolean canFilter() {
		return true;
	}

	@Override
	protected boolean canSort() {
		return true;
	}

}
