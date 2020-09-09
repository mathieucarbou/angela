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
import org.terracotta.angela.client.Voter;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.common.topology.Topology;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.terracotta.angela.client.config.custom.CustomConfigurationContext.customConfigurationContext;
import static org.terracotta.angela.common.TerracottaVoter.voter;
import static org.terracotta.angela.common.TerracottaVoterState.CONNECTED_TO_ACTIVE;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.dynamic_cluster.Stripe.stripe;
import static org.terracotta.angela.common.provider.DynamicConfigManager.dynamicCluster;
import static org.terracotta.angela.common.tcconfig.TerracottaServer.server;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.Version.version;

public class VoterTest {
  @Test
  public void testVoterStartup() throws Exception {
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
                                .failoverPriority("consistency:1"),
                            server("server-2", "localhost")
                                .tsaPort(9510)
                                .tsaGroupPort(9511)
                                .configRepo("terracotta2/repository")
                                .logs("terracotta2/logs")
                                .metaData("terracotta2/metadata")
                                .failoverPriority("consistency:1")
                        )
                    )
                )
            )
        ).voter(voter -> voter
            .distribution(distribution(version("3.9-SNAPSHOT"), KIT, TERRACOTTA_OS))
            .addVoter(voter("voter", "localhost", "localhost:9410", "localhost:9510"))
        );

    try (ClusterFactory factory = new ClusterFactory("VoterTest::testVoterStartup", configContext)) {
      factory.tsa().startAll().attachAll().activateAll();
      Voter voter = factory.voter();
      voter.startAll();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> voter.getTerracottaVoterState(configContext.voter().getTerracottaVoters().get(0)) == CONNECTED_TO_ACTIVE);
    }
  }
}