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
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.ConfigTool;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import org.terracotta.angela.client.ClusterAgent;
import static org.terracotta.angela.client.config.custom.CustomConfigurationContext.customConfigurationContext;
import static org.terracotta.angela.client.support.hamcrest.AngelaMatchers.successful;
import static org.terracotta.angela.common.TerracottaConfigTool.configTool;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.dynamic_cluster.Stripe.stripe;
import static org.terracotta.angela.common.provider.DynamicConfigManager.dynamicCluster;
import static org.terracotta.angela.common.tcconfig.TerracottaServer.server;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.Version.version;

public class ConfigToolTest {
  @Test
  public void testFailingConfigToolCommand() throws Exception {
    TerracottaServer server = server("server-1", "localhost")
        .tsaPort(9410)
        .tsaGroupPort(9411)
        .configRepo("terracotta1/repository")
        .logs("terracotta1/logs")
        .metaData("terracotta1/metadata")
        .failoverPriority("availability");
    Distribution distribution = distribution(version("3.9-SNAPSHOT"), KIT, TERRACOTTA_OS);
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(context -> context.topology(new Topology(distribution, dynamicCluster(stripe(server)))))
        .configTool(context -> context.configTool(configTool("config-tool", "localhost")).distribution(distribution));

    try (ClusterAgent agent = new ClusterAgent(false)) {
      try (ClusterFactory factory = new ClusterFactory(agent, "ConfigToolTest::testFailingClusterToolCommand", configContext)) {
        Tsa tsa = factory.tsa();
        tsa.startAll();
        ConfigTool configTool = factory.configTool();

        ToolExecutionResult result = configTool.executeCommand("non-existent-command");
        assertThat(result, is(not(successful())));
      }
    }
  }

  @Test
  public void testValidConfigToolCommand() throws Exception {
    TerracottaServer server = server("server-1", "localhost")
        .tsaPort(9410)
        .tsaGroupPort(9411)
        .configRepo("terracotta1/repository")
        .logs("terracotta1/logs")
        .metaData("terracotta1/metadata")
        .failoverPriority("availability");
    Distribution distribution = distribution(version("3.9-SNAPSHOT"), KIT, TERRACOTTA_OS);
    ConfigurationContext configContext = customConfigurationContext()
        .tsa(context -> context.topology(new Topology(distribution, dynamicCluster(stripe(server)))))
        .configTool(context -> context.configTool(configTool("config-tool", "localhost")).distribution(distribution));

    try (ClusterAgent agent = new ClusterAgent(false)) {
      try (ClusterFactory factory = new ClusterFactory(agent, "ConfigToolTest::testValidConfigToolCommand", configContext)) {
        Tsa tsa = factory.tsa();
        tsa.startAll();
        ConfigTool configTool = factory.configTool();

        ToolExecutionResult result = configTool.executeCommand("get", "-s", "localhost", "-c", "offheap-resources");
        System.out.println("######Result: " + result);
      }
    }
  }
}