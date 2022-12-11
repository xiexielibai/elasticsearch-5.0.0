/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.query;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptSettings;

import java.util.Collections;

/**
 * Context object used to rewrite {@link QueryBuilder} instances into simplified version.
 */
public class QueryRewriteContext implements ParseFieldMatcherSupplier {
    protected final MapperService mapperService;
    protected final ScriptService scriptService;
    protected final IndexSettings indexSettings;
    protected final IndicesQueriesRegistry indicesQueriesRegistry;
    protected final Client client;
    protected final IndexReader reader;
    protected final ClusterState clusterState;

    public QueryRewriteContext(IndexSettings indexSettings, MapperService mapperService, ScriptService scriptService,
                               IndicesQueriesRegistry indicesQueriesRegistry, Client client, IndexReader reader,
                               ClusterState clusterState) {
        this.mapperService = mapperService;
        this.scriptService = scriptService;
        this.indexSettings = indexSettings;
        this.indicesQueriesRegistry = indicesQueriesRegistry;
        this.client = client;
        this.reader = reader;
        this.clusterState = clusterState;
    }

    /**
     * Returns a clients to fetch resources from local or remove nodes.
     */
    public final Client getClient() {
        return client;
    }

    /**
     * Returns the index settings for this context. This might return null if the
     * context has not index scope.
     */
    public final IndexSettings getIndexSettings() {
        return indexSettings;
    }

    /**
     * Return the MapperService.
     */
    public final MapperService getMapperService() {
        return mapperService;
    }

    /** Return the current {@link IndexReader}, or {@code null} if we are on the coordinating node. */
    public IndexReader getIndexReader() {
        return reader;
    }

    @Override
    public ParseFieldMatcher getParseFieldMatcher() {
        return this.indexSettings.getParseFieldMatcher();
    }

    /**
     * Returns the cluster state as is when the operation started.
     */
    public ClusterState getClusterState() {
        return clusterState;
    }

    /**
     * Returns a new {@link QueryParseContext} that wraps the provided parser, using the ParseFieldMatcher settings that
     * are configured in the index settings. The default script language will always default to Painless.
     */
    public QueryParseContext newParseContext(XContentParser parser) {
        return new QueryParseContext(indicesQueriesRegistry, parser, indexSettings.getParseFieldMatcher());
    }

    /**
     * Returns a new {@link QueryParseContext} like {@link #newParseContext(XContentParser)} with the only diffence, that
     * the default script language will default to what has been set in the 'script.legacy.default_lang' setting.
     */
    public QueryParseContext newParseContextWithLegacyScriptLanguage(XContentParser parser) {
        String defaultScriptLanguage = ScriptSettings.getLegacyDefaultLang(indexSettings.getNodeSettings());
        return new QueryParseContext(defaultScriptLanguage, indicesQueriesRegistry, parser, indexSettings.getParseFieldMatcher());
    }

    public BytesReference getTemplateBytes(Script template) {
        ExecutableScript executable = scriptService.executable(template,
            ScriptContext.Standard.SEARCH, Collections.emptyMap());
        return (BytesReference) executable.run();
    }


}
