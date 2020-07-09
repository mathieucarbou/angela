/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */
package org.terracotta.angela;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.ConfigTool;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.common.topology.Topology;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.fail;
import static org.terracotta.angela.client.config.custom.CustomConfigurationContext.customConfigurationContext;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.dynamic_cluster.Stripe.stripe;
import static org.terracotta.angela.common.provider.DynamicConfigManager.dynamicCluster;
import static org.terracotta.angela.common.tcconfig.TerracottaServer.server;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.Version.version;

/**
 * @author Yakov Feldman
 */
public class ConfigToolTest {
  private final static Logger logger = LoggerFactory.getLogger(ConfigToolTest.class);

  @Test
  public void testFailingClusterToolCommand() throws Exception {
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(tsa -> tsa
            .topology(
                new Topology(
                    distribution(version("3.9-SNAPSHOT"), KIT, TERRACOTTA_OS),
                    dynamicCluster(
                        stripe(
                            server("server-1", "localhost")
                                .tsaPort(9410)
                                .tsaGroupPort(9411)
                                .configRepo("terracotta1/repository")
                                .logs("terracotta1/logs")
                                .metaData("terracotta1/metadata")
                                .failoverPriority("availability")
                        )
                    )
                )
            )
        );

    try (ClusterFactory factory = new ClusterFactory("ConfigToolTest::testFailingClusterToolCommand", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.startAll().attachAll().activateAll();

      await().atMost(Duration.ofSeconds(60)).until(() -> tsa.getTsaConfigurationContext().getTopology().getStripes().size(), is(1));

      ConfigTool configTool = tsa.configTool(tsa.getActive());

      try {
        configTool.executeCommand("fail");
        fail("config tool should fail because the option doesn't exist");
      } catch (Exception e) {
        // expected
      }
    }
  }
}