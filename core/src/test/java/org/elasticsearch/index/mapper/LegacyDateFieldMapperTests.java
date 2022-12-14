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

package org.elasticsearch.index.mapper;

import org.apache.lucene.analysis.LegacyNumericTokenStream.LegacyNumericTermAttribute;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.LegacyNumericRangeQuery;
import org.apache.lucene.util.Constants;
import org.elasticsearch.Version;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.LocaleUtils;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.ParseContext.Document;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.test.InternalSettingsPlugin;
import org.elasticsearch.test.TestSearchContext;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.carrotsearch.randomizedtesting.RandomizedTest.systemPropertyAsBoolean;
import static org.elasticsearch.index.mapper.LegacyStringMappingTests.docValuesType;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class LegacyDateFieldMapperTests extends ESSingleNodeTestCase {

    private static final Settings BW_SETTINGS = Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.V_2_3_0).build();

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return pluginList(InternalSettingsPlugin.class);
    }

    public void testAutomaticDateParser() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").endObject()
                .endObject().endObject().string();

        IndexService index = createIndex("test", BW_SETTINGS);
        client().admin().indices().preparePutMapping("test").setType("type").setSource(mapping).get();
        DocumentMapper defaultMapper = index.mapperService().documentMapper("type");

        ParsedDocument doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("date_field1", "2011/01/22")
                .field("date_field2", "2011/01/22 00:00:00")
                .field("wrong_date1", "-4")
                .field("wrong_date2", "2012/2")
                .field("wrong_date3", "2012/test")
                .endObject()
                .bytes());
        assertNotNull(doc.dynamicMappingsUpdate());
        client().admin().indices().preparePutMapping("test").setType("type").setSource(doc.dynamicMappingsUpdate().toString()).get();

        defaultMapper = index.mapperService().documentMapper("type");
        FieldMapper fieldMapper = defaultMapper.mappers().smartNameFieldMapper("date_field1");
        assertThat(fieldMapper, instanceOf(LegacyDateFieldMapper.class));
        LegacyDateFieldMapper dateFieldMapper = (LegacyDateFieldMapper)fieldMapper;
        assertEquals("yyyy/MM/dd HH:mm:ss||yyyy/MM/dd||epoch_millis", dateFieldMapper.fieldType().dateTimeFormatter().format());
        assertEquals(1265587200000L, dateFieldMapper.fieldType().dateTimeFormatter().parser().parseMillis("1265587200000"));
        fieldMapper = defaultMapper.mappers().smartNameFieldMapper("date_field2");
        assertThat(fieldMapper, instanceOf(LegacyDateFieldMapper.class));

        fieldMapper = defaultMapper.mappers().smartNameFieldMapper("wrong_date1");
        assertThat(fieldMapper, instanceOf(StringFieldMapper.class));
        fieldMapper = defaultMapper.mappers().smartNameFieldMapper("wrong_date2");
        assertThat(fieldMapper, instanceOf(StringFieldMapper.class));
        fieldMapper = defaultMapper.mappers().smartNameFieldMapper("wrong_date3");
        assertThat(fieldMapper, instanceOf(StringFieldMapper.class));
    }

    public void testParseLocal() {
        assertThat(Locale.GERMAN, equalTo(LocaleUtils.parse("de")));
        assertThat(Locale.GERMANY, equalTo(LocaleUtils.parse("de_DE")));
        assertThat(new Locale("de","DE","DE"), equalTo(LocaleUtils.parse("de_DE_DE")));

        try {
            LocaleUtils.parse("de_DE_DE_DE");
            fail();
        } catch(IllegalArgumentException ex) {
            // expected
        }
        assertThat(Locale.ROOT,  equalTo(LocaleUtils.parse("")));
        assertThat(Locale.ROOT,  equalTo(LocaleUtils.parse("ROOT")));
    }

    public void testLocale() throws IOException {
        assumeFalse("Locals are buggy on JDK9EA", Constants.JRE_IS_MINIMUM_JAVA9 && systemPropertyAsBoolean("tests.security.manager", false));
        String mapping = XContentFactory.jsonBuilder()
                    .startObject()
                        .startObject("type")
                            .startObject("properties")
                                .startObject("date_field_default")
                                    .field("type", "date")
                                    .field("format", "E, d MMM yyyy HH:mm:ss Z")
                                .endObject()
                                .startObject("date_field_en")
                                    .field("type", "date")
                                    .field("format", "E, d MMM yyyy HH:mm:ss Z")
                                    .field("locale", "EN")
                                .endObject()
                                 .startObject("date_field_de")
                                    .field("type", "date")
                                    .field("format", "E, d MMM yyyy HH:mm:ss Z")
                                    .field("locale", "DE_de")
                                .endObject()
                            .endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test", "type", mapping);
        ParsedDocument doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                  .field("date_field_en", "Wed, 06 Dec 2000 02:55:00 -0800")
                  .field("date_field_de", "Mi, 06 Dez 2000 02:55:00 -0800")
                  .field("date_field_default", "Wed, 06 Dec 2000 02:55:00 -0800") // check default - no exception is a success!
                .endObject()
                .bytes());
        assertNumericTokensEqual(doc, defaultMapper, "date_field_en", "date_field_de");
        assertNumericTokensEqual(doc, defaultMapper, "date_field_en", "date_field_default");
    }

    @Before
    public void reset() {
        i = 0;
    }

    int i = 0;

    private DocumentMapper mapper(String indexName, String type, String mapping) throws IOException {
        IndexService index = createIndex(indexName, BW_SETTINGS);
        client().admin().indices().preparePutMapping(indexName).setType(type).setSource(mapping).get();
        return index.mapperService().documentMapper(type);
    }

    private void assertNumericTokensEqual(ParsedDocument doc, DocumentMapper defaultMapper, String fieldA, String fieldB) throws IOException {
        assertThat(doc.rootDoc().getField(fieldA).tokenStream(defaultMapper.mappers().indexAnalyzer(), null), notNullValue());
        assertThat(doc.rootDoc().getField(fieldB).tokenStream(defaultMapper.mappers().indexAnalyzer(), null), notNullValue());

        TokenStream tokenStream = doc.rootDoc().getField(fieldA).tokenStream(defaultMapper.mappers().indexAnalyzer(), null);
        tokenStream.reset();
        LegacyNumericTermAttribute nta = tokenStream.addAttribute(LegacyNumericTermAttribute.class);
        List<Long> values = new ArrayList<>();
        while(tokenStream.incrementToken()) {
            values.add(nta.getRawValue());
        }

        tokenStream = doc.rootDoc().getField(fieldB).tokenStream(defaultMapper.mappers().indexAnalyzer(), null);
        tokenStream.reset();
        nta = tokenStream.addAttribute(LegacyNumericTermAttribute.class);
        int pos = 0;
        while(tokenStream.incrementToken()) {
            assertThat(values.get(pos++), equalTo(nta.getRawValue()));
        }
        assertThat(pos, equalTo(values.size()));
    }

    public void testTimestampAsDate() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("date_field").field("type", "date").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test", "type", mapping);
        long value = System.currentTimeMillis();

        ParsedDocument doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("date_field", value)
                .endObject()
                .bytes());

         assertThat(doc.rootDoc().getField("date_field").tokenStream(defaultMapper.mappers().indexAnalyzer(), null), notNullValue());
    }

    public void testDateDetection() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .field("date_detection", false)
                .startObject("properties").startObject("date_field").field("type", "date").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test", "type", mapping);

        ParsedDocument doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("date_field", "2010-01-01")
                .field("date_field_x", "2010-01-01")
                .endObject()
                .bytes());

        assertThat(doc.rootDoc().get("date_field"), equalTo("1262304000000"));
        assertThat(doc.rootDoc().get("date_field_x"), equalTo("2010-01-01"));
    }

    public void testHourFormat() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .field("date_detection", false)
                .startObject("properties").startObject("date_field").field("type", "date").field("format", "HH:mm:ss").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test", "type", mapping);

        ParsedDocument doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
            .startObject()
            .field("date_field", "10:00:00")
            .endObject()
            .bytes());
        assertThat(((LegacyLongFieldMapper.CustomLongNumericField) doc.rootDoc().getField("date_field")).numericAsString(), equalTo(Long.toString(new DateTime(TimeValue.timeValueHours(10).millis(), DateTimeZone.UTC).getMillis())));

        LegacyNumericRangeQuery<Long> rangeQuery;
        try {
            SearchContext.setCurrent(new TestSearchContext(null));
            rangeQuery = (LegacyNumericRangeQuery<Long>) defaultMapper.mappers().smartNameFieldMapper("date_field").fieldType().rangeQuery("10:00:00", "11:00:00", true, true).rewrite(null);
        } finally {
            SearchContext.removeCurrent();
        }
        assertThat(rangeQuery.getMax(), equalTo(new DateTime(TimeValue.timeValueHours(11).millis(), DateTimeZone.UTC).getMillis() + 999));
        assertThat(rangeQuery.getMin(), equalTo(new DateTime(TimeValue.timeValueHours(10).millis(), DateTimeZone.UTC).getMillis()));
    }

    public void testDayWithoutYearFormat() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .field("date_detection", false)
                .startObject("properties").startObject("date_field").field("type", "date").field("format", "MMM dd HH:mm:ss").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test", "type", mapping);

        ParsedDocument doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("date_field", "Jan 02 10:00:00")
                .endObject()
                .bytes());
        assertThat(((LegacyLongFieldMapper.CustomLongNumericField) doc.rootDoc().getField("date_field")).numericAsString(), equalTo(Long.toString(new DateTime(TimeValue.timeValueHours(34).millis(), DateTimeZone.UTC).getMillis())));

        LegacyNumericRangeQuery<Long> rangeQuery;
        try {
            SearchContext.setCurrent(new TestSearchContext(null));
            rangeQuery = (LegacyNumericRangeQuery<Long>) defaultMapper.mappers().smartNameFieldMapper("date_field").fieldType().rangeQuery("Jan 02 10:00:00", "Jan 02 11:00:00", true, true).rewrite(null);
        } finally {
            SearchContext.removeCurrent();
        }
        assertThat(rangeQuery.getMax(), equalTo(new DateTime(TimeValue.timeValueHours(35).millis(), DateTimeZone.UTC).getMillis() + 999));
        assertThat(rangeQuery.getMin(), equalTo(new DateTime(TimeValue.timeValueHours(34).millis(), DateTimeZone.UTC).getMillis()));
    }

    public void testIgnoreMalformedOption() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties")
                .startObject("field1").field("type", "date").field("ignore_malformed", true).endObject()
                .startObject("field2").field("type", "date").field("ignore_malformed", false).endObject()
                .startObject("field3").field("type", "date").endObject()
                .endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test", "type", mapping);

        ParsedDocument doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field1", "a")
                .field("field2", "2010-01-01")
                .endObject()
                .bytes());
        assertThat(doc.rootDoc().getField("field1"), nullValue());
        assertThat(doc.rootDoc().getField("field2"), notNullValue());

        try {
            defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                    .startObject()
                    .field("field2", "a")
                    .endObject()
                    .bytes());
        } catch (MapperParsingException e) {
            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), is("failed to parse [field2]"));
        }

        // Verify that the default is false
        try {
            defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                    .startObject()
                    .field("field3", "a")
                    .endObject()
                    .bytes());
        } catch (MapperParsingException e) {
            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), is("failed to parse [field3]"));
        }

        // Unless the global ignore_malformed option is set to true
        Settings indexSettings = Settings.builder()
                .put(IndexMetaData.SETTING_VERSION_CREATED, Version.V_2_3_0)
                .put("index.mapping.ignore_malformed", true)
                .build();
        defaultMapper = createIndex("test2", indexSettings).mapperService().documentMapperParser().parse("type", new CompressedXContent(mapping));
        doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field3", "a")
                .endObject()
                .bytes());
        assertThat(doc.rootDoc().getField("field3"), nullValue());

        // This should still throw an exception, since field2 is specifically set to ignore_malformed=false
        try {
            defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                    .startObject()
                    .field("field2", "a")
                    .endObject()
                    .bytes());
        } catch (MapperParsingException e) {
            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
            assertThat(e.getMessage(), is("failed to parse [field2]"));
        }
    }

    public void testThatMergingWorks() throws Exception {
        String initialMapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties")
                    .startObject("field").field("type", "date")
                        .field("format", "EEE MMM dd HH:mm:ss.S Z yyyy||EEE MMM dd HH:mm:ss.SSS Z yyyy")
                    .endObject()
                .endObject()
                .endObject().endObject().string();

        String updatedMapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties")
                .startObject("field")
                        .field("type", "date")
                        .field("format", "EEE MMM dd HH:mm:ss.S Z yyyy||EEE MMM dd HH:mm:ss.SSS Z yyyy||yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
                .endObject()
                .endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test1", "type", initialMapping);
        DocumentMapper mergeMapper = mapper("test2", "type", updatedMapping);

        assertThat(defaultMapper.mappers().getMapper("field"), is(instanceOf(LegacyDateFieldMapper.class)));
        LegacyDateFieldMapper initialDateFieldMapper = (LegacyDateFieldMapper) defaultMapper.mappers().getMapper("field");
        Map<String, String> config = getConfigurationViaXContent(initialDateFieldMapper);
        assertThat(config.get("format"), is("EEE MMM dd HH:mm:ss.S Z yyyy||EEE MMM dd HH:mm:ss.SSS Z yyyy"));

        defaultMapper = defaultMapper.merge(mergeMapper.mapping(), false);

        assertThat(defaultMapper.mappers().getMapper("field"), is(instanceOf(LegacyDateFieldMapper.class)));

        LegacyDateFieldMapper mergedFieldMapper = (LegacyDateFieldMapper) defaultMapper.mappers().getMapper("field");
        Map<String, String> mergedConfig = getConfigurationViaXContent(mergedFieldMapper);
        assertThat(mergedConfig.get("format"), is("EEE MMM dd HH:mm:ss.S Z yyyy||EEE MMM dd HH:mm:ss.SSS Z yyyy||yyyy-MM-dd'T'HH:mm:ss.SSSZZ"));
    }

    public void testDefaultDocValues() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
            .startObject("properties").startObject("date_field").field("type", "date").endObject().endObject()
            .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test", "type", mapping);

        ParsedDocument parsedDoc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
            .startObject()
            .field("date_field", "2010-01-01")
            .endObject()
            .bytes());
        ParseContext.Document doc = parsedDoc.rootDoc();
        assertEquals(DocValuesType.SORTED_NUMERIC, docValuesType(doc, "date_field"));
    }

    private Map<String, String> getConfigurationViaXContent(LegacyDateFieldMapper dateFieldMapper) throws IOException {
        XContentBuilder builder = JsonXContent.contentBuilder().startObject();
        dateFieldMapper.toXContent(builder, ToXContent.EMPTY_PARAMS).endObject();
        Map<String, Object> dateFieldMapperMap;
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes())) {
            dateFieldMapperMap = parser.map();
        }
        assertThat(dateFieldMapperMap, hasKey("field"));
        assertThat(dateFieldMapperMap.get("field"), is(instanceOf(Map.class)));
        return (Map<String, String>) dateFieldMapperMap.get("field");
    }

    private static long getDateAsMillis(Document doc, String field) {
        for (IndexableField f : doc.getFields(field)) {
            if (f.numericValue() != null) {
                return f.numericValue().longValue();
            }
        }
        throw new AssertionError("missing");
    }

    public void testThatEpochCanBeIgnoredWithCustomFormat() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("date_field").field("type", "date").field("format", "yyyyMMddHH").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper defaultMapper = mapper("test1", "type", mapping);

        XContentBuilder document = XContentFactory.jsonBuilder()
                .startObject()
                .field("date_field", "2015060210")
                .endObject();
        ParsedDocument doc = defaultMapper.parse("test", "type", "1", document.bytes());
        assertThat(getDateAsMillis(doc.rootDoc(), "date_field"), equalTo(1433239200000L));
        IndexResponse indexResponse = client().prepareIndex("test2", "test").setSource(document).get();
        assertEquals(DocWriteResponse.Result.CREATED, indexResponse.getResult());

        // integers should always be parsed as well... cannot be sure it is a unix timestamp only
        doc = defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("date_field", 2015060210)
                .endObject()
                .bytes());
        assertThat(getDateAsMillis(doc.rootDoc(), "date_field"), equalTo(1433239200000L));
        indexResponse = client().prepareIndex("test", "test").setSource(document).get();
        assertEquals(DocWriteResponse.Result.CREATED, indexResponse.getResult());
    }

    public void testThatNewIndicesOnlyAllowStrictDates() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("date_field").field("type", "date").endObject().endObject()
                .endObject().endObject().string();

        IndexService index = createIndex("test", BW_SETTINGS);
        client().admin().indices().preparePutMapping("test").setType("type").setSource(mapping).get();
        assertDateFormat(LegacyDateFieldMapper.Defaults.DATE_TIME_FORMATTER.format());
        DocumentMapper defaultMapper = index.mapperService().documentMapper("type");

        // also test normal date
        defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("date_field", "2015-06-06T00:00:44.000Z")
                .endObject()
                .bytes());

        try {
            defaultMapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                    .startObject()
                    .field("date_field", "1-1-1T00:00:44.000Z")
                    .endObject()
                    .bytes());
            fail("non strict date indexing should have been failed");
        } catch (MapperParsingException e) {
            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        }
    }

    private void assertDateFormat(String expectedFormat) throws IOException {
        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test").setTypes("type").get();
        Map<String, Object> mappingMap = response.getMappings().get("test").get("type").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappingMap.get("properties");
        Map<String, Object> dateField = (Map<String, Object>) properties.get("date_field");
        assertThat((String) dateField.get("format"), is(expectedFormat));
    }
}
