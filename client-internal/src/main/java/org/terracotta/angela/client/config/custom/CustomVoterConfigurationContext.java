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
package org.terracotta.angela.client.config.custom;

import org.terracotta.angela.client.config.VoterConfigurationContext;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CustomVoterConfigurationContext implements VoterConfigurationContext {
  private final List<TerracottaVoter> terracottaVoters = new ArrayList<>();
  private TerracottaCommandLineEnvironment tcEnv = TerracottaCommandLineEnvironment.DEFAULT;
  private SecurityRootDirectory securityRootDirectory;
  private Distribution distribution;
  private License license;

  protected CustomVoterConfigurationContext() {
  }

  public CustomVoterConfigurationContext addVoter(TerracottaVoter terracottaVoter) {
    this.terracottaVoters.add(terracottaVoter);
    return this;
  }

  public CustomVoterConfigurationContext securityRootDirectory(Path securityDir) {
    this.securityRootDirectory = SecurityRootDirectory.securityRootDirectory(securityDir);
    return this;
  }

  public CustomVoterConfigurationContext distribution(Distribution distribution) {
    this.distribution = distribution;
    return this;
  }

  public CustomVoterConfigurationContext license(License license) {
    this.license = license;
    return this;
  }

  public void terracottaCommandLineEnvironment(TerracottaCommandLineEnvironment tcEnv) {
    this.tcEnv = tcEnv;
  }

  @Override
  public Distribution getDistribution() {
    return distribution;
  }

  @Override
  public License getLicense() {
    return license;
  }

  @Override
  public TerracottaCommandLineEnvironment getTerracottaCommandLineEnvironment() {
    return tcEnv;
  }

  @Override
  public List<TerracottaVoter> getTerracottaVoters() {
    return terracottaVoters;
  }

  @Override
  public SecurityRootDirectory getSecurityRootDirectory() {
    return securityRootDirectory;
  }

  @Override
  public List<String> getHostNames() {
    List<String> hostNames = new ArrayList<>();
    for (TerracottaVoter terracottaVoter : terracottaVoters) {
      hostNames.add(terracottaVoter.getHostName());
    }
    return hostNames;
  }
}
