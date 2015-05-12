/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.queries;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.antlr.runtime.RecognitionException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.client.request.RequestConverter;
import com.linkedin.pinot.common.metrics.ServerMetrics;
import com.linkedin.pinot.common.query.QueryExecutor;
import com.linkedin.pinot.common.query.ReduceService;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.InstanceRequest;
import com.linkedin.pinot.common.response.BrokerResponse;
import com.linkedin.pinot.common.response.ServerInstance;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.core.data.manager.config.FileBasedInstanceDataManagerConfig;
import com.linkedin.pinot.core.data.manager.offline.FileBasedInstanceDataManager;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegmentLoader;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.query.executor.ServerQueryExecutorV1Impl;
import com.linkedin.pinot.core.query.reduce.DefaultReduceService;
import com.linkedin.pinot.core.segment.creator.SegmentIndexCreationDriver;
import com.linkedin.pinot.core.segment.creator.impl.SegmentIndexCreationDriverImpl;
import com.linkedin.pinot.pql.parsers.PQLCompiler;
import com.linkedin.pinot.segments.v1.creator.SegmentTestUtils;
import com.linkedin.pinot.util.TestUtils;
import com.yammer.metrics.core.MetricsRegistry;


public class QueryExceptionTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueriesSentinelTest.class);
  private static ReduceService REDUCE_SERVICE = new DefaultReduceService();

  private static final PQLCompiler REQUEST_COMPILER = new PQLCompiler(new HashMap<String, String[]>());
  private final String AVRO_DATA = "data/mirror-mv.avro";
  private static File INDEX_DIR = new File(FileUtils.getTempDirectory() + File.separator + "QueriesSentinelTest");
  private static QueryExecutor QUERY_EXECUTOR;
  private static TestingServerPropertiesBuilder CONFIG_BUILDER;
  private String segmentName;

  @BeforeClass
  public void setup() throws Exception {
    CONFIG_BUILDER = new TestingServerPropertiesBuilder("mirror");

    setupSegmentFor("mirror");

    final PropertiesConfiguration serverConf = CONFIG_BUILDER.build();
    serverConf.setDelimiterParsingDisabled(false);

    final FileBasedInstanceDataManager instanceDataManager = FileBasedInstanceDataManager.getInstanceDataManager();
    instanceDataManager.init(new FileBasedInstanceDataManagerConfig(serverConf.subset("pinot.server.instance")));
    instanceDataManager.start();

    System.out.println("************************** : " + new File(INDEX_DIR, "segment").getAbsolutePath());
    File segmentFile = new File(INDEX_DIR, "segment").listFiles()[0];
    segmentName = segmentFile.getName();
    final IndexSegment indexSegment = ColumnarSegmentLoader.load(segmentFile, ReadMode.heap);
    instanceDataManager.getResourceDataManager("mirror");
    instanceDataManager.getResourceDataManager("mirror").addSegment(indexSegment);

    QUERY_EXECUTOR = new ServerQueryExecutorV1Impl(false);
    QUERY_EXECUTOR.init(serverConf.subset("pinot.server.query.executor"), instanceDataManager, new ServerMetrics(
        new MetricsRegistry()));
  }

  @AfterClass
  public void tearDown() {
    FileUtils.deleteQuietly(INDEX_DIR);
  }

  private void setupSegmentFor(String resource) throws Exception {
    final String filePath = TestUtils.getFileFromResourceUrl(getClass().getClassLoader().getResource(AVRO_DATA));

    if (INDEX_DIR.exists()) {
      FileUtils.deleteQuietly(INDEX_DIR);
    }
    INDEX_DIR.mkdir();

    final SegmentGeneratorConfig config =
        SegmentTestUtils.getSegmentGenSpecWithSchemAndProjectedColumns(new File(filePath), new File(INDEX_DIR,
            "segment"), "daysSinceEpoch", TimeUnit.DAYS, resource, resource);

    final SegmentIndexCreationDriver driver = new SegmentIndexCreationDriverImpl();

    driver.init(config);
    driver.build();

    System.out.println("built at : " + INDEX_DIR.getAbsolutePath());
  }

  @Test
  public void testSingleQuery() throws RecognitionException, Exception {
    String query = "select count(*) from mirror where viewerId='24516187'";
    LOGGER.info("running  : " + query);
    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    final BrokerRequest brokerRequest = RequestConverter.fromJSON(REQUEST_COMPILER.compile(query));
    InstanceRequest instanceRequest = new InstanceRequest(1, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    instanceRequest.getSearchSegments().add(segmentName);
    final DataTable instanceResponse = QUERY_EXECUTOR.processQuery(instanceRequest);
    instanceResponseMap.clear();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
    final BrokerResponse brokerResponse = REDUCE_SERVICE.reduceOnDataTable(brokerRequest, instanceResponseMap);
    LOGGER.info("BrokerResponse is " + brokerResponse.getAggregationResults().get(0));
  }

  @Test
  public void testQueryParsingFailedQuery() throws RecognitionException, Exception {
    String query = "select sudm(blablaa) from mirror where viewerId='24516187'";
    LOGGER.info("running  : " + query);
    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    final BrokerRequest brokerRequest = RequestConverter.fromJSON(REQUEST_COMPILER.compile(query));
    InstanceRequest instanceRequest = new InstanceRequest(1, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    instanceRequest.getSearchSegments().add(segmentName);
    final DataTable instanceResponse = QUERY_EXECUTOR.processQuery(instanceRequest);
    instanceResponseMap.clear();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
    final BrokerResponse brokerResponse = REDUCE_SERVICE.reduceOnDataTable(brokerRequest, instanceResponseMap);
    LOGGER.info("BrokerResponse is {}", brokerResponse);
    Assert.assertTrue(brokerResponse.getExceptionsSize() > 0);
  }

  @Test
  public void testQueryPlanFailedQuery() throws RecognitionException, Exception {
    String query = "select sum(blablaa) from mirror where viewerId='24516187'";
    LOGGER.info("running  : " + query);
    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    final BrokerRequest brokerRequest = RequestConverter.fromJSON(REQUEST_COMPILER.compile(query));
    InstanceRequest instanceRequest = new InstanceRequest(1, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    instanceRequest.getSearchSegments().add(segmentName);
    final DataTable instanceResponse = QUERY_EXECUTOR.processQuery(instanceRequest);
    instanceResponseMap.clear();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
    final BrokerResponse brokerResponse = REDUCE_SERVICE.reduceOnDataTable(brokerRequest, instanceResponseMap);
    LOGGER.info("BrokerResponse is {}", brokerResponse);
    Assert.assertTrue(brokerResponse.getExceptionsSize() > 0);
  }

  @Test
  public void testQueryExecuteFailedQuery() throws RecognitionException, Exception {
    String query = "select count(*) from mirror where viewerId='24516187' group by bla";
    LOGGER.info("running  : " + query);
    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    final BrokerRequest brokerRequest = RequestConverter.fromJSON(REQUEST_COMPILER.compile(query));
    InstanceRequest instanceRequest = new InstanceRequest(1, brokerRequest);
    instanceRequest.setSearchSegments(new ArrayList<String>());
    instanceRequest.getSearchSegments().add(segmentName);
    final DataTable instanceResponse = QUERY_EXECUTOR.processQuery(instanceRequest);
    instanceResponseMap.clear();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
    final BrokerResponse brokerResponse = REDUCE_SERVICE.reduceOnDataTable(brokerRequest, instanceResponseMap);
    LOGGER.info("BrokerResponse is {}", brokerResponse);
    Assert.assertTrue(brokerResponse.getExceptionsSize() == 0);
  }
}