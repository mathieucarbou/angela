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

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.clustered.client.config.builders.ClusteredResourcePoolBuilder;
import org.ehcache.clustered.client.config.builders.ClusteringServiceConfigurationBuilder;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.junit.Test;
import org.terracotta.angela.client.ClientArray;
import org.terracotta.angela.client.ClientArrayFuture;
import org.terracotta.angela.client.ClientJob;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.custom.CustomConfigurationContext;
import org.terracotta.angela.common.topology.ClientArrayTopology;
import org.terracotta.angela.common.topology.LicenseType;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Topology;

import java.net.URI;

import static junit.framework.TestCase.assertEquals;
import static org.terracotta.angela.Versions.EHCACHE_SNAPSHOT_VERSION;
import static org.terracotta.angela.Versions.EHCACHE_VERSION;
import org.terracotta.angela.client.ClusterAgent;
import static org.terracotta.angela.common.clientconfig.ClientArrayConfig.newClientArrayConfig;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.tcconfig.TcConfig.tcConfig;
import static org.terracotta.angela.common.topology.Version.version;

public class EhcacheTest {
  @Test
  public void testTsaWithEhcacheReleaseKit() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .tsa(
            tsa -> tsa.topology(
                new Topology(
                    distribution(version(EHCACHE_VERSION), PackageType.KIT, LicenseType.TERRACOTTA_OS),
                    tcConfig(version(EHCACHE_VERSION), TestUtils.TC_CONFIG_A)
                )
            )
        );

    try (ClusterAgent agent = new ClusterAgent(false)) {
      try (ClusterFactory factory = new ClusterFactory(agent, "EhcacheTest::testTsaWithEhcacheReleaseKit", configContext)) {
        factory.tsa().startAll();
      }
    }
  }

  @Test
  public void testTsaWithEhcacheSnapshotKit() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .tsa(
            tsa -> tsa.topology(
                new Topology(
                    distribution(version(EHCACHE_SNAPSHOT_VERSION), PackageType.KIT, LicenseType.TERRACOTTA_OS),
                    tcConfig(version(EHCACHE_SNAPSHOT_VERSION), TestUtils.TC_CONFIG_A)
                )
            )
        );

    try (ClusterAgent agent = new ClusterAgent(false)) {
      try (ClusterFactory factory = new ClusterFactory(agent, "EhcacheTest::testTsaWithEhcacheSnapshotKit", configContext)) {
        factory.tsa().startAll();
      }
    }
  }

  @Test
  public void testClusteredEhcacheOperations() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .tsa(
            tsa -> tsa.topology(
                new Topology(
                    distribution(version(EHCACHE_VERSION), PackageType.KIT, LicenseType.TERRACOTTA_OS),
                    tcConfig(version(EHCACHE_VERSION), TestUtils.TC_CONFIG_A)
                )
            )
        ).clientArray(
            clientArray -> clientArray.clientArrayTopology(
                new ClientArrayTopology(
                    distribution(version(EHCACHE_VERSION), PackageType.KIT, LicenseType.TERRACOTTA_OS),
                    newClientArrayConfig().host("localhost")
                )
            )
        );

    try (ClusterAgent agent = new ClusterAgent(false)) {
      try (ClusterFactory factory = new ClusterFactory(agent, "EhcacheTest::testClusteredEhcacheOperations", configContext)) {
        factory.tsa().startAll();
        ClientArray clientArray = factory.clientArray();
        String uri = factory.tsa().uri().toString() + "/clustered-cache-manager";
        String cacheAlias = "clustered-cache";

        ClientJob clientJob = (cluster) -> {
          try (CacheManager cacheManager = createCacheManager(uri, cacheAlias)) {
            Cache<Long, String> cache = cacheManager.getCache(cacheAlias, Long.class, String.class);
            final int numKeys = 10;
            for (long key = 0; key < numKeys; key++) {
              cache.put(key, String.valueOf(key) + key);
            }

            for (long key = 0; key < numKeys; key++) {
              assertEquals(cache.get(key), String.valueOf(key) + key);
            }
          }
        };

        ClientArrayFuture caf = clientArray.executeOnAll(clientJob);
        caf.get();
      }
    }
  }

  private static CacheManager createCacheManager(String uri, String cacheAlias) {
    return CacheManagerBuilder
        .newCacheManagerBuilder()
        .with(ClusteringServiceConfigurationBuilder
            .cluster(URI.create(uri))
            .autoCreate(s -> s.defaultServerResource("main").resourcePool("resource-pool-a", 10, MemoryUnit.MB))
        ).withCache(cacheAlias, CacheConfigurationBuilder.newCacheConfigurationBuilder(
            Long.class,
            String.class,
            ResourcePoolsBuilder.newResourcePoolsBuilder()
                .heap(1000, EntryUnit.ENTRIES)
                .offheap(1, MemoryUnit.MB)
                .with(ClusteredResourcePoolBuilder.clusteredShared("resource-pool-a"))
            )
        ).build(true);
  }
}