package com.terracottatech.qa.angela;

import com.terracottatech.qa.angela.client.Client;
import com.terracottatech.qa.angela.client.ClientJob;
import com.terracottatech.qa.angela.client.ClusterFactory;
import com.terracottatech.qa.angela.client.Tms;
import com.terracottatech.qa.angela.client.Tsa;
import com.terracottatech.qa.angela.common.distribution.Distribution;
import com.terracottatech.qa.angela.common.tcconfig.License;
import com.terracottatech.qa.angela.common.topology.LicenseType;
import com.terracottatech.qa.angela.common.topology.PackageType;
import com.terracottatech.qa.angela.common.topology.Topology;
import com.terracottatech.store.Dataset;
import com.terracottatech.store.DatasetWriterReader;
import com.terracottatech.store.Type;
import com.terracottatech.store.configuration.DatasetConfigurationBuilder;
import com.terracottatech.store.definition.CellDefinition;
import com.terracottatech.store.manager.DatasetManager;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;

import static com.terracottatech.qa.angela.common.distribution.Distribution.distribution;
import static com.terracottatech.qa.angela.common.http.HttpUtils.sendGetRequest;
import static com.terracottatech.qa.angela.common.tcconfig.TcConfig.tcConfig;
import static com.terracottatech.qa.angela.common.topology.Version.version;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * @author Anthony Dahanne
 */

public class TmsTest {

  private final static Logger logger = LoggerFactory.getLogger(TmsTest.class);
  private static final String VERSION = "10.2.0.0.224";
  private static String connectionName;
  private static ClusterFactory factory;
  private static final String TMS_HOSTNAME = "localhost";

  @BeforeClass
  public static void setUp() throws Exception {
    Distribution distribution = distribution(version(VERSION), PackageType.KIT, LicenseType.TC_DB);
    Topology topology = new Topology(distribution,
        tcConfig(version("10.2.0.0.224"), TmsTest.class.getResource("/terracotta/10/tc-config-a.xml")));
    License license = new License(TmsTest.class.getResource("/terracotta/10/TerracottaDB101_license.xml"));

    factory = new ClusterFactory("TmsTest::testConnection");
    Tsa tsa = factory.tsa(topology, license);
    tsa.installAll();
    tsa.startAll();
    tsa.licenseAll();
    Tms tms = factory.tms(distribution, license, TMS_HOSTNAME);
    tms.install();
    tms.start();
    connectionName = tms.connectToCluster(tsa.uri());
  }

  @Test
  public void testConnectionName() {
    assertThat(connectionName, startsWith("TmsTest"));
  }

  @Test
  public void testTmsConnection() throws Exception {
    Client client = factory.client("localhost");
    ClientJob clientJob = (context) -> {
      try (DatasetManager datasetManager = DatasetManager.clustered(context.tsaURI()).build()) {
        DatasetConfigurationBuilder builder = datasetManager.datasetConfiguration()
            .offheap("primary-server-resource");
        boolean datasetCreated = datasetManager.newDataset("MyDataset", Type.STRING, builder.build());
        if (datasetCreated) {
          logger.info("created dataset");
        }
        try (Dataset<String> dataset = datasetManager.getDataset("MyDataset", Type.STRING)) {
          DatasetWriterReader<String> writerReader = dataset.writerReader();
          writerReader.add("ONE", CellDefinition.defineLong("val").newCell(1L));
          logger.info("Value created for key ONE");
          dataset.close();
        }

      }
      logger.info("client done");
    };


    ClientJob clientJobTms = (context) -> {
      String url = "http://" + TMS_HOSTNAME + ":9480/api/connections";
      String response = sendGetRequest(url);
      logger.info("tms list connections result :" + response.toString());
      assertThat(response.toString(), Matchers.containsString("datasetServerEntities\":{\"MyDataset\""));
    };

    Future<Void> f1 = client.submit(clientJob);
    f1.get();
    Future<Void> fTms = client.submit(clientJobTms);
    fTms.get();
    logger.info("---> Stop");
  }

  @AfterClass
  public static void tearDownStuff() throws Exception {
    if (factory != null) {
      factory.close();
    }
  }
}