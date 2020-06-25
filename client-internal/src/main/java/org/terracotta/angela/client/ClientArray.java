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

package org.terracotta.angela.client;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.ClientArrayConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.topology.InstanceId;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.terracotta.angela.client.util.IgniteClientHelper.executeRemotely;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;

/**
 * @author Aurelien Broszniowski
 */
public class ClientArray implements AutoCloseable {

  private final static Logger logger = LoggerFactory.getLogger(ClientArray.class);

  private final Ignite ignite;
  private final Supplier<InstanceId> instanceIdSupplier;
  private final LocalKitManager localKitManager;
  private final Map<ClientId, Client> clients = new HashMap<>();
  private final int ignitePort;
  private final ClientArrayConfigurationContext clientArrayConfigurationContext;
  private boolean closed = false;

  ClientArray(Ignite ignite, int ignitePort, Supplier<InstanceId> instanceIdSupplier, ClientArrayConfigurationContext clientArrayConfigurationContext) {
    this.ignitePort = ignitePort;
    this.clientArrayConfigurationContext = clientArrayConfigurationContext;
    this.instanceIdSupplier = instanceIdSupplier;
    this.ignite = ignite;
    this.localKitManager = new LocalKitManager(clientArrayConfigurationContext.getClientArrayTopology()
        .getDistribution());
    installAll();
  }

  private void installAll() {
    clientArrayConfigurationContext.getClientArrayTopology().getClientIds().forEach(this::install);
  }

  private void install(ClientId clientId) {
    logger.info("Setting up locally the extracted install to be deployed remotely");
    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(clientArrayConfigurationContext.getLicense(), kitInstallationPath, OFFLINE.getBooleanValue());

    try {
      logger.info("installing the client jars to {}", clientId);
// TODO : refactor Client to extract the uploading step and the execution step
//     uploadClientJars(ignite, terracottaClient.getClientHostname(), instanceId, );

      Client client = new Client(ignite, ignitePort, instanceIdSupplier.get(), clientId,
          clientArrayConfigurationContext.getTerracottaCommandLineEnvironment(), localKitManager);
      clients.put(clientId, client);
    } catch (Exception e) {
      logger.error("Cannot upload client jars to {}: {}", clientId, e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }

  private void uninstallAll() throws IOException {
    List<Exception> exceptions = new ArrayList<>();

    for (ClientId clientId : clientArrayConfigurationContext.getClientArrayTopology().getClientIds()) {
      try {
        uninstall(clientId);
      } catch (Exception ioe) {
        exceptions.add(ioe);
      }
    }

    if (!exceptions.isEmpty()) {
      IOException ioException = new IOException("Error uninstalling some clients");
      exceptions.forEach(ioException::addSuppressed);
      throw ioException;
    }
  }

  private void uninstall(ClientId clientId) throws IOException {
    logger.info("uninstalling {}", clientId);
    Client client = clients.get(clientId);
    try {
      if (client != null) {
        client.close();
      }
    } finally {
      clients.remove(clientId);
    }
  }

  public Jcmd jcmd(Client client) {
    TerracottaCommandLineEnvironment tcEnv = clientArrayConfigurationContext.getTerracottaCommandLineEnvironment();
    return new Jcmd(ignite, instanceIdSupplier.get(), client, ignitePort, tcEnv);
  }

  public void stopAll() throws IOException {
    List<Exception> exceptions = new ArrayList<>();

    for (ClientId clientId : clientArrayConfigurationContext.getClientArrayTopology().getClientIds()) {
      try {
        stop(clientId);
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    if (!exceptions.isEmpty()) {
      IOException ioException = new IOException("Error stopping some clients");
      exceptions.forEach(ioException::addSuppressed);
      throw ioException;
    }
  }

  public void stop(ClientId clientId) {
    logger.info("stopping {}", clientId);
    Client client = clients.get(clientId);
    if (client != null) {
      client.stop();
    }
  }

  public ClientArrayConfigurationContext getClientArrayConfigurationContext() {
    return clientArrayConfigurationContext;
  }

  public ClientArrayFuture executeOnAll(ClientJob clientJob) {
    return executeOnAll(clientJob, 1);
  }

  public ClientArrayFuture executeOnAll(ClientJob clientJob, int jobsPerClient) {
    List<Future<Void>> futures = new ArrayList<>();
    for (ClientId clientId : clientArrayConfigurationContext.getClientArrayTopology().getClientIds()) {
      for (int i = 1; i <= jobsPerClient; i++) {
        futures.add(executeOn(clientId, clientJob));
      }
    }
    return new ClientArrayFuture(futures);
  }

  public Future<Void> executeOn(ClientId clientId, ClientJob clientJob) {
    return clients.get(clientId).submit(clientId, clientJob);
  }

  public RemoteFolder browse(Client client, String remoteLocation) {
    IgniteCallable<String> callable = () -> Agent.controller.instanceWorkDir(client.getInstanceId());
    String clientWorkDir = executeRemotely(ignite, client.getHostname(), ignitePort, callable);
    return new RemoteFolder(ignite, client.getHostname(), ignitePort, clientWorkDir, remoteLocation);
  }

  public void download(String remoteLocation, File localRootPath) {
    List<Exception> exceptions = new ArrayList<>();
    for (Client client : clients.values()) {
      try {
        browse(client, remoteLocation).downloadTo(new File(localRootPath, client.getSymbolicName()));
      } catch (IOException e) {
        exceptions.add(e);
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error downloading cluster monitor remote files");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
  }

  @Override
  public void close() throws Exception {
    if (closed) {
      return;
    }
    closed = true;

    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstallAll();
    }
  }

  public Collection<Client> getClients() {
    return Collections.unmodifiableCollection(this.clients.values());
  }
}
