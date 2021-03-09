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
import org.terracotta.angela.client.Client;
import org.terracotta.angela.client.ClientArray;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.client.config.custom.CustomConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFile;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.ClientArrayTopology;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Topology;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.terracotta.angela.TestUtils.TC_CONFIG_AP;
import static org.terracotta.angela.Versions.EHCACHE_VERSION;
import org.terracotta.angela.client.ClusterAgent;
import static org.terracotta.angela.common.clientconfig.ClientArrayConfig.newClientArrayConfig;
import static org.terracotta.angela.common.distribution.Distribution.distribution;
import static org.terracotta.angela.common.tcconfig.TcConfig.tcConfig;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.Version.version;

/**
 * @author Ludovic Orban
 */
public class BrowseTest {
  @Test
  public void testClient() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(TERRACOTTA_OS.defaultLicense())
            .clientArrayTopology(new ClientArrayTopology(distribution(version(EHCACHE_VERSION), PackageType.KIT, TERRACOTTA_OS), newClientArrayConfig().host("localhost")))
        );
    try (ClusterAgent agent = new ClusterAgent(false); ClusterFactory factory = new ClusterFactory(agent, "BrowseTest::testClient", configContext)) {
      ClientArray clientArray = factory.clientArray();
      Client client = clientArray.getClients().stream().findFirst().get();

      File fileToUpload = new File("target/toUpload", "uploaded-data.txt");
      fileToUpload.getParentFile().mkdir();
      try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(fileToUpload))) {
        dos.writeUTF("uploaded : hello, world!");
      }

      client.browse("uploaded").upload(new File("target/toUpload"));

      clientArray.executeOnAll(cluster -> {
        try (DataInputStream dis = new DataInputStream(new FileInputStream("uploaded/uploaded-data.txt"))) {
          String line = dis.readUTF();
          assertThat(line, is("uploaded : hello, world!"));
        }

        File file = new File("toDownload", "downloaded-data.txt");
        file.getParentFile().mkdir();
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
          dos.writeUTF("downloaded: hello, world!");
        }
      }).get();

      client.browse("toDownload").list().stream().filter(remoteFile -> remoteFile.getName().equals("downloaded-data.txt")).findAny().get().downloadTo(new File("target/downloaded-data.txt"));

      try (DataInputStream dis = new DataInputStream(new FileInputStream("target/downloaded-data.txt"))) {
        assertThat(dis.readUTF(), is("downloaded: hello, world!"));
      }
    }
  }

  @Test
  public void testUploadPlugin() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .tsa(tsa -> tsa.topology(new Topology(distribution(version(EHCACHE_VERSION), PackageType.KIT, TERRACOTTA_OS),
            tcConfig(version(EHCACHE_VERSION), TC_CONFIG_AP)))
            .license(TERRACOTTA_OS.defaultLicense())
        );

    try (ClusterAgent agent = new ClusterAgent(false); ClusterFactory factory = new ClusterFactory(agent, "BrowseTest::testUploadPlugin", configContext)) {
      Tsa tsa = factory.tsa();
      tsa.uploadPlugin(new File(getClass().getResource("/keep-this-file-empty.txt").getFile()));

      for (TerracottaServer server : tsa.getTsaConfigurationContext().getTopology().getServers()) {
        RemoteFolder remoteFolder = tsa.browseFromKitLocation(server, "server/plugins/lib");
        remoteFolder.list().forEach(System.out::println);
        Optional<RemoteFile> remoteFile = remoteFolder.list().stream().filter(f -> f.getName().equals("keep-this-file-empty.txt")).findFirst();
        assertThat(remoteFile.isPresent(), is(true));
      }
    }
  }

  @Test
  public void testNonExistentFolder() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(TERRACOTTA_OS.defaultLicense())
            .clientArrayTopology(new ClientArrayTopology(distribution(version(EHCACHE_VERSION), PackageType.KIT, TERRACOTTA_OS), newClientArrayConfig().host("localhost")))
        );

    try (ClusterAgent agent = new ClusterAgent(false); ClusterFactory factory = new ClusterFactory(agent, "BrowseTest::testNonExistentFolder", configContext)) {
      ClientArray clientArray = factory.clientArray();
      try {
        Client localhost = clientArray.getClients().stream().findFirst().get();
        localhost.browse("/does/not/exist").downloadTo(new File("target/destination"));
        fail("expected IOException");
      } catch (IOException e) {
        // expected
      }
    }
  }

  @Test
  public void testUpload() throws Exception {
    ConfigurationContext configContext = CustomConfigurationContext.customConfigurationContext()
        .clientArray(clientArray -> clientArray.license(TERRACOTTA_OS.defaultLicense())
            .clientArrayTopology(new ClientArrayTopology(distribution(version(EHCACHE_VERSION), PackageType.KIT, TERRACOTTA_OS), newClientArrayConfig().host("localhost")))
        );

    try (ClusterAgent agent = new ClusterAgent(false); ClusterFactory factory = new ClusterFactory(agent, "BrowseTest::testUpload", configContext)) {
      ClientArray clientArray = factory.clientArray();
      Client localhost = clientArray.getClients().stream().findFirst().get();
      RemoteFolder folder = localhost.browse("does-not-exist"); // check that we can upload to non-existent folder & the folder will be created

      folder.upload("keep-this-file-empty.txt", getClass().getResource("/keep-this-file-empty.txt"));

      Optional<RemoteFile> createdFolder = localhost.browse(".").list().stream().filter(remoteFile -> remoteFile.getName().equals("does-not-exist") && remoteFile.isFolder()).findAny();
      assertThat(createdFolder.isPresent(), is(true));

      List<RemoteFile> remoteFiles = ((RemoteFolder) createdFolder.get()).list();
      Optional<RemoteFile> remoteFileOpt = remoteFiles.stream().filter(remoteFile -> remoteFile.getName().equals("keep-this-file-empty.txt")).findAny();
      assertThat(remoteFileOpt.isPresent(), is(true));
    }
  }
}
