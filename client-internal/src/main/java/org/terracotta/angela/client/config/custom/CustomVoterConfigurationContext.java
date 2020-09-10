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

import java.util.ArrayList;
import java.util.List;

public class CustomVoterConfigurationContext implements VoterConfigurationContext {
  private final TerracottaCommandLineEnvironment terracottaCommandLineEnvironment = TerracottaCommandLineEnvironment.DEFAULT;
  private final List<TerracottaVoter> terracottaVoters = new ArrayList<>();
  private Distribution distribution;
  private License license;

  protected CustomVoterConfigurationContext() {
  }

  public CustomVoterConfigurationContext addVoter(TerracottaVoter terracottaVoter) {
    this.terracottaVoters.add(terracottaVoter);
    return this;
  }

  public List<TerracottaVoter> getTerracottaVoters() {
    return terracottaVoters;
  }

  public CustomVoterConfigurationContext distribution(Distribution distribution) {
    this.distribution = distribution;
    return this;
  }

  @Override
  public Distribution getDistribution() {
    return distribution;
  }

  public CustomVoterConfigurationContext license(License license) {
    this.license = license;
    return this;
  }

  @Override
  public License getLicense() {
    return license;
  }

  @Override
  public TerracottaCommandLineEnvironment getTerracottaCommandLineEnvironment() {
    return terracottaCommandLineEnvironment;
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
