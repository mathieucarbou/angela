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
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.VoterConfigurationContext;
import org.terracotta.angela.client.util.IgniteClientHelper;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterState;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.topology.InstanceId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;

public class Voter implements AutoCloseable {

  private final static Logger logger = LoggerFactory.getLogger(Voter.class);

  private final Ignite ignite;
  private final InstanceId instanceId;
  private final int ignitePort;
  private final VoterConfigurationContext voterConfigurationContext;
  private final LocalKitManager localKitManager;
  private boolean closed = false;

  Voter(Ignite ignite, int ignitePort, InstanceId instanceId, VoterConfigurationContext voterConfigurationContext) {
    this.ignitePort = ignitePort;
    this.voterConfigurationContext = voterConfigurationContext;
    this.instanceId = instanceId;
    this.ignite = ignite;
    this.localKitManager = new LocalKitManager(voterConfigurationContext.getDistribution());
    installAll();
  }

  private void installAll() {
    List<TerracottaVoter> terracottaVoters = voterConfigurationContext.getTerracottaVoters();
    for (TerracottaVoter terracottaVoter : terracottaVoters) {
      install(terracottaVoter);
    }
  }

  public TerracottaVoterState getTerracottaVoterState(TerracottaVoter terracottaVoter) {
    return IgniteClientHelper.executeRemotely(ignite, terracottaVoter.getHostName(), ignitePort, () -> Agent.controller.getVoterState(instanceId, terracottaVoter));
  }

  public Voter startAll() {
    voterConfigurationContext.getTerracottaVoters().stream()
        .map(voter -> CompletableFuture.runAsync(() -> start(voter)))
        .reduce(CompletableFuture::allOf).ifPresent(CompletableFuture::join);
    return this;
  }

  public Voter start(TerracottaVoter terracottaVoter) {
    IgniteClientHelper.executeRemotely(ignite, terracottaVoter.getHostName(), ignitePort, () -> Agent.controller.startVoter(instanceId, terracottaVoter));
    return this;
  }

  public Voter stopAll() {
    List<Exception> exceptions = new ArrayList<>();

    for (TerracottaVoter terracottaVoter : voterConfigurationContext.getTerracottaVoters()) {
      try {
        stop(terracottaVoter);
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error stopping all voters");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
    return this;
  }

  public Voter stop(TerracottaVoter terracottaVoter) {
    TerracottaVoterState terracottaVoterState = getTerracottaVoterState(terracottaVoter);
    if (terracottaVoterState == TerracottaVoterState.STOPPED) {
      return this;
    }
    logger.info("Stopping Voter on {}", terracottaVoter.getHostName());
    IgniteClientHelper.executeRemotely(ignite, terracottaVoter.getHostName(), ignitePort, () -> Agent.controller.stopVoter(instanceId, terracottaVoter));
    return this;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    stopAll();
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstallAll();
    }
  }

  private void install(TerracottaVoter terracottaVoter) {
    installWithKitManager(terracottaVoter);
  }

  private void installWithKitManager(TerracottaVoter terracottaVoter) {
    TerracottaVoterState terracottaVoterState = getTerracottaVoterState(terracottaVoter);
    if (terracottaVoterState != TerracottaVoterState.NOT_INSTALLED) {
      throw new IllegalStateException("Cannot install: voter " + terracottaVoter.getId() + " in state " + terracottaVoterState);
    }

    Distribution distribution = voterConfigurationContext.getDistribution();
    License license = voterConfigurationContext.getLicense();
    TerracottaCommandLineEnvironment tcEnv = voterConfigurationContext.getTerracottaCommandLineEnvironment();
    SecurityRootDirectory securityRootDirectory = voterConfigurationContext.getSecurityRootDirectory();

    logger.info("starting voter on {}", terracottaVoter.getHostName());

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue());

    IgniteCallable<Boolean> callable = () -> Agent.controller.installVoter(instanceId, terracottaVoter, distribution, license,
        localKitManager.getKitInstallationName(), securityRootDirectory, tcEnv);
    boolean isRemoteInstallationSuccessful = kitInstallationPath == null && IgniteClientHelper.executeRemotely(ignite, terracottaVoter.getHostName(), ignitePort, callable);

    if (!isRemoteInstallationSuccessful) {
      try {
        IgniteClientHelper.uploadKit(ignite, terracottaVoter.getHostName(), ignitePort, instanceId, distribution,
            localKitManager.getKitInstallationName(), localKitManager.getKitInstallationPath().toFile());
        IgniteClientHelper.executeRemotely(ignite, terracottaVoter.getHostName(), ignitePort, callable);
      } catch (Exception e) {
        throw new RuntimeException("Cannot upload kit to " + terracottaVoter.getHostName(), e);
      }
    }
  }

  private void uninstallAll() {
    for (TerracottaVoter terracottaVoter : voterConfigurationContext.getTerracottaVoters()) {
      uninstall(terracottaVoter);
    }
  }

  private void uninstall(TerracottaVoter terracottaVoter) {
    TerracottaVoterState terracottaVoterState = getTerracottaVoterState(terracottaVoter);
    if (terracottaVoterState == null) {
      return;
    }
    if (terracottaVoterState != TerracottaVoterState.STOPPED) {
      throw new IllegalStateException("Cannot uninstall: voter " + terracottaVoter.getId() + " in state " + terracottaVoterState);
    }

    logger.info("Uninstalling voter from {}", terracottaVoter.getHostName());
    IgniteRunnable uninstaller = () -> Agent.controller.uninstallVoter(instanceId, voterConfigurationContext.getDistribution(), terracottaVoter, localKitManager.getKitInstallationName());
    IgniteClientHelper.executeRemotely(ignite, terracottaVoter.getHostName(), ignitePort, uninstaller);
  }
}
